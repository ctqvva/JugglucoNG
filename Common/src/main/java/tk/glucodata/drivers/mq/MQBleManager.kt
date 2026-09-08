// MQBleManager.kt — BLE manager for MQ/Glutec CGM (Nordic UART Service).
//
// Lifecycle:
//   1) connectDevice → GATT connect
//   2) onConnectionStateChange(CONNECTED) → discoverServices
//   3) onServicesDiscovered → find NUS, enable notifications on TX char
//   4) onCharacteristicChanged(TX) → MQParser.parse → dispatch by cmd
//      • 0x06 NOTIFY_BEGIN_WORK  → write confirmWithInit  (fresh session)
//      • 0x01 NOTIFY_WORKING     → write confirmWithoutInit (heartbeat)
//      • 0x04 NOTIFY_BG_DATA     → parse BG records, run vendor math, confirm
//
// The 0x04 records carry packet index + sample current, not final glucose.
// We only emit a glucose when we have enough state to reproduce the vendor
// calculation honestly; otherwise we keep the session alive but do not invent
// a reading.

package tk.glucodata.drivers.mq

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.Locale
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NightscoutUploadWake
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

@SuppressLint("MissingPermission")
class MQBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), MQDriver {

    companion object {
        private const val TAG = MQConstants.TAG

        /** SuperGattCallback generation tag — keep at 0 (same as iCan/AiDex). */
        const val SENSOR_GEN = 0

        /** Reconnect active sessions aggressively instead of waiting for generic polling. */
        private const val ACTIVE_SESSION_RECONNECT_DELAY_MS = 2_000L

        /** Service discovery should finish quickly; otherwise GATT is wedged. */
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L

        /** Retry silent service-discovery wedges before tearing GATT down. */
        private const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 1_500L
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2

        /** No-data watchdog multiplier. Real cadence is profile-controlled. */
        private const val NO_DATA_WATCHDOG_MULTIPLIER = 4L

        /** Allow one full packet interval plus some slack for the first frame. */
        private const val FIRST_FRAME_GRACE_MS = 30_000L

        /** Treat the link as stale after two missed packet intervals. */
        private const val LINK_FRAME_TIMEOUT_MULTIPLIER = 2L

        /** Give the reset frame time to leave Android before we drop GATT. */
        private const val RESET_RECONNECT_DELAY_MS = 700L

        /** Reconnect replay can span multiple 0x04 frames before 0x05 arrives. */
        private const val BG_BURST_FLUSH_DELAY_MS = 750L

        /** Avoid hammering Glutec cloud bookkeeping on every packet. */
        private const val CLOUD_SESSION_RETRY_MS = 60_000L
        private const val CLOUD_HISTORY_BACKFILL_RETRY_MS = 10L * 60L * 1000L
        private const val CLOUD_HISTORY_BACKFILL_LOOKBACK_MS = 20L * 24L * 60L * 60L * 1000L
        private const val CLOUD_HISTORY_BACKFILL_OVERLAP_MS = 15L * 60L * 1000L
        private const val CLOUD_HISTORY_BACKFILL_FUTURE_GRACE_MS = 5L * 60L * 1000L
        private const val CLOUD_HISTORY_NEAR_DUPLICATE_MS = 90L * 1000L
    }

    enum class Phase { IDLE, CONNECTING, DISCOVERING, STREAMING }

    private data class PendingBgRecord(
        val record: MQBgRecord,
        val receivedAtMs: Long,
    )

    @Volatile var phase: Phase = Phase.IDLE
        private set

    private val handlerThread = HandlerThread("MQ-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    private var nusService: BluetoothGattService? = null
    private var charTxNotify: BluetoothGattCharacteristic? = null
    private var charRxWrite: BluetoothGattCharacteristic? = null

    private val profile = MQProfileResolver.resolve()

    // Per-sensor config snapshot (populated in restoreFromPersistence).
    @Volatile private var protocolType: Int = MQConstants.SERVER_DEFAULT_PROTOCOL_TYPE
    @Volatile private var deviation: Int = MQConstants.SERVER_DEFAULT_DEVIATION
    @Volatile private var transmitter10: Int = MQConstants.SERVER_DEFAULT_TRANSMITTER10
    @Volatile private var sensitivitySeed: Float = 0f
    @Volatile private var algorithmVersion: Int = MQConstants.ALGO_DEFAULT_VERSION
    @Volatile private var kValue: Float = 0f
    @Volatile private var bValue: Float = 0f
    @Volatile private var multiplier: Float = MQConstants.ALGO_DEFAULT_MULTIPLIER.toFloat()
    @Volatile private var packages: Int = MQConstants.ALGO_DEFAULT_PACKAGES

    // CRC variant learned from the transmitter's first frame.
    //   0x0000 = Protocol01 (vanilla CRC16-Modbus)
    //   0x0100 = Protocol02 (Modbus ^ 0x0100 — observed on W25101399)
    // We seed it from the cached protocolType but override as soon as we see
    // the transmitter's first frame so we don't ship CRCs the firmware rejects.
    @Volatile private var crcXorOut: Int = 0
    @Volatile private var crcXorOutLearned: Boolean = false

    // Session state (derived while we run).
    @Volatile private var warmupStartedAtMs: Long = 0L
    @Volatile private var sensorStartAtMs: Long = 0L
    @Volatile private var packetCount: Int = 0
    @Volatile private var pendingReferenceBgTimes10Mmol: Double = 0.0
    @Volatile private var lastPacketIndex: Int = -1
    @Volatile private var lastObservedPacketIndex: Int = -1
    @Volatile private var lastRecordReceivedAtMs: Long = 0L
    @Volatile private var lastProtocolFrameAtMs: Long = 0L
    @Volatile private var lastRawCurrent: Double = 0.0
    @Volatile private var lastProcessed: Double = 0.0
    @Volatile private var lastBatteryPercent: Int = -1
    @Volatile private var lastGlucoseAtMs: Long = 0L
    @Volatile private var lastGlucoseMgdlTimes10: Int = 0
    @Volatile private var vendorModelNameInternal: String = MQConstants.DEFAULT_DISPLAY_NAME
    @Volatile private var vendorFirmwareVersionInternal: String = ""
    @Volatile private var reconnectReason: String = ""
    @Volatile private var bootstrapFetchInFlight: Boolean = false
    @Volatile private var lastBootstrapAttemptAtMs: Long = 0L
    @Volatile private var lastBootstrapFailure: MQBootstrapFailure = MQBootstrapFailure.NONE
    @Volatile private var lastBootstrapMessage: String = ""
    @Volatile private var localResetPending: Boolean = false
    @Volatile private var serviceDiscoveryHandled: Boolean = false
    @Volatile private var serviceDiscoveryRetryCount: Int = 0
    @Volatile private var cloudSessionSyncInFlight: Boolean = false
    @Volatile private var cloudSessionAttemptedAtMs: Long = 0L
    @Volatile private var cloudHistoryBackfillInFlight: Boolean = false
    @Volatile private var cloudHistoryBackfillAttemptedAtMs: Long = 0L
    @Volatile private var lastCloudHistoryBackfillTailMs: Long = 0L
    @Volatile private var lastAnnouncedCloudSnapshotId: String = ""
    @Volatile private var lastCloudReportedPacketIndex: Int = -1
    private val pendingBgBurstRecords = LinkedHashMap<Int, PendingBgRecord>()

    override var viewMode: Int = 0

    private val reconnectRunnable = Runnable {
        if (stop) return@Runnable
        Log.i(TAG, "Reconnect requested: $reconnectReason")
        connectDevice(0)
    }

    private val serviceDiscoveryWatchdog = Runnable {
        if (stop || phase != Phase.DISCOVERING) return@Runnable
        Log.w(TAG, "MQ service discovery stalled — forcing reconnect")
        phase = Phase.IDLE
        mActiveBluetoothDevice = null
        charTxNotify = null
        charRxWrite = null
        nusService = null
        runCatching { close() }
            .onFailure { Log.stack(TAG, "serviceDiscoveryWatchdog(close)", it) }
        scheduleReconnect("MQ service discovery watchdog", 250L)
    }

    private val serviceDiscoveryRetryRunnable = object : Runnable {
        override fun run() {
            if (stop || phase != Phase.DISCOVERING || serviceDiscoveryHandled) return
            val gatt = mBluetoothGatt ?: return
            if (serviceDiscoveryRetryCount >= MAX_SERVICE_DISCOVERY_RETRIES) return
            serviceDiscoveryRetryCount += 1
            val attempt = serviceDiscoveryRetryCount
            Log.w(
                TAG,
                "MQ service discovery retry $attempt/$MAX_SERVICE_DISCOVERY_RETRIES on ${gatt.device?.address}"
            )
            if (beginServiceDiscovery(gatt, "retry-$attempt") &&
                attempt < MAX_SERVICE_DISCOVERY_RETRIES &&
                phase == Phase.DISCOVERING &&
                !serviceDiscoveryHandled
            ) {
                handler.postDelayed(this, SERVICE_DISCOVERY_RETRY_DELAY_MS)
            }
        }
    }

    private val firstFrameWatchdog = Runnable {
        if (stop || phase != Phase.STREAMING || lastProtocolFrameAtMs > 0L) return@Runnable
        Log.w(TAG, "MQ connected but no frames arrived after notifications — forcing reconnect")
        mActiveBluetoothDevice = null
        runCatching { mBluetoothGatt?.disconnect() }
    }

    private val protocolFrameWatchdog = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        val lastFrameMs = lastProtocolFrameAtMs
        if (lastFrameMs <= 0L) return@Runnable
        val elapsedMs = System.currentTimeMillis() - lastFrameMs
        if (elapsedMs < protocolFrameTimeoutMs()) {
            armProtocolFrameWatchdog()
            return@Runnable
        }
        Log.w(TAG, "MQ link silent for ${elapsedMs / 1000}s — forcing reconnect")
        mActiveBluetoothDevice = null
        runCatching { mBluetoothGatt?.disconnect() }
    }

