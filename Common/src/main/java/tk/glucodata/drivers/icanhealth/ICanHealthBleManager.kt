// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Driver
// ICanHealthBleManager.kt — BLE manager for the standard CGM 0x181F service
//
// Protocol notes from RE:
// - Real identity is the DIS serial number, not the advertisement name.
// - Real auth is AES-CBC over a standalone userId/serial fallback, not a lookup table.
// - `0x2AA7` can carry live data plus authenticated history in several payload families:
//   legacy 19-byte live frames, raw 16-byte CBC history frames, and vendor `SN` batches.

package tk.glucodata.drivers.icanhealth

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus

@SuppressLint("MissingPermission")
class ICanHealthBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), ICanHealthDriver {

    companion object {
        private const val TAG = ICanHealthConstants.TAG

        const val SENSOR_GEN = 0

        private const val GATT_OP_TIMEOUT_MS = 10_000L
        private const val MTU_REQUEST_TIMEOUT_MS = 12_000L
        private const val TARGET_MTU = 517
        private const val MIN_VENDOR_HISTORY_MTU = 247
        private const val RECONNECT_ALARM_MIN_DELAY_MS = 10_000L
        private const val AUTHENTICATED_RECONNECT_DELAY_MS = 2_000L
        private const val FIRST_VALUE_BRUTE_RECONNECT_DELAY_MS = 8_000L
        private const val NEXT_READING_WAKE_EARLY_WINDOW_MS = 15_000L
        private const val NEXT_READING_BRUTE_RECONNECT_DELAY_MS = 8_000L
        private const val NEXT_READING_BRUTE_GRACE_WINDOW_MS = 45_000L
        private const val GENERIC_RECONNECT_SLOP_MS = 5_000L
        private const val ICAN_LOSS_SIGNAL_SHOWTIME_MS = 15 * 60 * 1000L
        private const val POST_MEASUREMENT_DISCONNECT_DELAY_MS = 1_500L
        private const val NO_DATA_WATCHDOG_MS = 210_000L
        private const val DRIVER_NOTIFICATION_REFRESH_DELAY_MS = 900L
        private const val SEQUENCE_UNIT_MS = 60_000L
        private const val SENSOR_INFO_TIMEOUT_MS = 1_500L
        private const val LIVE_SEQUENCE_LAG_ALLOWANCE = 3
        private const val MAX_SESSION_TIMESTAMP_PAST_DRIFT_MS = 6 * 60 * 60 * 1000L
        private const val MAX_SESSION_TIMESTAMP_FUTURE_DRIFT_MS = 2 * 60 * 1000L
        private const val RECENT_GLUCOSE_WINDOW_SIZE = 24
        private const val NATIVE_MIRROR_STREAM_WINDOW_SEC = 15L * 24L * 60L * 60L
    }

    enum class Phase {
        IDLE,
        GATT_CONNECTING,
        DISCOVERING_SERVICES,
        AUTH_PRIME,
        AUTH_HANDSHAKE,
        POST_AUTH_SETUP,
        STARTING_SENSOR,
        STREAMING,
    }

    private enum class HistoryBackfillPhase {
        NONE,
        GLUCOSE,
        ORIGINAL,
    }

    private data class PendingHistoryReading(
        val sequenceNumber: Int,
        var timestampMs: Long = 0L,
        var glucoseMgdl: Float = Float.NaN,
        var rawCurrent: Float = Float.NaN,
        var temperatureC: Float = Float.NaN,
    )

    private data class DeferredLiveReading(
        val sequenceNumber: Int,
        val timestampMs: Long,
        val glucoseMgdl: Float,
        val rawCurrent: Float,
        val temperatureC: Float,
    )

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    @Volatile
    private var uiPaused = false

    private val handlerThread = HandlerThread("iCan-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    private sealed class GattOp {
        data class EnableNotify(val charUuid: UUID) : GattOp()
        data class EnableIndicate(val charUuid: UUID) : GattOp()
        data class Write(
            val charUuid: UUID,
            val data: ByteArray,
            val serviceUuid: UUID = ICanHealthConstants.CGM_SERVICE,
        ) : GattOp()
        data class Read(
            val charUuid: UUID,
            val serviceUuid: UUID = ICanHealthConstants.CGM_SERVICE,
        ) : GattOp()
    }

    private val gattQueue = ArrayDeque<GattOp>()
    @Volatile private var gattOpActive = false

    private var cgmService: BluetoothGattService? = null
    private var disService: BluetoothGattService? = null
    private var charMeasurement: BluetoothGattCharacteristic? = null
    private var charStatus: BluetoothGattCharacteristic? = null
    private var charSessionStart: BluetoothGattCharacteristic? = null
    private var charRacp: BluetoothGattCharacteristic? = null
    private var charSpecificOps: BluetoothGattCharacteristic? = null

    @Volatile
    var aesKey: ByteArray? = null
        private set

    @Volatile private var isAuthenticated = false
    @Volatile private var hasAuthenticatedReconnectHint = false
    @Volatile private var authAttempted = false
    @Volatile private var hasExplicitAesKey = false
    @Volatile private var postAuthSetupStarted = false
    @Volatile private var historyBackfillRequested = false
    @Volatile private var historyBackfillAttemptedThisConnection = false
    @Volatile private var shouldRequestAuthenticatedHistoryBackfill = true
    @Volatile private var suppressAutomaticHistoryBackfill = false
    @Volatile private var sawUnsupportedSnHistoryBatch = false
    @Volatile private var awaitingFreshStatusForHistoryBackfill = false
    @Volatile private var historyBackfillPhase = HistoryBackfillPhase.NONE
    @Volatile private var glucoseHistoryImportedRecordCount = 0
    @Volatile private var pendingGlucoseHistoryStartSequence = ICanHealthConstants.DEFAULT_READING_INTERVAL_MINUTES
    @Volatile private var useModernGlucoseHistoryCommand = true
    @Volatile private var useModernOriginalHistoryCommand = true
    @Volatile private var pendingHistoryBackfillReason: String? = null
    @Volatile private var awaitingMtuNegotiation = false
    @Volatile private var negotiatedMtu = 23
    @Volatile private var serviceDiscoveryStarted = false
    @Volatile private var serviceDiscoveryHandled = false

    @Volatile private var lastConnectionStartedAtMs = 0L
    @Volatile private var receivedGlucoseThisConnection = false
    @Volatile private var lastGlucoseReceiptRealtimeMs = 0L
    @Volatile private var sessionStartEpochMs = 0L
    @Volatile private var currentSequenceNumber = -1
    @Volatile private var currentSequenceObservedAtMs = 0L
    @Volatile private var launcherState = -1
    @Volatile private var lastAuthChallenge: ByteArray? = null
    @Volatile private var serialFromDevice: String? = null
    @Volatile private var rawSerialFromDevice: String? = null
    @Volatile private var onboardingDeviceSn: String? = null
    @Volatile private var configuredAuthUserId: String? = null
    @Volatile private var recoveredAuthUserId: String? = null
    @Volatile private var authRetryCount = 0
    @Volatile private var lastHandledLiveSequence = -1
    @Volatile private var lastHandledLiveTimestampMs = 0L
    @Volatile private var deferredLiveReading: DeferredLiveReading? = null
    @Volatile private var persistedHistoryTailTimestampMs = 0L
    @Volatile private var persistedCoveredSequence = -1
    @Volatile private var persistedCoveredTimestampMs = 0L
    @Volatile private var persistedCoveredEdgeLoaded = false
    @Volatile private var persistedCoveredEdgeSensorId: String? = null
    @Volatile private var forceSessionMetadataRefresh = false
    @Volatile private var pendingStartTimeEpochMs = 0L
    @Volatile private var startCommandIssuedThisConnection = false
    @Volatile private var scheduledReconnectAtMs = 0L
    @Volatile private var scheduledReconnectToken = 0L
    @Volatile private var latestCurrentRaw = Float.NaN
    @Volatile private var latestTemperatureC = Float.NaN
    @Volatile private var lastCeCalibrationCount = 0
    @Volatile private var viewModeValue = 0
    @Volatile private var viewModeInitialized = false
    @Volatile private var latestDriverGlucoseMgdl = Float.NaN
    @Volatile private var latestDriverReadingTimestampMs = 0L
    @Volatile private var mirrorHistoryMergeScheduled = false
    @Volatile override var vendorModelName: String = ""
        private set
    @Volatile override var vendorFirmwareVersion: String = ""
        private set
    @Volatile private var vendorSoftwareVersion: String = ""
    private val pendingHistoryBatch = LinkedHashMap<Int, PendingHistoryReading>()
    private val recentLiveGlucoseMgdl = ArrayDeque<Float>(RECENT_GLUCOSE_WINDOW_SIZE)

    private var provisionalSensorIdForAdoption: String? =
        serial.takeIf { ICanHealthConstants.isProvisionalSensorId(it) }

    private enum class UiStatusKind {
        NONE,
        CONNECTED,
        DISCONNECTED,
        PREPARING,
        AUTHENTICATING,
        AUTHENTICATED,
        CONFIGURING,
        STREAMING,
        STREAMING_UNAUTHENTICATED,
        WAITING_FOR_NEXT_READING,
        SYNCING,
        NO_DATA_TIMEOUT,
        CUSTOM,
    }

    @Volatile
    private var uiStatusKind = UiStatusKind.NONE

    init {
        loadPersistedCoveredEdge(force = true)
    }

    private val noDataWatchdog: Runnable = Runnable {
        if (phase == Phase.STREAMING && lastGlucoseReceiptRealtimeMs > 0L) {
            val elapsed = System.currentTimeMillis() - lastGlucoseReceiptRealtimeMs
            if (elapsed > NO_DATA_WATCHDOG_MS) {
                Log.w(TAG, "No glucose data for ${elapsed / 1000}s — triggering reconnect")
                setUiStatus(UiStatusKind.NO_DATA_TIMEOUT)
                try {
                    mBluetoothGatt?.disconnect()
                } catch (_: Throwable) {
                }
            } else {
                handler.postDelayed(
                    noDataWatchdog,
                    (NO_DATA_WATCHDOG_MS - elapsed).coerceAtLeast(1_000L)
                )
            }
        }
    }

    private val foregroundNotificationRefreshRunnable = Runnable {
        try {
            val mainSensor = SensorIdentity.resolveLiveMainSensor(SerialNumber)
            if (SensorIdentity.matches(SerialNumber, mainSensor)) {
                Notify.showoldglucose()
            }
        } catch (t: Throwable) {
            Log.stack(TAG, "foregroundNotificationRefreshRunnable", t)
        }
    }

    private val mirrorHistoryMergeRunnable = Runnable {
        mirrorHistoryMergeScheduled = false
        if (hasLocalPersistedRecord()) {
            return@Runnable
        }
        val nativeSensorPtr = resolveNativeSensorPtr(SerialNumber)
        if (nativeSensorPtr == 0L) {
            return@Runnable
        }
        Log.i(TAG, "Requesting mirror history merge for iCan follower shell $SerialNumber")
        HistorySyncAccess.mergeFullSyncForSensor(SerialNumber)
        UiRefreshBus.requestDataRefresh()
    }

    private val sensorInfoFallbackRunnable = Runnable {
        if (!postAuthSetupStarted) {
            Log.w(TAG, "Sensor info response timeout — continuing with post-auth setup")
            startPostAuthSetup("sensor-info-timeout")
        }
    }

    private val mtuRequestTimeoutRunnable = Runnable {
        val gatt = mBluetoothGatt ?: return@Runnable
        if (!awaitingMtuNegotiation || phase != Phase.DISCOVERING_SERVICES) {
            return@Runnable
        }
        awaitingMtuNegotiation = false
        Log.w(TAG, "MTU request timed out — continuing with service discovery at mtu=$negotiatedMtu")
        beginServiceDiscovery(gatt, "mtu-timeout")
    }

    private val gattOpTimeoutRunnable: Runnable = object : Runnable {
        override fun run() {
            if (gattOpActive) {
                Log.w(TAG, "GATT op timeout — forcing queue drain")
                gattOpActive = false
                handler.removeCallbacks(this)
                drainGattQueue()
                maybeHandleQueueDrained()
            }
        }
    }

    private data class ReconnectPlan(
        val delayMs: Long,
        val exactAlarm: Boolean,
        val reason: String,
        val refreshSessionMetadata: Boolean = false,
    )

    private var scheduledReconnectRunnable: Runnable? = null
    private val postMeasurementDisconnectRunnable = Runnable {
        if (stop || isAuthenticated) {
            return@Runnable
        }
        try {
            Log.d(TAG, "Disconnecting after unauthenticated live packet to shorten wake window")
            mBluetoothGatt?.disconnect()
        } catch (_: Throwable) {
        }
    }

    init {
        viewModeValue = normalizeViewMode(resolvePersistedViewMode())
        viewModeInitialized = true
        applyViewModeToNative()
    }

    private fun resolvedProfile(): ICanHealthProfile =
        ICanHealthProfileResolver.resolve(
            modelName = vendorModelName,
            rawSerial = rawSerialFromDevice ?: serialFromDevice,
            softwareVersion = vendorSoftwareVersion,
        )

    private fun readingIntervalMinutes(): Int = resolvedProfile().readingIntervalMinutes

    private fun readingIntervalMs(): Long = readingIntervalMinutes() * SEQUENCE_UNIT_MS

    private fun warmupMinutes(): Int = resolvedProfile().warmupMinutes

    private fun resolvePersistedViewMode(): Int {
        return 0
    }

    private fun normalizeViewMode(value: Int): Int {
        return if (supportsRawDisplayModes()) value.coerceIn(0, 3) else 0
    }

    override fun supportsRawDisplayModes(): Boolean = false

    private fun connectedStatus(): String {
        return runCatching { Applic.app.getString(tk.glucodata.R.string.status_connected) }
            .getOrDefault("Connected")
    }

    private fun hasLocalPersistedRecord(): Boolean =
        ICanHealthRegistry.findRecord(Applic.app, SerialNumber) != null

    private fun disconnectedStatus(): String {
        return runCatching { Applic.app.getString(tk.glucodata.R.string.status_disconnected) }
            .getOrDefault("Disconnected")
    }

    private fun connectingStatus(): String {
        return runCatching { Applic.app.getString(tk.glucodata.R.string.connecting) }
            .getOrDefault("Connecting...")
    }

    override fun getPassiveConnectionStatus(): String {
        if (uiPaused || stop) {
            return disconnectedStatus()
        }
        return when {
            mBluetoothGatt != null -> connectedStatus()
            phase == Phase.IDLE -> disconnectedStatus()
            else -> connectedStatus()
        }
    }

    override fun canConnectWithoutDataptr(): Boolean = true

    override fun shouldShowSearchingStatusWhenIdle(): Boolean = true

    private fun syncingStatus(): String {
        return runCatching { Applic.app.getString(tk.glucodata.R.string.syncing) }
            .getOrDefault("Syncing...")
    }

    private fun waitingForDataStatus(): String {
        return runCatching { Applic.app.getString(tk.glucodata.R.string.status_waiting_for_data) }
            .getOrDefault("Connected, waiting for data...")
    }

    private fun isPassiveUiStatus(kind: UiStatusKind): Boolean {
        return when (kind) {
            UiStatusKind.NONE,
            UiStatusKind.CONNECTED,
            UiStatusKind.DISCONNECTED,
            UiStatusKind.PREPARING,
            UiStatusKind.AUTHENTICATING,
            UiStatusKind.AUTHENTICATED,
            UiStatusKind.CONFIGURING,
            UiStatusKind.STREAMING,
            UiStatusKind.STREAMING_UNAUTHENTICATED,
            UiStatusKind.WAITING_FOR_NEXT_READING -> true
            UiStatusKind.SYNCING,
            UiStatusKind.NO_DATA_TIMEOUT,
            UiStatusKind.CUSTOM -> false
        }
    }

    private fun setUiStatus(kind: UiStatusKind, customStatus: String? = null) {
        uiStatusKind = kind
        constatstatusstr = when (kind) {
            UiStatusKind.NONE -> ""
            UiStatusKind.CONNECTED,
            UiStatusKind.AUTHENTICATED,
            UiStatusKind.STREAMING,
            UiStatusKind.STREAMING_UNAUTHENTICATED -> connectedStatus()
            UiStatusKind.DISCONNECTED -> disconnectedStatus()
            UiStatusKind.PREPARING,
            UiStatusKind.AUTHENTICATING,
            UiStatusKind.CONFIGURING -> connectingStatus()
            UiStatusKind.WAITING_FOR_NEXT_READING,
            UiStatusKind.NO_DATA_TIMEOUT -> waitingForDataStatus()
            UiStatusKind.SYNCING -> syncingStatus()
            UiStatusKind.CUSTOM -> customStatus.orEmpty()
        }
    }

    private fun lifecycleSummary(): String {
        if (isSensorExpired()) {
            return runCatching { Applic.app.getString(tk.glucodata.R.string.expired) }
                .getOrDefault("Expired")
        }
        val start = getStartTimeMs()
        val summaryEnd = getExpectedEndMs().takeIf { it > start } ?: getOfficialEndMs()
        if (start <= 0L || summaryEnd <= start) {
            return ""
        }
        val totalDays = ((summaryEnd - start) / (24L * 60L * 60L * 1000L)).coerceAtLeast(1L)
        val usedDays = (((System.currentTimeMillis() - start).coerceAtLeast(0L)) / (24L * 60L * 60L * 1000L)) + 1L
        return "${usedDays.coerceAtLeast(1L)} / $totalDays"
    }

    private fun applyViewModeToNative() {
        if (dataptr != 0L) {
            runCatching { Natives.setViewMode(dataptr, viewModeValue) }
                .onFailure { Log.stack(TAG, "applyViewModeToNative", it) }
        }
    }

    override fun getDetailedBleStatus(): String {
        if (uiPaused) {
            return ""
        }
        val status = constatstatusstr?.trim().orEmpty()
        return if (isPassiveUiStatus(uiStatusKind)) "" else status
    }

    override fun getLifecycleSummary(): String = lifecycleSummary()

    override fun matchesManagedSensorId(sensorId: String?): Boolean {
        val candidate = sensorId?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return false
        }
        if (ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(SerialNumber, candidate)) {
            return true
        }
        listOfNotNull(
            serialFromDevice?.takeIf { it.isNotBlank() },
            rawSerialFromDevice?.takeIf { it.isNotBlank() },
            onboardingDeviceSn?.takeIf { it.isNotBlank() }?.let {
                ICanHealthConstants.deriveInitialSensorId(
                    deviceName = null,
                    address = mActiveDeviceAddress,
                    onboardingDeviceSn = it
                )
            },
            mActiveDeviceAddress?.takeIf { it.isNotBlank() }?.let {
                ICanHealthConstants.deriveInitialSensorId(
                    deviceName = null,
                    address = it,
                    onboardingDeviceSn = null
                )
            }
        ).forEach { knownId ->
            if (knownId.equals(candidate, ignoreCase = true) ||
                ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(knownId, candidate)
            ) {
                return true
            }
        }
        return false
    }

    override fun isUiEnabled(): Boolean = !uiPaused

    private fun clearSoftDisconnectState() {
        cancelScheduledReconnect()
        handler.removeCallbacks(noDataWatchdog)
        handler.removeCallbacks(foregroundNotificationRefreshRunnable)
        handler.removeCallbacks(postMeasurementDisconnectRunnable)
        handler.removeCallbacks(sensorInfoFallbackRunnable)
        handler.removeCallbacks(mtuRequestTimeoutRunnable)
        handler.removeCallbacks(gattOpTimeoutRunnable)
        historyBackfillRequested = false
        historyBackfillPhase = HistoryBackfillPhase.NONE
        awaitingFreshStatusForHistoryBackfill = false
        pendingHistoryBackfillReason = null
        pendingHistoryBatch.clear()
        deferredLiveReading = null
        awaitingMtuNegotiation = false
        serviceDiscoveryStarted = false
        serviceDiscoveryHandled = false
        glucoseHistoryImportedRecordCount = 0
    }

    override fun softDisconnect() {
        uiPaused = true
        clearSoftDisconnectState()
        setUiStatus(UiStatusKind.NONE)
        try {
            mBluetoothGatt?.disconnect()
        } catch (_: Throwable) {
        }
        if (mBluetoothGatt == null) {
            phase = Phase.IDLE
        }
        UiRefreshBus.requestStatusRefresh()
    }

    override fun softReconnect() {
        uiPaused = false
        clearSoftDisconnectState()
        if (phase == Phase.IDLE && mBluetoothGatt == null) {
            connectDevice(0)
        } else {
            try {
                mBluetoothGatt?.disconnect()
            } catch (_: Throwable) {
            }
            handler.postDelayed({
                if (!uiPaused && !stop) {
                    connectDevice(0)
                }
            }, 250L)
        }
        UiRefreshBus.requestStatusRefresh()
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        uiPaused = true
        clearSoftDisconnectState()
        setPause(true)
        val currentBeforeTerminate = runCatching { Natives.lastsensorname() }.getOrNull()
        val shouldRehomeCurrent = ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(
            SerialNumber,
            currentBeforeTerminate
        )
        val replacementSerial = if (shouldRehomeCurrent) {
            runCatching { SensorBluetooth.resolveReplacementSensorSerial(SerialNumber) }.getOrNull()
        } else {
            null
        }
        val sensorPtr = resolveNativeSensorPtr(SerialNumber)
        runCatching { mBluetoothGatt?.disconnect() }
            .onFailure { Log.stack(TAG, "terminateManagedSensor(disconnect)", it) }
        if (sensorPtr != 0L) {
            runCatching { Natives.finishfromSensorptr(sensorPtr) }
                .onFailure { Log.stack(TAG, "terminateManagedSensor(finishfromSensorptr)", it) }
        }
        dataptr = 0L
        runCatching { close() }
            .onFailure { Log.stack(TAG, "terminateManagedSensor(close)", it) }
        Applic.app?.let { context ->
            runCatching { ICanHealthRegistry.removeSensor(context, SerialNumber) }
                .onFailure { Log.stack(TAG, "terminateManagedSensor(removeSensor)", it) }
        }
        if (shouldRehomeCurrent) {
            val nativeReplacement = SensorIdentity.resolveNativeSensorName(replacementSerial)
            runCatching {
                Natives.setcurrentsensor(
                    nativeReplacement
                        ?: replacementSerial
                        ?: ""
                )
            }.onFailure { Log.stack(TAG, "terminateManagedSensor(setcurrentsensor)", it) }
        }
        provisionalSensorIdForAdoption = null
    }

    override fun hasNativeSensorBacking(): Boolean {
        if (SerialNumber.isBlank() || ICanHealthConstants.isProvisionalSensorId(SerialNumber)) {
            return false
        }
        if (dataptr != 0L) {
            val existingName = runCatching { Natives.getSensorName(dataptr) }.getOrNull()
            if (!existingName.isNullOrBlank() &&
                ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(SerialNumber, existingName)
            ) {
                return true
            }
        }
        return resolveExistingNativeSensorName(SerialNumber) != null
    }

    override fun getCurrentSnapshot(maxAgeMillis: Long): ICanHealthCurrentSnapshot? {
        val timestampMs = latestDriverReadingTimestampMs
        val glucoseMgdl = latestDriverGlucoseMgdl
        if (timestampMs <= 0L || !glucoseMgdl.isFinite() || glucoseMgdl <= 0f) {
            return null
        }
        if (kotlin.math.abs(System.currentTimeMillis() - timestampMs) > maxAgeMillis) {
            return null
        }
        val glucoseDisplay = if (Applic.unit == 1) {
            glucoseMgdl / ICanHealthConstants.MMOL_TO_MGDL
        } else {
            glucoseMgdl
        }
        return ICanHealthCurrentSnapshot(
            timeMillis = timestampMs,
            glucoseValue = glucoseDisplay,
            rawValue = Float.NaN,
            rate = Float.NaN,
            sensorGen = SENSOR_GEN,
        )
    }

    override var viewMode: Int
        get() {
            if (!viewModeInitialized) {
                viewModeValue = normalizeViewMode(resolvePersistedViewMode())
                viewModeInitialized = true
            }
            return viewModeValue
        }
        set(value) {
            viewModeValue = normalizeViewMode(value)
            viewModeInitialized = true
            applyViewModeToNative()
        }

    override fun getStartTimeMs(): Long {
        if (sessionStartEpochMs > 0L) return sessionStartEpochMs
        if (sensorstartmsec > 0L) return sensorstartmsec
        if (dataptr != 0L) {
            return runCatching { Natives.getSensorStartmsec(dataptr) }.getOrDefault(0L)
        }
        if (!hasLocalPersistedRecord()) {
            mirrorDerivedStartTimeMs()?.let { return it }
        }
        return 0L
    }

    override fun getOfficialEndMs(): Long {
        val start = getStartTimeMs()
        if (start <= 0L) return 0L
        return start + resolvedProfile().ratedLifetimeMs()
    }

    override fun getExpectedEndMs(): Long {
        val start = getStartTimeMs()
        if (start <= 0L) return 0L
        observedEndedStatusEndMs(start)?.let { return it }
        return start + resolvedProfile().expectedLifetimeMs()
    }

    override fun isSensorExpired(): Boolean {
        val nowMs = System.currentTimeMillis()
        if (ICanHealthConstants.isEndedStatusSequenceCap(launcherState, currentSequenceNumber)) {
            return !hasRecentOperationalData(nowMs)
        }
        return launcherState == ICanHealthConstants.LAUNCHER_STATE_ENDED
    }

    override fun getSensorRemainingHours(): Int {
        if (isSensorExpired()) return 0
        val expectedEnd = getExpectedEndMs()
        if (expectedEnd <= 0L) return -1
        val remainingHours = (expectedEnd - System.currentTimeMillis()) / (60L * 60L * 1000L)
        return if (remainingHours > 0L) remainingHours.toInt() else -1
    }

    override fun getSensorAgeHours(): Int {
        val start = getStartTimeMs()
        if (start <= 0L) return -1
        return ((System.currentTimeMillis() - start).coerceAtLeast(0L) / (60L * 60L * 1000L)).toInt()
    }

    override fun getReadingIntervalMinutes(): Int = readingIntervalMinutes()

    override fun calibrateSensor(glucoseMgDl: Int): Boolean {
        if (!supportsSensorCalibration()) {
            Log.w(TAG, "Ignoring iCan calibration: this firmware path is not supported")
            return false
        }
        if (!isAuthenticated) {
            Log.w(TAG, "Ignoring iCan calibration without authenticated session")
            return false
        }
        val challenge = lastAuthChallenge
        val glucoseKey = aesKey
        val sequenceNumber = currentSequenceNumber
        if (challenge == null || glucoseKey == null || sequenceNumber < 0 || charSpecificOps == null) {
            Log.w(
                TAG,
                "Calibration prerequisites missing: challenge=${challenge != null} key=${glucoseKey != null} seq=$sequenceNumber char=${charSpecificOps != null}"
            )
            return false
        }
        val packet = ICanHealthParser.buildCalibrationPacket(
            glucoseMgDl = glucoseMgDl,
            sequenceNumber = sequenceNumber,
            authChallenge = challenge,
            glucoseKey = glucoseKey,
        ) ?: return false
        if (latestCurrentRaw.isFinite() && latestCurrentRaw > 0f && latestTemperatureC.isFinite() && latestTemperatureC > 0f) {
            val recentHistory = recentLiveGlucoseMgdl.toList()
            val planned = ICanHealthCeCalibration.planCalib(
                currentGluMgDl = recentLiveGlucoseMgdl.lastOrNull() ?: glucoseMgDl.toFloat(),
                fingerGluMgDl = glucoseMgDl.toFloat(),
                historyMgDl = recentHistory,
                sensorCurrent = latestCurrentRaw,
                temperatureC = latestTemperatureC,
            )
            val started = ICanHealthCeCalibration.startCalib(
                currentGluMgDl = glucoseMgDl.toFloat(),
                isFirstCalibration = lastCeCalibrationCount <= 0,
                existingCalibrationCount = lastCeCalibrationCount,
                sensorCurrent = latestCurrentRaw,
                temperatureC = latestTemperatureC,
            )
            Log.i(
                TAG,
                "iCan calibration plan finger=$glucoseMgDl seq=$sequenceNumber current=$latestCurrentRaw temp=$latestTemperatureC " +
                    "planP=${planned.encodedP} startP=${started.encodedP}"
            )
        }
        enqueueOp(GattOp.Write(ICanHealthConstants.CGM_SPECIFIC_OPS, packet))
        drainGattQueue()
        return true
    }

    fun setAesKey(key: ByteArray) {
        if (key.size == ICanHealthConstants.AES_BLOCK_SIZE) {
            aesKey = key.copyOf()
            Log.i(TAG, "AES key set (${key.size} bytes)")
        } else {
            Log.e(TAG, "setAesKey: invalid key size ${key.size}")
        }
    }

    private fun applyAesKeyFromASCII(asciiKey: String, explicit: Boolean) {
        ICanHealthCrypto.keyFromASCII(asciiKey)?.let {
            hasExplicitAesKey = explicit
            setAesKey(it)
        }
    }

    fun setAesKeyFromASCII(asciiKey: String) {
        applyAesKeyFromASCII(asciiKey, explicit = false)
    }

    fun setConfiguredAesKeyFromASCII(asciiKey: String, explicit: Boolean) {
        applyAesKeyFromASCII(asciiKey, explicit)
    }

    fun setOnboardingDeviceSn(deviceSnOrCode: String?) {
        onboardingDeviceSn = ICanHealthConstants.normalizeOnboardingDeviceSn(deviceSnOrCode)
            .takeIf { it.isNotBlank() }
    }

    fun setConfiguredAuthUserId(userId: String?) {
        configuredAuthUserId = ICanHealthConstants.normalizeConfiguredAuthUserId(userId)
            .takeIf { it.isNotBlank() }
    }

    private fun resolveBundledKeySelector(): String? =
        when {
            !rawSerialFromDevice.isNullOrBlank() -> rawSerialFromDevice
            !serialFromDevice.isNullOrBlank() -> serialFromDevice
            !SerialNumber.isBlank() && !ICanHealthConstants.isProvisionalSensorId(SerialNumber) -> SerialNumber
            else -> null
        }

    private fun bundledKeyContext(
        launcherSerial: String? = resolveBundledKeySelector(),
        softwareVersion: String? = vendorSoftwareVersion,
    ): String {
        val context = ArrayList<String>(2)
        launcherSerial?.takeIf { it.isNotBlank() }?.let { context.add("launcher=$it") }
        softwareVersion?.takeIf { it.isNotBlank() }?.let { context.add("sw=$it") }
        return context.joinToString(separator = ", ")
    }

    private fun applyBundledGlucoseKey(
        launcherSerialOverride: String? = resolveBundledKeySelector(),
        softwareVersionOverride: String? = vendorSoftwareVersion,
    ) {
        if (hasExplicitAesKey) {
            return
        }
        val bundledKey = ICanHealthConstants.resolveBundledGlucoseKey(
            launcherSerialOverride,
            softwareVersionOverride
        )
        applyAesKeyFromASCII(bundledKey, explicit = false)
        Log.i(
            TAG,
            "Using bundled ${if (ICanHealthConstants.usesNewBundledCrypto(launcherSerialOverride, softwareVersionOverride)) "new" else "old"} glucose key" +
                bundledKeyContext(launcherSerialOverride, softwareVersionOverride)
                    .takeIf { it.isNotBlank() }
                    ?.let { " for $it" }
                    .orEmpty()
        )
    }

    fun loadAesKeyFromPrefs(context: Context, sensorIdOverride: String? = null): Boolean {
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val sensorId = sensorIdOverride?.takeIf { it.isNotBlank() } ?: SerialNumber
        val serialKey = prefs.getString("${ICanHealthConstants.PREF_AES_KEY_PREFIX}$sensorId", null)
        if (serialKey != null && serialKey.length == 16) {
            applyAesKeyFromASCII(serialKey, explicit = true)
            Log.i(TAG, "Loaded AES key for $sensorId")
            return true
        }
        val globalKey = prefs.getString(ICanHealthConstants.PREF_AES_KEY_GLOBAL, null)
        if (globalKey != null && globalKey.length == 16) {
            applyAesKeyFromASCII(globalKey, explicit = true)
            Log.i(TAG, "Loaded global AES key for $sensorId")
            return true
        }
        val legacyProvisionalId = mActiveDeviceAddress
            ?.takeIf { sensorId.startsWith(ICanHealthConstants.PROVISIONAL_SENSOR_PREFIX, ignoreCase = true) }
            ?.uppercase()
            ?.replace(":", "")
            ?.let { ICanHealthConstants.LEGACY_PROVISIONAL_SENSOR_PREFIX + it }
        if (legacyProvisionalId != null) {
            val legacyKey = prefs.getString("${ICanHealthConstants.PREF_AES_KEY_PREFIX}$legacyProvisionalId", null)
            if (legacyKey != null && legacyKey.length == 16) {
                applyAesKeyFromASCII(legacyKey, explicit = true)
                prefs.edit()
                    .putString("${ICanHealthConstants.PREF_AES_KEY_PREFIX}$sensorId", legacyKey)
                    .apply()
                Log.i(TAG, "Loaded legacy provisional AES key for $sensorId")
                return true
            }
        }
        applyBundledGlucoseKey()
        return true
    }

    /**
     * Load a previously auto-recovered userId for this sensor from SharedPreferences.
     * Called alongside [loadAesKeyFromPrefs] on connection/identity promotion so that
     * sensors activated under a different Sinocare account don't need the F4 recovery
     * handshake on every reconnect.
     */
    fun loadRecoveredUserIdFromPrefs(context: Context?, sensorIdOverride: String? = null) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val sensorId = sensorIdOverride?.takeIf { it.isNotBlank() } ?: SerialNumber
        val saved = prefs.getString(
            "${ICanHealthConstants.PREF_RECOVERED_USER_ID_PREFIX}$sensorId", null
        )
        if (!saved.isNullOrBlank()) {
            recoveredAuthUserId = saved
            Log.i(TAG, "Loaded recovered userId for $sensorId: '$saved'")
        }
    }

    /**
     * Persist a successfully recovered userId so it survives app restarts.
     * Writes under both the current sensor id and the canonical serial (if different).
     */
    private fun persistRecoveredUserId(userId: String) {
        val ctx = Applic.app ?: return
        val prefs = ctx.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val sensorId = SerialNumber.takeIf { it.isNotBlank() }
        val canonical = serialFromDevice?.takeIf { it.isNotBlank() }
        if (sensorId != null) {
            editor.putString("${ICanHealthConstants.PREF_RECOVERED_USER_ID_PREFIX}$sensorId", userId)
        }
        if (canonical != null && canonical != sensorId) {
            editor.putString("${ICanHealthConstants.PREF_RECOVERED_USER_ID_PREFIX}$canonical", userId)
        }
        editor.apply()
        Log.i(TAG, "Persisted recovered userId for ${sensorId ?: canonical}: '$userId'")
    }

    private fun shouldSkipHistoryOverlap(sequenceNumber: Int, sampleTimeMs: Long): Boolean {
        loadPersistedCoveredEdge()
        if (!canUseHistoryPastEndedStatusCap()) {
            val coveredSequence = maxOf(lastHandledLiveSequence, persistedCoveredSequence)
            if (coveredSequence >= 0 && sequenceNumber >= 0 && sequenceNumber <= coveredSequence) {
                return true
            }
        }
        val coveredTimestampMs = maxOf(persistedHistoryTailTimestampMs, persistedCoveredTimestampMs)
        return coveredTimestampMs > 0L &&
            sampleTimeMs > 0L &&
            sampleTimeMs <= coveredTimestampMs
    }

    private fun legacyAuthBypassPrefKeys(sensorId: String = SerialNumber): LinkedHashSet<String> {
        val keys = LinkedHashSet<String>(2)
        sensorId.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { keys.add(ICanHealthConstants.PREF_AUTH_BYPASS_UNTIL_PREFIX + it) }
        mActiveDeviceAddress
            ?.trim()
            ?.uppercase()
            ?.replace(":", "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { keys.add(ICanHealthConstants.PREF_AUTH_BYPASS_UNTIL_PREFIX + it) }
        return keys
    }

    private fun clearLegacyAuthBypassState(context: Context, sensorIdOverride: String? = null) {
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        legacyAuthBypassPrefKeys(sensorIdOverride?.takeIf { it.isNotBlank() } ?: SerialNumber).forEach { editor.remove(it) }
        editor.apply()
    }

    private fun clearLegacyAuthBypassState(sensorIdOverride: String? = null) {
        val context = Applic.app ?: return
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        legacyAuthBypassPrefKeys(sensorIdOverride?.takeIf { it.isNotBlank() } ?: SerialNumber).forEach { editor.remove(it) }
        editor.apply()
    }

    override fun setPause(pause: Boolean) {
        super.setPause(pause)
        if (!pause) {
            return
        }
        uiPaused = false
        clearSoftDisconnectState()
        if (phase == Phase.IDLE && mBluetoothGatt == null) {
            setUiStatus(UiStatusKind.NONE)
        }
    }

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop || uiPaused) {
            return false
        }
        if (mBluetoothGatt != null) {
            Log.d(TAG, "Skipping duplicate connect for $SerialNumber; GATT already active")
            return true
        }
        if (phase != Phase.IDLE) {
            Log.d(TAG, "Skipping duplicate connect for $SerialNumber; phase=$phase")
            return true
        }
        return super.connectDevice(delayMillis)
    }

    override fun resetdataptr(): Long {
        val previousDataptr = dataptr
        if (previousDataptr != 0L) {
            runCatching { Natives.freedataptr(previousDataptr) }
                .onFailure { Log.stack(TAG, "resetdataptr(freedataptr)", it) }
        }
        close()
        dataptr = 0L
        return 0L
    }

    override fun close() {
        hasAuthenticatedReconnectHint = false
        resetConnectionAttemptState()
        clearGattTransportState()
        super.close()
    }

    override fun getService(): UUID = ICanHealthConstants.CGM_SERVICE

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val trimmedName = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val knownAddress = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        if (knownAddress != null && address != null && address.equals(knownAddress, ignoreCase = true)) {
            return true
        }
        val expectedDisplayName = mygetDeviceName().trim().takeIf { it.isNotEmpty() }
        if (expectedDisplayName != null && trimmedName.equals(expectedDisplayName, ignoreCase = true)) {
            return true
        }
        val advertisedCanonical = ICanHealthConstants.canonicalSensorId(trimmedName)
        if (advertisedCanonical.equals(SerialNumber, ignoreCase = true)) {
            return true
        }
        if (serialFromDevice != null && advertisedCanonical.equals(serialFromDevice, ignoreCase = true)) {
            return true
        }
        if (knownAddress != null) {
            return false
        }
        return ICanHealthConstants.isICanHealthDevice(trimmedName)
    }

    override fun reconnect(now: Long): Boolean {
        if (stop || uiPaused) {
            return true
        }
        val actualNow = System.currentTimeMillis()
        val nextWakeAtMs = scheduledReconnectAtMs
        if (nextWakeAtMs > actualNow + GENERIC_RECONNECT_SLOP_MS) {
            Log.d(
                TAG,
                "Ignoring generic reconnect for $SerialNumber; next iCan wake in ${nextWakeAtMs - actualNow}ms"
            )
            return true
        }
        if (phase != Phase.IDLE || mBluetoothGatt != null) {
            return true
        }
        return connectDevice(0)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (stop) return

        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Connected to ${gatt.device?.address}")
                resetConnectionAttemptState()
                clearGattTransportState(clearReconnect = false, clearServices = true)
                mBluetoothGatt = gatt
                connectTime = System.currentTimeMillis()
                lastConnectionStartedAtMs = connectTime
                receivedGlucoseThisConnection = false
                startCommandIssuedThisConnection = false
                setUiStatus(UiStatusKind.CONNECTED)
                phase = Phase.DISCOVERING_SERVICES
                if (aesKey == null) {
                    Applic.app?.let { loadAesKeyFromPrefs(it) }
                }
                if (recoveredAuthUserId == null) {
                    loadRecoveredUserIdFromPrefs(Applic.app)
                }
                Applic.app?.let { clearLegacyAuthBypassState(it) }
                cancelScheduledReconnect()
                handler.removeCallbacks(postMeasurementDisconnectRunnable)
                ensureNativeDataptr(SerialNumber)
                negotiatedMtu = 23
                serviceDiscoveryStarted = false
                serviceDiscoveryHandled = false
                if (gatt.requestMtu(TARGET_MTU)) {
                    awaitingMtuNegotiation = true
                    Log.i(TAG, "Requesting MTU $TARGET_MTU before service discovery")
                    handler.postDelayed(mtuRequestTimeoutRunnable, MTU_REQUEST_TIMEOUT_MS)
                } else {
                    Log.w(TAG, "Failed to start MTU request — continuing with service discovery")
                    handler.postDelayed({ beginServiceDiscovery(gatt, "mtu-request-failed") }, 100)
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Disconnected (status=$status)")
                phase = Phase.IDLE
                val pausedByUser = uiPaused
                val nowMs = System.currentTimeMillis()
                val reconnectPlan = computeReconnectPlan(nowMs)
                if (reconnectPlan.refreshSessionMetadata) {
                    forceSessionMetadataRefresh = true
                    shouldRequestAuthenticatedHistoryBackfill = true
                }
                if (awaitingFreshStatusForHistoryBackfill) {
                    shouldRequestAuthenticatedHistoryBackfill = true
                }
                if (pausedByUser) {
                    setUiStatus(UiStatusKind.NONE)
                } else if (reconnectPlan.exactAlarm) {
                    setUiStatus(UiStatusKind.WAITING_FOR_NEXT_READING)
                } else {
                    setUiStatus(UiStatusKind.DISCONNECTED)
                }
                isAuthenticated = false
                authAttempted = false
                postAuthSetupStarted = false
                historyBackfillRequested = false
                historyBackfillPhase = HistoryBackfillPhase.NONE
                historyBackfillAttemptedThisConnection = false
                awaitingFreshStatusForHistoryBackfill = false
                awaitingMtuNegotiation = false
                glucoseHistoryImportedRecordCount = 0
                pendingHistoryBackfillReason = null
                pendingHistoryBatch.clear()
                deferredLiveReading = null
                lastAuthChallenge = null
                receivedGlucoseThisConnection = false
                currentSequenceObservedAtMs = 0L
                startCommandIssuedThisConnection = false
                pendingStartTimeEpochMs = 0L
                lastConnectionStartedAtMs = 0L
                clearGattTransportState(clearReconnect = false)

                try {
                    gatt.close()
                } catch (_: Throwable) {
                }
                mBluetoothGatt = null

                if (!stop && !pausedByUser) {
                    scheduleReconnect(reconnectPlan)
                }
                UiRefreshBus.requestStatusRefresh()
            }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        negotiatedMtu = mtu
        awaitingMtuNegotiation = false
        handler.removeCallbacks(mtuRequestTimeoutRunnable)
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val supportsModernHistoryCommands = mtu >= MIN_VENDOR_HISTORY_MTU
            useModernGlucoseHistoryCommand = supportsModernHistoryCommands
            useModernOriginalHistoryCommand = supportsModernHistoryCommands
            Log.i(
                TAG,
                "MTU changed to $mtu; ${if (supportsModernHistoryCommands) "using" else "disabling"} modern vendor history command family"
            )
        } else {
            Log.w(TAG, "MTU change failed status=$status; continuing with existing history command family")
        }
        if (phase == Phase.DISCOVERING_SERVICES && !serviceDiscoveryHandled) {
            // The watchdog may have started discovery early at the default MTU; if no
            // services-discovered callback has arrived, the late MTU callback supersedes
            // the watchdog and re-issues discovery so it is queued after MTU negotiation.
            if (serviceDiscoveryStarted) {
                Log.i(TAG, "MTU callback after watchdog-triggered discovery; reissuing discoverServices at mtu=$mtu")
                serviceDiscoveryStarted = false
            }
            beginServiceDiscovery(gatt, "mtu-changed")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            serviceDiscoveryStarted = false
            Log.e(TAG, "Service discovery failed status=$status")
            gatt.disconnect()
            return
        }
        if (serviceDiscoveryHandled) {
            Log.d(TAG, "Ignoring duplicate services discovered callback for $SerialNumber")
            return
        }
        serviceDiscoveryHandled = true

        cgmService = gatt.getService(ICanHealthConstants.CGM_SERVICE)
        disService = gatt.getService(ICanHealthConstants.DEVICE_INFO_SERVICE)
        charMeasurement = cgmService?.getCharacteristic(ICanHealthConstants.CGM_MEASUREMENT)
        charStatus = cgmService?.getCharacteristic(ICanHealthConstants.CGM_STATUS)
        charSessionStart = cgmService?.getCharacteristic(ICanHealthConstants.CGM_SESSION_START_TIME)
        charRacp = cgmService?.getCharacteristic(ICanHealthConstants.RACP)
        charSpecificOps = cgmService?.getCharacteristic(ICanHealthConstants.CGM_SPECIFIC_OPS)

        Log.i(
            TAG,
            "Services discovered measurement=${charMeasurement != null} status=${charStatus != null} " +
                "sessionStart=${charSessionStart != null} racp=${charRacp != null} specificOps=${charSpecificOps != null}"
        )

        if (charMeasurement == null) {
            Log.e(TAG, "CGM measurement characteristic missing")
            gatt.disconnect()
            return
        }

        setUiStatus(UiStatusKind.PREPARING)
        if (charSpecificOps != null) {
            phase = Phase.AUTH_PRIME
            enqueuePreAuthReads()
            enqueueOp(GattOp.EnableIndicate(ICanHealthConstants.CGM_SPECIFIC_OPS))
            drainGattQueue()
            maybeHandleQueueDrained()
        } else {
            hasAuthenticatedReconnectHint = false
            startPostAuthSetup("no-auth-characteristic")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val data = characteristic.value ?: return
        when (characteristic.uuid) {
            ICanHealthConstants.CGM_MEASUREMENT -> handleGlucoseNotification(data)
            ICanHealthConstants.CGM_SPECIFIC_OPS -> handleVendorAuthResponse(data)
            ICanHealthConstants.RACP -> handleRacpResponse(data)
            else -> Log.d(TAG, "Unhandled notify ${characteristic.uuid}: ${data.toHexString()}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "Read failed for ${characteristic.uuid} status=$status")
            if (characteristic.uuid == ICanHealthConstants.CGM_STATUS && awaitingFreshStatusForHistoryBackfill) {
                continuePendingHistoryBackfill("status read failed")
            }
            finishGattOp()
            return
        }

        val data = characteristic.value ?: byteArrayOf()
        when (characteristic.uuid) {
            ICanHealthConstants.MODEL_NUMBER -> {
                val model = parseDeviceInfoString(data)
                if (model.isNotEmpty()) {
                    vendorModelName = model
                    Log.i(TAG, "Model: $model")
                }
            }

            ICanHealthConstants.SERIAL_NUMBER -> {
                val rawSerial = ICanHealthParser.parseRawDeviceSerial(data)
                val resolvedSerial = ICanHealthParser.parseDeviceSerial(data)
                if (resolvedSerial.isNotEmpty()) {
                    rawSerialFromDevice = rawSerial.takeIf { it.isNotBlank() }
                    applyBundledGlucoseKey(rawSerialFromDevice, vendorSoftwareVersion)
                    Log.i(TAG, "Device serial: $resolvedSerial")
                    handleResolvedSerial(resolvedSerial)
                }
            }

            ICanHealthConstants.FIRMWARE_REVISION -> {
                val fw = parseDeviceInfoString(data)
                if (fw.isNotEmpty()) {
                    vendorFirmwareVersion = fw
                    Log.i(TAG, "Firmware: $fw")
                }
            }

            ICanHealthConstants.SOFTWARE_REVISION -> {
                val sw = parseDeviceInfoString(data)
                if (sw.isNotEmpty()) {
                    vendorSoftwareVersion = sw
                    applyBundledGlucoseKey(rawSerialFromDevice, sw)
                    Log.i(TAG, "Software: $sw")
                }
            }

            ICanHealthConstants.CGM_STATUS -> {
                val statusInfo = ICanHealthParser.parseCgmStatus(data)
                if (statusInfo != null) {
                    val observedSequence = statusInfo.sequenceNumber
                    val previousSequence = currentSequenceNumber
                    val previousLauncherState = launcherState
                    currentSequenceNumber = observedSequence
                    currentSequenceObservedAtMs = System.currentTimeMillis()
                    launcherState = statusInfo.launcherState
                    forceSessionMetadataRefresh = false
                    if (previousSequence >= 0 && observedSequence != previousSequence) {
                        Log.i(
                            TAG,
                            "CGM status advanced $previousSequence->$observedSequence"
                        )
                    }
                    if (previousLauncherState != launcherState) {
                        Log.i(
                            TAG,
                            "Launcher state changed 0x${"%02X".format(previousLauncherState.coerceAtLeast(0))} -> 0x${"%02X".format(launcherState)}"
                        )
                    }
                    Log.i(
                        TAG,
                        "CGM status offset=$observedSequence launcherState=0x${"%02X".format(launcherState)}"
                    )
                }
                if (awaitingFreshStatusForHistoryBackfill) {
                    continuePendingHistoryBackfill("after fresh status read")
                }
            }

            ICanHealthConstants.CGM_SESSION_START_TIME -> {
                val sessionStart = ICanHealthParser.parseSessionStartTime(data)
                if (sessionStart != null) {
                    sessionStartEpochMs = sessionStart.toEpochMillis()
                    sensorstartmsec = sessionStartEpochMs
                    ensureNativeDataptr(SerialNumber)
                    applyNativeSensorMetadata()
                    Log.i(TAG, "Session start epochMs=$sessionStartEpochMs")
                } else {
                    sessionStartEpochMs = 0L
                }
            }
        }

        finishGattOp()
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "Descriptor write failed for ${descriptor.characteristic?.uuid} status=$status")
        }
        finishGattOp()
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "Write failed for ${characteristic.uuid} status=$status")
        }
        finishGattOp()
    }

    private fun handleResolvedSerial(resolvedSerial: String) {
        val canonicalSerial = ICanHealthConstants.canonicalSensorId(resolvedSerial)
        serialFromDevice = canonicalSerial
        val previousId = SerialNumber
        if (provisionalSensorIdForAdoption == null && ICanHealthConstants.isProvisionalSensorId(previousId)) {
            provisionalSensorIdForAdoption = previousId
        }
        SerialNumber = canonicalSerial
        ensureNativeDataptr(canonicalSerial)
        if (previousId != canonicalSerial) {
            clearLegacyAuthBypassState(previousId)
            promoteNativeSensorIdentity(previousId, canonicalSerial)
            promotePersistedSensorIdentity(previousId, canonicalSerial)
            if (Applic.app != null) {
                loadAesKeyFromPrefs(Applic.app, canonicalSerial)
                loadRecoveredUserIdFromPrefs(Applic.app, canonicalSerial)
                clearLegacyAuthBypassState(Applic.app, canonicalSerial)
            }
        }
        loadPersistedCoveredEdge(force = true)
        applyViewModeToNative()
    }

    private fun promoteNativeSensorIdentity(previousId: String, resolvedSerial: String) {
        if (sessionStartEpochMs > 0L) {
            sensorstartmsec = sessionStartEpochMs
        }
        val newSensorPtr = resolveNativeSensorPtr(resolvedSerial)
        val current = runCatching { Natives.lastsensorname() }.getOrNull()
        if ((current.isNullOrBlank() || current == previousId) && newSensorPtr != 0L) {
            val existingNativeName = resolveExistingNativeSensorName(resolvedSerial)
            runCatching {
                Natives.setcurrentsensor(existingNativeName ?: nativeCreationSensorName(resolvedSerial))
            }
                .onFailure { Log.stack(TAG, "promoteNativeSensorIdentity(setcurrentsensor)", it) }
        }

    }

    private fun promotePersistedSensorIdentity(previousId: String, resolvedSerial: String) {
        ICanHealthRegistry.promoteSensorIdentity(
            context = Applic.app,
            oldSensorId = previousId,
            newSensorId = resolvedSerial,
            address = mActiveDeviceAddress,
            displayName = mygetDeviceName()
        )
    }

    private fun handleGlucoseNotification(data: ByteArray) {
        if (ICanHealthParser.isSNNotification(data)) {
            handleSnHistoryNotification(data)
            return
        }

        if (historyBackfillRequested && data.size == ICanHealthConstants.AES_BLOCK_SIZE) {
            if (handleEncryptedHistoryRecord(data, readingOriginalHistory = historyBackfillPhase == HistoryBackfillPhase.ORIGINAL)) {
                return
            }
        }

        val key = aesKey
        if (key == null) {
            Log.w(TAG, "Measurement received without AES key (${data.size} bytes)")
            return
        }

        val reading = ICanHealthParser.parseGlucoseNotification(
            data = data,
            aesKey = key,
            authChallenge = lastAuthChallenge,
            onboardingDeviceSn = onboardingDeviceSn,
            usesNewBundledCrypto = ICanHealthConstants.usesNewBundledCrypto(
                resolveBundledKeySelector(),
                vendorSoftwareVersion
            ),
            warmupMinutes = warmupMinutes(),
        )
            ?: run {
                if (historyBackfillRequested && handleEncryptedHistoryRecord(data, readingOriginalHistory = historyBackfillPhase == HistoryBackfillPhase.ORIGINAL)) {
                    return
                }
                if (historyBackfillRequested) {
                    logUnhandledHistoryPayload("unparsed 2AA7 payload", data)
                }
                return
            }
        if (reading.glucoseMgdl !in ICanHealthConstants.MIN_VALID_GLUCOSE_MGDL..ICanHealthConstants.MAX_VALID_GLUCOSE_MGDL) {
            Log.w(TAG, "Discarding out-of-range glucose ${reading.glucoseMgdl} mg/dL")
            return
        }

        val nowMs = System.currentTimeMillis()
        val anchoredCurrentSequence = currentSequenceNumber
        val sampleTimeMs = resolveSampleTimestampMs(reading.sequenceNumber, nowMs)
        val historySampleTimeMs = resolveHistoryTimestampMs(reading.sequenceNumber, nowMs)
        val cappedHistoryTransfer =
            historyBackfillRequested &&
                historyBackfillPhase == HistoryBackfillPhase.GLUCOSE &&
                canUseHistoryPastEndedStatusCap()
        val liveSequence = !cappedHistoryTransfer &&
            isLiveSequence(reading.sequenceNumber, sampleTimeMs, nowMs, anchoredCurrentSequence)
        if (historyBackfillRequested && historyBackfillPhase == HistoryBackfillPhase.GLUCOSE && !liveSequence) {
            if (historySampleTimeMs == null) {
                logSkippedUnanchoredHistory(reading.sequenceNumber, "2AA7 glucose")
            } else if (!shouldSkipHistoryOverlap(reading.sequenceNumber, historySampleTimeMs)) {
                recordHistoryBackfillSample(
                    sequenceNumber = reading.sequenceNumber,
                    sampleTimeMs = historySampleTimeMs,
                    glucoseMgdl = reading.glucoseMgdl,
                    rawCurrent = reading.currentValue,
                    temperatureC = reading.temperatureC,
                )
            }
        }
        if (liveSequence) {
            receivedGlucoseThisConnection = true
            lastGlucoseReceiptRealtimeMs = nowMs
            forceSessionMetadataRefresh = false
            latestCurrentRaw = reading.currentValue
            latestTemperatureC = reading.temperatureC
            if (reading.sequenceNumber >= 0 &&
                (anchoredCurrentSequence < 0 || reading.sequenceNumber >= anchoredCurrentSequence)
            ) {
                currentSequenceNumber = reading.sequenceNumber
                currentSequenceObservedAtMs = nowMs
            }
            if (historyBackfillRequested && historyBackfillPhase == HistoryBackfillPhase.GLUCOSE) {
                deferredLiveReading = DeferredLiveReading(
                    sequenceNumber = reading.sequenceNumber,
                    timestampMs = sampleTimeMs,
                    glucoseMgdl = reading.glucoseMgdl,
                    rawCurrent = reading.currentValue,
                    temperatureC = reading.temperatureC,
                )
                rememberDriverCurrentReading(reading.glucoseMgdl, sampleTimeMs)
                phase = Phase.STREAMING
                setUiStatus(UiStatusKind.SYNCING)
                scheduleNoDataWatchdog()
                return
            }
            val historyCoveredLiveEdge = historySampleTimeMs?.let {
                shouldSkipHistoryOverlap(reading.sequenceNumber, it)
            } ?: false
            if (reading.sequenceNumber == lastHandledLiveSequence) {
                phase = Phase.STREAMING
                setUiStatus(UiStatusKind.CONNECTED)
                scheduleNoDataWatchdog()
                return
            }
            if (historyCoveredLiveEdge) {
                phase = Phase.STREAMING
                setUiStatus(UiStatusKind.CONNECTED)
                lastHandledLiveSequence = reading.sequenceNumber
                lastHandledLiveTimestampMs = maxOf(sampleTimeMs, persistedCoveredTimestampMs)
                scheduleNoDataWatchdog()
                return
            }
            rememberDriverCurrentReading(reading.glucoseMgdl, sampleTimeMs)
            storeMeasurement(reading.glucoseMgdl, sampleTimeMs)
            phase = Phase.STREAMING
            setUiStatus(UiStatusKind.CONNECTED)
            val packed = packGlucoseResult(reading.glucoseMgdl)
            handleGlucoseResult(packed, sampleTimeMs, resolveRawLaneValue(reading.currentValue, reading.glucoseMgdl))
            lastHandledLiveSequence = reading.sequenceNumber
            lastHandledLiveTimestampMs = sampleTimeMs
            rememberRecentGlucose(reading.glucoseMgdl)
            updatePersistedHistoryTailTimestamp(sampleTimeMs)
            rememberCoveredEdge(reading.sequenceNumber, sampleTimeMs)
            scheduleForegroundNotificationRefresh()
            if (isAuthenticated && shouldRequestAuthenticatedHistoryBackfill && !historyBackfillRequested) {
                requestHistoryBackfill()
            }
            scheduleNoDataWatchdog()
            if (!isAuthenticated) {
                handler.removeCallbacks(postMeasurementDisconnectRunnable)
                handler.postDelayed(postMeasurementDisconnectRunnable, POST_MEASUREMENT_DISCONNECT_DELAY_MS)
            }
        }
    }

    private fun handleEncryptedHistoryRecord(data: ByteArray, readingOriginalHistory: Boolean): Boolean {
        val challenge = lastAuthChallenge
        if (challenge == null || challenge.size != 4) {
            return false
        }
        val record = ICanHealthParser.parseEncryptedHistoryRecord(
            data = data,
            authChallenge = challenge,
            readingOriginalHistory = readingOriginalHistory,
            onboardingDeviceSn = onboardingDeviceSn,
            launcherSerial = rawSerialFromDevice ?: serialFromDevice,
            softwareVersion = vendorSoftwareVersion,
            warmupMinutes = warmupMinutes(),
        ) ?: return false

        return when (record.subtype) {
            ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE -> {
                val glucoseMgdl = record.glucoseMgdl ?: return false
                if (glucoseMgdl !in ICanHealthConstants.MIN_VALID_GLUCOSE_MGDL..ICanHealthConstants.MAX_VALID_GLUCOSE_MGDL) {
                    Log.w(TAG, "Discarding encrypted history glucose ${glucoseMgdl} mg/dL seq=${record.sequenceNumber}")
                    return false
                }
                val nowMs = System.currentTimeMillis()
                val sampleTimeMs = resolveHistoryTimestampMs(record.sequenceNumber, nowMs)
                if (sampleTimeMs == null) {
                    logSkippedUnanchoredHistory(record.sequenceNumber, "encrypted glucose")
                    return true
                }
                receivedGlucoseThisConnection = true
                lastGlucoseReceiptRealtimeMs = nowMs
                if (shouldSkipHistoryOverlap(record.sequenceNumber, sampleTimeMs)) {
                    Log.d(TAG, "Skipping overlapping encrypted history record seq=${record.sequenceNumber}")
                    return true
                }
                recordHistoryBackfillSample(
                    sequenceNumber = record.sequenceNumber,
                    sampleTimeMs = sampleTimeMs,
                    glucoseMgdl = glucoseMgdl,
                    rawCurrent = record.currentValue ?: Float.NaN,
                    temperatureC = record.temperatureC ?: Float.NaN,
                )
                Log.i(
                    TAG,
                    "Parsed encrypted glucose history record seq=${record.sequenceNumber} glucose=${"%.1f".format(glucoseMgdl)}mg/dL"
                )
                true
            }

            ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL -> {
                val nowMs = System.currentTimeMillis()
                val sampleTimeMs = resolveHistoryTimestampMs(record.sequenceNumber, nowMs)
                if (sampleTimeMs == null) {
                    logSkippedUnanchoredHistory(record.sequenceNumber, "encrypted original")
                    return true
                }
                recordHistoryBackfillSample(
                    sequenceNumber = record.sequenceNumber,
                    sampleTimeMs = sampleTimeMs,
                    rawCurrent = record.currentValue ?: Float.NaN,
                    temperatureC = record.temperatureC ?: Float.NaN,
                )
                Log.d(
                    TAG,
                    "Parsed encrypted original-history record seq=${record.sequenceNumber} current=${record.currentValue} temp=${record.temperatureC}"
                )
                true
            }

            else -> false
        }
    }

    private fun handleSnHistoryNotification(data: ByteArray) {
        val snType = ICanHealthParser.parseSNType(data)
        if (!historyBackfillRequested) {
            Log.d(TAG, "SN notification type=${snType ?: -1} len=${data.size} ignored")
            return
        }
        val challenge = lastAuthChallenge
        if (challenge == null || challenge.size != 4) {
            sawUnsupportedSnHistoryBatch = true
            Log.w(TAG, "RACP returned SN history batch without a retained auth challenge; cannot decrypt")
            return
        }
        val batch = ICanHealthParser.parseSnHistoryBatch(
            data = data,
            authChallenge = challenge,
            onboardingDeviceSn = onboardingDeviceSn,
            launcherSerial = rawSerialFromDevice ?: serialFromDevice,
            softwareVersion = vendorSoftwareVersion,
            readingIntervalMinutes = readingIntervalMinutes(),
        )
        if (batch == null) {
            sawUnsupportedSnHistoryBatch = true
            Log.w(TAG, "Failed to parse SN history batch type=${snType ?: -1} len=${data.size}")
            logUnhandledHistoryPayload("unparsed SN history batch type=${snType ?: -1}", data)
            return
        }
        when (batch.subtype) {
            ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE -> {
                val nowMs = System.currentTimeMillis()
                var importedCount = 0
                var skippedUnanchoredCount = 0
                for (record in batch.records) {
                    val glucoseMgdl = record.glucoseMgdl
                    if (glucoseMgdl == null ||
                        glucoseMgdl !in ICanHealthConstants.MIN_VALID_GLUCOSE_MGDL..ICanHealthConstants.MAX_VALID_GLUCOSE_MGDL) {
                        continue
                    }
                    val sampleTimeMs = resolveHistoryTimestampMs(record.sequenceNumber, nowMs)
                    if (sampleTimeMs == null) {
                        skippedUnanchoredCount++
                        continue
                    }
                    if (shouldSkipHistoryOverlap(record.sequenceNumber, sampleTimeMs)) {
                        continue
                    }
                    recordHistoryBackfillSample(
                        sequenceNumber = record.sequenceNumber,
                        sampleTimeMs = sampleTimeMs,
                        glucoseMgdl = glucoseMgdl,
                        rawCurrent = record.currentValue ?: Float.NaN,
                        temperatureC = record.temperatureC ?: Float.NaN,
                    )
                    importedCount++
                }
                Log.i(
                    TAG,
                    "Parsed SN glucose history batch base=${batch.baseSequenceNumber} records=${batch.records.size} " +
                        "imported=$importedCount skippedUnanchored=$skippedUnanchoredCount"
                )
                if (importedCount > 0) {
                    sawUnsupportedSnHistoryBatch = false
                } else if (skippedUnanchoredCount > 0) {
                    sawUnsupportedSnHistoryBatch = false
                } else {
                    sawUnsupportedSnHistoryBatch = true
                }
            }

            ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL -> {
                val nowMs = System.currentTimeMillis()
                var importedCount = 0
                var skippedUnanchoredCount = 0
                for (record in batch.records) {
                    val sampleTimeMs = resolveHistoryTimestampMs(record.sequenceNumber, nowMs)
                    if (sampleTimeMs == null) {
                        skippedUnanchoredCount++
                        continue
                    }
                    recordHistoryBackfillSample(
                        sequenceNumber = record.sequenceNumber,
                        sampleTimeMs = sampleTimeMs,
                        rawCurrent = record.currentValue ?: Float.NaN,
                        temperatureC = record.temperatureC ?: Float.NaN,
                    )
                    importedCount++
                }
                Log.d(
                    TAG,
                    "Parsed SN original-history batch base=${batch.baseSequenceNumber} records=${batch.records.size} " +
                        "imported=$importedCount skippedUnanchored=$skippedUnanchoredCount"
                )
                if (importedCount > 0) {
                    sawUnsupportedSnHistoryBatch = false
                }
            }

            else -> {
                sawUnsupportedSnHistoryBatch = true
                Log.w(TAG, "Unsupported SN history subtype=${batch.subtype} len=${data.size}")
            }
        }
    }

    private fun storeMeasurement(glucoseMgdl: Float, sampleTimeMs: Long) {
        if (SerialNumber.isBlank() || sampleTimeMs <= 0L) {
            return
        }
        val sampleTimeSec = sampleTimeMs / 1000L
        val nativeStreamValue = glucoseMgdl / 10f
        runCatching {
            ensureNativeDataptr(SerialNumber)
            applyNativeSensorMetadata()
            val nativeWriteName = resolveExistingNativeSensorName(SerialNumber)
                ?: nativeCreationSensorName(SerialNumber)
            prepareNativeMirrorWindow(nativeWriteName, sampleTimeSec)
            // Native addGlucoseStream() multiplies its float input by 10 before
            // storing the internal mg/dL value. Feed mg/dL / 10 here so native
            // stream storage matches the driver-decoded glucose value.
            Natives.addGlucoseStreamWithTemp(
                sampleTimeSec,
                nativeStreamValue,
                latestTemperatureC,
                nativeWriteName
            )
            applyNativeSensorMetadata()
            val sensorPtr = resolveNativeSensorPtr(SerialNumber)
            if (sensorPtr != 0L || nativeWriteName.isNotBlank()) {
                adoptNativeSensorIfAppropriate(SerialNumber, nativeWriteName)
            }
            Natives.wakebackup()
        }.onFailure {
            Log.stack(TAG, "storeMeasurement", it)
        }
    }

    private fun resolveSampleTimestampMs(sequenceNumber: Int, fallbackNowMs: Long): Long {
        if (sequenceNumber < 0) {
            return fallbackNowMs
        }
        val resolved = resolveSequenceTimelineTimestampMs(
            sequenceNumber = sequenceNumber,
            fallbackNowMs = fallbackNowMs,
            requireRecentCandidate = true
        )
        if (resolved != null) {
            if (resolved <= fallbackNowMs) {
                return resolved
            }
            Log.w(
                TAG,
                "Rejecting future live timestamp seq=$sequenceNumber candidate=$resolved now=$fallbackNowMs"
            )
        }
        return fallbackNowMs
    }

    private fun resolveHistoryTimestampMs(sequenceNumber: Int, fallbackNowMs: Long): Long? {
        if (sequenceNumber < 0) {
            return null
        }
        return resolveSequenceTimelineTimestampMs(
            sequenceNumber = sequenceNumber,
            fallbackNowMs = fallbackNowMs,
            requireRecentCandidate = false
        )
    }

    private fun isLiveSequence(
        sequenceNumber: Int,
        sampleTimeMs: Long,
        nowMs: Long,
        anchorSequenceNumber: Int = currentSequenceNumber,
    ): Boolean {
        if (anchorSequenceNumber >= 0) {
            return sequenceNumber >= (anchorSequenceNumber - LIVE_SEQUENCE_LAG_ALLOWANCE)
        }
        return abs(nowMs - sampleTimeMs) <= (readingIntervalMs() * 2)
    }

    private fun packGlucoseResult(glucoseMgdl: Float): Long {
        val glucoseTimesTen = (glucoseMgdl * 10f).toInt()
        val rate: Short = 0
        val alarm = 0
        return (glucoseTimesTen.toLong() and 0xFFFFFFFFL) or
            (rate.toLong() shl 32) or
            (alarm.toLong() shl 48)
    }

    private fun handleVendorAuthResponse(data: ByteArray) {
        Log.d(TAG, "Vendor response: ${data.toHexString()}")
        when {
            ICanHealthParser.isChallengeResponse(data) -> {
                val challenge = ICanHealthParser.extractChallenge(data) ?: return
                lastAuthChallenge = challenge.copyOf()
                val authKeyAscii = ICanHealthConstants.resolveBundledAuthKey(
                    resolveBundledKeySelector(),
                    vendorSoftwareVersion
                )
                val authKey = ICanHealthCrypto.keyFromASCII(authKeyAscii)
                val userId = resolveAuthUserId()
                val token = authKey?.let { ICanHealthParser.deriveAuthToken(challenge, userId, it) }
                if (token == null) {
                    hasAuthenticatedReconnectHint = false
                    Log.w(
                        TAG,
                        "Failed to derive auth token for ${challenge.toHexString()} userId=$userId ${bundledKeyContext()} — continuing without vendor auth"
                    )
                    startPostAuthSetup("auth-derivation-failed")
                    return
                }
                ICanHealthParser.buildAuthTokenPacket(token)?.let {
                    enqueueOp(GattOp.Write(ICanHealthConstants.CGM_SPECIFIC_OPS, it))
                    drainGattQueue()
                } ?: run {
                    Log.w(TAG, "Failed to build auth token packet — continuing without vendor auth")
                    startPostAuthSetup("auth-packet-build-failed")
                }
            }

            ICanHealthParser.isAuthSuccess(data) -> {
                isAuthenticated = true
                hasAuthenticatedReconnectHint = true
                shouldRequestAuthenticatedHistoryBackfill = true
                setUiStatus(UiStatusKind.AUTHENTICATED)
                handler.removeCallbacks(sensorInfoFallbackRunnable)
                ICanHealthConstants.CMD_REQUEST_SENSOR_INFO.let {
                    enqueueOp(GattOp.Write(ICanHealthConstants.CGM_SPECIFIC_OPS, it))
                    drainGattQueue()
                    handler.postDelayed(sensorInfoFallbackRunnable, SENSOR_INFO_TIMEOUT_MS)
                }
            }

            ICanHealthParser.parseStartSensorResult(data) != null -> {
                val started = ICanHealthParser.parseStartSensorResult(data) == true
                if (started) {
                    val startTimeMs = pendingStartTimeEpochMs.takeIf { it > 0L } ?: resolveStandaloneStartTimeMs()
                    pendingStartTimeEpochMs = startTimeMs
                    sessionStartEpochMs = startTimeMs
                    sensorstartmsec = startTimeMs
                    applyNativeSensorMetadata()
                    val startTimePacket = ICanHealthParser.buildSessionStartWrite(startTimeMs)
                    phase = Phase.STARTING_SENSOR
                    enqueueOp(
                        GattOp.Write(
                            ICanHealthConstants.CGM_SESSION_START_TIME,
                            startTimePacket,
                            ICanHealthConstants.CGM_SERVICE
                        )
                    )
                    enqueueOp(GattOp.Read(ICanHealthConstants.CGM_STATUS))
                    enqueueOp(GattOp.Read(ICanHealthConstants.CGM_SESSION_START_TIME))
                    drainGattQueue()
                } else {
                    Log.w(TAG, "Start sensor command rejected: ${data.toHexString()}")
                    // Recovery path for half-activated WARMUP state: if the sensor
                    // rejected 0x1A (e.g., already past IDLE) but still has no valid
                    // session start time, write it directly. The CGM Session Start
                    // Time characteristic (0x2AAA) is Read/Write per BT CGM spec —
                    // writing it anchors measurement timestamps so the sensor can
                    // produce valid readings once warmup completes.
                    val needsSessionStartRecovery = sessionStartEpochMs <= 0L &&
                        (launcherState == ICanHealthConstants.LAUNCHER_STATE_WARMUP ||
                            launcherState == ICanHealthConstants.LAUNCHER_STATE_RUNNING)
                    if (needsSessionStartRecovery && charSessionStart != null) {
                        val recoveryStartMs = resolveStandaloneStartTimeMs()
                        pendingStartTimeEpochMs = recoveryStartMs
                        sessionStartEpochMs = recoveryStartMs
                        sensorstartmsec = recoveryStartMs
                        applyNativeSensorMetadata()
                        Log.i(
                            TAG,
                            "Writing session start time directly to recover stuck WARMUP/RUNNING state"
                        )
                        phase = Phase.STARTING_SENSOR
                        enqueueOp(
                            GattOp.Write(
                                ICanHealthConstants.CGM_SESSION_START_TIME,
                                ICanHealthParser.buildSessionStartWrite(recoveryStartMs),
                                ICanHealthConstants.CGM_SERVICE
                            )
                        )
                        enqueueOp(GattOp.Read(ICanHealthConstants.CGM_STATUS))
                        enqueueOp(GattOp.Read(ICanHealthConstants.CGM_SESSION_START_TIME))
                        drainGattQueue()
                    } else {
                        hasAuthenticatedReconnectHint = false
                        phase = Phase.STREAMING
                        setUiStatus(UiStatusKind.CONNECTED)
                        scheduleNoDataWatchdogIfNeeded()
                    }
                }
            }

            ICanHealthParser.parseCalibrationResult(data) != null -> {
                val calibrationResult = ICanHealthParser.parseCalibrationResult(data) ?: return
                if (calibrationResult == 0x01) {
                    lastCeCalibrationCount = (lastCeCalibrationCount + 1).coerceAtMost(4)
                    Log.i(TAG, "Sensor calibration accepted")
                } else {
                    Log.w(TAG, "Sensor calibration rejected with code=0x${"%02X".format(calibrationResult)}")
                }
            }

            ICanHealthParser.parseAuthResult(data) != null -> {
                val authResult = ICanHealthParser.parseAuthResult(data) ?: return

                // Attempt userId recovery: when status == 0x02 with a non-zero
                // trailing byte, the sensor echoes back the expected userId
                // encrypted with the same auth key + challenge IV.
                val authKeyAscii = ICanHealthConstants.resolveBundledAuthKey(
                    resolveBundledKeySelector(),
                    vendorSoftwareVersion
                )
                val authKey = ICanHealthCrypto.keyFromASCII(authKeyAscii)
                val recovered = if (authKey != null) {
                    ICanHealthParser.recoverUserIdFromAuthReject(data, lastAuthChallenge, authKey)
                } else null

                if (recovered != null && authRetryCount < 1) {
                    authRetryCount++
                    recoveredAuthUserId = recovered
                    persistRecoveredUserId(recovered)
                    Log.w(
                        TAG,
                        "Auth rejected (0x${"%02X".format(authResult)}) but recovered realUserId='$recovered' — retrying auth"
                    )
                    // Re-request a fresh challenge with the recovered userId
                    ICanHealthConstants.CMD_REQUEST_CHALLENGE.let {
                        enqueueOp(GattOp.Write(ICanHealthConstants.CGM_SPECIFIC_OPS, it))
                        drainGattQueue()
                    }
                } else {
                    hasAuthenticatedReconnectHint = false
                    Log.w(
                        TAG,
                        "Vendor auth rejected with code=0x${"%02X".format(authResult)} userId=${resolveAuthUserId()} ${bundledKeyContext()} — continuing unauthenticated"
                    )
                    startPostAuthSetup("auth-failed-0x${"%02X".format(authResult)}")
                }
            }

            ICanHealthParser.isSensorInfoResponse(data) -> {
                handler.removeCallbacks(sensorInfoFallbackRunnable)
                Log.i(TAG, "Sensor info: ${data.toHexString()}")
                startPostAuthSetup("sensor-info")
            }

            else -> {
                if (phase == Phase.AUTH_HANDSHAKE) {
                    hasAuthenticatedReconnectHint = false
                    Log.w(TAG, "Unexpected auth response ${data.toHexString()} — continuing")
                    startPostAuthSetup("unexpected-auth-response")
                }
            }
        }
    }

    private fun resolveAuthUserId(): String {
        val source = when {
            !recoveredAuthUserId.isNullOrBlank() -> recoveredAuthUserId
            !configuredAuthUserId.isNullOrBlank() -> configuredAuthUserId
            ICanHealthConstants.DEFAULT_AUTH_USER_ID.isNotBlank() -> ICanHealthConstants.DEFAULT_AUTH_USER_ID
            !onboardingDeviceSn.isNullOrBlank() -> onboardingDeviceSn
            !rawSerialFromDevice.isNullOrBlank() -> rawSerialFromDevice
            !serialFromDevice.isNullOrBlank() -> serialFromDevice
            !SerialNumber.isBlank() && !ICanHealthConstants.isProvisionalSensorId(SerialNumber) -> SerialNumber
            !mygetDeviceName().isNullOrBlank() && ICanHealthConstants.isLikelyPersistedSensorName(mygetDeviceName()) -> mygetDeviceName()
            !mActiveDeviceAddress.isNullOrBlank() -> mActiveDeviceAddress?.replace(":", "")
            else -> null
        }
        return ICanHealthConstants.normalizeStandaloneUserId(source)
    }

    private fun enqueuePreAuthReads() {
        if (serialFromDevice.isNullOrBlank()) {
            disService?.getCharacteristic(ICanHealthConstants.SERIAL_NUMBER)?.let {
                enqueueOp(GattOp.Read(ICanHealthConstants.SERIAL_NUMBER, ICanHealthConstants.DEVICE_INFO_SERVICE))
            }
        }
        if (vendorSoftwareVersion.isBlank()) {
            disService?.getCharacteristic(ICanHealthConstants.SOFTWARE_REVISION)?.let {
                enqueueOp(GattOp.Read(ICanHealthConstants.SOFTWARE_REVISION, ICanHealthConstants.DEVICE_INFO_SERVICE))
            }
        }
    }

    private fun handleRacpResponse(data: ByteArray) {
        when (ICanHealthParser.parseRacpResultCode(data)) {
            ICanHealthConstants.RACP_RESULT_SUCCESS -> {
                Log.i(TAG, "RACP transfer complete")
                handleRacpPhaseCompletion(noData = false)
            }

            ICanHealthConstants.RACP_RESULT_NO_DATA -> {
                Log.i(TAG, "RACP reported no data")
                handleRacpPhaseCompletion(noData = true)
            }

            ICanHealthConstants.RACP_RESULT_NOT_SUPPORTED -> {
                handleRacpNotSupported()
            }

            ICanHealthConstants.RACP_RESULT_FAILED -> {
                handleRacpFailed()
            }

            else -> {
            Log.d(TAG, "RACP response: ${data.toHexString()}")
            }
        }
    }

    private fun startPostAuthSetup(reason: String) {
        if (postAuthSetupStarted) {
            return
        }
        Log.i(TAG, "Starting post-auth setup ($reason)")
        handler.removeCallbacks(sensorInfoFallbackRunnable)
        postAuthSetupStarted = true
        phase = Phase.POST_AUTH_SETUP
        setUiStatus(UiStatusKind.CONFIGURING)

        charMeasurement?.let { enqueueOp(GattOp.EnableNotify(ICanHealthConstants.CGM_MEASUREMENT)) }
        if (isAuthenticated) {
            charRacp?.let { enqueueOp(GattOp.EnableIndicate(ICanHealthConstants.RACP)) }
        }
        val refreshStaticInfo = shouldRefreshStaticDeviceInfo()
        val refreshSessionInfo = shouldRefreshSessionMetadata()
        if (!refreshStaticInfo && !refreshSessionInfo) {
            Log.d(TAG, "Using cached iCan metadata on reconnect; waiting for measurement only")
        }
        if (refreshStaticInfo) {
            disService?.getCharacteristic(ICanHealthConstants.MODEL_NUMBER)?.let {
                enqueueOp(GattOp.Read(ICanHealthConstants.MODEL_NUMBER, ICanHealthConstants.DEVICE_INFO_SERVICE))
            }
            disService?.getCharacteristic(ICanHealthConstants.SERIAL_NUMBER)?.let {
                enqueueOp(GattOp.Read(ICanHealthConstants.SERIAL_NUMBER, ICanHealthConstants.DEVICE_INFO_SERVICE))
            }
            disService?.getCharacteristic(ICanHealthConstants.FIRMWARE_REVISION)?.let {
                enqueueOp(GattOp.Read(ICanHealthConstants.FIRMWARE_REVISION, ICanHealthConstants.DEVICE_INFO_SERVICE))
            }
            disService?.getCharacteristic(ICanHealthConstants.SOFTWARE_REVISION)?.let {
                enqueueOp(GattOp.Read(ICanHealthConstants.SOFTWARE_REVISION, ICanHealthConstants.DEVICE_INFO_SERVICE))
            }
        }
        if (refreshSessionInfo) {
            charStatus?.let { enqueueOp(GattOp.Read(ICanHealthConstants.CGM_STATUS)) }
            charSessionStart?.let { enqueueOp(GattOp.Read(ICanHealthConstants.CGM_SESSION_START_TIME)) }
        }
        drainGattQueue()
        maybeHandleQueueDrained()
    }

    private fun resolveAutomaticGlucoseHistoryStartSequence(): Int? {
        val nowMs = System.currentTimeMillis()
        val readingInterval = readingIntervalMinutes()
        if (canUseHistoryPastEndedStatusCap()) {
            return resolveCappedEndedHistoryStartSequence(nowMs, readingInterval)
        }
        val anchorTimeMs = resolveSequenceAnchorTimeMs(nowMs)
        val persistedTailSequence = estimatePersistedTailSequence(anchorTimeMs)
        val latestKnownSequence = maxOf(lastHandledLiveSequence, persistedTailSequence ?: -1)
        if (latestKnownSequence >= readingInterval) {
            val nextMissingSequence = latestKnownSequence + readingInterval
            if (currentSequenceNumber >= nextMissingSequence) {
                return nextMissingSequence
            }
            Log.i(
                TAG,
                "Skipping glucose-history read; persisted/local tail already covers current end index (tailSeq=$latestKnownSequence current=$currentSequenceNumber)"
            )
            return null
        }
        // Cold start / no in-memory tail: mirror the vendor fallback and ask
        // from the first valid glucose-history slot.
        return readingInterval
    }

    private fun resolveCappedEndedHistoryStartSequence(nowMs: Long, readingInterval: Int): Int? {
        val capSequence = currentSequenceNumber.coerceAtLeast(readingInterval)
        val latestTailTimestamp = refreshPersistedHistoryTailTimestamp()
        if (latestTailTimestamp <= 0L) {
            Log.i(TAG, "Requesting capped iCan glucose history from first slot; no persisted tail is available")
            return readingInterval
        }

        val intervalMs = readingIntervalMs()
        val elapsedMs = nowMs - latestTailTimestamp
        if (elapsedMs < intervalMs - MAX_SESSION_TIMESTAMP_FUTURE_DRIFT_MS) {
            Log.d(
                TAG,
                "Skipping capped iCan history read; latest tail is still current (tail=$latestTailTimestamp now=$nowMs)"
            )
            return null
        }

        val missedIntervals = ((elapsedMs.coerceAtLeast(0L) + intervalMs - 1L) / intervalMs)
            .coerceAtLeast(1L)
            .toInt()
        val startSequence = (capSequence - (missedIntervals + 1) * readingInterval)
            .coerceAtLeast(readingInterval)
        Log.i(
            TAG,
            "Requesting capped iCan history window from seq=$startSequence " +
                "(cap=$capSequence tail=$latestTailTimestamp now=$nowMs)"
        )
        return startSequence
    }

    private fun continuePendingHistoryBackfill(reasonSuffix: String) {
        if (!awaitingFreshStatusForHistoryBackfill) {
            return
        }
        awaitingFreshStatusForHistoryBackfill = false
        val baseReason = pendingHistoryBackfillReason ?: "after authenticated session metadata refresh"
        pendingHistoryBackfillReason = null
        glucoseHistoryImportedRecordCount = 0
        val startSequence = resolveAutomaticGlucoseHistoryStartSequence()
        if (startSequence == null) {
            historyBackfillAttemptedThisConnection = false
            shouldRequestAuthenticatedHistoryBackfill = canUseHistoryPastEndedStatusCap()
            return
        }
        pendingGlucoseHistoryStartSequence = startSequence
        historyBackfillAttemptedThisConnection = true
        if (!beginHistoryBackfillPhase(
                HistoryBackfillPhase.GLUCOSE,
                "$baseReason ($reasonSuffix)"
            )
        ) {
            historyBackfillAttemptedThisConnection = false
            shouldRequestAuthenticatedHistoryBackfill = true
        }
    }

    private fun beginHistoryBackfillPhase(phase: HistoryBackfillPhase, reason: String): Boolean {
        val racpCharacteristic = charRacp ?: return false
        val command = when (phase) {
            HistoryBackfillPhase.GLUCOSE -> {
                val startSequence = pendingGlucoseHistoryStartSequence
                pendingGlucoseHistoryStartSequence = startSequence
                if (currentSequenceNumber in 0 until startSequence &&
                    !canUseHistoryPastEndedStatusCap()
                ) {
                    Log.i(
                        TAG,
                        "Skipping glucose-history read from seq=$startSequence because current end index=$currentSequenceNumber; chaining original-history phase"
                    )
                    return beginHistoryBackfillPhase(
                        HistoryBackfillPhase.ORIGINAL,
                        "current end index below glucose-history start"
                    )
                }
                ICanHealthConstants.buildRacpReportFromGlucose(
                    startSequence,
                    modernPrefix = useModernGlucoseHistoryCommand
                )
            }

            HistoryBackfillPhase.ORIGINAL -> ICanHealthConstants.buildRacpReportAllOriginal(
                modernPrefix = useModernOriginalHistoryCommand
            )

            HistoryBackfillPhase.NONE -> return false
        }
        historyBackfillRequested = true
        historyBackfillPhase = phase
        sawUnsupportedSnHistoryBatch = false
        setUiStatus(UiStatusKind.SYNCING)
        if (phase == HistoryBackfillPhase.GLUCOSE) {
            pendingHistoryBatch.clear()
        }
        val family = when (phase) {
            HistoryBackfillPhase.GLUCOSE -> if (useModernGlucoseHistoryCommand) "F1" else "01"
            HistoryBackfillPhase.ORIGINAL -> if (useModernOriginalHistoryCommand) "F1" else "01"
            HistoryBackfillPhase.NONE -> ""
        }
        val phaseDescription = when (phase) {
            HistoryBackfillPhase.GLUCOSE -> "glucose history from seq=$pendingGlucoseHistoryStartSequence"
            HistoryBackfillPhase.ORIGINAL -> "original history report-all"
            HistoryBackfillPhase.NONE -> "history"
        }
        Log.i(TAG, "Requesting $phaseDescription using $family command family ($reason)")
        enqueueOp(GattOp.Write(racpCharacteristic.uuid, command))
        drainGattQueue()
        return true
    }

    private fun finalizeHistoryBackfillAfterOriginalPhase() {
        historyBackfillRequested = false
        historyBackfillPhase = HistoryBackfillPhase.NONE
        pendingHistoryBatch.clear()
        if (phase == Phase.STREAMING) {
            setUiStatus(UiStatusKind.CONNECTED)
        }
        val importedGlucoseHistory = glucoseHistoryImportedRecordCount
        val unsupportedBatch = sawUnsupportedSnHistoryBatch
        sawUnsupportedSnHistoryBatch = false
        if (importedGlucoseHistory > 0) {
            val cappedHistoryPolling = canUseHistoryPastEndedStatusCap()
            suppressAutomaticHistoryBackfill = false
            shouldRequestAuthenticatedHistoryBackfill = cappedHistoryPolling
            receivedGlucoseThisConnection = true
            lastGlucoseReceiptRealtimeMs = System.currentTimeMillis()
            phase = Phase.STREAMING
            setUiStatus(UiStatusKind.CONNECTED)
            scheduleForegroundNotificationRefresh()
            scheduleNoDataWatchdog()
            Log.i(
                TAG,
                "Authenticated glucose-history cycle completed with $importedGlucoseHistory imported records" +
                    if (cappedHistoryPolling) "; capped history polling remains enabled" else ""
            )
        } else if (unsupportedBatch) {
            suppressAutomaticHistoryBackfill = true
            shouldRequestAuthenticatedHistoryBackfill = false
            Log.w(TAG, "Automatic iCan history fetch disabled for this callback: sensor returned unsupported history batches")
        } else {
            suppressAutomaticHistoryBackfill = false
            shouldRequestAuthenticatedHistoryBackfill = true
            Log.i(TAG, "Authenticated history cycle completed without parseable glucose backlog; will retry on a later authenticated reconnect")
        }
    }

    private fun flushImportedGlucoseHistory() {
        val importedHistoryCount = pendingHistoryBatch.size
        if (importedHistoryCount > 0) {
            glucoseHistoryImportedRecordCount += importedHistoryCount
        }
        val storedDirectlyInRoom = flushHistoryBackfillBatch()
        if (importedHistoryCount > 0 && SerialNumber.isNotBlank()) {
            if (!storedDirectlyInRoom) {
                Log.w(
                    TAG,
                    "Direct iCan Room history import failed; falling back to native/full sensor merge for $SerialNumber"
                )
                HistorySyncAccess.mergeFullSyncForSensor(SerialNumber)
            } else {
                if (hasObservedLiveMeasurement()) {
                    scheduleForegroundNotificationRefresh()
                }
            }
            UiRefreshBus.requestDataRefresh()
        }
        applyDeferredLiveReadingAfterHistoryImport()
    }

    private fun applyDeferredLiveReadingAfterHistoryImport() {
        val deferred = deferredLiveReading ?: return
        deferredLiveReading = null
        if (deferred.sequenceNumber == lastHandledLiveSequence) {
            return
        }
        if (shouldSkipHistoryOverlap(deferred.sequenceNumber, deferred.timestampMs)) {
            phase = Phase.STREAMING
            setUiStatus(UiStatusKind.CONNECTED)
            lastHandledLiveSequence = deferred.sequenceNumber
            lastHandledLiveTimestampMs = maxOf(deferred.timestampMs, persistedCoveredTimestampMs)
            scheduleForegroundNotificationRefresh()
            scheduleNoDataWatchdog()
            return
        }
        rememberDriverCurrentReading(deferred.glucoseMgdl, deferred.timestampMs)
        storeMeasurement(deferred.glucoseMgdl, deferred.timestampMs)
        phase = Phase.STREAMING
        setUiStatus(UiStatusKind.CONNECTED)
        val packed = packGlucoseResult(deferred.glucoseMgdl)
        handleGlucoseResult(
            packed,
            deferred.timestampMs,
            resolveRawLaneValue(deferred.rawCurrent, deferred.glucoseMgdl)
        )
        lastHandledLiveSequence = deferred.sequenceNumber
        lastHandledLiveTimestampMs = deferred.timestampMs
        rememberRecentGlucose(deferred.glucoseMgdl)
        updatePersistedHistoryTailTimestamp(deferred.timestampMs)
        rememberCoveredEdge(deferred.sequenceNumber, deferred.timestampMs)
        scheduleForegroundNotificationRefresh()
        scheduleNoDataWatchdog()
    }

    private fun scheduleMirrorHistoryMerge(delayMs: Long = 750L) {
        if (hasLocalPersistedRecord()) {
            return
        }
        mirrorHistoryMergeScheduled = true
        handler.removeCallbacks(mirrorHistoryMergeRunnable)
        handler.postDelayed(mirrorHistoryMergeRunnable, delayMs)
    }

    private fun mirrorDerivedStartTimeMs(): Long? {
        val nativeSensorPtr = resolveNativeSensorPtr(SerialNumber)
        if (nativeSensorPtr == 0L) {
            return null
        }
        val reportedMinutes = runCatching {
            maxOf(
                Natives.getlastHistoricLifeCountReceived(nativeSensorPtr),
                Natives.getlastLifeCountReceived(nativeSensorPtr)
            )
        }.getOrDefault(0)
        if (reportedMinutes <= 0) {
            return null
        }
        val ageMs = reportedMinutes.toLong() * 60_000L
        return (System.currentTimeMillis() - ageMs).coerceAtLeast(0L)
    }

    private fun handleRacpPhaseCompletion(noData: Boolean) {
        when (historyBackfillPhase) {
            HistoryBackfillPhase.GLUCOSE -> {
                historyBackfillRequested = false
                historyBackfillPhase = HistoryBackfillPhase.NONE
                flushImportedGlucoseHistory()
                finalizeHistoryBackfillAfterOriginalPhase()
            }

            HistoryBackfillPhase.ORIGINAL -> {
                finalizeHistoryBackfillAfterOriginalPhase()
            }

            HistoryBackfillPhase.NONE -> {
                historyBackfillRequested = false
                pendingHistoryBatch.clear()
                sawUnsupportedSnHistoryBatch = false
                Log.d(TAG, "RACP response arrived with no active history phase")
            }
        }
    }

    // Some firmware variants (e.g. iCGM-t3 V01.00.06.00_B0005, iCGM-t6 V01.05.00.00)
    // accept the modern F1 RACP prefix but return RACP_RESULT_FAILED instead of
    // RACP_RESULT_NOT_SUPPORTED. Mirror the not-supported fallback once so legacy
    // 01-prefixed commands get a chance before we give up for this connection.
    private fun handleRacpFailed() {
        when (historyBackfillPhase) {
            HistoryBackfillPhase.GLUCOSE -> {
                historyBackfillRequested = false
                historyBackfillPhase = HistoryBackfillPhase.NONE
                pendingHistoryBatch.clear()
                sawUnsupportedSnHistoryBatch = false
                if (useModernGlucoseHistoryCommand) {
                    useModernGlucoseHistoryCommand = false
                    Log.w(TAG, "Modern glucose-history RACP command failed; retrying with legacy command family")
                    beginHistoryBackfillPhase(
                        HistoryBackfillPhase.GLUCOSE,
                        "retrying after RACP-failed on modern glucose-history command"
                    )
                } else {
                    suppressAutomaticHistoryBackfill = false
                    shouldRequestAuthenticatedHistoryBackfill = glucoseHistoryImportedRecordCount <= 0
                    Log.w(TAG, "RACP history request failed; will retry on a later authenticated reconnect")
                }
            }

            HistoryBackfillPhase.ORIGINAL -> {
                historyBackfillRequested = false
                historyBackfillPhase = HistoryBackfillPhase.NONE
                pendingHistoryBatch.clear()
                sawUnsupportedSnHistoryBatch = false
                if (useModernOriginalHistoryCommand) {
                    useModernOriginalHistoryCommand = false
                    Log.w(TAG, "Modern original-history RACP command failed; retrying with legacy command family")
                    beginHistoryBackfillPhase(
                        HistoryBackfillPhase.ORIGINAL,
                        "retrying after RACP-failed on modern original-history command"
                    )
                } else {
                    suppressAutomaticHistoryBackfill = false
                    shouldRequestAuthenticatedHistoryBackfill = glucoseHistoryImportedRecordCount <= 0
                    Log.w(TAG, "RACP history request failed; will retry on a later authenticated reconnect")
                }
            }

            HistoryBackfillPhase.NONE -> {
                historyBackfillRequested = false
                pendingHistoryBatch.clear()
                sawUnsupportedSnHistoryBatch = false
                suppressAutomaticHistoryBackfill = false
                shouldRequestAuthenticatedHistoryBackfill = glucoseHistoryImportedRecordCount <= 0
                Log.w(TAG, "RACP history request failed; will retry on a later authenticated reconnect")
            }
        }
    }

    private fun handleRacpNotSupported() {
        when (historyBackfillPhase) {
            HistoryBackfillPhase.GLUCOSE -> {
                historyBackfillRequested = false
                historyBackfillPhase = HistoryBackfillPhase.NONE
                pendingHistoryBatch.clear()
                sawUnsupportedSnHistoryBatch = false
                if (useModernGlucoseHistoryCommand) {
                    useModernGlucoseHistoryCommand = false
                    Log.w(TAG, "Modern glucose-history RACP command not supported; retrying with legacy command family")
                    beginHistoryBackfillPhase(
                        HistoryBackfillPhase.GLUCOSE,
                        "retrying after operation-not-supported on modern glucose-history command"
                    )
                } else {
                    Log.w(TAG, "Legacy glucose-history RACP command also not supported")
                    finalizeHistoryBackfillAfterOriginalPhase()
                }
            }

            HistoryBackfillPhase.ORIGINAL -> {
                historyBackfillRequested = false
                historyBackfillPhase = HistoryBackfillPhase.NONE
                pendingHistoryBatch.clear()
                sawUnsupportedSnHistoryBatch = false
                if (useModernOriginalHistoryCommand) {
                    useModernOriginalHistoryCommand = false
                    Log.w(TAG, "Modern original-history RACP command not supported; retrying with legacy command family")
                    beginHistoryBackfillPhase(
                        HistoryBackfillPhase.ORIGINAL,
                        "retrying after operation-not-supported on modern original-history command"
                    )
                } else {
                    Log.w(TAG, "Legacy original-history RACP command also not supported")
                    finalizeHistoryBackfillAfterOriginalPhase()
                }
            }

            HistoryBackfillPhase.NONE -> {
                historyBackfillRequested = false
                pendingHistoryBatch.clear()
                sawUnsupportedSnHistoryBatch = false
                suppressAutomaticHistoryBackfill = true
                shouldRequestAuthenticatedHistoryBackfill = false
                Log.w(TAG, "RACP command is not supported by this sensor/session")
            }
        }
    }

    private fun requestHistoryBackfill() {
        if (!isAuthenticated) {
            Log.i(TAG, "Skipping RACP history fetch without vendor auth")
            return
        }
        if (!shouldRequestAuthenticatedHistoryBackfill) {
            Log.d(TAG, "Skipping RACP history fetch; authenticated backfill already satisfied")
            return
        }
        if (suppressAutomaticHistoryBackfill) {
            Log.i(TAG, "Skipping automatic RACP history fetch; previous transfer completed without parseable records")
            shouldRequestAuthenticatedHistoryBackfill = false
            return
        }
        if (historyBackfillAttemptedThisConnection) {
            Log.d(TAG, "Skipping automatic RACP history fetch; already attempted in this authenticated connection")
            return
        }
        if (awaitingFreshStatusForHistoryBackfill) {
            Log.d(TAG, "Skipping automatic RACP history fetch; waiting for fresh status read")
            return
        }
        if (historyBackfillRequested || charRacp == null) {
            return
        }
        pendingHistoryBatch.clear()
        sawUnsupportedSnHistoryBatch = false
        if (!canStartAuthenticatedHistoryBackfill()) {
            Log.i(TAG, "Deferring authenticated history fetch until active session metadata is available")
            return
        }
        shouldRequestAuthenticatedHistoryBackfill = false
        if (charStatus == null) {
            val startSequence = resolveAutomaticGlucoseHistoryStartSequence()
            if (startSequence == null) {
                return
            }
            pendingGlucoseHistoryStartSequence = startSequence
            historyBackfillAttemptedThisConnection = true
            glucoseHistoryImportedRecordCount = 0
            val baseReason = if (hasObservedLiveMeasurement()) {
                "after first confirmed live reading"
            } else {
                "after authenticated session metadata refresh"
            }
            if (!beginHistoryBackfillPhase(HistoryBackfillPhase.GLUCOSE, "$baseReason (no status read available)")) {
                historyBackfillAttemptedThisConnection = false
                shouldRequestAuthenticatedHistoryBackfill = true
            }
            return
        }
        awaitingFreshStatusForHistoryBackfill = true
        pendingHistoryBackfillReason = if (hasObservedLiveMeasurement()) {
            "after first confirmed live reading"
        } else {
            "after authenticated session metadata refresh"
        }
        Log.i(TAG, "Refreshing CGM status before glucose-history request")
        enqueueOp(GattOp.Read(ICanHealthConstants.CGM_STATUS))
        drainGattQueue()
    }

    private fun resolveRawLaneValue(rawCurrent: Float, glucoseMgdl: Float): Float {
        return Float.NaN
    }

    private fun rememberRecentGlucose(glucoseMgdl: Float) {
        if (!glucoseMgdl.isFinite() || glucoseMgdl <= 0f) {
            return
        }
        if (recentLiveGlucoseMgdl.size >= RECENT_GLUCOSE_WINDOW_SIZE) {
            recentLiveGlucoseMgdl.removeFirst()
        }
        recentLiveGlucoseMgdl.addLast(glucoseMgdl)
    }

    private fun recordHistoryBackfillSample(
        sequenceNumber: Int,
        sampleTimeMs: Long,
        glucoseMgdl: Float = Float.NaN,
        rawCurrent: Float = Float.NaN,
        temperatureC: Float = Float.NaN,
    ) {
        if (SerialNumber.isBlank() || sequenceNumber < 0) {
            return
        }
        val entry = pendingHistoryBatch.getOrPut(sequenceNumber) { PendingHistoryReading(sequenceNumber = sequenceNumber) }
        if (sampleTimeMs > 0L && entry.timestampMs <= 0L) {
            entry.timestampMs = sampleTimeMs
        }
        if (glucoseMgdl.isFinite() && glucoseMgdl > 0f) {
            entry.glucoseMgdl = glucoseMgdl
        }
        if (rawCurrent.isFinite() && rawCurrent > 0f) {
            entry.rawCurrent = rawCurrent
        }
        if (temperatureC.isFinite() && temperatureC > 0f) {
            entry.temperatureC = temperatureC
        }
    }

    private fun flushHistoryBackfillBatch(): Boolean {
        if (SerialNumber.isBlank() || pendingHistoryBatch.isEmpty()) {
            pendingHistoryBatch.clear()
            return true
        }
        val ordered = pendingHistoryBatch.values
            .asSequence()
            .filter { record ->
                val hasTimestamp = record.timestampMs > 0L
                val hasAuto = record.glucoseMgdl.isFinite() && record.glucoseMgdl > 0f
                hasTimestamp && hasAuto
            }
            .sortedWith(compareBy<PendingHistoryReading> { it.timestampMs }.thenBy { it.sequenceNumber })
            .toList()
        pendingHistoryBatch.clear()
        if (ordered.isEmpty()) {
            return true
        }
        val timestamps = LongArray(ordered.size)
        val values = FloatArray(ordered.size)
        val rawValues = FloatArray(ordered.size)
        ordered.forEachIndexed { index, record ->
            timestamps[index] = record.timestampMs
            values[index] = record.glucoseMgdl.takeIf { it.isFinite() && it > 0f } ?: 0f
            rawValues[index] = resolveRawLaneValue(record.rawCurrent, record.glucoseMgdl)
                .takeIf { it.isFinite() && it > 0f }
                ?: 0f
        }
        Log.i(TAG, "Persisting ${timestamps.size} direct RACP history readings to Room")
        val stored = HistorySyncAccess.storeSensorHistoryBatchAsync(SerialNumber, timestamps, values, rawValues)
        if (stored) {
            updatePersistedHistoryTailTimestamp(timestamps.last())
            val lastRecord = ordered.last()
            rememberCoveredEdge(lastRecord.sequenceNumber, lastRecord.timestampMs)
            mirrorHistoryBatchIntoNative(ordered)
        }
        return stored
    }

    private fun mirrorHistoryBatchIntoNative(ordered: List<PendingHistoryReading>) {
        if (ordered.isEmpty() || SerialNumber.isBlank()) {
            return
        }
        runCatching {
            ensureNativeDataptr(SerialNumber)
            applyNativeSensorMetadata()
            val nativeWriteName = resolveExistingNativeSensorName(SerialNumber)
                ?: nativeCreationSensorName(SerialNumber)
            val newestTimestampSec = ordered.last().timestampMs / 1000L
            val windowStartSec = prepareNativeMirrorWindow(nativeWriteName, newestTimestampSec)
            val replayable = if (windowStartSec > 0L) {
                ordered.filter { (it.timestampMs / 1000L) >= windowStartSec }
            } else {
                ordered
            }
            for (record in replayable) {
                if (record.timestampMs <= 0L) continue
                val glucoseMgdl = record.glucoseMgdl
                if (!glucoseMgdl.isFinite() || glucoseMgdl <= 0f) continue
                Natives.addGlucoseStreamWithTemp(
                    record.timestampMs / 1000L,
                    glucoseMgdl / 10f,
                    record.temperatureC,
                    nativeWriteName
                )
            }
            applyNativeSensorMetadata()
            if (nativeWriteName.isNotBlank()) {
                adoptNativeSensorIfAppropriate(SerialNumber, nativeWriteName)
            }
            Natives.wakebackup()
        }.onFailure {
            Log.stack(TAG, "mirrorHistoryBatchIntoNative", it)
        }
    }

    private fun resolveSequenceTimelineTimestampMs(
        sequenceNumber: Int,
        fallbackNowMs: Long,
        requireRecentCandidate: Boolean,
    ): Long? {
        if (sequenceNumber < 0) {
            return null
        }
        val anchorTimeMs = resolveSequenceAnchorTimeMs(fallbackNowMs)
        val useSessionTimeline = when {
            requireRecentCandidate -> canUseSessionTimeline(anchorTimeMs)
            canUseHistoryPastEndedStatusCap() && hasRecentOperationalData(fallbackNowMs) -> false
            else -> sessionStartEpochMs > 0L
        }
        if (useSessionTimeline) {
            val candidate = sessionStartEpochMs + sequenceNumber.toLong() * SEQUENCE_UNIT_MS
            val lowerBound = if (requireRecentCandidate) {
                fallbackNowMs - MAX_SESSION_TIMESTAMP_PAST_DRIFT_MS
            } else {
                1L
            }
            if (candidate in lowerBound..(fallbackNowMs + MAX_SESSION_TIMESTAMP_FUTURE_DRIFT_MS)) {
                return candidate
            }
            Log.w(
                TAG,
                "Rejecting implausible session-derived timestamp seq=$sequenceNumber candidate=$candidate now=$fallbackNowMs requireRecent=$requireRecentCandidate"
            )
        }
        if (currentSequenceNumber >= 0) {
            val deltaMinutes = currentSequenceNumber.toLong() - sequenceNumber.toLong()
            val candidate = anchorTimeMs - deltaMinutes * SEQUENCE_UNIT_MS
            val lowerBound = when {
                requireRecentCandidate -> fallbackNowMs - MAX_SESSION_TIMESTAMP_PAST_DRIFT_MS
                sessionStartEpochMs > 0L -> sessionStartEpochMs - MAX_SESSION_TIMESTAMP_PAST_DRIFT_MS
                else -> 1L
            }
            if (candidate in lowerBound..(fallbackNowMs + MAX_SESSION_TIMESTAMP_FUTURE_DRIFT_MS)) {
                return candidate
            }
            Log.w(
                TAG,
                "Rejecting implausible relative timestamp seq=$sequenceNumber current=$currentSequenceNumber candidate=$candidate anchor=$anchorTimeMs now=$fallbackNowMs requireRecent=$requireRecentCandidate"
            )
        }
        return null
    }

    private fun observedHistoryWindowMs(): Long {
        val currentWindowMs = currentSequenceNumber
            .takeIf { it > 0 }
            ?.toLong()
            ?.times(SEQUENCE_UNIT_MS)
            ?: 0L
        val persistedWindowMs = persistedCoveredSequence
            .takeIf { it > 0 }
            ?.toLong()
            ?.times(SEQUENCE_UNIT_MS)
            ?: 0L
        val sessionWindowMs = if (sessionStartEpochMs > 0L) {
            (System.currentTimeMillis() - sessionStartEpochMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return maxOf(currentWindowMs, persistedWindowMs, sessionWindowMs)
            .coerceAtLeast(readingIntervalMs() * 2L)
    }

    private fun earliestObservedHistoryTimeMs(fallbackNowMs: Long): Long {
        if (sessionStartEpochMs > 0L) {
            return (sessionStartEpochMs - MAX_SESSION_TIMESTAMP_PAST_DRIFT_MS).coerceAtLeast(1L)
        }
        val observedWindowMs = observedHistoryWindowMs()
        return (fallbackNowMs - observedWindowMs - MAX_SESSION_TIMESTAMP_PAST_DRIFT_MS)
            .coerceAtLeast(1L)
    }

    private fun latestObservedHistoryTimeMs(fallbackNowMs: Long): Long =
        fallbackNowMs + MAX_SESSION_TIMESTAMP_FUTURE_DRIFT_MS

    private fun isPlausibleHistoryAnchor(candidateTimeMs: Long, fallbackNowMs: Long): Boolean {
        if (candidateTimeMs <= 0L) {
            return false
        }
        return candidateTimeMs in
            earliestObservedHistoryTimeMs(fallbackNowMs)..latestObservedHistoryTimeMs(fallbackNowMs)
    }

    private fun isPlausibleHistoryTailDelta(deltaMs: Long, anchorTimeMs: Long): Boolean {
        if (deltaMs < -MAX_SESSION_TIMESTAMP_FUTURE_DRIFT_MS) {
            return false
        }
        val earliestAllowed = earliestObservedHistoryTimeMs(anchorTimeMs)
        val tailTimeMs = anchorTimeMs - deltaMs
        return tailTimeMs >= earliestAllowed
    }

    private fun invalidatePersistedCoveredEdge(reason: String) {
        val sensorId = SerialNumber.takeIf {
            it.isNotBlank() && !ICanHealthConstants.isProvisionalSensorId(it)
        } ?: return
        Log.w(TAG, "Dropping persisted covered edge for $sensorId: $reason")
        persistedCoveredEdgeLoaded = true
        persistedCoveredEdgeSensorId = sensorId
        persistedCoveredSequence = -1
        persistedCoveredTimestampMs = 0L
        persistedHistoryTailTimestampMs = HistorySyncAccess.getLatestTimestampForSensor(sensorId)
        val context = Applic.app ?: return
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
            .edit()
            .remove("${ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX}$sensorId")
            .remove("${ICanHealthConstants.PREF_HISTORY_EDGE_TIMESTAMP_PREFIX}$sensorId")
            .commit()
    }

    private fun resolveSequenceAnchorTimeMs(fallbackNowMs: Long): Long {
        loadPersistedCoveredEdge()
        if (canUseHistoryPastEndedStatusCap()) {
            return resolveCappedEndedSequenceAnchorTimeMs(fallbackNowMs)
        }
        if (launcherState == ICanHealthConstants.LAUNCHER_STATE_ENDED) {
            refreshPersistedHistoryTailTimestamp()
            if (!hasRecentOperationalData(fallbackNowMs)) {
                return 0L
            }
        }
        if (currentSequenceNumber >= 0 &&
            persistedCoveredSequence >= 0 &&
            persistedCoveredTimestampMs > 0L &&
            currentSequenceNumber >= persistedCoveredSequence) {
            val deltaMinutes = currentSequenceNumber - persistedCoveredSequence
            val persistedAnchorTimeMs = persistedCoveredTimestampMs + deltaMinutes.toLong() * SEQUENCE_UNIT_MS
            if (isPlausibleHistoryAnchor(persistedAnchorTimeMs, fallbackNowMs)) {
                return persistedAnchorTimeMs
            }
            invalidatePersistedCoveredEdge(
                "persisted seq=$persistedCoveredSequence time=$persistedCoveredTimestampMs " +
                    "reconstructed anchor=$persistedAnchorTimeMs current=$currentSequenceNumber now=$fallbackNowMs"
            )
        }
        return when {
            lastHandledLiveSequence == currentSequenceNumber && lastHandledLiveTimestampMs > 0L ->
                lastHandledLiveTimestampMs
            currentSequenceObservedAtMs > 0L -> currentSequenceObservedAtMs
            else -> fallbackNowMs
        }
    }

    private fun resolveCappedEndedSequenceAnchorTimeMs(fallbackNowMs: Long): Long {
        refreshPersistedHistoryTailTimestamp()
        if (hasRecentOperationalData(fallbackNowMs)) {
            return fallbackNowMs
        }
        observedEndedStatusEndMs(sessionStartEpochMs)?.let { return it }
        return 0L
    }

    private fun canUseHistoryPastEndedStatusCap(): Boolean {
        return ICanHealthConstants.isEndedStatusSequenceCap(launcherState, currentSequenceNumber)
    }

    private fun observedEndedStatusEndMs(startMs: Long): Long? {
        if (startMs <= 0L || !canUseHistoryPastEndedStatusCap()) {
            return null
        }
        val endedSequence = currentSequenceNumber
            .coerceAtLeast(ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES)
        return startMs + endedSequence.toLong() * SEQUENCE_UNIT_MS
    }

    private fun hasRecentOperationalData(nowMs: Long = System.currentTimeMillis()): Boolean {
        val latestReadingTimeMs = maxOf(
            latestDriverReadingTimestampMs,
            lastHandledLiveTimestampMs,
            persistedHistoryTailTimestampMs,
            persistedCoveredTimestampMs
        )
        if (latestReadingTimeMs <= 0L) {
            return false
        }
        val maxAgeMs = maxOf(ICAN_LOSS_SIGNAL_SHOWTIME_MS, readingIntervalMs() * 3)
        return latestReadingTimeMs <= nowMs + MAX_SESSION_TIMESTAMP_FUTURE_DRIFT_MS &&
            nowMs - latestReadingTimeMs <= maxAgeMs
    }

    private fun refreshPersistedHistoryTailTimestamp(): Long {
        if (SerialNumber.isBlank()) {
            persistedHistoryTailTimestampMs = 0L
            return 0L
        }
        val latestTimestamp = HistorySyncAccess.getLatestTimestampForSensor(SerialNumber)
        if (latestTimestamp > 0L) {
            persistedHistoryTailTimestampMs = latestTimestamp
        }
        return persistedHistoryTailTimestampMs
    }

    private fun loadPersistedCoveredEdge(force: Boolean = false) {
        val sensorId = SerialNumber.takeIf {
            it.isNotBlank() && !ICanHealthConstants.isProvisionalSensorId(it)
        }
        if (sensorId.isNullOrBlank()) {
            if (force) {
                persistedCoveredEdgeLoaded = false
                persistedCoveredEdgeSensorId = null
                persistedCoveredSequence = -1
                persistedCoveredTimestampMs = 0L
            }
            return
        }
        if (!force && persistedCoveredEdgeLoaded && persistedCoveredEdgeSensorId == sensorId) {
            return
        }
        persistedCoveredEdgeLoaded = true
        persistedCoveredEdgeSensorId = sensorId
        persistedCoveredSequence = -1
        persistedCoveredTimestampMs = 0L
        val context = Applic.app ?: return
        val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        persistedCoveredSequence =
            prefs.getInt("${ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX}$sensorId", -1)
        persistedCoveredTimestampMs =
            prefs.getLong("${ICanHealthConstants.PREF_HISTORY_EDGE_TIMESTAMP_PREFIX}$sensorId", 0L)
        if (persistedCoveredTimestampMs > persistedHistoryTailTimestampMs) {
            persistedHistoryTailTimestampMs = persistedCoveredTimestampMs
        }
    }

    private fun rememberCoveredEdge(sequenceNumber: Int, timestampMs: Long) {
        if (sequenceNumber < 0 || timestampMs <= 0L) {
            return
        }
        loadPersistedCoveredEdge()
        val shouldUpdate = when {
            persistedCoveredSequence < 0 -> true
            sequenceNumber > persistedCoveredSequence -> true
            sequenceNumber == persistedCoveredSequence && timestampMs > persistedCoveredTimestampMs -> true
            else -> false
        }
        if (!shouldUpdate) {
            return
        }
        persistedCoveredSequence = sequenceNumber
        persistedCoveredTimestampMs = timestampMs
        persistedCoveredEdgeLoaded = true
        if (timestampMs > persistedHistoryTailTimestampMs) {
            persistedHistoryTailTimestampMs = timestampMs
        }
        val sensorId = SerialNumber.takeIf {
            it.isNotBlank() && !ICanHealthConstants.isProvisionalSensorId(it)
        } ?: return
        persistedCoveredEdgeSensorId = sensorId
        val context = Applic.app ?: return
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
            .edit()
            .putInt("${ICanHealthConstants.PREF_HISTORY_EDGE_SEQUENCE_PREFIX}$sensorId", sequenceNumber)
            .putLong("${ICanHealthConstants.PREF_HISTORY_EDGE_TIMESTAMP_PREFIX}$sensorId", timestampMs)
            .commit()
    }

    private fun updatePersistedHistoryTailTimestamp(candidateTimestampMs: Long) {
        if (candidateTimestampMs > persistedHistoryTailTimestampMs) {
            persistedHistoryTailTimestampMs = candidateTimestampMs
        }
    }

    private fun estimatePersistedTailSequence(anchorTimeMs: Long): Int? {
        loadPersistedCoveredEdge()
        if (persistedCoveredSequence >= readingIntervalMinutes()) {
            if (isPlausibleHistoryAnchor(persistedCoveredTimestampMs, anchorTimeMs)) {
                return persistedCoveredSequence
            }
            invalidatePersistedCoveredEdge(
                "persisted covered timestamp $persistedCoveredTimestampMs is outside the active history window for anchor=$anchorTimeMs"
            )
        }
        val readingInterval = readingIntervalMinutes()
        if (currentSequenceNumber < readingInterval) {
            return null
        }
        val persistedTailTimestamp = refreshPersistedHistoryTailTimestamp()
        if (persistedTailTimestamp <= 0L) {
            return null
        }
        val deltaMs = anchorTimeMs - persistedTailTimestamp
        if (!isPlausibleHistoryTailDelta(deltaMs, anchorTimeMs)) {
            return null
        }
        val deltaIntervals = round(deltaMs.toDouble() / readingIntervalMs().toDouble()).toInt()
        val estimatedSequence = currentSequenceNumber - deltaIntervals * readingInterval
        return estimatedSequence.takeIf { it >= readingInterval }
    }

    private fun canUseSessionTimeline(anchorTimeMs: Long): Boolean {
        if (sessionStartEpochMs <= 0L) {
            return false
        }
        if (currentSequenceNumber < 0) {
            return true
        }
        val currentCandidate = sessionStartEpochMs + currentSequenceNumber.toLong() * SEQUENCE_UNIT_MS
        if (currentCandidate <= 0L) {
            return false
        }
        return currentCandidate in
            (anchorTimeMs - MAX_SESSION_TIMESTAMP_PAST_DRIFT_MS)..(anchorTimeMs + MAX_SESSION_TIMESTAMP_FUTURE_DRIFT_MS)
    }

    private fun hasConfirmedLiveSession(): Boolean {
        if (lastGlucoseReceiptRealtimeMs > 0L) {
            return true
        }
        if (currentSequenceNumber > 0) {
            return true
        }
        return launcherState == ICanHealthConstants.LAUNCHER_STATE_RUNNING
    }

    private fun hasObservedLiveMeasurement(): Boolean {
        return lastGlucoseReceiptRealtimeMs > 0L ||
            lastHandledLiveSequence > 0 ||
            lastHandledLiveTimestampMs > 0L
    }

    private fun canStartAuthenticatedHistoryBackfill(): Boolean {
        if (hasObservedLiveMeasurement()) {
            return true
        }
        if (currentSequenceNumber < readingIntervalMinutes()) {
            return false
        }
        return hasStartedOrWarmupSession()
    }

    private fun logUnhandledHistoryPayload(reason: String, data: ByteArray) {
        Log.w(
            TAG,
            "$reason during ${historyBackfillPhase.name.lowercase()} phase len=${data.size} hex=${data.toHexString()}"
        )
    }

    private fun logSkippedUnanchoredHistory(sequenceNumber: Int, source: String) {
        Log.w(TAG, "Skipping iCan $source history record seq=$sequenceNumber; no trusted timestamp anchor")
    }

    private fun hasStartedOrWarmupSession(): Boolean {
        if (hasConfirmedLiveSession()) {
            return true
        }
        if (sessionStartEpochMs > 0L) {
            return true
        }
        return launcherState == ICanHealthConstants.LAUNCHER_STATE_WARMUP
    }

    private fun shouldAttemptStandaloneStart(): Boolean {
        if (!isAuthenticated || startCommandIssuedThisConnection) {
            return false
        }
        // If we have a confirmed live session or a valid session start timestamp,
        // the sensor is already properly activated — no need to start again.
        if (hasConfirmedLiveSession()) {
            return false
        }
        if (sessionStartEpochMs > 0L) {
            return false
        }
        // Normal path: IDLE sensor needs 0x1A to enter WARMUP.
        // Recovery path: sensor already in WARMUP but no session start time was ever
        // written (half-activated state — can happen if a previous app session sent
        // 0x1A but disconnected before writing session start time, or if the sensor
        // firmware auto-transitions to WARMUP after being worn for a period). In
        // that case, we still send 0x1A — if the firmware accepts it idempotently,
        // the existing success handler writes session start; if it rejects it, the
        // failure handler (below) writes session start directly.
        return launcherState == ICanHealthConstants.LAUNCHER_STATE_IDLE ||
            launcherState == ICanHealthConstants.LAUNCHER_STATE_WARMUP
    }

    private fun shouldUseAuthenticatedContinuousReconnect(): Boolean {
        if (!isAuthenticated && !hasAuthenticatedReconnectHint) {
            return false
        }
        if (startCommandIssuedThisConnection && !hasStartedOrWarmupSession()) {
            return false
        }
        loadPersistedCoveredEdge()
        if (persistedCoveredSequence >= readingIntervalMinutes() && persistedCoveredTimestampMs > 0L) {
            return false
        }
        if (refreshPersistedHistoryTailTimestamp() > 0L) {
            return false
        }
        return !hasObservedLiveMeasurement()
    }

    private fun resolveStandaloneStartTimeMs(): Long {
        val nowMs = System.currentTimeMillis()
        return nowMs - (nowMs % 1000L)
    }

    private fun maybeIssueStandaloneStart(): Boolean {
        if (!shouldAttemptStandaloneStart()) {
            return false
        }
        if (charSessionStart == null || charSpecificOps == null) {
            Log.w(TAG, "Cannot issue standalone start without session-start/auth characteristics")
            return false
        }
        startCommandIssuedThisConnection = true
        pendingStartTimeEpochMs = resolveStandaloneStartTimeMs()
        phase = Phase.STARTING_SENSOR
        setUiStatus(UiStatusKind.CONFIGURING)
        Log.i(
            TAG,
            "Launcher state=0x${"%02X".format(launcherState)} with no session start — issuing standalone start command"
        )
        enqueueOp(GattOp.Write(ICanHealthConstants.CGM_SPECIFIC_OPS, ICanHealthConstants.CMD_START_SENSOR))
        drainGattQueue()
        return true
    }

    private fun shouldRefreshStaticDeviceInfo(): Boolean {
        return serialFromDevice.isNullOrBlank() ||
            vendorModelName.isBlank() ||
            vendorFirmwareVersion.isBlank() ||
            vendorSoftwareVersion.isBlank()
    }

    private fun shouldRefreshSessionMetadata(): Boolean {
        return forceSessionMetadataRefresh ||
            sessionStartEpochMs <= 0L ||
            launcherState < 0 ||
            currentSequenceNumber < 0 ||
            lastGlucoseReceiptRealtimeMs <= 0L
    }

    private fun resolveNativeShellStartTimeSec(): Long {
        val startMs = when {
            sessionStartEpochMs > 0L -> sessionStartEpochMs
            sensorstartmsec > 0L -> sensorstartmsec
            pendingStartTimeEpochMs > 0L -> pendingStartTimeEpochMs
            else -> 0L
        }
        return if (startMs > 0L) startMs / 1000L else 0L
    }

    private fun prepareNativeMirrorWindow(nativeName: String, anchorTimestampSec: Long): Long {
        if (nativeName.isBlank() || anchorTimestampSec <= 0L) {
            return 0L
        }
        val windowStartSec =
            (anchorTimestampSec - (NATIVE_MIRROR_STREAM_WINDOW_SEC - SEQUENCE_UNIT_MS / 1000L)).coerceAtLeast(0L)
        if (windowStartSec <= 0L) {
            return 0L
        }
        val sensorPtr = resolveNativeSensorPtr(SerialNumber)
        val currentNativeStartSec = if (sensorPtr != 0L) {
            runCatching { Natives.getSensorStartmsecFromSensorptr(sensorPtr) / 1000L }
                .getOrDefault(0L)
        } else {
            0L
        }
        if (currentNativeStartSec in 1 until windowStartSec) {
            Log.i(TAG, "Rebasing iCan native mirror window from $currentNativeStartSec to $windowStartSec for $nativeName")
            Natives.rebaseDirectStreamWindow(nativeName, windowStartSec)
        }
        return windowStartSec
    }

    private fun adoptNativeSensorIfAppropriate(sensorId: String, nativeName: String) {
        if (nativeName.isBlank()) {
            return
        }
        val current = runCatching { Natives.lastsensorname() }.getOrNull()
        val brokenAlias = ICanHealthConstants.legacyBrokenNativeAlias(sensorId)
        val currentMatchesSameSensor = !current.isNullOrBlank() &&
            ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(sensorId, current)
        val shouldAdopt = current.isNullOrBlank() ||
            (provisionalSensorIdForAdoption != null && current == provisionalSensorIdForAdoption) ||
            (brokenAlias != null && brokenAlias.equals(current, ignoreCase = true)) ||
            (currentMatchesSameSensor && !nativeName.equals(current, ignoreCase = true))
        if (!shouldAdopt) {
            return
        }
        runCatching { Natives.setcurrentsensor(nativeName) }
            .onFailure { Log.stack(TAG, "adoptNativeSensorIfAppropriate(setcurrentsensor)", it) }
        provisionalSensorIdForAdoption = null
    }

    private fun ensureNativeDataptr(sensorId: String) {
        val canonicalSensorId = ICanHealthConstants.canonicalSensorId(sensorId)
        if (canonicalSensorId.isBlank() || ICanHealthConstants.isProvisionalSensorId(canonicalSensorId)) {
            return
        }
        if (dataptr != 0L) {
            runCatching { Natives.freedataptr(dataptr) }
                .onFailure { Log.stack(TAG, "ensureNativeDataptr(freedataptr)", it) }
            dataptr = 0L
        }
        runCatching {
            Natives.ensureSensorShell(
                nativeCreationSensorName(canonicalSensorId),
                resolveNativeShellStartTimeSec()
            )
        }.onFailure {
            Log.stack(TAG, "ensureNativeDataptr(ensureSensorShell)", it)
        }
        val nativeName = resolveExistingNativeSensorName(canonicalSensorId)
            ?: nativeCreationSensorName(canonicalSensorId)
        adoptNativeSensorIfAppropriate(canonicalSensorId, nativeName)
        applyNativeSensorMetadata()
    }

    private fun nativeCreationSensorName(sensorId: String): String =
        ICanHealthConstants.canonicalSensorId(sensorId)

    private fun nativeLookupSensorName(sensorId: String): String? =
        ICanHealthConstants.nativeLookupSensorAlias(sensorId)

    private fun resolveExistingNativeSensorName(sensorId: String): String? {
        if (sensorId.isBlank() || ICanHealthConstants.isProvisionalSensorId(sensorId)) {
            return null
        }
        val canonicalName = nativeCreationSensorName(sensorId)
        val preferredAlias = nativeLookupSensorName(sensorId)
        val brokenAlias = ICanHealthConstants.legacyBrokenNativeAlias(sensorId)
        val activeSensors = runCatching { Natives.activeSensors() }.getOrNull()
        activeSensors
            ?.firstOrNull { canonicalName.equals(it, ignoreCase = true) }
            ?.takeIf { !it.isNullOrBlank() }
            ?.let { return it }

        runCatching { Natives.lastsensorname() }
            .getOrNull()
            ?.takeIf { canonicalName.equals(it, ignoreCase = true) && activeSensors?.any { active -> active.equals(it, ignoreCase = true) } == true }
            ?.let { return it }

        activeSensors
            ?.firstOrNull { preferredAlias != null && preferredAlias.equals(it, ignoreCase = true) }
            ?.takeIf { !it.isNullOrBlank() }
            ?.let { return it }

        activeSensors
            ?.firstOrNull {
                ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(sensorId, it) &&
                    (brokenAlias == null || !brokenAlias.equals(it, ignoreCase = true))
            }
            ?.takeIf { !it.isNullOrBlank() }
            ?.let { return it }

        return null
    }

    private fun resolveNativeSensorPtr(sensorId: String): Long {
        if (sensorId.isBlank() || ICanHealthConstants.isProvisionalSensorId(sensorId)) {
            return 0L
        }
        if (dataptr != 0L) {
            val currentSensorPtr = runCatching { Natives.getsensorptr(dataptr) }
                .onFailure { Log.stack(TAG, "resolveNativeSensorPtr(getsensorptr)", it) }
                .getOrDefault(0L)
            if (currentSensorPtr != 0L) {
                return currentSensorPtr
            }
        }
        val existingNativeName = resolveExistingNativeSensorName(sensorId)
            ?: return 0L
        return runCatching { Natives.str2sensorptr(existingNativeName) }
            .onFailure { Log.stack(TAG, "resolveNativeSensorPtr(str2sensorptr)", it) }
            .getOrDefault(0L)
    }

    private fun rememberDriverCurrentReading(glucoseMgdl: Float, timestampMs: Long) {
        if (!glucoseMgdl.isFinite() || glucoseMgdl <= 0f || timestampMs <= 0L) {
            return
        }
        if (timestampMs < latestDriverReadingTimestampMs) {
            return
        }
        latestDriverGlucoseMgdl = glucoseMgdl
        latestDriverReadingTimestampMs = timestampMs
        if (!hasLocalPersistedRecord()) {
            scheduleMirrorHistoryMerge()
        }
    }

    private fun scheduleForegroundNotificationRefresh() {
        handler.removeCallbacks(foregroundNotificationRefreshRunnable)
        handler.postDelayed(foregroundNotificationRefreshRunnable, DRIVER_NOTIFICATION_REFRESH_DELAY_MS)
    }

    private fun beginServiceDiscovery(gatt: BluetoothGatt, reason: String) {
        if (phase != Phase.DISCOVERING_SERVICES) {
            return
        }
        if (serviceDiscoveryHandled) {
            return
        }
        if (serviceDiscoveryStarted) {
            Log.d(TAG, "Service discovery already started for $SerialNumber ($reason)")
            return
        }
        serviceDiscoveryStarted = true
        if (!gatt.discoverServices()) {
            serviceDiscoveryStarted = false
            Log.e(TAG, "Failed to start service discovery ($reason)")
            gatt.disconnect()
        }
    }

    private fun applyNativeSensorMetadata() {
        val sensorPtr = resolveNativeSensorPtr(SerialNumber)
        if (sensorPtr == 0L) {
            return
        }
        if (dataptr != 0L && !mActiveDeviceAddress.isNullOrEmpty()) {
            runCatching { Natives.setDeviceAddress(dataptr, mActiveDeviceAddress) }
                .onFailure { Log.stack(TAG, "applyNativeSensorMetadata(setDeviceAddress)", it) }
        }
        if (dataptr != 0L) {
            runCatching { Natives.unfinishSensor(dataptr) }
                .onFailure { Log.stack(TAG, "applyNativeSensorMetadata(unfinishSensor)", it) }
        }
        if (sessionStartEpochMs > 0L) {
            sensorstartmsec = sessionStartEpochMs
        } else if (sensorstartmsec <= 0L) {
            sensorstartmsec = if (dataptr != 0L) {
                runCatching { Natives.getSensorStartmsec(dataptr) }.getOrDefault(0L)
            } else {
                runCatching { Natives.getSensorStartmsecFromSensorptr(sensorPtr) }.getOrDefault(0L)
            }
        }
    }

    private fun computeReconnectPlan(nowMs: Long): ReconnectPlan {
        if (canUseHistoryPastEndedStatusCap() && !hasRecentOperationalData(nowMs)) {
            return ReconnectPlan(
                delayMs = ICAN_LOSS_SIGNAL_SHOWTIME_MS,
                exactAlarm = false,
                reason = "iCan reports vendor-ended capped status; probing periodically",
                refreshSessionMetadata = true
            )
        }
        if (shouldUseAuthenticatedContinuousReconnect()) {
            return ReconnectPlan(
                delayMs = AUTHENTICATED_RECONNECT_DELAY_MS,
                exactAlarm = false,
                reason = "Authenticated session reconnect"
            )
        }
        if (hasObservedLiveMeasurement() || hasStartedOrWarmupSession()) {
            return ReconnectPlan(
                delayMs = AUTHENTICATED_RECONNECT_DELAY_MS,
                exactAlarm = false,
                reason = "Maintaining active session"
            )
        }
        if (lastGlucoseReceiptRealtimeMs <= 0L) {
            return ReconnectPlan(
                delayMs = FIRST_VALUE_BRUTE_RECONNECT_DELAY_MS,
                exactAlarm = false,
                reason = "Still acquiring first live reading — brute-forcing"
            )
        }
        val intervalMs = readingIntervalMs()
        val expectedReadingAtMs = lastGlucoseReceiptRealtimeMs + intervalMs
        val missedCycles = if (nowMs > expectedReadingAtMs + NEXT_READING_BRUTE_GRACE_WINDOW_MS) {
            ((nowMs - (expectedReadingAtMs + NEXT_READING_BRUTE_GRACE_WINDOW_MS)) / intervalMs) + 1L
        } else {
            0L
        }
        return ReconnectPlan(
            delayMs = NEXT_READING_BRUTE_RECONNECT_DELAY_MS,
            exactAlarm = false,
            reason = when {
                missedCycles > 0L -> "Missed $missedCycles reading windows — brute-forcing reconnect"
                else -> "Still acquiring first stable cadence — brute-forcing"
            },
            refreshSessionMetadata = missedCycles > 0L
        )
    }

    private fun parseDeviceInfoString(data: ByteArray): String {
        if (data.isEmpty()) return ""
        return buildString(data.size) {
            for (byte in data) {
                val code = byte.toInt() and 0xFF
                if (code == 0) break
                if (code in 0x20..0x7E) {
                    append(code.toChar())
                }
            }
        }.trim()
    }

    private fun enqueueOp(op: GattOp) {
        gattQueue.add(op)
    }

    private fun drainGattQueue() {
        if (gattOpActive || gattQueue.isEmpty()) return
        val gatt = mBluetoothGatt ?: return
        val op = gattQueue.poll() ?: return
        gattOpActive = true
        handler.removeCallbacks(gattOpTimeoutRunnable)
        handler.postDelayed(gattOpTimeoutRunnable, GATT_OP_TIMEOUT_MS)

        when (op) {
            is GattOp.EnableNotify -> {
                val char = findCharacteristic(ICanHealthConstants.CGM_SERVICE, op.charUuid) ?: run {
                    Log.w(TAG, "EnableNotify missing ${op.charUuid}")
                    gattOpActive = false
                    drainGattQueue()
                    return
                }
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(ICanHealthConstants.CCCD) ?: run {
                    Log.w(TAG, "EnableNotify missing CCCD ${op.charUuid}")
                    gattOpActive = false
                    drainGattQueue()
                    return
                }
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!gatt.writeDescriptor(cccd)) {
                    Log.e(TAG, "EnableNotify writeDescriptor failed ${op.charUuid}")
                    gattOpActive = false
                    drainGattQueue()
                }
            }

            is GattOp.EnableIndicate -> {
                val char = findCharacteristic(ICanHealthConstants.CGM_SERVICE, op.charUuid) ?: run {
                    Log.w(TAG, "EnableIndicate missing ${op.charUuid}")
                    gattOpActive = false
                    drainGattQueue()
                    return
                }
                gatt.setCharacteristicNotification(char, true)
                val cccd = char.getDescriptor(ICanHealthConstants.CCCD) ?: run {
                    Log.w(TAG, "EnableIndicate missing CCCD ${op.charUuid}")
                    gattOpActive = false
                    drainGattQueue()
                    return
                }
                cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (!gatt.writeDescriptor(cccd)) {
                    Log.e(TAG, "EnableIndicate writeDescriptor failed ${op.charUuid}")
                    gattOpActive = false
                    drainGattQueue()
                }
            }

            is GattOp.Write -> {
                val char = findCharacteristic(op.serviceUuid, op.charUuid) ?: run {
                    Log.w(TAG, "Write missing ${op.charUuid} in ${op.serviceUuid}")
                    gattOpActive = false
                    drainGattQueue()
                    return
                }
                char.value = op.data
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (!gatt.writeCharacteristic(char)) {
                    Log.e(TAG, "Write failed to start ${op.charUuid} in ${op.serviceUuid}")
                    gattOpActive = false
                    drainGattQueue()
                }
            }

            is GattOp.Read -> {
                val service = gatt.getService(op.serviceUuid)
                val char = service?.getCharacteristic(op.charUuid) ?: run {
                    Log.w(TAG, "Read missing ${op.charUuid} in ${op.serviceUuid}")
                    gattOpActive = false
                    drainGattQueue()
                    return
                }
                if (!gatt.readCharacteristic(char)) {
                    Log.e(TAG, "Read failed to start ${op.charUuid}")
                    gattOpActive = false
                    drainGattQueue()
                }
            }
        }
    }

    private fun reconnectAlarmPendingIntent(flags: Int, token: Long = scheduledReconnectToken, triggerAtMs: Long = scheduledReconnectAtMs): PendingIntent? {
        val appContext = Applic.app ?: return null
        val serial = SerialNumber.takeIf { it.isNotBlank() } ?: return null
        val address = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        val intent = Intent(appContext, ICanHealthReconnectReceiver::class.java).apply {
            action = ICanHealthReconnectReceiver.ACTION_ICAN_RECONNECT
            putExtra(ICanHealthReconnectReceiver.EXTRA_SERIAL, serial)
            putExtra(ICanHealthReconnectReceiver.EXTRA_TOKEN, token)
            putExtra(ICanHealthReconnectReceiver.EXTRA_TRIGGER_AT_MS, triggerAtMs)
            if (address != null) {
                putExtra(ICanHealthReconnectReceiver.EXTRA_ADDRESS, address)
            }
        }
        return PendingIntent.getBroadcast(
            appContext,
            serial.hashCode(),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleReconnectAlarm(delayMs: Long, token: Long, triggerAtMs: Long): Boolean {
        cancelReconnectAlarm()
        if (delayMs < RECONNECT_ALARM_MIN_DELAY_MS || stop) {
            return false
        }
        val appContext = Applic.app ?: return false
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        try {
            val pendingIntent = reconnectAlarmPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT, token, triggerAtMs) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
            Log.d(TAG, "Scheduled reconnect wake alarm in ${delayMs}ms for $SerialNumber")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule reconnect alarm: ${e.message}")
            return false
        }
    }

    private fun cancelReconnectAlarm() {
        val appContext = Applic.app ?: return
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            val pendingIntent = reconnectAlarmPendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
            alarmManager.cancel(pendingIntent)
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun cancelScheduledReconnect() {
        scheduledReconnectToken += 1L
        scheduledReconnectRunnable?.let { handler.removeCallbacks(it) }
        scheduledReconnectRunnable = null
        scheduledReconnectAtMs = 0L
        cancelReconnectAlarm()
    }

    @Synchronized
    private fun scheduleReconnect(plan: ReconnectPlan) {
        cancelScheduledReconnect()
        if (stop) {
            return
        }
        val triggerAtMs = System.currentTimeMillis() + plan.delayMs
        val token = scheduledReconnectToken
        scheduledReconnectAtMs = triggerAtMs
        Log.i(TAG, "${plan.reason} (delay=${plan.delayMs}ms)")
        if (plan.exactAlarm && scheduleReconnectAlarm(plan.delayMs, token, triggerAtMs)) {
            return
        }
        val runnable = Runnable {
            handleScheduledReconnect(token, "handler")
        }
        scheduledReconnectRunnable = runnable
        handler.postDelayed(runnable, plan.delayMs)
    }

    private fun handleScheduledReconnect(token: Long, source: String) {
        synchronized(this) {
            if (token != scheduledReconnectToken || scheduledReconnectAtMs <= 0L) {
                return
            }
            scheduledReconnectRunnable?.let { handler.removeCallbacks(it) }
            scheduledReconnectRunnable = null
            scheduledReconnectAtMs = 0L
            cancelReconnectAlarm()
        }
        if (stop || phase != Phase.IDLE || mBluetoothGatt != null) {
            return
        }
        Log.d(TAG, "Reconnect $source fired for $SerialNumber")
        connectDevice(0)
    }

    private fun clearGattTransportState(
        clearReconnect: Boolean = true,
        clearServices: Boolean = true,
    ) {
        gattQueue.clear()
        gattOpActive = false
        handler.removeCallbacks(noDataWatchdog)
        handler.removeCallbacks(postMeasurementDisconnectRunnable)
        handler.removeCallbacks(sensorInfoFallbackRunnable)
        handler.removeCallbacks(mtuRequestTimeoutRunnable)
        handler.removeCallbacks(gattOpTimeoutRunnable)
        if (clearReconnect) {
            cancelScheduledReconnect()
        }
        if (clearServices) {
            cgmService = null
            disService = null
            charMeasurement = null
            charStatus = null
            charSessionStart = null
            charRacp = null
            charSpecificOps = null
        }
    }

    private fun resetConnectionAttemptState() {
        phase = Phase.IDLE
        isAuthenticated = false
        authAttempted = false
        postAuthSetupStarted = false
        historyBackfillRequested = false
        historyBackfillPhase = HistoryBackfillPhase.NONE
        historyBackfillAttemptedThisConnection = false
        awaitingFreshStatusForHistoryBackfill = false
        glucoseHistoryImportedRecordCount = 0
        pendingHistoryBackfillReason = null
        pendingHistoryBatch.clear()
        deferredLiveReading = null
        lastAuthChallenge = null
        authRetryCount = 0
        receivedGlucoseThisConnection = false
        currentSequenceObservedAtMs = 0L
        startCommandIssuedThisConnection = false
        pendingStartTimeEpochMs = 0L
        awaitingMtuNegotiation = false
        negotiatedMtu = 23
        serviceDiscoveryStarted = false
        serviceDiscoveryHandled = false
    }

    internal fun handleReconnectAlarm(token: Long) {
        handleScheduledReconnect(token, "alarm")
    }

    private fun finishGattOp() {
        handler.removeCallbacks(gattOpTimeoutRunnable)
        gattOpActive = false
        drainGattQueue()
        maybeHandleQueueDrained()
    }

    private fun maybeHandleQueueDrained() {
        if (gattOpActive || gattQueue.isNotEmpty()) {
            return
        }
        when (phase) {
            Phase.AUTH_PRIME -> {
                if (charSpecificOps != null && !authAttempted) {
                    authAttempted = true
                    phase = Phase.AUTH_HANDSHAKE
                    setUiStatus(UiStatusKind.AUTHENTICATING)
                    enqueueOp(GattOp.Write(ICanHealthConstants.CGM_SPECIFIC_OPS, ICanHealthConstants.CMD_REQUEST_CHALLENGE))
                    drainGattQueue()
                } else {
                    hasAuthenticatedReconnectHint = false
                    startPostAuthSetup("no-auth-characteristic")
                }
            }

            Phase.POST_AUTH_SETUP -> {
                if (maybeIssueStandaloneStart()) {
                    return
                }
                phase = Phase.STREAMING
                setUiStatus(UiStatusKind.CONNECTED)
                requestHistoryBackfill()
                scheduleNoDataWatchdogIfNeeded()
            }

            Phase.STARTING_SENSOR -> {
                phase = Phase.STREAMING
                setUiStatus(UiStatusKind.CONNECTED)
                requestHistoryBackfill()
                scheduleNoDataWatchdogIfNeeded()
            }

            else -> Unit
        }
    }

    private fun findCharacteristic(serviceUuid: UUID, uuid: UUID): BluetoothGattCharacteristic? {
        val service = when (serviceUuid) {
            ICanHealthConstants.CGM_SERVICE -> cgmService
            ICanHealthConstants.DEVICE_INFO_SERVICE -> disService
            else -> mBluetoothGatt?.getService(serviceUuid)
        }
        return service?.getCharacteristic(uuid)
    }

    private fun scheduleNoDataWatchdog() {
        handler.removeCallbacks(noDataWatchdog)
        handler.postDelayed(noDataWatchdog, NO_DATA_WATCHDOG_MS)
    }

    private fun scheduleNoDataWatchdogIfNeeded() {
        if (hasConfirmedLiveSession()) {
            scheduleNoDataWatchdog()
        } else {
            handler.removeCallbacks(noDataWatchdog)
        }
    }

    fun destroy() {
        stop = true
        handler.removeCallbacksAndMessages(null)
        cancelScheduledReconnect()
        try {
            mBluetoothGatt?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            mBluetoothGatt?.close()
        } catch (_: Throwable) {
        }
        mBluetoothGatt = null
        try {
            handlerThread.quitSafely()
        } catch (_: Throwable) {
        }
    }
}

internal fun ByteArray.toHexString(): String =
    joinToString(" ") { "%02X".format(it) }