    private val noDataWatchdog = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        val lastReadingMs = lastGlucoseAtMs
        if (lastReadingMs <= 0L) return@Runnable
        val elapsedMs = System.currentTimeMillis() - lastReadingMs
        if (elapsedMs < noDataWatchdogMs()) {
            armNoDataWatchdog()
            return@Runnable
        }
        Log.w(TAG, "No MQ glucose for ${elapsedMs / 1000}s — forcing reconnect")
        mActiveBluetoothDevice = null
        runCatching { mBluetoothGatt?.disconnect() }
        scheduleReconnect("No-data watchdog", ACTIVE_SESSION_RECONNECT_DELAY_MS)
    }

    private val bgBurstFlushRunnable = Runnable {
        flushPendingBgBurst("timeout")
    }

    // ---- Persistence ----

    private fun hydrateKnownDeviceAddress(context: Context, sensorId: String) {
        val currentAddress = mActiveDeviceAddress?.trim().orEmpty()
        if (currentAddress.isNotEmpty()) {
            MQRegistry.ensureSensorRecord(
                context = context,
                sensorId = sensorId,
                address = currentAddress,
                displayName = vendorModelNameInternal,
            )
            return
        }
        val persistedAddress = MQRegistry.findRecord(context, sensorId)
            ?.address
            ?.trim()
            .orEmpty()
            .takeIf { it.isNotEmpty() }
        if (persistedAddress != null) {
            mActiveDeviceAddress = persistedAddress
            return
        }
        val nativeAddress = runCatching { Natives.getDeviceAddress(dataptr, true) }
            .getOrNull()
            ?.trim()
            .orEmpty()
            .takeIf { it.isNotEmpty() }
        if (nativeAddress != null) {
            mActiveDeviceAddress = nativeAddress
            MQRegistry.ensureSensorRecord(
                context = context,
                sensorId = sensorId,
                address = nativeAddress,
                displayName = vendorModelNameInternal,
            )
        }
    }

    fun restoreFromPersistence(context: Context) {
        val id = SerialNumber ?: return
        hydrateKnownDeviceAddress(context, id)
        protocolType = MQRegistry.loadProtocolType(context, id)
        deviation = MQRegistry.loadDeviation(context, id)
        transmitter10 = MQRegistry.loadTransmitter10(context, id)
        warmupStartedAtMs = MQRegistry.loadWarmupStartedAt(context, id)
        sensorStartAtMs = MQRegistry.loadSensorStartAt(context, id)
        sensitivitySeed = MQRegistry.loadSensitivity(context, id)
        algorithmVersion = MQRegistry.loadAlgorithmVersion(context, id)
        kValue = MQRegistry.loadKValue(context, id)
        bValue = MQRegistry.loadBValue(context, id)
        localResetPending = MQRegistry.loadLocalResetPending(context, id)
        if (!hasValidSlopeSeed(kValue.toDouble()) && kValue != 0f) {
            Log.w(TAG, "Discarding invalid persisted MQ K seed=$kValue for $id")
            kValue = 0f
            bValue = 0f
            MQRegistry.saveKValue(context, id, 0f)
            MQRegistry.saveBValue(context, id, 0f)
        }
        multiplier = MQRegistry.loadMultiplier(context, id)
        packages = MQRegistry.loadPackages(context, id)
        lastProcessed = MQRegistry.loadLastProcessed(context, id).toDouble().coerceAtLeast(0.0)
        lastPacketIndex = MQRegistry.loadLastPacketIndex(context, id)
        lastCloudReportedPacketIndex = MQRegistry.loadLastCloudReportedPacketIndex(context, id)
        lastObservedPacketIndex = lastPacketIndex
        crcXorOut = if (protocolType == 2) 0x0100 else 0x0000
        ensureNativeDataptr(id)
        hydrateKnownDeviceAddress(context, id)
        if (hasUsableSlopeSeed()) {
            if (localResetPending) {
                clearLocalResetPending("restored-valid-k")
            }
            lastBootstrapFailure = MQBootstrapFailure.NONE
            lastBootstrapMessage = ""
        }
        if (needsVendorBootstrap()) {
            maybeRefreshBootstrapAsync(context, "restore")
        }
        maybeFetchCloudHistoryBackfillAsync(context, "restore")
    }

    private fun persistSensorStart(context: Context, timestamp: Long) {
        val id = SerialNumber ?: return
        MQRegistry.saveSensorStartAt(context, id, timestamp)
    }

    private fun persistWarmupStart(context: Context, timestamp: Long) {
        val id = SerialNumber ?: return
        MQRegistry.saveWarmupStartedAt(context, id, timestamp)
    }

    private fun noDataWatchdogMs(): Long =
        NO_DATA_WATCHDOG_MULTIPLIER * profile.readingIntervalMinutes * 60L * 1000L

    private fun firstFrameTimeoutMs(): Long =
        profile.readingIntervalMinutes * 60L * 1000L + FIRST_FRAME_GRACE_MS

    private fun protocolFrameTimeoutMs(): Long =
        LINK_FRAME_TIMEOUT_MULTIPLIER * profile.readingIntervalMinutes * 60L * 1000L + FIRST_FRAME_GRACE_MS

    private fun armNoDataWatchdog() {
        handler.removeCallbacks(noDataWatchdog)
        if (lastGlucoseAtMs > 0L) {
            handler.postDelayed(noDataWatchdog, noDataWatchdogMs())
        }
    }

    private fun armProtocolFrameWatchdog() {
        handler.removeCallbacks(protocolFrameWatchdog)
        if (lastProtocolFrameAtMs > 0L) {
            handler.postDelayed(protocolFrameWatchdog, protocolFrameTimeoutMs())
        }
    }

    private fun clearLinkWatchdogs() {
        handler.removeCallbacks(serviceDiscoveryWatchdog)
        handler.removeCallbacks(serviceDiscoveryRetryRunnable)
        handler.removeCallbacks(firstFrameWatchdog)
        handler.removeCallbacks(protocolFrameWatchdog)
        handler.removeCallbacks(noDataWatchdog)
        handler.removeCallbacks(bgBurstFlushRunnable)
    }

    private fun cancelReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        reconnectReason = ""
    }

    private fun scheduleReconnect(reason: String, delayMs: Long = ACTIVE_SESSION_RECONNECT_DELAY_MS) {
        if (stop) return
        reconnectReason = reason
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun nativeCreationSensorName(sensorId: String): String =
        MQConstants.canonicalSensorId(sensorId)

    private fun resolveExistingNativeSensorName(sensorId: String): String? {
        val canonical = nativeCreationSensorName(sensorId)
        if (canonical.isBlank() || MQConstants.isProvisionalSensorId(canonical)) {
            return null
        }
        runCatching { Natives.resolveFullSensorName(canonical) }
            .getOrNull()
            ?.trim()
            ?.takeIf { MQConstants.matchesCanonicalOrKnownNativeAlias(it, canonical) }
            ?.let { return it }
        runCatching { Natives.activeSensors() }
            .getOrNull()
            ?.firstOrNull { MQConstants.matchesCanonicalOrKnownNativeAlias(it, canonical) }
            ?.takeIf { !it.isNullOrBlank() }
            ?.let { return it }
        runCatching { Natives.lastsensorname() }
            .getOrNull()
            ?.takeIf { MQConstants.matchesCanonicalOrKnownNativeAlias(it, canonical) }
            ?.let { return it }
        return null
    }

    private fun resolveNativeSensorPtr(sensorId: String): Long {
        if (sensorId.isBlank() || MQConstants.isProvisionalSensorId(sensorId)) {
            return 0L
        }
        if (dataptr != 0L) {
            runCatching { Natives.getsensorptr(dataptr) }
                .getOrNull()
                ?.takeIf { it != 0L }
                ?.let { return it }
            runCatching { Natives.freedataptr(dataptr) }
                .onFailure { Log.stack(TAG, "resolveNativeSensorPtr(freedataptr)", it) }
            dataptr = 0L
        }
        val nativeName = resolveExistingNativeSensorName(sensorId) ?: return 0L
        return runCatching { Natives.str2sensorptr(nativeName) }.getOrDefault(0L)
    }

    private fun applyNativeSensorMetadata(nativeName: String = nativeCreationSensorName(SerialNumber)) {
        if (nativeName.isBlank()) return
        if (dataptr != 0L && !mActiveDeviceAddress.isNullOrEmpty()) {
            runCatching { Natives.setDeviceAddress(dataptr, mActiveDeviceAddress) }
                .onFailure { Log.stack(TAG, "applyNativeSensorMetadata(setDeviceAddress)", it) }
            runCatching { Natives.unfinishSensor(dataptr) }
                .onFailure { Log.stack(TAG, "applyNativeSensorMetadata(unfinishSensor)", it) }
        }
        val current = runCatching { Natives.lastsensorname() }.getOrNull()
        if (current.isNullOrBlank() || MQConstants.matchesCanonicalOrKnownNativeAlias(current, SerialNumber)) {
            runCatching { Natives.setcurrentsensor(nativeName) }
                .onFailure { Log.stack(TAG, "applyNativeSensorMetadata(setcurrentsensor)", it) }
        }
        if (sensorStartAtMs > 0L) {
            sensorstartmsec = sensorStartAtMs
        } else {
            val sensorPtr = resolveNativeSensorPtr(SerialNumber)
            if (sensorPtr != 0L) {
                sensorstartmsec = runCatching { Natives.getSensorStartmsecFromSensorptr(sensorPtr) }
                    .getOrDefault(sensorstartmsec)
            }
        }
    }

    private fun ensureNativeDataptr(sensorId: String) {
        val canonical = nativeCreationSensorName(sensorId)
        if (canonical.isBlank() || MQConstants.isProvisionalSensorId(canonical)) {
            return
        }
        if (dataptr != 0L) {
            val sensorPtr = runCatching { Natives.getsensorptr(dataptr) }.getOrDefault(0L)
            if (sensorPtr == 0L) {
                runCatching { Natives.freedataptr(dataptr) }
                    .onFailure { Log.stack(TAG, "ensureNativeDataptr(freedataptr)", it) }
                dataptr = 0L
            }
        }
        val startSec = if (sensorStartAtMs > 0L) sensorStartAtMs / 1000L else 0L
        runCatching { Natives.ensureSensorShell(canonical, startSec) }
            .onFailure { Log.stack(TAG, "ensureNativeDataptr(ensureSensorShell)", it) }
        val nativeName = resolveExistingNativeSensorName(canonical) ?: canonical
        if (dataptr == 0L) {
            dataptr = runCatching { Natives.getdataptr(nativeName) }.getOrDefault(0L)
        }
        applyNativeSensorMetadata(nativeName)
    }

    private fun mirrorReadingIntoNative(sampleMs: Long, glucoseMgdl: Int) {
        if (sampleMs <= 0L || glucoseMgdl <= 0 || SerialNumber.isBlank()) {
            return
        }
        val nativeName = nativeCreationSensorName(SerialNumber)
        runCatching {
            ensureNativeDataptr(SerialNumber)
            // Native direct-stream storage multiplies the float by 10 internally.
            val stored = Natives.addGlucoseStream(sampleMs / 1000L, glucoseMgdl / 10f, nativeName)
            if (stored) {
                NightscoutUploadWake.afterLiveNativeWrite("mq", sampleMs)
                markLocalReadingAccepted(sampleMs)
            }
            applyNativeSensorMetadata(nativeName)
            Natives.wakebackup()
        }.onFailure { Log.stack(TAG, "mirrorReadingIntoNative", it) }
    }

    // ---- ManagedBluetoothSensorDriver / MQDriver overrides ----

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        MQConstants.matchesCanonicalOrKnownNativeAlias(SerialNumber, sensorId)

    override fun hasNativeSensorBacking(): Boolean =
        SerialNumber.isNotBlank() && resolveExistingNativeSensorName(SerialNumber) != null
    override fun shouldUseNativeHistorySync(): Boolean = false

    override fun getDetailedBleStatus(): String = when (phase) {
        Phase.IDLE -> if (mActiveBluetoothDevice == null) "Searching" else "Idle"
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Discovering services"
        Phase.STREAMING -> when {
            lastGlucoseAtMs > 0L -> "Connected"
            bootstrapFetchInFlight -> "Restoring vendor session"
            lastBootstrapFailure == MQBootstrapFailure.AUTH_EXPIRED -> "Need MQ login to restore session"
            lastBootstrapFailure == MQBootstrapFailure.SERVER -> "MQ bootstrap failed"
            lastRecordReceivedAtMs > 0L && hasBootstrapSlopeSeed() -> "Connected, solving session state"
            lastRecordReceivedAtMs > 0L && !hasUsableSlopeSeed() -> "Need vendor restore or local calibration"
            lastProtocolFrameAtMs == 0L -> "Waiting for first packet"
            else -> "Connected, waiting for glucose"
        }
    }

    override fun getStartTimeMs(): Long = sensorStartAtMs
    override fun getOfficialEndMs(): Long =
        if (sensorStartAtMs > 0L) sensorStartAtMs + profile.ratedLifetimeMs() else 0L
    override fun getExpectedEndMs(): Long = getOfficialEndMs()

    override fun isSensorExpired(): Boolean {
        val end = getOfficialEndMs()
        return end > 0L && System.currentTimeMillis() >= end
    }

    override fun getSensorRemainingHours(): Int {
        val end = getOfficialEndMs()
        if (end <= 0L) return -1
        val remaining = end - System.currentTimeMillis()
        return if (remaining <= 0L) 0 else ((remaining + 30 * 60 * 1000L) / (60L * 60 * 1000L)).toInt()
    }

    override fun getSensorAgeHours(): Int {
        if (sensorStartAtMs <= 0L) return -1
        val age = System.currentTimeMillis() - sensorStartAtMs
        if (age <= 0L) return 0
        return ((age + 30 * 60 * 1000L) / (60L * 60 * 1000L)).toInt()
    }

    override fun getReadingIntervalMinutes(): Int = profile.readingIntervalMinutes

    override fun calibrateSensor(glucoseMgDl: Int): Boolean {
        if (glucoseMgDl <= 0) return false
        pendingReferenceBgTimes10Mmol = (glucoseMgDl / 18.0182) * 10.0
        if (!applyPendingCalibrationFromLatestSample()) {
            Log.i(TAG, "Queued local MQ calibration for next live packet")
        }
        UiRefreshBus.requestStatusRefresh()
        return true
    }

    override fun refreshVendorBootstrap(
        context: Context,
        qrCode: String?,
        account: String?,
        password: String?,
    ): Boolean {
        val id = SerialNumber ?: return false
        if (bootstrapFetchInFlight) return false
        val normalizedQr = qrCode?.trim().orEmpty().takeIf { it.isNotEmpty() }
            ?: MQRegistry.loadQrContent(context, id)?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val normalizedAccount = account?.trim().orEmpty()
        val credentials = if (normalizedAccount.isNotEmpty() && !password.isNullOrBlank()) {
            MQRegistry.saveAuthCredentials(context, normalizedAccount, password)
            MQAuthCredentials(normalizedAccount, password)
        } else {
            MQRegistry.loadAuthCredentials(context)
        }
        if (normalizedQr != null) {
            MQRegistry.saveQrContent(context, id, normalizedQr)
        }
        val bleId = resolveBootstrapBleId(context, id)
        if (normalizedQr == null && bleId == null) return false
        bootstrapFetchInFlight = true
        lastBootstrapAttemptAtMs = System.currentTimeMillis()
        return try {
            val result = MQBootstrapClient.fetchBestEffort(
                context = context,
                bleId = bleId,
                qrCode = normalizedQr,
                authToken = MQRegistry.loadAuthToken(context),
                credentials = credentials,
                allowContinueWearRestore = !localResetPending,
            )
            applyBootstrapFetchResult(
                context = context,
                sensorId = id,
                result = result,
                reason = "manual",
            )
        } catch (t: Throwable) {
            Log.stack(TAG, "refreshVendorBootstrap(manual)", t)
            false
        } finally {
            bootstrapFetchInFlight = false
        }
    }

    override val vendorFirmwareVersion: String get() = vendorFirmwareVersionInternal
    override val vendorModelName: String get() = vendorModelNameInternal
    override val batteryMillivolts: Int
        get() = 0
    override val batteryPercent: Int
        get() = lastBatteryPercent

    override fun getCurrentSnapshot(maxAgeMillis: Long): MQCurrentSnapshot? {
        if (lastGlucoseAtMs == 0L) return null
        val age = System.currentTimeMillis() - lastGlucoseAtMs
        if (age > maxAgeMillis) return null
        val glucoseMgdl = lastGlucoseMgdlTimes10 / 10f
        val glucoseDisplay = if (Applic.unit == 1) {
            (glucoseMgdl / MQConstants.MMOL_TO_MGDL).toFloat()
        } else {
            glucoseMgdl
        }
        return MQCurrentSnapshot(
            timeMillis = lastGlucoseAtMs,
            glucoseValue = glucoseDisplay,
            rawValue = lastRawCurrent.toFloat(),
            rate = Float.NaN,
            sensorGen = sensorgen,
        )
    }

    override fun removeManagedPersistence(context: Context) {
        MQRegistry.removeSensor(context, SerialNumber)
    }

    override fun softDisconnect() {
        setPause(true)
        cancelReconnect()
        flushPendingBgBurst("soft-disconnect")
        clearLinkWatchdogs()
        phase = Phase.IDLE
        runCatching { close() }
            .onFailure { Log.stack(TAG, "softDisconnect(close)", it) }
        mActiveBluetoothDevice = null
        UiRefreshBus.requestStatusRefresh()
    }

    override fun softReconnect() {
        setPause(false)
        cancelReconnect()
        flushPendingBgBurst("soft-reconnect")
        clearLinkWatchdogs()
        if (dataptr != 0L) {
            runCatching { Natives.unfinishSensor(dataptr) }
                .onFailure { Log.stack(TAG, "softReconnect(unfinishSensor)", it) }
        }
        runCatching { close() }
            .onFailure { Log.stack(TAG, "softReconnect(close)", it) }
        phase = Phase.IDLE
        handler.postDelayed({
            if (!stop) connectDevice(0)
        }, 250L)
        UiRefreshBus.requestStatusRefresh()
    }

    override fun onBluetoothAdapterUnavailable() {
        cancelReconnect()
        flushPendingBgBurst("bluetooth-off")
        clearLinkWatchdogs()
        phase = Phase.IDLE
        mActiveBluetoothDevice = null
        constatstatusstr = Applic.getContext().getString(tk.glucodata.R.string.status_bluetooth_off)
        UiRefreshBus.requestStatusRefresh()
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        Applic.app?.let { maybeEndCloudSessionAsync(it, "terminate") }
        setPause(true)
        cancelReconnect()
        flushPendingBgBurst("terminate")
        clearLinkWatchdogs()
        phase = Phase.IDLE
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
    }

    override fun resetSensor(): Boolean {
        val frame = when {
            protocolType == 2 || crcXorOut == 0x0100 -> MagicAck.reset00.copyOf()
            else -> MQParser.buildConfirmReset(0)
        }
        if (!writeFrameNow(frame, "confirmReset")) {
            Log.w(TAG, "MQ reset rejected: GATT write path not ready")
            return false
        }
        Applic.app?.let { maybeEndCloudSessionAsync(it, "reset") }
        markLocalResetPending()
        handler.postDelayed({
            if (!stop) {
                runCatching { mBluetoothGatt?.disconnect() }
            }
        }, RESET_RECONNECT_DELAY_MS)
        UiRefreshBus.requestStatusRefresh()
        return true
    }

    override fun setDeviceAddress(address: String?) {
        super.setDeviceAddress(address)
        val normalized = address?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return
        val context = Applic.app ?: return
        val sensorId = SerialNumber ?: return
        MQRegistry.ensureSensorRecord(
            context = context,
            sensorId = sensorId,
            address = normalized,
            displayName = vendorModelNameInternal,
        )
    }

    // ---- BLE lifecycle ----

    override fun getService(): UUID = MQConstants.NUS_SERVICE

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        if (phase == Phase.CONNECTING || phase == Phase.DISCOVERING || phase == Phase.STREAMING) {
            val hasLiveGatt = mBluetoothGatt != null
            val hasKnownDevice = mActiveBluetoothDevice != null || !mActiveDeviceAddress.isNullOrBlank()
            if (hasLiveGatt || hasKnownDevice) {
                Log.i(
                    TAG,
                    "Skipping duplicate connect request while phase=$phase delay=${delayMillis}ms " +
                        "gatt=${mBluetoothGatt != null} device=${mActiveBluetoothDevice != null}"
                )
                return true
            }
        }
        phase = Phase.CONNECTING
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled && phase == Phase.CONNECTING) {
            phase = Phase.IDLE
        }
        return scheduled
    }

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val trimmed = deviceName?.trim().orEmpty()
        val knownAddress = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        if (knownAddress != null && address != null && address.equals(knownAddress, ignoreCase = true)) return true
        if (!address.isNullOrBlank() &&
            MQConstants.canonicalSensorId(address).equals(SerialNumber, ignoreCase = true)
        ) {
            return true
        }
        if (trimmed.isEmpty()) return false
        val advertisedCanonical = MQConstants.canonicalSensorId(trimmed)
        if (advertisedCanonical.equals(SerialNumber, ignoreCase = true)) return true
        return MQConstants.isMqDevice(trimmed)
    }

    private fun beginServiceDiscovery(gatt: BluetoothGatt, reason: String): Boolean {
        return try {
            Log.i(TAG, "MQ discoverServices($reason) on ${gatt.device?.address}")
            if (!gatt.discoverServices()) {
                Log.e(TAG, "discoverServices() returned false ($reason)")
                phase = Phase.IDLE
                runCatching { close() }
                    .onFailure { Log.stack(TAG, "discoverServices(close:$reason)", it) }
                scheduleReconnect("discoverServices() returned false ($reason)", 250L)
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.stack(TAG, "discoverServices($reason)", t)
            phase = Phase.IDLE
            runCatching { close() }
                .onFailure { Log.stack(TAG, "discoverServices(close:$reason)", it) }
            scheduleReconnect("discoverServices() threw ($reason)", 250L)
            false
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        noteFirstGattCallback("onConnectionStateChange", gatt)
        super.onConnectionStateChange(gatt, status, newState)
        if (stop) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Connected to ${gatt.device?.address}")
                cancelReconnect()
                clearLinkWatchdogs()
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                connectTime = System.currentTimeMillis()
                lastProtocolFrameAtMs = 0L
                serviceDiscoveryHandled = false
                serviceDiscoveryRetryCount = 0
                phase = Phase.DISCOVERING
                handler.postDelayed({
                    if (phase == Phase.DISCOVERING && mBluetoothGatt === gatt && !serviceDiscoveryHandled) {
                        if (beginServiceDiscovery(gatt, "initial")) {
                            handler.postDelayed(serviceDiscoveryRetryRunnable, SERVICE_DISCOVERY_RETRY_DELAY_MS)
                        }
                    }
                }, 250)
                handler.postDelayed(serviceDiscoveryWatchdog, SERVICE_DISCOVERY_TIMEOUT_MS)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Disconnected (status=$status)")
                flushPendingBgBurst("disconnect")
                phase = Phase.IDLE
                charTxNotify = null
                charRxWrite = null
                nusService = null
                mActiveBluetoothDevice = null
                lastProtocolFrameAtMs = 0L
                serviceDiscoveryHandled = false
                serviceDiscoveryRetryCount = 0
                try { gatt.close() } catch (_: Throwable) {}
                mBluetoothGatt = null
                handler.removeCallbacksAndMessages(null)
                if (!stop) {
                    scheduleReconnect("GATT disconnected (status=$status)")
                }
            }
            else -> {
                if (phase == Phase.CONNECTING && status != BluetoothGatt.GATT_SUCCESS) {
                    phase = Phase.IDLE
                }
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        handler.removeCallbacks(serviceDiscoveryWatchdog)
        handler.removeCallbacks(serviceDiscoveryRetryRunnable)
        if (serviceDiscoveryHandled) {
            Log.d(TAG, "Ignoring duplicate services discovered callback for $SerialNumber")
            return
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            serviceDiscoveryHandled = false
            Log.e(TAG, "Service discovery failed status=$status")
            runCatching { gatt.disconnect() }
            return
        }
        serviceDiscoveryHandled = true
        val service = gatt.getService(MQConstants.NUS_SERVICE)
        if (service == null) {
            Log.e(TAG, "NUS service not present on ${gatt.device?.address}")
            runCatching { gatt.disconnect() }
            return
        }
        nusService = service
        charTxNotify = service.getCharacteristic(MQConstants.NUS_TX_NOTIFY)
        charRxWrite = service.getCharacteristic(MQConstants.NUS_RX_WRITE)
        val tx = charTxNotify
        if (tx == null) {
            Log.e(TAG, "NUS TX characteristic missing")
            runCatching { gatt.disconnect() }
            return
        }
        if (!enableNotification(gatt, tx)) {
            Log.e(TAG, "Failed to enable TX notifications")
        } else {
            Log.i(TAG, "TX notifications enabled")
            phase = Phase.STREAMING
            handler.removeCallbacks(firstFrameWatchdog)
            handler.postDelayed(firstFrameWatchdog, firstFrameTimeoutMs())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (characteristic.uuid != MQConstants.NUS_TX_NOTIFY) return
        val data = characteristic.value ?: return
        val frame = MQParser.parse(data)
        if (frame == null) {
            logRejectedFrame(data)
            return
        }
        lastProtocolFrameAtMs = System.currentTimeMillis()
        handler.removeCallbacks(firstFrameWatchdog)
        armProtocolFrameWatchdog()
        learnCrcVariant(frame)
        when (frame.cmd) {
            MQConstants.CMD_NOTIFY_BEGIN_WORK -> onBeginWork()
            MQConstants.CMD_NOTIFY_WORKING -> onHeartbeat()
            MQConstants.CMD_NOTIFY_BG_DATA -> onBgData(frame)
            MQConstants.CMD_NOTIFY_BG_COMPLETE -> flushPendingBgBurst("bg-complete")
            else -> Log.d(TAG, "Unhandled cmd 0x${"%02X".format(frame.cmdUnsigned)}")
        }
    }

    /**
     * Infer the firmware's CRC variant from the first incoming frame.
     *
     * We recompute Modbus over the frame body and XOR the result with the
     * observed trailing bytes. If the XOR is 0x0000 the firmware is Protocol01;
     * if it's 0x0100 it's the Protocol02 variant observed on W25101399. Any
     * other value gets logged so we can add it to the table later.
     */
    private fun learnCrcVariant(frame: MQFrame) {
        if (crcXorOutLearned) return
        val body = frame.raw
        if (body.size < 2) return
        val computed = MQCrc16.compute(body, 0, body.size - 2)
        val observed = ((body[body.size - 2].toInt() and 0xFF) shl 8) or
            (body[body.size - 1].toInt() and 0xFF)
        val xorOut = (computed xor observed) and 0xFFFF
        crcXorOut = xorOut
        crcXorOutLearned = true
        Log.i(TAG, "Learned CRC variant: xorOut=0x%04X (computed=0x%04X observed=0x%04X)"
            .format(xorOut, computed, observed))
    }

    /**
     * Emit everything we know about a rejected frame so we can figure out
     * whether it's a header mismatch, CRC endianness flip, or an unknown
     * protocol variant. Prints hex, ASCII-ish view, and three CRC candidates.
     */
    private fun logRejectedFrame(data: ByteArray) {
        val hex = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        val hdrOk = data.size >= 2 &&
            data[0] == MQConstants.HEADER0 && data[1] == MQConstants.HEADER1
        val cmd = if (data.size > MQConstants.OFFSET_CMD)
            "0x%02X".format(data[MQConstants.OFFSET_CMD].toInt() and 0xFF) else "?"
        val len = if (data.size > MQConstants.OFFSET_LEN)
            data[MQConstants.OFFSET_LEN].toInt() and 0xFF else -1
        val expectedSize = if (len >= 0) MQConstants.FRAME_OVERHEAD + len else -1

        // CRC candidates over all bytes except the last two.
        val computed = if (data.size >= 2) MQCrc16.compute(data, 0, data.size - 2) else 0
        val tailHi = if (data.size >= 2) data[data.size - 2].toInt() and 0xFF else 0
        val tailLo = if (data.size >= 2) data[data.size - 1].toInt() and 0xFF else 0
        val tailBE = (tailHi shl 8) or tailLo
        val tailLE = (tailLo shl 8) or tailHi

        Log.w(
            TAG,
            "Rejected frame (${data.size} B): [$hex] " +
                "hdrOk=$hdrOk cmd=$cmd len=$len expSize=$expectedSize " +
                "crcComputed=0x%04X tailBE=0x%04X tailLE=0x%04X".format(computed, tailBE, tailLE)
        )
    }

    // ---- Frame handlers ----

    /**
     * Protocol02 pre-baked ack bytes, reverse-engineered from the official
     * Glutec app's HCI btsnoop log on transmitter W25101399. These are NOT
     * computed CRCs — they're hardcoded byte sequences in the app's Protocol02
     * table. Our own CRC code will never reproduce them.
     *
     * In the log, the transmitter got stuck in a BEGIN_WORK loop for 103s while
     * the app cycled through Modbus and Modbus^0x0100 variants. The loop broke
     * only after a fresh BLE reconnection followed by ONE write of
     * {5A A5 08 01 00 4A 6F}, which caused the transmitter to advance to
     * heartbeat (0x01) within 1.8 seconds.
     */
    private object MagicAck {
        // confirmWithInit (cmd=0x08, payload=0x00) — the unlock ack.
        val withInit00: ByteArray = byteArrayOf(0x5A, -0x5B, 0x08, 0x01, 0x00, 0x4A, 0x6F)
        // bgDataConfirm (cmd=0x02, payload=0x00) — observed 21x in official log.
        val bgData00: ByteArray = byteArrayOf(0x5A, -0x5B, 0x02, 0x01, 0x00, 0x49, 0x7F)
        // confirmWithoutInit (cmd=0x03, payload=0x00) — observed (Modbus^0x0100 form).
        val withoutInit00: ByteArray = byteArrayOf(0x5A, -0x5B, 0x03, 0x01, 0x00, 0x50, -0x51)
        // confirmReset (cmd=0x11, payload=0x00) — fixed Protocol02 bytes from the official app.
        val reset00: ByteArray = byteArrayOf(0x5A, -0x5B, 0x11, 0x01, 0x00, -0x56, 0x07)
    }

    private data class AckCandidate(
        val label: String,
        val cmd: Byte,
        val payload: Byte,
        val useLearnedXor: Boolean,
    )

    @Volatile private var beginWorkRetryCount: Int = 0
    @Volatile private var beginWorkFirstSeenMs: Long = 0L
    /** After this many 0x06 pings without the transmitter advancing, drop the
     *  connection and let SuperGattCallback reconnect fresh. This is what the
     *  official app did in the wild — 103s of failures then a fresh connect
     *  unlocked the state machine. */
    private val MAX_BEGIN_WORK_RETRIES_BEFORE_RECONNECT = 5

    /**
     * Once the transmitter advances past 0x06 we freeze the ack convention
     * (payload byte + CRC xor) that got us there, and reuse it for all
     * subsequent acks (heartbeat/BG). The command byte still comes from the
     * per-frame mapping (0x03 for heartbeat, 0x02 for BG data).
     */
    @Volatile private var learnedAckPayload: Byte = 0x00
    @Volatile private var learnedAckUseLearnedXor: Boolean = true
    @Volatile private var ackConventionLocked: Boolean = false
    @Volatile private var lastBeginWorkCandidate: AckCandidate? = null

    private fun buildAck(c: AckCandidate): ByteArray {
        val xor = if (c.useLearnedXor) crcXorOut else 0x0000
        val frame = byteArrayOf(
            MQConstants.HEADER0,
            MQConstants.HEADER1,
            c.cmd,
            0x01, // LEN
            c.payload,
            0x00, 0x00, // CRC placeholder
        )
        return MQCrc16.stamp(frame, xor)
    }

    private fun onBeginWork() {
        val now = System.currentTimeMillis()
        if (sensorStartAtMs == 0L) {
            sensorStartAtMs = now
            sensorstartmsec = now
            warmupStartedAtMs = now
            packetCount = 0
            Applic.app?.let {
                persistSensorStart(it, now)
                persistWarmupStart(it, now)
            }
            ensureNativeDataptr(SerialNumber)
            Log.i(TAG, "Sensor begin work — session start marked")
        }
        Applic.app?.takeIf { needsVendorBootstrap() }?.let {
            maybeRefreshBootstrapAsync(it, "begin-work")
        }
        if (beginWorkFirstSeenMs == 0L) beginWorkFirstSeenMs = now
        beginWorkRetryCount++

        // If we've sent several acks and the transmitter is still echoing 0x06,
        // the GATT state is wedged. Drop and reconnect — in the official app's
        // log this is exactly what broke the loop.
        if (beginWorkRetryCount > MAX_BEGIN_WORK_RETRIES_BEFORE_RECONNECT) {
            Log.w(TAG, "BEGIN_WORK looped ${beginWorkRetryCount}x — forcing GATT reconnect")
            beginWorkRetryCount = 0
            beginWorkFirstSeenMs = 0L
            runCatching { mBluetoothGatt?.disconnect() }
            return
        }

        // Send the Protocol02 magic ack — hardcoded bytes from the official app's
        // HCI log. This is what unlocks the transmitter on a fresh connection.
        val lastAck = AckCandidate("withInit/00/magic", MQConstants.CMD_WRITE_CONFIRM_WITH_INIT, 0x00, true)
        lastBeginWorkCandidate = lastAck
        Log.i(TAG, "BEGIN_WORK ack #$beginWorkRetryCount — Protocol02 magic (withInit/00)")
        writeFrame(MagicAck.withInit00.copyOf(), "beginWorkAck(magic)")
    }

    /**
     * Called when we observe the transmitter advance past BEGIN_WORK (i.e. it
     * sends a 0x01 heartbeat or 0x04 BG data). The last-tried candidate is
     * what worked — freeze its payload-byte and CRC-variant for all future
     * acks so we don't rotate away from a working shape.
     */
    private fun lockAckConventionIfNeeded() {
        if (ackConventionLocked) return
        val c = lastBeginWorkCandidate ?: return
        learnedAckPayload = c.payload
        learnedAckUseLearnedXor = c.useLearnedXor
        ackConventionLocked = true
        Log.i(
            TAG,
            "Locked ack convention: payload=0x%02X crcXorOut=0x%04X (from %s)".format(
                c.payload.toInt() and 0xFF,
                if (c.useLearnedXor) crcXorOut else 0x0000,
                c.label,
            ),
        )
    }

    private fun buildLockedAck(cmd: Byte, tag: String): ByteArray {
        val candidate = AckCandidate(tag, cmd, learnedAckPayload, learnedAckUseLearnedXor)
        return buildAck(candidate)
    }

    private fun onHeartbeat() {
        beginWorkRetryCount = 0
        beginWorkFirstSeenMs = 0L
        flushPendingBgBurst("heartbeat")
        armNoDataWatchdog()
        // Protocol02 magic — computed CRC would be rejected (see HCI log analysis).
        writeFrame(MagicAck.withoutInit00.copyOf(), "confirmWithoutInit(magic)")
    }

    private fun persistAlgorithmState() {
        val context = Applic.app ?: return
        val id = SerialNumber ?: return
        MQRegistry.saveKValue(context, id, kValue)
        MQRegistry.saveBValue(context, id, bValue)
        MQRegistry.saveLastProcessed(context, id, lastProcessed.toFloat())
        MQRegistry.saveLastPacketIndex(context, id, lastPacketIndex)
    }

    private fun clearLocalResetPending(reason: String) {
        if (!localResetPending) return
        localResetPending = false
        val context = Applic.app ?: return
        MQRegistry.saveLocalResetPending(context, SerialNumber, false)
        Log.i(TAG, "Cleared local MQ reset pending ($reason)")
    }

    private fun markLocalResetPending() {
        localResetPending = true
        val context = Applic.app
        val id = SerialNumber ?: return
        clearVolatileSessionState()
        if (context != null) {
            MQRegistry.clearRuntimeState(
                context = context,
                sensorId = id,
                markLocalResetPending = true,
            )
        }
        lastBootstrapFailure = MQBootstrapFailure.NONE
        lastBootstrapMessage = ""
        Log.i(TAG, "Marked local MQ reset pending for $id")
    }

    private fun clearVolatileSessionState() {
        clearPendingBgBurst()
        sensorStartAtMs = 0L
        sensorstartmsec = 0L
        warmupStartedAtMs = 0L
        packetCount = 0
        pendingReferenceBgTimes10Mmol = 0.0
        lastPacketIndex = -1
        lastObservedPacketIndex = -1
        lastRecordReceivedAtMs = 0L
        lastProtocolFrameAtMs = 0L
        lastRawCurrent = 0.0
        lastProcessed = 0.0
        lastGlucoseAtMs = 0L
        lastGlucoseMgdlTimes10 = 0
        beginWorkRetryCount = 0
        beginWorkFirstSeenMs = 0L
        learnedAckPayload = 0x00
        learnedAckUseLearnedXor = true
        ackConventionLocked = false
        lastBeginWorkCandidate = null
        kValue = 0f
        bValue = 0f
    }

    private fun clearPendingBgBurst() {
        handler.removeCallbacks(bgBurstFlushRunnable)
        pendingBgBurstRecords.clear()
    }

    private fun hasValidSlopeSeed(value: Double): Boolean =
        value > MQConstants.ALGO_MIN_VALID_K

    private fun hasBootstrapSlopeSeed(): Boolean =
        hasValidSlopeSeed(sensitivitySeed.toDouble())

    private fun hasUsableSlopeSeed(): Boolean =
        hasValidSlopeSeed(kValue.toDouble())

    private fun needsVendorBootstrap(): Boolean =
        !hasUsableSlopeSeed() && !hasBootstrapSlopeSeed()

    private fun resolveBootstrapBleId(context: Context, sensorId: String): String? =
        mActiveDeviceAddress?.takeIf { it.isNotBlank() }
            ?: MQRegistry.findRecord(context, sensorId)?.address?.takeIf { it.isNotBlank() }

    private fun importBootstrapHistory(history: List<MQBootstrapHistoryPoint>, sensorId: String) {
        if (history.isEmpty()) return
        val imported = VirtualGlucoseSensorBridge.importHistory(
            sensorSerial = sensorId,
            readings = history.map { point ->
                VirtualGlucoseSensorBridge.Reading(
                    timestampMs = point.timestampMs,
                    glucoseMgdl = point.glucoseMgdl,
                )
            },
            logLabel = "MQ snapshot",
            nearDuplicateWindowMs = CLOUD_HISTORY_NEAR_DUPLICATE_MS,
        )
        if (imported > 0) {
            Log.i(TAG, "Imported $imported MQ snapshot history points into local history")
        }
    }

    private fun maybeFetchCloudHistoryBackfillAsync(
        context: Context,
        reason: String,
        snapshotIdOverride: String? = null,
    ) {
        val sensorId = SerialNumber ?: return
        val snapshotId = snapshotIdOverride?.trim().orEmpty()
            .ifEmpty { MQRegistry.loadSnapshotId(context, sensorId)?.trim().orEmpty() }
            .takeIf { it.isNotEmpty() } ?: return
        val accountState = MQRegistry.loadAccountState(context)
        if (accountState.authToken.isNullOrBlank() && accountState.credentials == null) return
        val now = System.currentTimeMillis()
        if (cloudHistoryBackfillInFlight ||
            now - cloudHistoryBackfillAttemptedAtMs < CLOUD_HISTORY_BACKFILL_RETRY_MS
        ) {
            return
        }
        cloudHistoryBackfillInFlight = true
        cloudHistoryBackfillAttemptedAtMs = now
        handler.post {
            try {
                val token = ensureCloudSyncToken(context, accountState) ?: return@post
                val previousBackfillTailMs = lastCloudHistoryBackfillTailMs
                val latestRoomMs = HistorySyncAccess.getLatestTimestampForSensor(sensorId)
                val persistedStartMs = MQRegistry.loadSensorStartAt(context, sensorId)
                val startMs = when {
                    previousBackfillTailMs > 0L -> (previousBackfillTailMs - CLOUD_HISTORY_BACKFILL_OVERLAP_MS).coerceAtLeast(1L)
                    sensorStartAtMs > 0L -> sensorStartAtMs
                    persistedStartMs > 0L -> persistedStartMs
                    latestRoomMs > 0L -> (latestRoomMs - CLOUD_HISTORY_BACKFILL_OVERLAP_MS).coerceAtLeast(1L)
                    else -> (now - CLOUD_HISTORY_BACKFILL_LOOKBACK_MS).coerceAtLeast(1L)
                }
                val result = MQCloudClient.fetchSnapshotTimeBucketHistory(
                    context = context,
                    authToken = token,
                    snapshotId = snapshotId,
                    startTimeMs = startMs,
                    endTimeMs = now + CLOUD_HISTORY_BACKFILL_FUTURE_GRACE_MS,
                )
                when {
                    result.failure == MQBootstrapFailure.AUTH_EXPIRED -> {
                        MQRegistry.clearAuthToken(context)
                        Log.w(TAG, "MQ cloud history auth expired ($reason)")
                    }
                    result.history.isNotEmpty() -> {
                        importBootstrapHistory(result.history, sensorId)
                        lastCloudHistoryBackfillTailMs = maxOf(
                            lastCloudHistoryBackfillTailMs,
                            result.history.maxOf { it.timestampMs },
                        )
                        Log.i(
                            TAG,
                            "MQ cloud history backfill synced ($reason): snapshot=$snapshotId points=${result.history.size}",
                        )
                    }
                    result.failure != MQBootstrapFailure.NONE -> {
                        Log.w(TAG, "MQ cloud history backfill failed ($reason): ${result.message}")
                    }
                    else -> {
                        Log.i(TAG, "MQ cloud history backfill empty ($reason): snapshot=$snapshotId")
                    }
                }
            } catch (t: Throwable) {
                Log.stack(TAG, "maybeFetchCloudHistoryBackfillAsync($reason)", t)
            } finally {
                cloudHistoryBackfillInFlight = false
            }
        }
    }

    private fun applyBootstrapFetchResult(
        context: Context,
        sensorId: String,
        result: MQBootstrapFetchResult,
        reason: String,
    ): Boolean {
        result.refreshedToken?.let { MQRegistry.saveAuthToken(context, it) }
        lastBootstrapFailure = result.failure
        lastBootstrapMessage = result.message.orEmpty()
        if (result.config != null) {
            MQRegistry.applyBootstrapConfig(context, sensorId, result.config)
            restoreFromPersistence(context)
            importBootstrapHistory(result.history, sensorId)
            maybeFetchCloudHistoryBackfillAsync(context, "bootstrap-$reason", result.config.snapshotId)
            if (hasUsableSlopeSeed()) {
                lastBootstrapFailure = MQBootstrapFailure.NONE
                lastBootstrapMessage = ""
            }
            UiRefreshBus.requestStatusRefresh()
            Log.i(
                TAG,
                "Applied MQ bootstrap ($reason): sensitivity=$sensitivitySeed k=$kValue b=$bValue packet=$lastPacketIndex algo=$algorithmVersion packages=$packages multiplier=$multiplier",
            )
            return true
        }
        Log.w(TAG, "MQ bootstrap unavailable for $sensorId ($reason): ${result.message}")
        UiRefreshBus.requestStatusRefresh()
        return false
    }

    private fun ensureCloudSyncToken(
        context: Context,
        accountState: MQRegistry.AccountState,
    ): String? {
        accountState.authToken?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val credentials = accountState.credentials ?: return null
        val login = MQCloudClient.signInWithPassword(context, credentials)
        val token = login.token?.trim().orEmpty()
        if (token.isNotEmpty()) {
            MQRegistry.saveAuthToken(context, token)
            return token
        }
        if (login.failure == MQBootstrapFailure.AUTH_EXPIRED) {
            MQRegistry.clearAuthToken(context)
        }
        Log.w(TAG, "MQ sync login unavailable: ${login.message}")
        return null
    }

    private fun maybeEnsureCloudSessionAsync(context: Context, reason: String) {
        if (!MQRegistry.isCloudSyncEnabled(context)) return
        val sensorId = SerialNumber ?: return
        val qrCode = MQRegistry.loadQrContent(context, sensorId)?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return
        val accountState = MQRegistry.loadAccountState(context)
        val account = accountState.phone.trim().takeIf { it.isNotEmpty() } ?: return
        val bleId = resolveBootstrapBleId(context, sensorId)
            ?.let { MQConstants.canonicalSensorId(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val snapshotId = MQRegistry.loadSnapshotId(context, sensorId)?.trim().orEmpty()
        if (snapshotId.isNotEmpty()) {
            maybeFetchCloudHistoryBackfillAsync(context, "session-$reason", snapshotId)
        }
        if (snapshotId.isNotEmpty() && snapshotId == lastAnnouncedCloudSnapshotId) return
        val now = System.currentTimeMillis()
        if (cloudSessionSyncInFlight || now - cloudSessionAttemptedAtMs < CLOUD_SESSION_RETRY_MS) return
        cloudSessionSyncInFlight = true
        cloudSessionAttemptedAtMs = now
        handler.post {
            try {
                val token = ensureCloudSyncToken(context, accountState) ?: return@post
                if (snapshotId.isNotEmpty()) {
                    val result = MQCloudClient.continueWearSession(
                        context = context,
                        authToken = token,
                        snapshotId = snapshotId,
                        bleId = bleId,
                        qrCode = qrCode,
                    )
                    when {
                        result.success -> {
                            lastAnnouncedCloudSnapshotId = snapshotId
                            Log.i(TAG, "MQ cloud continue-wear synced ($reason): snapshot=$snapshotId")
                        }
                        result.failure == MQBootstrapFailure.AUTH_EXPIRED -> {
                            MQRegistry.clearAuthToken(context)
                            Log.w(TAG, "MQ cloud session sync auth expired ($reason)")
                        }
                        else -> {
                            Log.w(TAG, "MQ cloud session sync failed ($reason): ${result.message}")
                        }
                    }
                } else {
                    val result = MQCloudClient.startWearSession(
                        context = context,
                        authToken = token,
                        bleId = bleId,
                        mac = bleId,
                        account = account,
                        qrCode = qrCode,
                    )
                    when {
                        result.success && !result.snapshotId.isNullOrBlank() -> {
                            MQRegistry.saveSnapshotId(context, sensorId, result.snapshotId)
                            MQRegistry.saveLastCloudReportedPacketIndex(context, sensorId, -1)
                            lastAnnouncedCloudSnapshotId = result.snapshotId
                            lastCloudReportedPacketIndex = -1
                            maybeFetchCloudHistoryBackfillAsync(context, "session-start-$reason", result.snapshotId)
                            Log.i(TAG, "MQ cloud start-wear created snapshot=${result.snapshotId} ($reason)")
                        }
                        result.failure == MQBootstrapFailure.AUTH_EXPIRED -> {
                            MQRegistry.clearAuthToken(context)
                            Log.w(TAG, "MQ cloud session sync auth expired ($reason)")
                        }
                        else -> {
                            Log.w(TAG, "MQ cloud session sync failed ($reason): ${result.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.stack(TAG, "maybeEnsureCloudSessionAsync($reason)", t)
            } finally {
                cloudSessionSyncInFlight = false
            }
        }
    }

    private fun maybeUploadCalibrationSyncAsync(
        context: Context,
        packetIndex: Int,
        sampleMs: Long,
        referenceBgTimes10Mmol: Double,
        solvedK: Double,
        solvedB: Double,
    ) {
        if (!MQRegistry.isCloudSyncEnabled(context)) return
        val sensorId = SerialNumber ?: return
        val snapshotId = MQRegistry.loadSnapshotId(context, sensorId)?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return
        val accountState = MQRegistry.loadAccountState(context)
        handler.post {
            try {
                val token = ensureCloudSyncToken(context, accountState) ?: return@post
                val bgValueMmol = referenceBgTimes10Mmol / 10.0
                val eventResult = MQCloudClient.uploadCalibrationEvent(
                    context = context,
                    authToken = token,
                    snapshotId = snapshotId,
                    packetIndex = packetIndex,
                    bagTimeMs = sampleMs,
                    bgValueMmol = bgValueMmol,
                    referenceK = solvedK,
                    referenceB = solvedB,
                )
                val timeResult = MQCloudClient.uploadCalibrationTime(
                    context = context,
                    authToken = token,
                    snapshotId = snapshotId,
                    bagTimeMs = sampleMs,
                    packetIndex = packetIndex,
                )
                if (eventResult.success && timeResult.success) {
                    Log.i(TAG, "MQ cloud calibration synced: snapshot=$snapshotId packet=$packetIndex")
                } else if (eventResult.failure == MQBootstrapFailure.AUTH_EXPIRED ||
                    timeResult.failure == MQBootstrapFailure.AUTH_EXPIRED
                ) {
                    MQRegistry.clearAuthToken(context)
                    Log.w(TAG, "MQ cloud calibration sync auth expired")
                } else {
                    Log.w(
                        TAG,
                        "MQ cloud calibration sync incomplete: event=${eventResult.message} time=${timeResult.message}",
                    )
                }
            } catch (t: Throwable) {
                Log.stack(TAG, "maybeUploadCalibrationSyncAsync", t)
            }
        }
    }

    private fun maybeUploadLiveReportAsync(
        context: Context,
        rec: MQBgRecord,
        result: MQAlgorithm.Result,
    ) {
        if (!MQRegistry.isCloudSyncEnabled(context)) return
        val sensorId = SerialNumber ?: return
        val snapshotId = MQRegistry.loadSnapshotId(context, sensorId)?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return
        if (rec.packetIndex <= lastCloudReportedPacketIndex) return
        val accountState = MQRegistry.loadAccountState(context)
        val calculateData = buildVendorCalculateData(rec, result)
        handler.post {
            try {
                if (rec.packetIndex <= lastCloudReportedPacketIndex) return@post
                val token = ensureCloudSyncToken(context, accountState) ?: return@post
                val upload = MQCloudClient.reportData(
                    context = context,
                    authToken = token,
                    snapshotId = snapshotId,
                    calculateDataHex = listOf(calculateData),
                )
                if (upload.success) {
                    lastCloudReportedPacketIndex = rec.packetIndex
                    MQRegistry.saveLastCloudReportedPacketIndex(context, sensorId, rec.packetIndex)
                    Log.i(TAG, "MQ cloud report synced: snapshot=$snapshotId packet=${rec.packetIndex}")
                } else if (upload.failure == MQBootstrapFailure.AUTH_EXPIRED) {
                    MQRegistry.clearAuthToken(context)
                    Log.w(TAG, "MQ cloud report auth expired")
                } else {
                    Log.w(TAG, "MQ cloud report failed: ${upload.message}")
                }
            } catch (t: Throwable) {
                Log.stack(TAG, "maybeUploadLiveReportAsync", t)
            }
        }
    }

    private fun buildVendorCalculateData(
        rec: MQBgRecord,
        result: MQAlgorithm.Result,
    ): String {
        fun leU16(value: Int): String {
            val normalized = value.coerceAtLeast(0) and 0xFFFF
            val hex = "%04x".format(Locale.US, normalized)
            return hex.substring(2, 4) + hex.substring(0, 2)
        }
        fun u8(value: Int): String = "%02x".format(Locale.US, value.coerceIn(0, 0xFF))

        // The vendor app appends a trailing "0" nibble after the byte-aligned
        // payload. We preserve that wire shape, but keep battery byte-aligned.
        return buildString(21) {
            append("40")
            append(leU16(rec.packetIndex))
            append(leU16(rec.sampleCurrent))
            append(u8(rec.batteryPercent))
            append(leU16(result.reviseCurrent2.toInt()))
            append(leU16(result.glucoseTimes10Mmol))
            append('0')
        }
    }

    private fun maybeEndCloudSessionAsync(context: Context, reason: String) {
        if (!MQRegistry.isCloudSyncEnabled(context)) return
        val sensorId = SerialNumber ?: return
        val snapshotId = MQRegistry.loadSnapshotId(context, sensorId)?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return
        val accountState = MQRegistry.loadAccountState(context)
        handler.post {
            try {
                val token = ensureCloudSyncToken(context, accountState) ?: return@post
                val result = MQCloudClient.endWearSession(
                    context = context,
                    authToken = token,
                    snapshotId = snapshotId,
                )
                if (result.success) {
                    if (lastAnnouncedCloudSnapshotId == snapshotId) {
                        lastAnnouncedCloudSnapshotId = ""
                    }
                    Log.i(TAG, "MQ cloud end-wear synced ($reason): snapshot=$snapshotId")
                } else if (result.failure == MQBootstrapFailure.AUTH_EXPIRED) {
                    MQRegistry.clearAuthToken(context)
                    Log.w(TAG, "MQ cloud end-wear auth expired ($reason)")
                } else {
                    Log.w(TAG, "MQ cloud end-wear failed ($reason): ${result.message}")
                }
            } catch (t: Throwable) {
                Log.stack(TAG, "maybeEndCloudSessionAsync($reason)", t)
            }
        }
    }

    private fun maybeRefreshBootstrapAsync(context: Context, reason: String) {
        val id = SerialNumber ?: return
        val now = System.currentTimeMillis()
        if (bootstrapFetchInFlight || now - lastBootstrapAttemptAtMs < MQConstants.VENDOR_BOOTSTRAP_RETRY_MS) {
            return
        }
        val qrCode = MQRegistry.loadQrContent(context, id)?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val bleId = resolveBootstrapBleId(context, id)
        if (qrCode == null && bleId == null) return
        bootstrapFetchInFlight = true
        lastBootstrapAttemptAtMs = now
        handler.post {
            try {
                val result = MQBootstrapClient.fetchBestEffort(
                    context = context,
                    bleId = bleId,
                    qrCode = qrCode,
                    authToken = MQRegistry.loadAuthToken(context),
                    credentials = MQRegistry.loadAuthCredentials(context),
                    allowContinueWearRestore = !localResetPending,
                )
                applyBootstrapFetchResult(context, id, result, reason)
            } catch (t: Throwable) {
                Log.stack(TAG, "maybeRefreshBootstrapAsync($reason)", t)
            } finally {
                bootstrapFetchInFlight = false
            }
        }
    }

    private fun calculateInitTimeMinutes(sampleMs: Long): Double {
        val startAt = sensorStartAtMs.takeIf { it > 0L } ?: return MQConstants.ALGO_DEFAULT_INIT_TIME_MINUTES
        val elapsedMs = sampleMs - startAt
        return if (elapsedMs > 0L) {
            elapsedMs / 60_000.0
        } else {
            MQConstants.ALGO_DEFAULT_INIT_TIME_MINUTES
        }
    }

    private fun synthesizeReferenceFromSensitivity(
        rec: MQBgRecord,
        initTimeMinutes: Double,
        previousProcessed: Double,
    ): Double? {
        val seed = sensitivitySeed.toDouble()
        if (!hasValidSlopeSeed(seed) || rec.packetIndex < MQConstants.ALGO_WARMUP_PACKET_THRESHOLD.toInt()) {
            return null
        }
        val seeded = MQAlgorithm.calculateResult(
            algorithmVersion = algorithmVersion,
            initTimeMinutes = initTimeMinutes,
            packetIndex = rec.packetIndex.toDouble(),
            sampleCurrent = rec.sampleCurrent.toDouble(),
            previousReviseCurrent2 = previousProcessed,
            kValue = seed,
            referenceBgTimes10Mmol = 0.0,
            bValue = 2.0,
            packages = packages.toDouble(),
            multiplier = multiplier.toDouble(),
        )
        val syntheticReference = seeded.glucoseTimes10Mmol.toDouble()
        if (syntheticReference <= 0.0) {
            return null
        }
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "Bootstrapping MQ reference from sensitivity: packet=%d current=%d sensitivity=%.2f ref=%.1f revise=%.1f",
                rec.packetIndex,
                rec.sampleCurrent,
                seed,
                syntheticReference / 10.0,
                seeded.reviseCurrent2,
            ),
        )
        return syntheticReference
    }

    private fun calculateVendorGlucose(rec: MQBgRecord, sampleMs: Long): MQAlgorithm.Result? {
        val initTimeMinutes = calculateInitTimeMinutes(sampleMs)
        val previousProcessed = lastProcessed
        val manualReference = pendingReferenceBgTimes10Mmol.takeIf { it > 0.0 && rec.packetIndex > 8 } ?: 0.0
        var reference = manualReference
        var seedK = kValue.toDouble()
        var seedB = bValue.toDouble()

        if (!hasValidSlopeSeed(seedK) && reference <= 0.0) {
            reference = synthesizeReferenceFromSensitivity(
                rec = rec,
                initTimeMinutes = initTimeMinutes,
                previousProcessed = previousProcessed,
            ) ?: return null
            seedK = 0.0
            seedB = 0.0
        } else if (!hasValidSlopeSeed(seedK) && reference > 0.0) {
            seedK = 0.0
            seedB = 0.0
        }

        val result = MQAlgorithm.calculateResult(
            algorithmVersion = algorithmVersion,
            initTimeMinutes = initTimeMinutes,
            packetIndex = rec.packetIndex.toDouble(),
            sampleCurrent = rec.sampleCurrent.toDouble(),
            previousReviseCurrent2 = previousProcessed,
            kValue = seedK,
            referenceBgTimes10Mmol = reference,
            bValue = seedB,
            packages = packages.toDouble(),
            multiplier = multiplier.toDouble(),
        )
        lastProcessed = result.reviseCurrent2
        if (hasValidSlopeSeed(result.kValue)) {
            kValue = result.kValue.toFloat()
        }
        bValue = result.bValue.toFloat()
        if (manualReference > 0.0) {
            pendingReferenceBgTimes10Mmol = 0.0
        }
        persistAlgorithmState()
        if (manualReference > 0.0) {
            Applic.app?.let { context ->
                maybeUploadCalibrationSyncAsync(
                    context = context,
                    packetIndex = rec.packetIndex,
                    sampleMs = sampleMs,
                    referenceBgTimes10Mmol = manualReference,
                    solvedK = result.kValue,
                    solvedB = result.bValue,
                )
            }
        }
        if (result.glucoseTimes10Mmol > 0 && hasValidSlopeSeed(result.kValue)) {
            clearLocalResetPending("live-calculated-k")
        }
        Log.d(
            TAG,
            String.format(
                Locale.US,
                "Calculated packet=%d initMin=%.1f current=%d prev=%.1f revise=%.1f k=%.2f b=%.2f mmol=%.1f",
                rec.packetIndex,
                initTimeMinutes,
                rec.sampleCurrent,
                previousProcessed,
                result.reviseCurrent2,
                result.kValue,
                result.bValue,
                result.glucoseMmol,
            ),
        )
        return if (result.glucoseTimes10Mmol > 0) result else null
    }

    private fun applyPendingCalibrationFromLatestSample(): Boolean {
        val sampleMs = lastRecordReceivedAtMs
        val packetIndex = lastObservedPacketIndex
        val sampleCurrent = lastRawCurrent.toInt()
        if (sampleMs <= 0L || packetIndex < 0 || sampleCurrent <= 0) {
            return false
        }
        if (System.currentTimeMillis() - sampleMs > protocolFrameTimeoutMs()) {
            return false
        }
        val rec = MQBgRecord(
            indexInPacket = 0,
            marker = MQConstants.BG_RECORD_MARKER,
            packetIndex = packetIndex,
            sampleCurrent = sampleCurrent,
            batteryPercent = lastBatteryPercent.coerceAtLeast(0),
            recordBytes = byteArrayOf(
                MQConstants.BG_RECORD_MARKER.toByte(),
                (packetIndex and 0xFF).toByte(),
                ((packetIndex shr 8) and 0xFF).toByte(),
                (sampleCurrent and 0xFF).toByte(),
                ((sampleCurrent shr 8) and 0xFF).toByte(),
                lastBatteryPercent.coerceAtLeast(0).toByte(),
            ),
        )
        val result = calculateVendorGlucose(rec, sampleMs) ?: return false
        lastPacketIndex = maxOf(lastPacketIndex, rec.packetIndex)
        persistAlgorithmState()
        if (sampleMs >= lastGlucoseAtMs) {
            lastGlucoseAtMs = sampleMs
            lastGlucoseMgdlTimes10 = result.mgdlTimes10
        }
        mirrorReadingIntoNative(sampleMs, result.mgdlTimes10 / 10)
        emitGlucose(result, sampleMs)
        armNoDataWatchdog()
        Log.i(TAG, "Applied local MQ calibration immediately from packet=${rec.packetIndex}")
        return true
    }

    private fun onBgData(frame: MQFrame) {
        beginWorkRetryCount = 0
        beginWorkFirstSeenMs = 0L
        val records = MQParser.parseBgRecords(frame)
        if (records.isEmpty()) {
            writeFrame(MagicAck.bgData00.copyOf(), "confirmBgData(empty,magic)")
            return
        }
        Applic.app?.takeIf { needsVendorBootstrap() }?.let {
            maybeRefreshBootstrapAsync(it, "bg-data")
        }
        val nowMs = System.currentTimeMillis()
        if (sensorStartAtMs == 0L) {
            sensorStartAtMs = nowMs
            sensorstartmsec = nowMs
            warmupStartedAtMs = nowMs
            Applic.app?.let {
                persistSensorStart(it, nowMs)
                persistWarmupStart(it, nowMs)
            }
        }
        ensureNativeDataptr(SerialNumber)
        Applic.app?.let { maybeEnsureCloudSessionAsync(it, "bg-data") }
        Applic.app?.let { maybeFetchCloudHistoryBackfillAsync(it, "bg-data") }
        queuePendingBgBurst(records, nowMs)
        writeFrame(MagicAck.bgData00.copyOf(), "confirmBgData(magic)")
    }

    private fun queuePendingBgBurst(records: List<MQBgRecord>, receivedAtMs: Long) {
        for (rec in records) {
            pendingBgBurstRecords[rec.packetIndex] = PendingBgRecord(
                record = rec,
                receivedAtMs = receivedAtMs,
            )
        }
        handler.removeCallbacks(bgBurstFlushRunnable)
        handler.postDelayed(bgBurstFlushRunnable, BG_BURST_FLUSH_DELAY_MS)
    }

    private fun flushPendingBgBurst(reason: String) {
        if (pendingBgBurstRecords.isEmpty()) return
        val burst = pendingBgBurstRecords.values
            .toList()
            .sortedBy { it.record.packetIndex }
        clearPendingBgBurst()
        val newest = burst.maxByOrNull { it.record.packetIndex } ?: return
        Log.i(
            TAG,
            "Flushing MQ BG burst ($reason): records=${burst.size} packet=${burst.first().record.packetIndex}..${newest.record.packetIndex}"
        )
        processBgRecords(
            records = burst,
            anchorPacketIndex = newest.record.packetIndex,
            anchorMs = newest.receivedAtMs,
        )
    }

    private fun processBgRecords(
        records: List<PendingBgRecord>,
        anchorPacketIndex: Int,
        anchorMs: Long,
    ) {
        if (records.isEmpty()) return
        val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
        val previousLastPacketIndex = lastPacketIndex
        var highestProcessedPacketIndex = lastPacketIndex
        for (pending in records) {
            val rec = pending.record
            if (previousLastPacketIndex >= 0 && rec.packetIndex <= previousLastPacketIndex) {
                Log.i(TAG, "Skipping already-seen MQ packet=${rec.packetIndex} (last=$previousLastPacketIndex)")
                continue
            }
            packetCount++
            lastObservedPacketIndex = maxOf(lastObservedPacketIndex, rec.packetIndex)
            lastRawCurrent = rec.sampleCurrent.toDouble()
            lastBatteryPercent = rec.batteryPercent

            val rb = rec.recordBytes
            val recHex = rb.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            val packetDelta = (anchorPacketIndex - rec.packetIndex).coerceAtLeast(0)
            val sampleMs = anchorMs - packetDelta * intervalMs
            lastRecordReceivedAtMs = maxOf(lastRecordReceivedAtMs, sampleMs)
            Log.i(
                TAG,
                "BG record #${rec.indexInPacket}: [$recHex]  marker=0x%02X  packet=%d  current=%d  battery=%d%%".format(
                    rec.marker,
                    rec.packetIndex,
                    rec.sampleCurrent,
                    rec.batteryPercent,
                )
            )
            if (rec.marker != MQConstants.BG_RECORD_MARKER) {
                Log.w(TAG, "Ignoring MQ BG record with unexpected marker 0x%02X".format(rec.marker))
                continue
            }
            val result = calculateVendorGlucose(rec, sampleMs)
            if (result == null) {
                Log.w(
                    TAG,
                    "Skipping glucose emit for packet=${rec.packetIndex}: current=${rec.sampleCurrent} seedK=%.2f b=%.2f pendingRef=%.1f".format(
                        kValue,
                        bValue,
                        pendingReferenceBgTimes10Mmol,
                    ),
                )
                continue
            }
            if (sampleMs >= lastGlucoseAtMs) {
                lastGlucoseAtMs = sampleMs
                lastGlucoseMgdlTimes10 = result.mgdlTimes10
            }
            highestProcessedPacketIndex = maxOf(highestProcessedPacketIndex, rec.packetIndex)
            mirrorReadingIntoNative(sampleMs, result.mgdlTimes10 / 10)
            emitGlucose(result, sampleMs)
            Applic.app?.let { maybeUploadLiveReportAsync(it, rec, result) }
        }
        if (highestProcessedPacketIndex != lastPacketIndex) {
            lastPacketIndex = highestProcessedPacketIndex
            persistAlgorithmState()
        }
        armNoDataWatchdog()
    }

    private fun emitGlucose(result: MQAlgorithm.Result, sampleMs: Long) {
        val alarm = 0L
        val rateShort = 0  // we don't compute trend rate yet
        val mgdlTimes10 = result.mgdlTimes10.toLong() and 0xFFFFFFFFL
        val res = (alarm shl 48) or ((rateShort.toLong() and 0xFFFF) shl 32) or mgdlTimes10
        try {
            handleGlucoseResult(res, sampleMs)
        } catch (t: Throwable) {
            Log.stack(TAG, "emitGlucose", t)
        }
    }

    /**
     * Write a framed packet to the RX characteristic. The official Glutec app
     * writes fast enough that the first confirm can collide with the CCCD-write
     * ack, so Android sometimes returns false on the first attempt. We pick the
     * best writeType for the characteristic's advertised properties, then retry
     * once after a short delay if the initial write is rejected.
     */
    private fun writeFrame(bytes: ByteArray, tag: String) {
        attemptWrite(bytes, tag, attempt = 1)
    }

    private fun writeFrameNow(bytes: ByteArray, tag: String): Boolean {
        val gatt = mBluetoothGatt ?: return false
        val ch = charRxWrite ?: return false
        return try {
            val props = ch.properties
            val supportsNoResp = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val supportsResp = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            ch.writeType = when {
                supportsNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                supportsResp -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            ch.value = bytes
            val ok = gatt.writeCharacteristic(ch)
            val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            if (ok) {
                Log.d(TAG, "wrote $tag [$hex] (immediate writeType=${ch.writeType})")
            } else {
                Log.w(TAG, "writeCharacteristic($tag) returned false (immediate bytes=[$hex])")
            }
            ok
        } catch (t: Throwable) {
            Log.stack(TAG, "writeFrameNow($tag)", t)
            false
        }
    }

    private fun attemptWrite(bytes: ByteArray, tag: String, attempt: Int) {
        val gatt = mBluetoothGatt ?: return
        val ch = charRxWrite ?: run {
            Log.w(TAG, "writeFrame($tag): RX characteristic not ready (attempt=$attempt)")
            return
        }
        try {
            val props = ch.properties
            val supportsNoResp = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val supportsResp = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            ch.writeType = when {
                supportsNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                supportsResp -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // try anyway
            }
            ch.value = bytes
            val ok = gatt.writeCharacteristic(ch)
            val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            if (!ok) {
                Log.w(
                    TAG,
                    "writeCharacteristic($tag) returned false " +
                        "(attempt=$attempt props=0x%02X writeType=%d bytes=[%s])"
                            .format(props, ch.writeType, hex),
                )
                if (attempt < 3) {
                    val delay = 150L * attempt
                    handler.postDelayed({ attemptWrite(bytes, tag, attempt + 1) }, delay)
                }
            } else {
                Log.d(TAG, "wrote $tag [$hex] (attempt=$attempt writeType=${ch.writeType})")
            }
        } catch (t: Throwable) {
            Log.stack(TAG, "writeFrame($tag)", t)
        }
    }

    override fun close() {
        flushPendingBgBurst("close")
        phase = Phase.IDLE
        clearLinkWatchdogs()
        try { handlerThread.quitSafely() } catch (_: Throwable) {}
        super.close()
    }
}
