package tk.glucodata.drivers.sibionics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NightscoutUploadWake
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorCurrentSnapshot
import tk.glucodata.drivers.ManagedSensorMaintenanceDriver
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorUiFamily
import tk.glucodata.drivers.ManagedSensorUiSnapshot
import tk.glucodata.drivers.ManagedSensorViewModeStore

@SuppressLint("MissingPermission")
class SibionicsBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SibionicsConstants.SENSOR_GEN),
    ManagedBluetoothSensorDriver,
    ManagedSensorMaintenanceDriver {

    private enum class Phase {
        IDLE,
        CONNECTING,
        DISCOVERING,
        ENABLING_NOTIFY,
        PROBING_CHINESE,
        AUTHENTICATING,
        ACTIVATING,
        SYNCING_TIME,
        REQUESTING_DATA,
        STREAMING,
        ERROR,
    }

    private enum class BatteryReadPurpose {
        SETUP,
        STABILIZATION,
        PERIODIC,
        MANUAL,
    }

    private data class EmittedReading(
        val sampleMs: Long,
        val glucoseMgdl: Float,
        val rawMgdl: Float,
        val temperatureC: Float,
        val impedance: Float,
        val index: Int,
        val live: Boolean,
    )

    private data class AlgorithmRebuildResult(
        val context: SibionicsAlgorithmContext,
        val readings: List<EmittedReading>,
        val sourceSamples: List<SibionicsSourceSample>,
    )

    companion object {
        private const val RECONNECT_DELAY_MS = 8_000L
        private const val CONNECT_CALLBACK_TIMEOUT_MS = 20_000L
        private const val ADVERTISEMENT_RECOVERY_TIMEOUT_MS = 25_000L
        private const val SETUP_STAGE_TIMEOUT_MS = 15_000L
        private const val CHINESE_PROBE_TIMEOUT_MS = 5_000L
        private const val CHINESE_DATA_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val BATTERY_READ_TIMEOUT_MS = 3_000L
        private const val BATTERY_STABILIZATION_INTERVAL_MS = 60_000L
        private const val BATTERY_PERIODIC_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val STREAMING_TIMEOUT_MS = 180_000L
        private const val CHINESE_POLL_INTERVAL_MS = 60_000L
        private const val CURRENT_FRESH_MS = 180_000L
        private const val MAX_FUTURE_DRIFT_MS = 10L * 60L * 1000L
        private const val MIN_REASONABLE_TIME_MS = 946_684_800_000L
        private const val ALGORITHM_REBUILD_DEBOUNCE_MS = 600L
        private const val LOCAL_REBUILD_FORMAT_VERSION = 6
        private const val POST_RESET_DISCARD_TIMEOUT_MS = 15_000L
        private const val RESET_COMFORT_RECHECK_MS = 15L * 60L * 1000L
        private const val NATIVE_STREAM_CAPACITY_MINUTES = 46 * 24 * 60

        private fun sampleJournalFile(context: Context, sensorId: String): File {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(sensorId.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            return File(context.filesDir, "sibionics-managed/$digest/source-samples-v1.bin")
        }
    }

    @Volatile private var phase = Phase.IDLE
    private val handlerThread = HandlerThread("Sibionics-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val advertisementRecovery = SibionicsAdvertisementRecovery(
        context = Applic.getContext(),
        handler = handler,
        onFinished = ::finishAdvertisementRecovery,
    )
    private val notificationDispatcher = SibionicsNotificationDispatcher(
        post = { task ->
            handler.post(task)
            Unit
        },
        consume = ::handleIncoming,
    )
    private val rebuildExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Sibionics-rebuild-$serial").apply { isDaemon = true }
    }
    private val algorithmLock = Any()

    private var service: BluetoothGattService? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var batteryLevelChar: BluetoothGattCharacteristic? = null
    @Volatile private var retiredGatt: BluetoothGatt? = null
    @Volatile private var batteryReadPurpose: BatteryReadPurpose? = null
    @Volatile private var lastBatteryPercent: Int = -1
    @Volatile private var batteryStabilizationComplete: Boolean = false
    private var batterySessionAttemptCount: Int = 0
    private val batterySessionSamples = ArrayList<Int>(SibionicsBattery.MAX_STABILIZATION_ATTEMPTS)
    private var batteryStabilizationScheduled: Boolean = false
    private var batteryPeriodicScheduled: Boolean = false
    @Volatile private var manualBatteryRefreshQueued: Boolean = false

    @Volatile private var record: SibionicsRegistry.SensorRecord? = null
    @Volatile private var variant: SibionicsConstants.Variant = SibionicsConstants.Variant.EU
    @Volatile private var protocolMode: SibionicsConstants.ProtocolMode = SibionicsConstants.ProtocolMode.UNKNOWN
    @Volatile private var shortCode: String = variant.fallbackShortCode
    @Volatile private var automaticSensitivity: Float = 1.27f
    @Volatile private var sensitivityOverride: Float? = null
    @Volatile private var sensitivity: Float = 1.27f
    @Volatile private var sessionKey: ByteArray? = null
    @Volatile private var keyGroupIndex: Int = 0
    @Volatile private var authCandidateVariant: SibionicsConstants.Variant? = null
    @Volatile private var pendingResetCommand: Boolean = false
    @Volatile private var discardNotificationsUntilResetDisconnect: Boolean = false
    @Volatile private var loggedDiscardedPostResetNotification: Boolean = false
    @Volatile private var connectionPrioritySettled: Boolean = false
    @Volatile private var uiPaused: Boolean = false
    @Volatile private var pendingMatchedBleName: String = ""
    @Volatile private var autoResetDays: Int = 300
    @Volatile private var autoResetScheduled: Boolean = false
    @Volatile private var algorithmSelection: SibionicsAlgorithmSelection = SibionicsAlgorithmSelection.STOCK
    @Volatile private var historyReceivedCount: Int = 0
    @Volatile private var historyTotalCount: Int = 0
    private val historySeenIndices = HashSet<Int>()

    @Volatile private var lastIndex: Int = 0
    @Volatile private var lastIndexDirty: Boolean = false
    @Volatile private var algorithmStateDirty: Boolean = false
    @Volatile private var algorithmRehydrating: Boolean = false
    @Volatile private var rehydrationExpectedIndex: Int = -1
    @Volatile private var rehydrationTargetIndex: Int = 0
    @Volatile private var lastLiveIndexSeen: Int = -1
    @Volatile private var lastLiveAlgorithmIndexSeen: Int = -1
    @Volatile private var startTimeMs: Long = 0L
    @Volatile private var latestReadingTimeMs: Long = 0L
    @Volatile private var latestGlucoseMgdl: Float = Float.NaN
    @Volatile private var latestRawMgdl: Float = Float.NaN
    @Volatile private var latestRateMgdlPerMin: Float = Float.NaN
    @Volatile private var latestTemperatureC: Float = Float.NaN
    @Volatile private var latestImpedance: Float = Float.NaN
    @Volatile private var sampleJournal: SibionicsSampleJournal? = null
    @Volatile private var rebuildGeneration: Long = 0L
    @Volatile private var calibrationRevision: Long = 0L
    @Volatile private var rebuildAfterNextSourceSample: Boolean = false
    @Volatile private var preserveResumeStateOnRemoval: Boolean = false

    @Volatile private var algorithm = SibionicsAlgorithmContext(serial)
    private val authTimeoutRunnable = Runnable {
        if (phase == Phase.AUTHENTICATING) tryNextKeyGroup("auth timeout")
    }
    private val connectCallbackTimeoutRunnable = Runnable {
        if (SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = phase == Phase.CONNECTING,
                isStopped = stop,
                isPaused = uiPaused,
            )
        ) {
            Log.w(SibionicsConstants.TAG, "connectGatt callback timeout serial=$SerialNumber")
            prepareForReconnect()
            setStatus(connectingStatus())
            if (!beginAdvertisementRecovery("connectGatt callback timeout")) {
                scheduleReconnect("connectGatt callback timeout")
            }
        }
    }
    private val setupStageTimeoutRunnable = Runnable {
        if (SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = phase == Phase.DISCOVERING || phase == Phase.ENABLING_NOTIFY,
                isStopped = stop,
                isPaused = uiPaused,
            )
        ) {
            Log.w(SibionicsConstants.TAG, "GATT setup timeout phase=$phase serial=$SerialNumber")
            prepareForReconnect()
            setStatus(connectingStatus())
            scheduleReconnect("GATT setup timeout", delayMs = 500L)
        }
    }
    private val batteryReadTimeoutRunnable = Runnable {
        val purpose = batteryReadPurpose
        if (purpose != null) {
            batteryReadPurpose = null
            Log.w(SibionicsConstants.TAG, "standard battery read timeout serial=$SerialNumber")
            finishBatteryRead(purpose, percent = null)
        }
    }
    private val batteryStabilizationRunnable = Runnable {
        batteryStabilizationScheduled = false
        if (!stop && phase == Phase.STREAMING && !batteryStabilizationComplete) {
            requestBatteryRead(BatteryReadPurpose.STABILIZATION)
        }
    }
    private val batteryPeriodicRunnable = Runnable {
        batteryPeriodicScheduled = false
        if (!stop && phase == Phase.STREAMING) {
            requestBatteryRead(BatteryReadPurpose.PERIODIC)
        }
    }
    private val rebuildLaunchRunnable = Runnable {
        val generation = rebuildGeneration
        if (!rebuildExecutor.isShutdown) {
            rebuildExecutor.execute { rebuildAlgorithmLocally(generation) }
        }
    }
    private val resetMaintenanceRunnable = Runnable { maybeScheduleAutoReset() }

    @Volatile private var viewModeValue: Int = ManagedSensorViewModeStore.read(Applic.app, serial, 0)

    override var viewMode: Int
        get() = viewModeValue
        set(value) {
            val normalized = ManagedSensorViewModeStore.sanitize(value)
            viewModeValue = normalized
            ManagedSensorViewModeStore.write(Applic.app, SerialNumber, normalized)
        }

    fun restoreFromPersistence(context: Context) {
        sampleJournal = SibionicsSampleJournal(sampleJournalFile(context, SerialNumber))
        calibrationRevision = tk.glucodata.CalibrationAccess.getRevision()
        val restored = SibionicsRegistry.findRecord(context, SerialNumber)
        record = restored
        restored?.let {
            variant = it.variant
            mActiveDeviceAddress = it.address.ifBlank { null }
        }
        variant = SibionicsRegistry.loadVariant(context, SerialNumber)
        protocolMode = SibionicsConstants.initialProtocolMode(
            variant,
            SibionicsRegistry.loadProtocolMode(context, SerialNumber),
        )
        shortCode = SibionicsRegistry.loadShortCode(context, SerialNumber)
        automaticSensitivity = SibionicsSensitivity.sensitivityFor(shortCode, variant)
        sensitivityOverride = SibionicsRegistry.loadAlgorithmSensitivityOverride(context, SerialNumber)
        sensitivity = sensitivityOverride ?: automaticSensitivity
        val persistedAutoResetDays = SibionicsRegistry.loadAutoResetDays(context, SerialNumber)
        autoResetDays = SibionicsResetPolicy.normalizedDays(
            variant = variant,
            persistedDays = persistedAutoResetDays,
            hasPersistedSetting = SibionicsRegistry.hasAutoResetSetting(context, SerialNumber),
        )
        if (autoResetDays != persistedAutoResetDays ||
            !SibionicsRegistry.hasAutoResetSetting(context, SerialNumber)
        ) {
            SibionicsRegistry.saveAutoResetDays(context, SerialNumber, autoResetDays)
        }
        pendingResetCommand = SibionicsRegistry.isResetRequested(context, SerialNumber)
        algorithmSelection = SibionicsRegistry.loadAlgorithmSelection(context, SerialNumber)
        SibionicsRegistry.loadIntegratedCalibrationBaseline(context, SerialNumber)
            ?.takeIf { it.unit == Applic.unit }
            ?.let { baseline ->
                tk.glucodata.CalibrationAccess.seedIntegratedCalibrationBaseline(
                    values = baseline.values,
                    timestamps = baseline.timestamps,
                    isRawMode = false,
                    sensorIdOverride = SerialNumber,
                )
            }
        synchronized(algorithmLock) {
            algorithm.configure(shortCode, sensitivity, variant, algorithmSelection)
        }
        lastIndex = SibionicsRegistry.loadLastIndex(context, SerialNumber)
        val algorithmState = SibionicsRegistry.loadAlgorithmState(context, SerialNumber)
        val restoredAlgorithm = algorithmState != null && synchronized(algorithmLock) {
            algorithm.restore(algorithmState) && algorithm.hasExactContinuation(lastIndex)
        }
        if (restoredAlgorithm) {
            lastLiveAlgorithmIndexSeen = lastIndex - 1
            Log.i(SibionicsConstants.TAG, "restored exact algorithm state idx=$lastIndex")
        } else if (lastIndex > 1) {
            beginAlgorithmRehydration(lastIndex, "no usable or index-aligned saved state")
        }
        startTimeMs = SibionicsRegistry.loadStartTimeMs(context, SerialNumber)
        scheduleResetMaintenanceCheck()
        val (time, glucose, raw) = SibionicsRegistry.loadLastReading(context, SerialNumber)
        latestReadingTimeMs = time
        latestGlucoseMgdl = glucose
        latestRawMgdl = raw
        sessionKey = SibionicsProtocol.deriveSessionKey(variant)
        rebuildAfterNextSourceSample = algorithmSelection != SibionicsAlgorithmSelection.STOCK
        constatstatusstr = disconnectedStatus()
    }

    override fun getService(): UUID = SibionicsConstants.SERVICE_FF30

    override fun canConnectWithoutDataptr(): Boolean = true

    override fun hasNativeSensorBacking(): Boolean = false

    override fun shouldUseNativeHistorySync(): Boolean = false

    override fun managesLiveRoomStorage(): Boolean = true

    override fun shouldUseSharedCurrentSensorHandoffOnTerminate(): Boolean = false

    override fun isManagedOutsideNativeActiveSet(): Boolean = true

    override fun supportsDisplayModes(): Boolean = true

    override fun supportsManualCalibration(): Boolean = false

    override fun integratesUserCalibration(isRawMode: Boolean): Boolean =
        !isRawMode && algorithmSelection.calibrationEnabled

    override fun shouldShowSearchingStatusWhenIdle(): Boolean = true

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        SibionicsConstants.matchesId(SerialNumber, sensorId) ||
            record?.matchesId(sensorId) == true ||
            SibionicsRegistry.findRecord(Applic.app, sensorId)?.matchesId(SerialNumber) == true

    override fun mygetDeviceName(): String =
        SibionicsConstants.stripManagedPrefix(
            record?.displayName?.takeIf { it.isNotBlank() }
                ?: SibionicsRegistry.findRecord(Applic.app, SerialNumber)?.displayName?.takeIf { it.isNotBlank() }
                ?: SerialNumber
        )

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        uiPaused = false
        if (advertisementRecovery.isActive) {
            Log.d(SibionicsConstants.TAG, "connect coalesced during advertisement recovery serial=$SerialNumber")
            return true
        }
        // Coalesce only a connection which owns a real GATT. During first setup the
        // scanner calls us before it has found a BluetoothDevice; that attempt must
        // not suppress the real connection after the scan result is bound.
        if (phase != Phase.IDLE && phase != Phase.ERROR && mBluetoothGatt != null) {
            Log.d(SibionicsConstants.TAG, "connect coalesced phase=$phase serial=$SerialNumber")
            return true
        }
        if (mActiveBluetoothDevice == null) {
            hydrateBluetoothDeviceFromAddress()
        }
        if (mActiveBluetoothDevice == null) {
            phase = Phase.IDLE
            return false
        }
        phase = Phase.CONNECTING
        setStatus(connectingStatus())
        val scheduled = super.connectDevice(delayMillis)
        if (scheduled) {
            armConnectCallbackTimeout(delayMillis)
        } else if (phase == Phase.CONNECTING) {
            handler.removeCallbacks(connectCallbackTimeoutRunnable)
            phase = Phase.IDLE
        }
        return scheduled
    }

    @Synchronized
    override fun softDisconnect() {
        notificationDispatcher.invalidateSession()
        uiPaused = true
        stop = true
        advertisementRecovery.stop()
        batteryLevelChar = null
        resetBatterySession()
        handler.removeCallbacksAndMessages(null)
        runCatching { mBluetoothGatt?.disconnect() }
        runCatching { mBluetoothGatt?.close() }
        mBluetoothGatt = null
        mActiveBluetoothDevice = null
        phase = Phase.IDLE
        setStatus(disconnectedStatus())
    }

    @Synchronized
    override fun softReconnect() {
        uiPaused = false
        stop = false
        advertisementRecovery.stop()
        phase = Phase.IDLE
        connectDevice(0)
    }

    override fun onBluetoothAdapterUnavailable() {
        advertisementRecovery.stop()
        handler.removeCallbacks(reconnectRunnable)
        prepareForReconnect()
        constatstatusstr = Applic.getContext().getString(tk.glucodata.R.string.status_bluetooth_off)
        UiRefreshBus.requestStatusRefresh()
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        Log.i(SibionicsConstants.TAG, "terminateManagedSensor serial=$SerialNumber wipeData=$wipeData")
        preserveResumeStateOnRemoval = !wipeData
        if (preserveResumeStateOnRemoval) flushAlgorithmCheckpointIfDirty()
        softDisconnect()
        rebuildGeneration++
        if (wipeData) sampleJournal?.clear()
        Applic.app?.let {
            SibionicsRegistry.removeSensor(
                context = it,
                sensorId = SerialNumber,
                preserveResumeState = preserveResumeStateOnRemoval,
            )
        }
        // The owning removal flow calls SensorBluetooth.sensorEnded() after
        // persistence is gone. Reconciling here is re-entrant: it can free this
        // callback while terminateManagedSensor() is still executing.
        UiRefreshBus.requestStatusRefresh()
    }

    override fun removeManagedPersistence(context: Context) {
        if (!preserveResumeStateOnRemoval) {
            SibionicsSampleJournal(sampleJournalFile(context, SerialNumber)).clear()
        }
        SibionicsRegistry.removeSensor(
            context = context,
            sensorId = SerialNumber,
            preserveResumeState = preserveResumeStateOnRemoval,
        )
    }

    override fun close() {
        notificationDispatcher.invalidateSession()
        advertisementRecovery.stop()
        batteryLevelChar = null
        resetBatterySession()
        handler.removeCallbacksAndMessages(null)
        rebuildGeneration++
        rebuildExecutor.shutdownNow()
        runCatching { handlerThread.quitSafely() }
        super.close()
    }

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val scanned = SibionicsConstants.normalizeBleAddress(address)
        val known = knownBleAddress()
        if (known != null && scanned != null && known.equals(scanned, ignoreCase = true)) return true

        val name = SibionicsConstants.normalizeBleName(deviceName)
        if (name.isBlank()) return false
        val rec = record ?: SibionicsRegistry.findRecord(Applic.app, SerialNumber)
        val ctx = Applic.app
        if (rec != null && ctx != null && SibionicsRegistry.canClaimUnboundSibionics2Device(
                record = rec,
                records = SibionicsRegistry.persistedRecords(ctx),
                deviceName = name,
                address = scanned,
            )
        ) {
            Log.i(SibionicsConstants.TAG, "binding unbound Sibionics 2 ${rec.sensorId} to $name/$scanned")
            pendingMatchedBleName = name
            return true
        }
        val bleAlias = SibionicsConstants.normalizeBleName(rec?.bleName)
        if (matchesBleNameAlias(name, bleAlias)) return true
        val embeddedBle = SibionicsRegistry.deriveBleName(rec?.displayName)
            ?: SibionicsRegistry.deriveBleName(SibionicsConstants.stripManagedPrefix(SerialNumber))
        if (matchesBleNameAlias(name, embeddedBle)) return true
        val display = SibionicsConstants.normalizeBleName(rec?.displayName)
        val stripped = SibionicsConstants.normalizeBleName(SibionicsConstants.stripManagedPrefix(SerialNumber))
        if (matchesQrShortName(name, display) || matchesQrShortName(name, stripped)) return true
        return display.isNotBlank() && (
            name.equals(display, ignoreCase = true) ||
                display.contains(name, ignoreCase = true) ||
                name.endsWith(display.takeLast(6), ignoreCase = true) ||
                display.endsWith(name.takeLast(6), ignoreCase = true)
            ) ||
            stripped.isNotBlank() && (
                name.equals(stripped, ignoreCase = true) ||
                    stripped.contains(name, ignoreCase = true) ||
                    name.endsWith(stripped.takeLast(6), ignoreCase = true) ||
                    stripped.endsWith(name.takeLast(6), ignoreCase = true)
                )
    }

    private fun matchesBleNameAlias(advertisedName: String, alias: String?): Boolean {
        val normalizedAlias = SibionicsConstants.normalizeBleName(alias)
        if (advertisedName.length < 6 || normalizedAlias.length < 6) return false
        return advertisedName.equals(normalizedAlias, ignoreCase = true) ||
            advertisedName.endsWith(normalizedAlias.takeLast(6), ignoreCase = true) ||
            normalizedAlias.endsWith(advertisedName.takeLast(6), ignoreCase = true)
    }

    private fun matchesQrShortName(advertisedName: String, qrShortName: String?): Boolean {
        val normalizedQr = SibionicsConstants.normalizeBleName(qrShortName)
        if (advertisedName.length < 8 || normalizedQr.length < 8) return false
        // Native QR identity uses the trailing 11-char SI name; some BLE local names embed
        // that SI name's first four chars as their suffix (e.g. QR 46HU804EBJ4 ↔ ...46HU).
        return advertisedName.endsWith(normalizedQr.take(4), ignoreCase = true)
    }

    override fun setDeviceAddress(address: String?) {
        super.setDeviceAddress(address)
        val normalized = SibionicsConstants.normalizeBleAddress(address) ?: return
        val ctx = Applic.app ?: return
        val rec = record ?: SibionicsRegistry.findRecord(ctx, SerialNumber)
        if (rec != null) {
            record = SibionicsRegistry.ensureSensorRecord(
                context = ctx,
                rawInput = rec.displayName,
                address = normalized,
                displayName = rec.displayName,
                variant = variant,
                shortCodeOverride = shortCode,
                bleNameOverride = pendingMatchedBleName.takeIf { it.isNotBlank() } ?: rec.bleName,
            )
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        // A late callback from a retired GATT must not invalidate the active connection's
        // notification queue. The handler repeats this ownership check before mutating state.
        if (isCurrentGatt(gatt)) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> notificationDispatcher.beginSession()
                BluetoothProfile.STATE_DISCONNECTED -> notificationDispatcher.invalidateSession()
            }
        }
        handler.post { handleConnectionStateChange(gatt, status, newState) }
    }

    private fun handleConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (stop) return
        if (gatt === retiredGatt) {
            retiredGatt = null
            Log.d(SibionicsConstants.TAG, "ignore retired GATT callback state=$newState serial=$SerialNumber")
            runCatching { gatt.close() }
            return
        }
        if (mBluetoothGatt != null && gatt !== mBluetoothGatt) {
            Log.d(SibionicsConstants.TAG, "ignore stale GATT callback state=$newState serial=$SerialNumber")
            runCatching { gatt.close() }
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                advertisementRecovery.stop()
                handler.removeCallbacks(connectCallbackTimeoutRunnable)
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                keyGroupIndex = 0
                authCandidateVariant = null
                connectionPrioritySettled = false
                gatt.device?.address?.let { setDeviceAddress(it) }
                connectTime = System.currentTimeMillis()
                phase = Phase.DISCOVERING
                armSetupStageTimeout()
                setStatus(connectingStatus())
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                handler.postDelayed({ runCatching { gatt.discoverServices() } }, 250L)
                UiRefreshBus.requestStatusRefresh()
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                val failedDuringConnect = phase == Phase.CONNECTING
                handler.removeCallbacks(connectCallbackTimeoutRunnable)
                handler.removeCallbacks(setupStageTimeoutRunnable)
                Log.i(SibionicsConstants.TAG, "disconnected status=$status serial=$SerialNumber")
                finishPostResetDisconnectGuard()
                service = null
                notifyChar = null
                writeChar = null
                batteryLevelChar = null
                resetBatterySession()
                phase = Phase.IDLE
                runCatching { gatt.close() }
                mBluetoothGatt = null
                mActiveBluetoothDevice = null
                setStatus(disconnectedStatus())
                handler.removeCallbacks(chinesePollRunnable)
                handler.removeCallbacks(streamingTimeoutRunnable)
                handler.removeCallbacks(handshakeTimeoutRunnable)
                handler.removeCallbacks(authTimeoutRunnable)
                handler.removeCallbacks(chineseProbeTimeoutRunnable)
                handler.removeCallbacks(chineseDataTimeoutRunnable)
                if (!stop && !uiPaused) {
                    if (!beginAdvertisementRecovery(
                            reason = "connect failed status=$status",
                            failedDuringConnect = failedDuringConnect,
                        )
                    ) {
                        scheduleReconnect("disconnect status=$status")
                    }
                }
                UiRefreshBus.requestStatusRefresh()
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (!isCurrentGattCallback(gatt, "services discovered")) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            handler.removeCallbacks(setupStageTimeoutRunnable)
            setStatus("Discovery failed: $status")
            scheduleReconnect("discover failed")
            return
        }
        service = gatt.getService(SibionicsConstants.SERVICE_FF30)
        val svc = service
        if (svc == null) {
            handler.removeCallbacks(setupStageTimeoutRunnable)
            setStatus("Sibionics FF30 service missing")
            scheduleReconnect("missing service")
            return
        }
        notifyChar = svc.getCharacteristic(SibionicsConstants.CHAR_NOTIFY_FF31)
        writeChar = svc.getCharacteristic(SibionicsConstants.CHAR_WRITE_FF32)
        resetBatterySession()
        batteryLevelChar = gatt.getService(SibionicsBattery.SERVICE)
            ?.getCharacteristic(SibionicsBattery.LEVEL_CHARACTERISTIC)
        Log.d(
            SibionicsConstants.TAG,
            "standard battery characteristic available=${batteryLevelChar != null} serial=$SerialNumber",
        )
        if (notifyChar == null || writeChar == null) {
            handler.removeCallbacks(setupStageTimeoutRunnable)
            setStatus("Sibionics FF31/FF32 missing")
            scheduleReconnect("missing chars")
            return
        }
        phase = Phase.ENABLING_NOTIFY
        armSetupStageTimeout()
        enableNotify(gatt, notifyChar!!)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (!isCurrentGattCallback(gatt, "descriptor write")) return
        if (descriptor.characteristic?.uuid == SibionicsConstants.CHAR_NOTIFY_FF31) {
            handler.removeCallbacks(setupStageTimeoutRunnable)
            if (pendingResetCommand) {
                pendingResetCommand = false
                if (!writeResetCommand("pending")) {
                    pendingResetCommand = true
                    scheduleReconnect("pending reset write failed")
                }
                return
            }
            requestBatteryRead(BatteryReadPurpose.SETUP)
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        handleCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        handleCharacteristicRead(gatt, characteristic, value, status)
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(SibionicsConstants.TAG, "write ${characteristic.uuid} failed status=$status")
        }
    }

    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!isCurrentGattCallback(gatt, "notification")) return
        if (characteristic.uuid == SibionicsConstants.CHAR_NOTIFY_FF31) {
            notificationDispatcher.dispatch(characteristic.value ?: ByteArray(0))
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (!isCurrentGattCallback(gatt, "notification")) return
        if (characteristic.uuid == SibionicsConstants.CHAR_NOTIFY_FF31) {
            notificationDispatcher.dispatch(value)
        }
    }

    private fun isCurrentGattCallback(gatt: BluetoothGatt, callback: String): Boolean {
        if (!isCurrentGatt(gatt)) {
            Log.d(SibionicsConstants.TAG, "ignore stale GATT $callback serial=$SerialNumber")
            return false
        }
        return true
    }

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean {
        val activeGatt = mBluetoothGatt
        return gatt !== retiredGatt && (activeGatt == null || gatt === activeGatt)
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (characteristic.uuid != SibionicsBattery.LEVEL_CHARACTERISTIC) return
        if (!isCurrentGattCallback(gatt, "battery read")) return
        handler.post {
            if (!isCurrentGatt(gatt)) return@post
            val purpose = batteryReadPurpose ?: return@post
            batteryReadPurpose = null
            handler.removeCallbacks(batteryReadTimeoutRunnable)
            val percent = if (status == BluetoothGatt.GATT_SUCCESS) {
                SibionicsBattery.parsePercent(value)
            } else {
                null
            }
            if (percent != null) {
                lastBatteryPercent = percent
                Log.i(SibionicsConstants.TAG, "standard battery level=$percent% serial=$SerialNumber")
                UiRefreshBus.requestStatusRefresh()
            } else {
                Log.w(
                    SibionicsConstants.TAG,
                    "standard battery read failed status=$status bytes=${value.size} serial=$SerialNumber",
                )
            }
            finishBatteryRead(purpose, percent)
        }
    }

    @Suppress("DEPRECATION")
    private fun requestBatteryRead(purpose: BatteryReadPurpose): Boolean {
        val gatt = mBluetoothGatt ?: return false
        val characteristic = batteryLevelChar
        if (characteristic == null) {
            if (purpose == BatteryReadPurpose.SETUP) startProtocolProbe()
            return false
        }
        if (batteryReadPurpose != null) return false
        if (purpose != BatteryReadPurpose.SETUP && phase != Phase.STREAMING) return false
        batteryReadPurpose = purpose
        val started = runCatching { gatt.readCharacteristic(characteristic) }
            .onFailure { Log.stack(SibionicsConstants.TAG, "read standard battery level", it) }
            .getOrDefault(false)
        if (!started) {
            batteryReadPurpose = null
            finishBatteryRead(purpose, percent = null)
            return false
        }
        handler.removeCallbacks(batteryReadTimeoutRunnable)
        handler.postDelayed(batteryReadTimeoutRunnable, BATTERY_READ_TIMEOUT_MS)
        return true
    }

    private fun finishBatteryRead(purpose: BatteryReadPurpose, percent: Int?) {
        if (purpose == BatteryReadPurpose.SETUP || purpose == BatteryReadPurpose.STABILIZATION) {
            batterySessionAttemptCount++
            if (percent != null) batterySessionSamples.add(percent)
            val decision = SibionicsBattery.stabilizationDecision(
                batterySessionAttemptCount,
                batterySessionSamples,
            )
            Log.i(
                SibionicsConstants.TAG,
                "battery stabilization=$decision attempts=$batterySessionAttemptCount " +
                    "samples=${batterySessionSamples.joinToString()} serial=$SerialNumber",
            )
            when (decision) {
                SibionicsBattery.StabilizationDecision.CONTINUE -> {
                    if (phase == Phase.STREAMING) scheduleBatteryStabilization()
                }
                SibionicsBattery.StabilizationDecision.STABLE,
                SibionicsBattery.StabilizationDecision.EXHAUSTED -> {
                    batteryStabilizationComplete = true
                    schedulePeriodicBatteryRefresh(resetDelay = true)
                }
            }
        } else {
            schedulePeriodicBatteryRefresh(resetDelay = true)
        }
        if (purpose == BatteryReadPurpose.SETUP) startProtocolProbe()
    }

    private fun scheduleBatteryRefreshAfterStreaming() {
        if (batteryLevelChar == null || batteryReadPurpose != null) return
        if (batteryStabilizationComplete) {
            schedulePeriodicBatteryRefresh()
        } else {
            scheduleBatteryStabilization()
        }
    }

    private fun scheduleBatteryStabilization() {
        if (batteryStabilizationScheduled) return
        batteryStabilizationScheduled = true
        handler.postDelayed(batteryStabilizationRunnable, BATTERY_STABILIZATION_INTERVAL_MS)
    }

    private fun schedulePeriodicBatteryRefresh(resetDelay: Boolean = false) {
        if (batteryLevelChar == null) return
        if (batteryPeriodicScheduled && !resetDelay) return
        handler.removeCallbacks(batteryPeriodicRunnable)
        batteryPeriodicScheduled = true
        handler.postDelayed(batteryPeriodicRunnable, BATTERY_PERIODIC_INTERVAL_MS)
    }

    private fun resetBatterySession() {
        batteryReadPurpose = null
        batteryStabilizationComplete = false
        batterySessionAttemptCount = 0
        batterySessionSamples.clear()
        batteryStabilizationScheduled = false
        batteryPeriodicScheduled = false
        manualBatteryRefreshQueued = false
        handler.removeCallbacks(batteryReadTimeoutRunnable)
        handler.removeCallbacks(batteryStabilizationRunnable)
        handler.removeCallbacks(batteryPeriodicRunnable)
    }

    private fun startProtocolProbe() {
        handler.removeCallbacks(setupStageTimeoutRunnable)
        when (protocolMode) {
            SibionicsConstants.ProtocolMode.CHINESE -> {
                sendChineseDataRequest(probing = false)
                scheduleChineseDataTimeout()
            }
            SibionicsConstants.ProtocolMode.V120 -> sendAuthPacket()
            SibionicsConstants.ProtocolMode.UNKNOWN -> {
                sendChineseDataRequest(probing = true)
                scheduleChineseProbeTimeout()
            }
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (discardNotificationsUntilResetDisconnect) {
            if (!loggedDiscardedPostResetNotification) {
                loggedDiscardedPostResetNotification = true
                Log.i(SibionicsConstants.TAG, "discarding buffered pre-reset notifications until disconnect")
            }
            return
        }
        if (bytes.size >= 2 && bytes[0] == 0xAA.toByte() && bytes[1] == 0x55.toByte()) {
            handleChinese(SibionicsProtocol.parseChinese(bytes))
            return
        }
        if (protocolMode == SibionicsConstants.ProtocolMode.UNKNOWN || phase == Phase.PROBING_CHINESE) {
            val chinese = SibionicsProtocol.parseChinese(bytes)
            if (chinese is SibionicsProtocol.ParseResult.V120AuthRequired) {
                switchToV120("auth marker")
                return
            }
        }
        handleV120(SibionicsProtocol.parseV120(bytes))
    }

    private fun handleChinese(result: SibionicsProtocol.ParseResult) {
        when (result) {
            is SibionicsProtocol.ParseResult.ChineseEcho -> {
                if (protocolMode == SibionicsConstants.ProtocolMode.UNKNOWN) {
                    protocolMode = SibionicsConstants.ProtocolMode.CHINESE
                    Applic.app?.let { SibionicsRegistry.saveProtocolMode(it, SerialNumber, protocolMode) }
                }
                confirmChineseVariant()
                handler.removeCallbacks(chineseProbeTimeoutRunnable)
                handler.removeCallbacks(chineseDataTimeoutRunnable)
                if (phase != Phase.STREAMING) {
                    phase = Phase.REQUESTING_DATA
                    setStatus(waitingForDataStatus())
                    scheduleChineseDataTimeout()
                }
            }

            is SibionicsProtocol.ParseResult.ChineseData -> {
                if (protocolMode != SibionicsConstants.ProtocolMode.CHINESE) {
                    protocolMode = SibionicsConstants.ProtocolMode.CHINESE
                    Applic.app?.let { SibionicsRegistry.saveProtocolMode(it, SerialNumber, protocolMode) }
                }
                confirmChineseVariant()
                handler.removeCallbacks(chineseProbeTimeoutRunnable)
                handler.removeCallbacks(chineseDataTimeoutRunnable)
                phase = Phase.STREAMING
                updateChineseHistoryProgress(result.entries)
                processChineseEntries(result.entries)
                scheduleChinesePoll()
                scheduleStreamingTimeout()
            }

            is SibionicsProtocol.ParseResult.V120AuthRequired -> switchToV120("chinese parser requested V120")
            is SibionicsProtocol.ParseResult.ChecksumError -> {
                Log.w(SibionicsConstants.TAG, "Chinese checksum error ${SibionicsProtocol.toHex(result.plaintext)}")
            }
            is SibionicsProtocol.ParseResult.Unknown -> {
                Log.w(SibionicsConstants.TAG, "Chinese unknown ${SibionicsProtocol.toHex(result.bytes)}")
            }
            else -> Unit
        }
    }

    private fun confirmChineseVariant() {
        if (variant == SibionicsConstants.Variant.CHINESE) return
        variant = SibionicsConstants.Variant.CHINESE
        Applic.app?.let { context ->
            record = SibionicsRegistry.confirmAuthenticatedVariant(
                context,
                SerialNumber,
                SibionicsConstants.Variant.CHINESE,
            ) ?: record
        }
        synchronized(algorithmLock) {
            algorithm.configure(shortCode, sensitivity, variant, algorithmSelection)
        }
        Log.i(SibionicsConstants.TAG, "confirmed Chinese protocol variant serial=$SerialNumber")
    }

    private fun handleV120(result: SibionicsProtocol.ParseResult) {
        when (result) {
            is SibionicsProtocol.ParseResult.Handshake -> handleV120Handshake(result.response)
            is SibionicsProtocol.ParseResult.V120Data -> {
                confirmProtocolMode(SibionicsConstants.ProtocolMode.V120)
                handler.removeCallbacks(handshakeTimeoutRunnable)
                phase = Phase.STREAMING
                updateV120HistoryProgress(result.entries)
                processV120Entries(result.entries)
                scheduleStreamingTimeout()
            }
            is SibionicsProtocol.ParseResult.V120AuthRequired -> switchToV120("auth marker")
            is SibionicsProtocol.ParseResult.ChecksumError -> {
                Log.w(SibionicsConstants.TAG, "V120 checksum error ${SibionicsProtocol.toHex(result.plaintext)}")
            }
            is SibionicsProtocol.ParseResult.Unknown -> {
                Log.w(SibionicsConstants.TAG, "V120 unknown ${SibionicsProtocol.toHex(result.bytes)}")
            }
            else -> Unit
        }
    }

    private fun handleV120Handshake(response: SibionicsProtocol.ResponseType) {
        when (response) {
            SibionicsProtocol.ResponseType.AUTH_ACCEPTED -> {
                if (phase != Phase.AUTHENTICATING) {
                    logIgnoredV120Handshake(response)
                    return
                }
                clearV120StepTimeouts()
                protocolMode = SibionicsConstants.ProtocolMode.V120
                val authenticatedVariant = authCandidateVariant ?: variant
                if (variant != authenticatedVariant) {
                    Log.i(
                        SibionicsConstants.TAG,
                        "confirmed variant ${authenticatedVariant.id} (was ${variant.id}) serial=$SerialNumber",
                    )
                }
                variant = authenticatedVariant
                Applic.app?.let { context ->
                    SibionicsRegistry.saveProtocolMode(context, SerialNumber, protocolMode)
                    record = SibionicsRegistry.confirmAuthenticatedVariant(
                        context,
                        SerialNumber,
                        authenticatedVariant,
                    ) ?: record
                }
                synchronized(algorithmLock) {
                    algorithm.configure(shortCode, sensitivity, variant, algorithmSelection)
                }
                setStatus("Authenticated")
                phase = Phase.ACTIVATING
                writeCommand(SibionicsProtocol.buildActivationPacket(), "activation")
                scheduleHandshakeTimeout()
            }
            SibionicsProtocol.ResponseType.TIME_SYNC_NEEDED -> {
                if (phase == Phase.SYNCING_TIME || phase == Phase.REQUESTING_DATA || phase == Phase.STREAMING) {
                    logIgnoredV120Handshake(response)
                    return
                }
                clearV120StepTimeouts()
                phase = Phase.SYNCING_TIME
                writeCommand(SibionicsProtocol.buildTimeSyncPacket(), "time-sync")
                scheduleHandshakeTimeout()
            }
            SibionicsProtocol.ResponseType.DATA_REQUESTED -> {
                if (phase == Phase.REQUESTING_DATA || phase == Phase.STREAMING) {
                    logIgnoredV120Handshake(response)
                    return
                }
                clearV120StepTimeouts()
                phase = Phase.REQUESTING_DATA
                writeCommand(SibionicsProtocol.buildDataRequestPacket(lastIndex), "data-request")
                scheduleHandshakeTimeout()
            }
            SibionicsProtocol.ResponseType.STREAMING_READY -> {
                clearV120StepTimeouts()
                phase = Phase.STREAMING
                setStatus(waitingForDataStatus())
                scheduleStreamingTimeout()
            }
        }
    }

    private fun logIgnoredV120Handshake(response: SibionicsProtocol.ResponseType) {
        Log.d(SibionicsConstants.TAG, "ignoring duplicate V120 $response in phase=$phase serial=$SerialNumber")
    }

    private fun clearV120StepTimeouts() {
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.removeCallbacks(authTimeoutRunnable)
    }

    private fun switchToV120(reason: String) {
        handler.removeCallbacks(chineseProbeTimeoutRunnable)
        handler.removeCallbacks(chineseDataTimeoutRunnable)
        handler.removeCallbacks(authTimeoutRunnable)
        protocolMode = SibionicsConstants.ProtocolMode.V120
        keyGroupIndex = 0
        authCandidateVariant = null
        Applic.app?.let { SibionicsRegistry.saveProtocolMode(it, SerialNumber, protocolMode) }
        Log.i(SibionicsConstants.TAG, "switching to V120: $reason")
        sendAuthPacket()
    }

    private fun confirmProtocolMode(mode: SibionicsConstants.ProtocolMode) {
        if (protocolMode == mode) return
        protocolMode = mode
        Applic.app?.let { SibionicsRegistry.saveProtocolMode(it, SerialNumber, mode) }
        UiRefreshBus.requestStatusRefresh()
    }

    private fun sendAuthPacket() {
        val groups = keyGroups()
        if (groups.isEmpty()) {
            setStatus("No V120 key")
            return
        }
        val selected = groups[keyGroupIndex.coerceIn(0, groups.lastIndex)]
        authCandidateVariant = selected
        sessionKey = SibionicsProtocol.deriveSessionKey(selected)
        val key = sessionKey
        if (key == null) {
            tryNextKeyGroup("key derivation failed")
            return
        }
        phase = Phase.AUTHENTICATING
        setStatus("Authenticating")
        writeCommand(SibionicsProtocol.buildAuthPacket(SibionicsConstants.macBytes(mActiveDeviceAddress), key), "auth")
        scheduleAuthTimeout()
    }

    private fun tryNextKeyGroup(reason: String) {
        val groups = keyGroups()
        keyGroupIndex++
        if (keyGroupIndex >= groups.size) {
            keyGroupIndex = 0
            phase = Phase.ERROR
            setStatus("Auth failed")
            Log.w(SibionicsConstants.TAG, "V120 auth failed: $reason")
            scheduleReconnect("auth failed")
            return
        }
        Log.i(SibionicsConstants.TAG, "V120 auth retry: $reason -> ${groups[keyGroupIndex].id}")
        sendAuthPacket()
    }

    private fun keyGroups(): List<SibionicsConstants.Variant> {
        val preferred = variant
        return listOf(
            preferred,
            SibionicsConstants.Variant.EU,
            SibionicsConstants.Variant.HEMATONIX,
            SibionicsConstants.Variant.SIBIONICS2,
            SibionicsConstants.Variant.GS3,
        ).distinctBy { it.appId + it.registrationKeyHex }
    }

    private fun sendChineseDataRequest(probing: Boolean) {
        protocolMode = if (protocolMode == SibionicsConstants.ProtocolMode.UNKNOWN) {
            SibionicsConstants.ProtocolMode.UNKNOWN
        } else {
            SibionicsConstants.ProtocolMode.CHINESE
        }
        if (probing) {
            phase = Phase.PROBING_CHINESE
            setStatus("Probing")
        } else if (phase != Phase.STREAMING) {
            phase = Phase.REQUESTING_DATA
            setStatus(waitingForDataStatus())
        }
        val packet = SibionicsProtocol.buildChineseDataRequest(lastIndex.coerceAtLeast(1), SibionicsConstants.macBytes(mActiveDeviceAddress))
        writeCommand(packet, "chinese-data-request")
    }

    private fun processChineseEntries(entries: List<SibionicsProtocol.ChineseEntry>) {
        if (entries.isEmpty()) {
            setStatus(waitingForDataStatus())
            return
        }
        observeCalibrationRevision()
        val now = System.currentTimeMillis()
        val ordered = entries.sortedBy { it.index }.map { entry ->
            entry to sanitizeSampleTime(entry.eventTimeMs(now))
        }
        val journalEntries = if (algorithmRehydrating) ordered else ordered.filter { (entry, _) ->
            entry.index >= lastIndex || (entry.index <= 1 && entry.isLive)
        }
        sampleJournal?.appendAll(journalEntries.map { (entry, eventMs) ->
            SibionicsSourceSample(
                index = entry.index,
                timestampMs = eventMs,
                rawMmol = entry.rawMmol,
                temperatureC = entry.temperatureC,
                impedance = entry.rawImpedance.toFloat(),
                variantId = variant.ordinal,
            )
        })
        val emitted = ordered
            .mapNotNull { (entry, eventMs) ->
                processEntry(
                    index = entry.index,
                    rawMmol = entry.rawMmol,
                    rawMgdl = entry.rawMgdl,
                    temperatureC = entry.temperatureC,
                    impedance = entry.rawImpedance.toFloat(),
                    eventMs = eventMs,
                    live = entry.isLive,
                )
            }
        flushAlgorithmCheckpointIfDirty()
        storeAndPublish(emitted)
        maybeScheduleInitialLocalRebuild()
        updateHistoryStatus(resultHasLive = entries.any { it.isLive })
    }

    private fun processV120Entries(entries: List<SibionicsProtocol.V120Entry>) {
        if (entries.isEmpty()) {
            setStatus(waitingForDataStatus())
            return
        }
        observeCalibrationRevision()
        val now = System.currentTimeMillis()
        val ordered = entries.sortedBy { it.index }
        val journalEntries = if (algorithmRehydrating) ordered else ordered.filter { entry ->
            entry.index >= lastIndex || (entry.index <= 1 && isV120Current(entry, now))
        }
        sampleJournal?.appendAll(journalEntries.map { entry ->
            SibionicsSourceSample(
                index = entry.index,
                timestampMs = sanitizeSampleTime(entry.eventTimeMs),
                rawMmol = entry.rawMmol,
                temperatureC = entry.temperatureC,
                impedance = entry.rawImpedance.toFloat(),
                variantId = variant.ordinal,
            )
        })
        val emitted = ordered
            .mapNotNull { entry ->
                processEntry(
                    index = entry.index,
                    rawMmol = entry.rawMmol,
                    rawMgdl = entry.rawMgdl,
                    temperatureC = entry.temperatureC,
                    impedance = entry.rawImpedance.toFloat(),
                    eventMs = sanitizeSampleTime(entry.eventTimeMs),
                    live = isV120Current(entry, now),
                )
            }
        flushAlgorithmCheckpointIfDirty()
        storeAndPublish(emitted)
        maybeScheduleInitialLocalRebuild()
        updateHistoryStatus(resultHasLive = entries.any { isV120Current(it, now) })
    }

    private fun updateChineseHistoryProgress(entries: List<SibionicsProtocol.ChineseEntry>) {
        if (entries.isEmpty()) return
        historySeenIndices.addAll(entries.map { it.index })
        historyReceivedCount = historySeenIndices.size
        historyTotalCount = SibionicsProtocol.estimateChineseHistoryTotal(
            previousTotal = historyTotalCount,
            receivedCount = historyReceivedCount,
            entries = entries,
        )
    }

    private fun updateV120HistoryProgress(entries: List<SibionicsProtocol.V120Entry>) {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        historySeenIndices.addAll(entries.map { it.index })
        historyReceivedCount = historySeenIndices.size
        val estimates = entries.mapNotNull { entry ->
            val candidateStart = entry.eventTimeMs - entry.index * SibionicsConstants.READING_INTERVAL_MS
            if (candidateStart <= 0L || candidateStart > now) null
            else ((now - candidateStart) / SibionicsConstants.READING_INTERVAL_MS).toInt()
        }
        historyTotalCount = maxOf(
            historyTotalCount,
            estimates.maxOrNull() ?: historyReceivedCount,
            historyReceivedCount,
        )
    }

    private fun isV120Current(entry: SibionicsProtocol.V120Entry, now: Long): Boolean =
        entry.eventTimeMs in (now - 90_000L)..(now + MAX_FUTURE_DRIFT_MS)

    private fun updateHistoryStatus(resultHasLive: Boolean) {
        if (resultHasLive) {
            // Keep high priority through authentication and history recovery. Relax only once
            // the sensor has delivered a current sample, otherwise large V120 history transfers
            // become unnecessarily slow on conservative Android Bluetooth stacks.
            settleConnectionPriority()
            scheduleBatteryRefreshAfterStreaming()
            setStatus(connectedStatus())
        } else if (SibionicsSessionPolicy.shouldShowHistoryProgress(
                historyReceivedCount,
                historyTotalCount,
                hasReceivedLiveReading = lastLiveIndexSeen >= 0,
            )
        ) {
            setStatus(historyProgressStatus())
        }
    }

    private fun processEntry(
        index: Int,
        rawMmol: Float,
        rawMgdl: Float,
        temperatureC: Float,
        impedance: Float,
        eventMs: Long,
        live: Boolean,
    ): EmittedReading? {
        if (index < 0 || eventMs <= 0L) return null
        // Repeated history pages may begin at index 1. A current index-1 sample is
        // the evidence that the physical sensor actually restarted.
        if (SibionicsSessionPolicy.isConfirmedIndexRestart(index, lastIndex, live, algorithmRehydrating)) {
            resetForSensorRestart()
        }
        if (!rawMmol.isFinite() || rawMmol <= 0f) return null
        if (!live && lastIndex > 0 && index < lastIndex) return null
        if (live && lastLiveAlgorithmIndexSeen >= 0 && index <= lastLiveAlgorithmIndexSeen) return null
        if (!algorithmRehydrating && lastIndex > 0 && index > lastIndex) {
            beginAlgorithmRehydration(lastIndex, "algorithm index gap: expected $lastIndex, received $index")
            scheduleReconnect("exact algorithm index gap")
            return null
        }
        val wasRehydrating = algorithmRehydrating
        if (wasRehydrating && !acceptRehydrationIndex(index)) return null
        val shouldRebaseNativeWindow =
            SibionicsSessionPolicy.shouldRebaseNativeWindow(startTimeMs > 0L, index)
        if (startTimeMs <= 0L && index >= 0) {
            startTimeMs = eventMs - index * SibionicsConstants.READING_INTERVAL_MS
            Applic.app?.let { SibionicsRegistry.saveStartTimeMs(it, SerialNumber, startTimeMs) }
            scheduleResetMaintenanceCheck()
            if (shouldRebaseNativeWindow) {
                SerialNumber?.let { nativeName ->
                    runCatching {
                        Natives.rebaseDirectStreamWindow(nativeName, startTimeMs / 1000L)
                    }.onFailure {
                        Log.stack(SibionicsConstants.TAG, "rebase native stream after sensor restart", it)
                    }
                }
            }
        }
        val displayMmol = synchronized(algorithmLock) {
            if (index <= 1) algorithm.reset()
            val stockMmol = algorithm.processStock(
                rawMmol = rawMmol,
                temperatureC = temperatureC,
                index = index,
                mode = if (live) SibionicsAlgorithmMode.LIVE else SibionicsAlgorithmMode.REPLAY,
            )
            val measurementMmol = integratedCalibratedMmol(stockMmol, eventMs)
            algorithm.processPreparedMeasurement(
                stockMmol = stockMmol,
                measurementMmol = measurementMmol,
                rawMmol = rawMmol,
                temperatureC = temperatureC,
                index = index,
                impedance = impedance,
                eventTimeMs = eventMs,
            )
        }
        if (live) lastLiveAlgorithmIndexSeen = index
        algorithmStateDirty = true
        advanceLastIndex(index)
        if (wasRehydrating && index >= rehydrationTargetIndex - 1) {
            algorithmRehydrating = false
            rehydrationExpectedIndex = -1
            rehydrationTargetIndex = 0
            Log.i(SibionicsConstants.TAG, "exact algorithm rehydrated through idx=$index")
            scheduleAlgorithmRebuild("source history recovered", delayMs = 0L)
        }
        if (wasRehydrating && !live) return null
        val glucoseMgdl = displayMmol * SibionicsConstants.MGDL_PER_MMOLL
        if (!SibionicsConstants.isValidAlgorithmGlucoseMgdl(glucoseMgdl)) {
            Log.w(SibionicsConstants.TAG, "skip invalid glucose idx=$index display=$glucoseMgdl raw=$rawMgdl")
            return null
        }

        return EmittedReading(eventMs, glucoseMgdl, rawMgdl, temperatureC, impedance, index, live)
    }

    private fun integratedCalibratedMmol(stockMmol: Float, eventMs: Long): Float {
        if (!algorithmSelection.calibrationEnabled || !stockMmol.isFinite() || stockMmol <= 0f) {
            return stockMmol
        }
        val input = if (Applic.unit == 1) {
            stockMmol
        } else {
            stockMmol * SibionicsConstants.MGDL_PER_MMOLL
        }
        val calibrated = tk.glucodata.CalibrationAccess.getIntegratedCalibratedSeries(
            values = floatArrayOf(input),
            timestamps = longArrayOf(eventMs),
            isRawMode = false,
            sensorIdOverride = SerialNumber,
        ).firstOrNull() ?: input
        if (!calibrated.isFinite() || calibrated <= 0f) return stockMmol
        return if (Applic.unit == 1) calibrated else calibrated / SibionicsConstants.MGDL_PER_MMOLL
    }

    private fun observeCalibrationRevision() {
        val revision = tk.glucodata.CalibrationAccess.getRevision()
        if (revision != calibrationRevision) onUserCalibrationRevisionChanged(revision)
    }

    private fun maybeScheduleInitialLocalRebuild() {
        if (!rebuildAfterNextSourceSample) return
        rebuildAfterNextSourceSample = false
        val currentFingerprint = algorithmRebuildFingerprint(algorithmSelection)
        val savedFingerprint = Applic.app?.let {
            SibionicsRegistry.loadLocalRebuildFingerprint(it, SerialNumber)
        }.orEmpty()
        if (savedFingerprint == currentFingerprint) return
        scheduleAlgorithmRebuild("initialize persisted algorithm selection")
    }

    private fun algorithmRebuildFingerprint(selection: SibionicsAlgorithmSelection): String {
        val calibrationFingerprint = if (selection.calibrationEnabled) {
            tk.glucodata.CalibrationAccess.getIntegratedCalibrationFingerprint(SerialNumber, false)
        } else {
            0L
        }
        return "$LOCAL_REBUILD_FORMAT_VERSION:${selection.storageId}:${sensitivity.toRawBits()}:$calibrationFingerprint"
    }

    override fun onUserCalibrationRevisionChanged(revision: Long) {
        if (revision == calibrationRevision) return
        calibrationRevision = revision
        if (algorithmSelection.calibrationEnabled) {
            val currentFingerprint = algorithmRebuildFingerprint(algorithmSelection)
            val savedFingerprint = Applic.app?.let {
                SibionicsRegistry.loadLocalRebuildFingerprint(it, SerialNumber)
            }.orEmpty()
            if (currentFingerprint == savedFingerprint) return
            scheduleAlgorithmRebuild("calibration revision $revision")
        }
    }

    @Synchronized
    private fun scheduleAlgorithmRebuild(
        reason: String,
        delayMs: Long = ALGORITHM_REBUILD_DEBOUNCE_MS,
    ) {
        if (rebuildExecutor.isShutdown) return
        rebuildGeneration++
        handler.removeCallbacks(rebuildLaunchRunnable)
        handler.postDelayed(rebuildLaunchRunnable, delayMs.coerceAtLeast(0L))
        Log.i(SibionicsConstants.TAG, "algorithm rebuild scheduled: $reason generation=$rebuildGeneration")
    }

    private fun rebuildAlgorithmLocally(generation: Long) {
        if (generation != rebuildGeneration) return
        val journal = sampleJournal ?: return
        val selection = algorithmSelection
        val variantSnapshot = variant
        val shortCodeSnapshot = shortCode
        val sensitivitySnapshot = sensitivity
        val rebuildFingerprint = algorithmRebuildFingerprint(selection)
        if (generation != rebuildGeneration) return
        val sourceSamples = journal.snapshot().filter { it.variantId == variantSnapshot.ordinal }
        if (!SibionicsAlgorithmRebuilder.isContiguousFromSensorStart(sourceSamples)) {
            requestSourceJournalBackfill(generation, sourceSamples)
            return
        }

        val result = runCatching {
            buildAlgorithmRebuild(
                sourceSamples = sourceSamples,
                selection = selection,
                variantSnapshot = variantSnapshot,
                shortCodeSnapshot = shortCodeSnapshot,
                sensitivitySnapshot = sensitivitySnapshot,
            )
        }.onFailure {
            Log.stack(SibionicsConstants.TAG, "local algorithm rebuild", it)
        }.getOrNull() ?: return
        if (result.readings.isEmpty() || generation != rebuildGeneration || selection != algorithmSelection ||
            variantSnapshot != variant || shortCodeSnapshot != shortCode || sensitivitySnapshot != sensitivity
        ) return

        val timestamps = LongArray(result.readings.size) { result.readings[it].sampleMs }
        val values = FloatArray(result.readings.size) { result.readings[it].glucoseMgdl }
        val raws = FloatArray(result.readings.size) { result.readings[it].rawMgdl }
        if (!HistorySyncAccess.storeSensorHistoryBatchBlocking(SerialNumber, timestamps, values, raws)) {
            Log.w(SibionicsConstants.TAG, "local algorithm rebuild could not replace Room history")
            return
        }

        val committed = synchronized(algorithmLock) {
            val currentSources = journal.snapshot().filter { it.variantId == variantSnapshot.ordinal }
            if (generation != rebuildGeneration || selection != algorithmSelection ||
                variantSnapshot != variant || shortCodeSnapshot != shortCode || sensitivitySnapshot != sensitivity ||
                currentSources != result.sourceSamples
            ) {
                false
            } else {
                algorithm = result.context
                val last = result.readings.last()
                val previous = result.readings.getOrNull(result.readings.lastIndex - 1)
                lastIndex = last.index + 1
                lastLiveIndexSeen = last.index
                lastLiveAlgorithmIndexSeen = last.index
                algorithmRehydrating = false
                rehydrationExpectedIndex = -1
                rehydrationTargetIndex = 0
                latestReadingTimeMs = last.sampleMs
                latestGlucoseMgdl = last.glucoseMgdl
                latestRawMgdl = last.rawMgdl
                latestTemperatureC = last.temperatureC
                latestImpedance = last.impedance
                latestRateMgdlPerMin = previous?.let {
                    val elapsedMinutes = (last.sampleMs - it.sampleMs) / 60_000f
                    if (elapsedMinutes > 0f) (last.glucoseMgdl - it.glucoseMgdl) / elapsedMinutes else Float.NaN
                } ?: Float.NaN
                if (startTimeMs <= 0L) {
                    startTimeMs = last.sampleMs - last.index * SibionicsConstants.READING_INTERVAL_MS
                    Applic.app?.let { SibionicsRegistry.saveStartTimeMs(it, SerialNumber, startTimeMs) }
                    scheduleResetMaintenanceCheck()
                }
                lastIndexDirty = false
                algorithmStateDirty = false
                true
            }
        }
        if (!committed) {
            scheduleAlgorithmRebuild("source/settings changed during rebuild")
            return
        }

        Applic.app?.let { context ->
            synchronized(algorithmLock) {
                SibionicsRegistry.saveAlgorithmCheckpoint(context, SerialNumber, lastIndex, algorithm.snapshot())
                SibionicsRegistry.saveStartTimeMs(context, SerialNumber, startTimeMs)
                SibionicsRegistry.saveLastReading(
                    context,
                    SerialNumber,
                    latestReadingTimeMs,
                    latestGlucoseMgdl,
                    latestRawMgdl,
                )
                SibionicsRegistry.saveLocalRebuildFingerprint(
                    context,
                    SerialNumber,
                    rebuildFingerprint,
                )
            }
        }
        mirrorReadingsIntoNative(result.readings)
        val last = result.readings.last()
        HistorySyncAccess.storeCurrentReadingAsync(
            last.sampleMs,
            last.glucoseMgdl,
            last.rawMgdl,
            latestRateMgdlPerMin.takeIf { it.isFinite() } ?: 0f,
            SerialNumber,
        )
        UiRefreshBus.requestDataRefresh()
        UiRefreshBus.requestStatusRefresh()
        Log.i(
            SibionicsConstants.TAG,
            "local algorithm rebuild committed samples=${result.readings.size} " +
                "selection=${selection.name} revision=$calibrationRevision",
        )
    }

    private fun buildAlgorithmRebuild(
        sourceSamples: List<SibionicsSourceSample>,
        selection: SibionicsAlgorithmSelection,
        variantSnapshot: SibionicsConstants.Variant,
        shortCodeSnapshot: String,
        sensitivitySnapshot: Float,
    ): AlgorithmRebuildResult {
        val replay = SibionicsAlgorithmRebuilder.rebuild(
            sensorId = SerialNumber,
            sourceSamples = sourceSamples,
            selection = selection,
            variant = variantSnapshot,
            shortCode = shortCodeSnapshot,
            sensitivity = sensitivitySnapshot,
            unitIsMmol = Applic.unit == 1,
        ) { displayStock, timestamps ->
            calibratedDisplaySeries(displayStock, timestamps)
        }
        return AlgorithmRebuildResult(
            context = replay.context,
            readings = replay.readings.map { reading ->
                EmittedReading(
                    sampleMs = reading.sampleMs,
                    glucoseMgdl = reading.glucoseMgdl,
                    rawMgdl = reading.rawMgdl,
                    temperatureC = reading.temperatureC,
                    impedance = reading.impedance,
                    index = reading.index,
                    live = false,
                )
            },
            sourceSamples = replay.sourceSamples,
        )
    }

    private fun calibratedDisplaySeries(displayStock: FloatArray, timestamps: LongArray): FloatArray {
        val packedAnchors = tk.glucodata.CalibrationAccess.getActiveCalibrationAnchors(
            SerialNumber,
            false,
        )
        SibionicsAlgorithmRebuilder.calibrationBaselineAtAnchors(
            displayStock = displayStock,
            timestamps = timestamps,
            packedAnchors = packedAnchors,
        )?.let { baseline ->
            tk.glucodata.CalibrationAccess.seedIntegratedCalibrationBaseline(
                values = baseline.values,
                timestamps = baseline.timestamps,
                isRawMode = false,
                sensorIdOverride = SerialNumber,
            )
            Applic.app?.let { context ->
                SibionicsRegistry.saveIntegratedCalibrationBaseline(
                    context = context,
                    sensorId = SerialNumber,
                    unit = Applic.unit,
                    values = baseline.values,
                    timestamps = baseline.timestamps,
                )
            }
        }
        return tk.glucodata.CalibrationAccess.getIntegratedCalibratedSeries(
            values = displayStock,
            timestamps = timestamps,
            isRawMode = false,
            sensorIdOverride = SerialNumber,
        )
    }

    private fun requestSourceJournalBackfill(
        generation: Long,
        samples: List<SibionicsSourceSample>,
    ) {
        handler.post {
            if (generation != rebuildGeneration || algorithmRehydrating || stop || uiPaused) return@post
            val previousIndex = maxOf(lastIndex, (samples.lastOrNull()?.index ?: 0) + 1)
            beginAlgorithmRehydration(previousIndex, "local source journal incomplete")
            setStatus(waitingForDataStatus())
            scheduleReconnect("local algorithm source backfill")
        }
    }

    private fun advanceLastIndex(index: Int) {
        if (index >= lastIndex) {
            lastIndex = index + 1
            lastIndexDirty = true
        }
    }

    private fun flushAlgorithmCheckpointIfDirty() {
        if (!lastIndexDirty && !algorithmStateDirty) return
        val indexDirty = lastIndexDirty
        val stateDirty = algorithmStateDirty
        lastIndexDirty = false
        algorithmStateDirty = false
        Applic.app?.let { context ->
            if (stateDirty) {
                val snapshot = synchronized(algorithmLock) { algorithm.snapshot() }
                SibionicsRegistry.saveAlgorithmCheckpoint(context, SerialNumber, lastIndex, snapshot)
            } else if (indexDirty) {
                SibionicsRegistry.saveLastIndex(context, SerialNumber, lastIndex)
            }
        }
    }

    private fun beginAlgorithmRehydration(previousIndex: Int, reason: String) {
        synchronized(algorithmLock) { algorithm.reset() }
        algorithmStateDirty = false
        algorithmRehydrating = true
        rehydrationExpectedIndex = -1
        rehydrationTargetIndex = previousIndex.coerceAtLeast(1)
        lastLiveAlgorithmIndexSeen = -1
        lastIndex = 1
        lastIndexDirty = true
        Log.w(
            SibionicsConstants.TAG,
            "exact algorithm state unavailable ($reason); requesting contiguous replay from idx=1",
        )
    }

    private fun resetForSensorRestart() {
        Log.i(SibionicsConstants.TAG, "sensor index restarted; clearing exact algorithm state")
        rebuildGeneration++
        synchronized(algorithmLock) { algorithm.reset() }
        sampleJournal?.clear()
        lastIndex = 0
        lastIndexDirty = true
        algorithmStateDirty = true
        lastLiveAlgorithmIndexSeen = -1
        lastLiveIndexSeen = -1
        startTimeMs = 0L
        latestReadingTimeMs = 0L
        latestGlucoseMgdl = Float.NaN
        latestRawMgdl = Float.NaN
        Applic.app?.let { context ->
            SibionicsRegistry.clearStartTimeMs(context, SerialNumber)
            SibionicsRegistry.clearResetMaintenanceState(context, SerialNumber)
            SibionicsResetReminder.cancel(context, SerialNumber)
            HistorySyncAccess.markSensorReset(SerialNumber)
        }
    }

    private fun acceptRehydrationIndex(index: Int): Boolean {
        if (rehydrationExpectedIndex < 0) {
            if (index > 1) {
                Log.w(
                    SibionicsConstants.TAG,
                    "cannot rehydrate exact algorithm: expected idx 0/1, received idx=$index",
                )
                return false
            }
            rehydrationExpectedIndex = index + 1
            return true
        }
        if (index < rehydrationExpectedIndex) return false
        if (index != rehydrationExpectedIndex) {
            Log.w(
                SibionicsConstants.TAG,
                "cannot rehydrate exact algorithm: expected idx=$rehydrationExpectedIndex, received idx=$index",
            )
            return false
        }
        rehydrationExpectedIndex = index + 1
        return true
    }

    private fun storeAndPublish(readings: List<EmittedReading>) {
        if (readings.isEmpty()) return
        val history = ArrayList<EmittedReading>()
        var live: EmittedReading? = null
        for (reading in readings.sortedBy { it.sampleMs }) {
            if (reading.live) {
                live = reading
            } else if (reading.index != lastLiveIndexSeen) {
                history += reading
            }
        }

        if (history.isNotEmpty()) {
            val timestamps = LongArray(history.size) { history[it].sampleMs }
            val values = FloatArray(history.size) { history[it].glucoseMgdl }
            val raws = FloatArray(history.size) { history[it].rawMgdl }
            HistorySyncAccess.storeSensorHistoryBatchAsync(SerialNumber, timestamps, values, raws)
            // Native stream writes are minute-index-addressed and idempotent,
            // so replayed backfill batches are safe. Without this the sensor
            // never exists in the native store — invisible to the watch
            // mirror, /data stream, and phone↔phone followers.
            mirrorReadingsIntoNative(history)
        }

        live?.let { publishLiveReading(it) }
        UiRefreshBus.requestDataRefresh()
    }

    // Same native mirroring the Anytime driver does (AnytimeBleManager
    // .mirrorReadingIntoNative): shell + stream write keyed by SerialNumber.
    // Native scales glucose ×10 internally; callers pass mgdl/10 by
    // convention (verified against g.cpp addGlucoseStreamInternal).
    private fun mirrorReadingIntoNative(reading: EmittedReading): Boolean =
        mirrorReadingsIntoNative(listOf(reading))

    private fun mirrorReadingsIntoNative(readings: List<EmittedReading>): Boolean {
        val name = SerialNumber ?: return false
        val validReadings = readings.filter {
            val sampleSec = it.sampleMs / 1000L
            sampleSec > 0L && it.glucoseMgdl.isFinite() && it.glucoseMgdl > 0f
        }
        if (validReadings.isEmpty()) return false
        return runCatching {
            val firstSampleSec = validReadings.first().sampleMs / 1000L
            val startSec = when {
                startTimeMs > 0L -> startTimeMs / 1000L
                firstSampleSec > 3600L -> firstSampleSec - 3600L
                else -> 1L
            }.coerceAtLeast(1L)
            Natives.ensureSensorShell(name, startSec)
            if (!Natives.hasSensorStreamCapacity(name, NATIVE_STREAM_CAPACITY_MINUTES)) {
                Log.e(SibionicsConstants.TAG, "native stream capacity unavailable for $name")
                return@runCatching false
            }
            var stored = false
            for (reading in validReadings) {
                if (Thread.currentThread().isInterrupted) return@runCatching false
                val temperatureC = reading.temperatureC
                    .takeIf { it.isFinite() && it > -20f && it < 80f }
                    ?: 0f
                val rawMgdl = reading.rawMgdl
                    .takeIf { it.isFinite() && it > 0f }
                    ?: reading.glucoseMgdl
                stored = Natives.addGlucoseStreamWithRawTemp(
                    reading.sampleMs / 1000L,
                    reading.glucoseMgdl / 10f,
                    rawMgdl,
                    temperatureC,
                    name,
                ) || stored
            }
            stored
        }.onFailure {
            Log.stack(SibionicsConstants.TAG, "mirrorReadingIntoNative", it)
        }.getOrDefault(false)
    }

    private fun publishLiveReading(reading: EmittedReading) {
        val previousTime = latestReadingTimeMs
        val previousValue = latestGlucoseMgdl
        latestReadingTimeMs = reading.sampleMs
        latestGlucoseMgdl = reading.glucoseMgdl
        latestRawMgdl = reading.rawMgdl
        latestTemperatureC = reading.temperatureC
        latestImpedance = reading.impedance
        lastLiveIndexSeen = reading.index
        latestRateMgdlPerMin = if (previousTime > 0L && previousValue.isFinite() && reading.sampleMs > previousTime) {
            (reading.glucoseMgdl - previousValue) / ((reading.sampleMs - previousTime) / 60_000f)
        } else {
            Float.NaN
        }
        Applic.app?.let {
            SibionicsRegistry.saveLastReading(it, SerialNumber, reading.sampleMs, reading.glucoseMgdl, reading.rawMgdl)
        }
        if (mirrorReadingIntoNative(reading)) {
            NightscoutUploadWake.afterLiveNativeWrite("sibionics-managed", reading.sampleMs)
        }
        HistorySyncAccess.storeCurrentReadingAsync(
            reading.sampleMs,
            reading.glucoseMgdl,
            reading.rawMgdl,
            latestRateMgdlPerMin.takeIf { it.isFinite() } ?: 0f,
            SerialNumber,
        )
        val displayValue = toDisplay(reading.glucoseMgdl)
        val displayRate = if (Applic.unit == 1) latestRateMgdlPerMin / SibionicsConstants.MGDL_PER_MMOLL else latestRateMgdlPerMin
        SuperGattCallback.processExternalCurrentReading(
            SerialNumber,
            displayValue,
            displayRate.takeIf { it.isFinite() } ?: 0f,
            reading.sampleMs,
            SibionicsConstants.SENSOR_GEN,
        )
        setStatus(connectedStatus())
        Log.i(
            SibionicsConstants.TAG,
            "live idx=${reading.index} mgdl=%.1f raw=%.1f temp=%.1f".format(
                reading.glucoseMgdl,
                reading.rawMgdl,
                reading.temperatureC,
            ),
        )
        maybeScheduleAutoReset()
    }

    private fun sanitizeSampleTime(timeMs: Long): Long {
        val now = System.currentTimeMillis()
        if (timeMs < MIN_REASONABLE_TIME_MS) return now
        if (timeMs > now + MAX_FUTURE_DRIFT_MS) return now
        return timeMs
    }

    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val descriptor = ch.getDescriptor(SibionicsConstants.CCCD)
        if (descriptor == null) {
            requestBatteryRead(BatteryReadPurpose.SETUP)
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val wrote = runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
        if (!wrote) {
            Log.w(SibionicsConstants.TAG, "write CCCD failed; starting probe anyway")
            requestBatteryRead(BatteryReadPurpose.SETUP)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCommand(bytes: ByteArray, label: String): Boolean {
        val gatt = mBluetoothGatt ?: return false
        val ch = writeChar ?: return false
        val writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        ch.writeType = writeType
        ch.value = bytes
        val ok = runCatching { gatt.writeCharacteristic(ch) }.getOrDefault(false)
        Log.i(SibionicsConstants.TAG, "write $label ok=$ok bytes=${SibionicsProtocol.toHex(bytes)}")
        return ok
    }

    override fun supportsResetAction(): Boolean = true

    override fun sendMaintenanceCommand(opCode: Int): Boolean =
        if (opCode == 0x10 || opCode == 0) resetSensor() else false

    override fun resetSensor(): Boolean {
        Applic.app?.let { SibionicsRegistry.requestReset(it, SerialNumber) }
        pendingResetCommand = false
        val ok = writeResetCommand("user")
        if (ok) {
            return true
        }
        pendingResetCommand = true
        connectDevice(0)
        setStatus("Reset queued")
        return true
    }

    private fun writeResetCommand(reason: String): Boolean {
        val packet = SibionicsProtocol.buildMaintenanceResetPacket(variant)
        val ok = writeCommand(packet, "reset-$reason")
        if (ok) markResetSent()
        return ok
    }

    private fun markResetSent() {
        handler.removeCallbacks(resetMaintenanceRunnable)
        discardNotificationsUntilResetDisconnect = true
        loggedDiscardedPostResetNotification = false
        handler.removeCallbacks(postResetDiscardTimeoutRunnable)
        handler.postDelayed(postResetDiscardTimeoutRunnable, POST_RESET_DISCARD_TIMEOUT_MS)
        rebuildGeneration++
        synchronized(algorithmLock) { algorithm.reset() }
        sampleJournal?.clear()
        lastIndex = 1
        lastIndexDirty = true
        algorithmStateDirty = true
        algorithmRehydrating = false
        rehydrationExpectedIndex = -1
        rehydrationTargetIndex = 0
        lastLiveAlgorithmIndexSeen = -1
        lastLiveIndexSeen = -1
        startTimeMs = 0L
        latestReadingTimeMs = 0L
        latestGlucoseMgdl = Float.NaN
        latestRawMgdl = Float.NaN
        historyReceivedCount = 0
        historyTotalCount = 0
        historySeenIndices.clear()
        autoResetScheduled = false
        Applic.app?.let {
            val snapshot = synchronized(algorithmLock) { algorithm.snapshot() }
            SibionicsRegistry.saveAlgorithmCheckpoint(it, SerialNumber, lastIndex, snapshot)
            SibionicsRegistry.clearStartTimeMs(it, SerialNumber)
            SibionicsRegistry.clearResetMaintenanceState(it, SerialNumber)
            SibionicsResetReminder.cancel(it, SerialNumber)
            HistorySyncAccess.markSensorReset(SerialNumber)
        }
        lastIndexDirty = false
        algorithmStateDirty = false
        setStatus("Reset sent")
        UiRefreshBus.requestStatusRefresh()
    }

    private val postResetDiscardTimeoutRunnable = Runnable {
        if (!discardNotificationsUntilResetDisconnect) return@Runnable
        Log.w(SibionicsConstants.TAG, "reset disconnect timeout; forcing a clean reconnect")
        discardNotificationsUntilResetDisconnect = false
        loggedDiscardedPostResetNotification = false
        scheduleReconnect("reset disconnect timeout")
    }

    private fun finishPostResetDisconnectGuard() {
        handler.removeCallbacks(postResetDiscardTimeoutRunnable)
        discardNotificationsUntilResetDisconnect = false
        loggedDiscardedPostResetNotification = false
    }

    override fun supportsClearCalibrationAction(): Boolean = false

    override fun supportsAutoReset(): Boolean =
        variant == SibionicsConstants.Variant.SIBIONICS2

    override fun getAutoResetDays(): Int = autoResetDays

    override fun setAutoResetDays(days: Int): Boolean {
        if (!supportsAutoReset()) return false
        autoResetDays = if (SibionicsResetPolicy.isEnabled(days)) {
            days
        } else {
            SibionicsResetPolicy.DISABLED_DAYS
        }
        autoResetScheduled = false
        Applic.app?.let { SibionicsRegistry.saveAutoResetDays(it, SerialNumber, autoResetDays) }
        scheduleResetMaintenanceCheck()
        UiRefreshBus.requestStatusRefresh()
        return true
    }

    override fun supportsCustomAlgorithm(): Boolean = true

    override fun isCustomAlgorithmEnabled(): Boolean =
        algorithmSelection.customModelEnabled

    override fun setCustomAlgorithmEnabled(enabled: Boolean): Boolean {
        return setCustomAlgorithmMode(
            algorithmSelection.withModel(
                if (enabled) SibionicsCustomAlgorithmModel.STATE_MODEL
                else SibionicsCustomAlgorithmModel.STOCK,
            ).storageId,
        )
    }

    override fun getCustomAlgorithmMode(): Int = algorithmSelection.storageId

    override fun setCustomAlgorithmMode(mode: Int): Boolean {
        val selection = SibionicsAlgorithmSelection.fromStorage(mode)
        if (algorithmSelection == selection) return true
        rebuildAfterNextSourceSample = false
        synchronized(algorithmLock) {
            algorithmSelection = selection
            algorithm.setSelection(selection)
        }
        algorithmStateDirty = true
        Applic.app?.let { SibionicsRegistry.saveAlgorithmSelection(it, SerialNumber, selection) }
        // The selection flips integratesUserCalibration(); drop the memoized value so manual
        // calibration isn't double-applied (or silently skipped) until the next record write.
        ManagedSensorRuntime.clearCaches()
        tk.glucodata.CalibrationAccess.notifyExternalCalibrationPipelineChanged()
        scheduleAlgorithmRebuild("algorithm selection ${selection.name}", delayMs = 0L)
        UiRefreshBus.requestStatusRefresh()
        UiRefreshBus.requestDataRefresh()
        return true
    }

    override fun supportsAlgorithmSensitivity(): Boolean = true

    override fun getAlgorithmSensitivity(): Float = sensitivity

    override fun getAutomaticAlgorithmSensitivity(): Float = automaticSensitivity

    override fun hasAlgorithmSensitivityOverride(): Boolean = sensitivityOverride != null

    override fun setAlgorithmSensitivityOverride(sensitivity: Float?): Boolean {
        if (sensitivity != null && !SibionicsSensitivity.isSupported(sensitivity)) return false
        val normalized = sensitivity
            ?.let { (it * 100f).roundToInt() / 100f }
            ?.takeIf(SibionicsSensitivity::isSupported)
        if (normalized == sensitivityOverride) return true

        sensitivityOverride = normalized
        this.sensitivity = normalized ?: automaticSensitivity
        Applic.app?.let {
            SibionicsRegistry.saveAlgorithmSensitivityOverride(it, SerialNumber, normalized)
            // A checkpoint encodes calculations made with the previous sensitivity.
            // Clearing it makes a killed/restarted rebuild resume from source samples.
            SibionicsRegistry.clearAlgorithmState(it, SerialNumber)
        }
        algorithmStateDirty = true
        scheduleAlgorithmRebuild(
            reason = if (normalized == null) {
                "restore sensor sensitivity $automaticSensitivity"
            } else {
                "algorithm sensitivity $normalized"
            },
            delayMs = 0L,
        )
        UiRefreshBus.requestStatusRefresh()
        UiRefreshBus.requestDataRefresh()
        return true
    }

    private fun maybeScheduleAutoReset() {
        if (variant != SibionicsConstants.Variant.SIBIONICS2 ||
            autoResetScheduled || pendingResetCommand || startTimeMs <= 0L
        ) return
        val context = Applic.app ?: return
        val now = System.currentTimeMillis()
        val decision = SibionicsResetPolicy.evaluate(
            nowMs = now,
            startTimeMs = startTimeMs,
            autoResetDays = autoResetDays,
            postponedUntilMs = SibionicsRegistry.loadResetPostponedUntilMs(context, SerialNumber),
            lastReminderAtMs = SibionicsRegistry.loadResetReminderAtMs(context, SerialNumber),
            glucoseMgdl = latestGlucoseMgdl,
            rateMgdlPerMin = latestRateMgdlPerMin,
        )
        if (decision.showReminder) {
            SibionicsRegistry.markResetReminderShown(context, SerialNumber, now)
            SibionicsResetReminder.show(
                context = context,
                sensorId = SerialNumber,
                dueAtMs = SibionicsResetPolicy.dueAtMs(startTimeMs, autoResetDays),
                hardDeadlineMs = SibionicsResetPolicy.hardDeadlineMs(startTimeMs),
                nowMs = now,
            )
        }
        if (!decision.resetNow) {
            scheduleResetMaintenanceCheck()
            return
        }
        autoResetScheduled = true
        Log.i(
            SibionicsConstants.TAG,
            "auto reset due serial=$SerialNumber forced=${decision.forced} " +
                "enabled=${SibionicsResetPolicy.isEnabled(autoResetDays)} days=$autoResetDays",
        )
        handler.post { resetSensor() }
    }

    private fun scheduleResetMaintenanceCheck() {
        handler.removeCallbacks(resetMaintenanceRunnable)
        if (variant != SibionicsConstants.Variant.SIBIONICS2 ||
            autoResetScheduled || pendingResetCommand || startTimeMs <= 0L
        ) return
        val context = Applic.app ?: return
        val now = System.currentTimeMillis()
        val dueAt = SibionicsResetPolicy.dueAtMs(startTimeMs, autoResetDays)
        val hardDeadline = SibionicsResetPolicy.hardDeadlineMs(startTimeMs)
        val reminderAt = dueAt - SibionicsResetPolicy.REMINDER_LEAD_MS
        val postponedUntil = SibionicsRegistry.loadResetPostponedUntilMs(context, SerialNumber)
        val lastReminderAt = SibionicsRegistry.loadResetReminderAtMs(context, SerialNumber)
        val candidates = buildList {
            if (reminderAt > now) add(reminderAt)
            if (dueAt > now) add(dueAt)
            if (postponedUntil > now) add(postponedUntil)
            if (lastReminderAt > 0L && lastReminderAt + SibionicsResetPolicy.POSTPONE_MS > now) {
                add(lastReminderAt + SibionicsResetPolicy.POSTPONE_MS)
            }
            if (hardDeadline > now) add(hardDeadline)
            if (now >= reminderAt && now < hardDeadline) add(now + RESET_COMFORT_RECHECK_MS)
            if (now >= reminderAt && lastReminderAt <= 0L && postponedUntil <= now) add(now + 1_000L)
        }
        val next = candidates.minOrNull() ?: now + 1_000L
        handler.postDelayed(resetMaintenanceRunnable, (next - now).coerceAtLeast(1_000L))
    }

    override fun clearSensorCalibration(): Boolean {
        scheduleAlgorithmRebuild("manual local rebuild", delayMs = 0L)
        return true
    }

    override fun getManagedCurrentSnapshot(maxAgeMillis: Long): ManagedSensorCurrentSnapshot? {
        val time = latestReadingTimeMs
        val glucose = latestGlucoseMgdl
        if (time <= 0L || !glucose.isFinite() || glucose <= 0f) return null
        if (abs(System.currentTimeMillis() - time) > maxAgeMillis) return null
        val raw = latestRawMgdl
        val displayGlucose = toDisplay(glucose)
        val displayRaw = if (raw.isFinite() && raw > 0f) toDisplay(raw) else Float.NaN
        val displayRate = if (latestRateMgdlPerMin.isFinite()) {
            if (Applic.unit == 1) latestRateMgdlPerMin / SibionicsConstants.MGDL_PER_MMOLL else latestRateMgdlPerMin
        } else {
            Float.NaN
        }
        return ManagedSensorCurrentSnapshot(
            timeMillis = time,
            glucoseValue = displayGlucose,
            rawGlucoseValue = displayRaw,
            calibratedGlucoseValue = Float.NaN,
            rate = displayRate,
            sensorGen = SibionicsConstants.SENSOR_GEN,
        )
    }

    override fun getManagedUiSnapshot(activeSensorId: String?): ManagedSensorUiSnapshot =
        ManagedSensorUiSnapshot(
            serial = SerialNumber,
            displayName = mygetDeviceName(),
            deviceAddress = mActiveDeviceAddress ?: record?.address ?: "Unknown",
            uiFamily = ManagedSensorUiFamily.SIBIONICS,
            connectionStatus = if (phase == Phase.STREAMING) connectedStatus() else constatstatusstr.orEmpty(),
            detailedStatus = getDetailedBleStatus(),
            subtitleStatus = getDetailedBleStatus().ifBlank { constatstatusstr.orEmpty() },
            showConnectionStatusInDetails = true,
            startTimeMs = startTimeMs,
            officialEndMs = officialEndMs(),
            expectedEndMs = expectedEndMs(),
            isUiEnabled = !stop,
            isActive = SensorIdentity.matches(activeSensorId, SerialNumber),
            rssi = readrssi,
            dataptr = 0L,
            viewMode = viewMode,
            autoResetDays = autoResetDays,
            customAlgorithmEnabled = algorithmSelection.customModelEnabled,
            customAlgorithmMode = algorithmSelection.storageId,
            algorithmSensitivity = sensitivity,
            automaticAlgorithmSensitivity = automaticSensitivity,
            hasAlgorithmSensitivityOverride = sensitivityOverride != null,
            supportsDisplayModes = supportsDisplayModes(),
            supportsManualCalibration = supportsManualCalibration(),
            supportsHardwareReset = supportsResetAction(),
            supportsClearCalibration = supportsClearCalibrationAction(),
            sensorDetailTelemetry = detailTelemetry(),
            isVendorPaired = true,
            isVendorConnected = mBluetoothGatt != null && phase == Phase.STREAMING,
            sensorRemainingHours = remainingHours(),
            sensorAgeHours = ageHours(),
            batteryPercent = lastBatteryPercent,
            vendorModel = variant.displayLabel,
//            vendorFirmware = SibionicsConstants.initialProtocolMode(variant, protocolMode).name,
        )

    override fun getDetailedBleStatus(): String {
        if (uiPaused || stop) return disconnectedStatus()
        return when (phase) {
            Phase.IDLE -> disconnectedStatus()
            Phase.CONNECTING -> connectingStatus()
            Phase.DISCOVERING -> "Discovering"
            Phase.ENABLING_NOTIFY -> "Subscribing"
            Phase.PROBING_CHINESE -> "Probing"
            Phase.AUTHENTICATING -> "Authenticating"
            Phase.ACTIVATING -> "Activating"
            Phase.SYNCING_TIME -> "Syncing time"
            Phase.REQUESTING_DATA -> "Requesting data"
            Phase.STREAMING -> when {
                lastLiveIndexSeen >= 0 -> connectedStatus()
                SibionicsSessionPolicy.shouldShowHistoryProgress(
                    historyReceivedCount,
                    historyTotalCount,
                    hasReceivedLiveReading = false,
                ) -> historyProgressStatus()
                latestReadingTimeMs > 0L -> connectedStatus()
                else -> waitingForDataStatus()
            }
            Phase.ERROR -> constatstatusstr.orEmpty().ifBlank { "Error" }
        }
    }

    override fun requestBatteryRefresh(): Boolean {
        if (stop || uiPaused || phase != Phase.STREAMING || batteryLevelChar == null) return false
        if (batteryReadPurpose != null || manualBatteryRefreshQueued) return false
        manualBatteryRefreshQueued = true
        handler.post {
            manualBatteryRefreshQueued = false
            if (!stop && !uiPaused && phase == Phase.STREAMING) {
                if (!batteryStabilizationComplete) {
                    handler.removeCallbacks(batteryStabilizationRunnable)
                    batteryStabilizationScheduled = false
                }
                requestBatteryRead(
                    if (batteryStabilizationComplete) {
                        BatteryReadPurpose.MANUAL
                    } else {
                        BatteryReadPurpose.STABILIZATION
                    },
                )
            }
        }
        return true
    }

    private fun detailTelemetry(): String =
        buildString {
//            append(" • ")
//            append(variant.id)
//            append(" • idx=")
//            append(lastIndex)

            if (shortCode.isNotBlank()) {
                append(
                    when {
                        sensitivityOverride != null -> "customSens="
                        SibionicsSensitivity.tryDecode(shortCode) != null -> "qrSens="
                        else -> "fallbackSens="
                    },
                )
                append("%.2f".format(sensitivity))
            }
            if (latestTemperatureC.isFinite()) {
                append(" • ")
                append("temp=")
                append("%.1f".format(latestTemperatureC))
            }
            if (shortCode.isNotBlank()) {
                append(" ")
                append(shortCode)
            }
            append(" • ")
            append(protocolMode.name)

        }

    private fun officialEndMs(): Long =
        if (startTimeMs > 0L) startTimeMs + variant.officialLifetimeMs else 0L

    private fun expectedEndMs(): Long {
        if (startTimeMs <= 0L) return 0L
        return startTimeMs + variant.expectedLifetimeMs
    }

    private fun remainingHours(): Int {
        val end = expectedEndMs()
        if (end <= 0L) return -1
        val remaining = end - System.currentTimeMillis()
        return if (remaining <= 0L) 0 else (remaining / 3_600_000L).toInt()
    }

    private fun ageHours(): Int =
        if (startTimeMs <= 0L) -1 else ((System.currentTimeMillis() - startTimeMs) / 3_600_000L).toInt()

    private fun toDisplay(mgdl: Float): Float =
        if (Applic.unit == 1) mgdl / SibionicsConstants.MGDL_PER_MMOLL else mgdl

    private fun connectedStatus(): String =
        runCatching { Applic.app.getString(R.string.status_connected) }.getOrDefault("Connected")

    private fun disconnectedStatus(): String =
        runCatching { Applic.app.getString(R.string.status_disconnected) }.getOrDefault("Disconnected")

    private fun connectingStatus(): String =
        runCatching { Applic.app.getString(R.string.connecting) }.getOrDefault("Connecting...")

    private fun waitingForDataStatus(): String =
        runCatching { Applic.app.getString(R.string.status_waiting_for_data) }.getOrDefault("Connected, waiting for data...")

    private fun historyProgressStatus(): String {
        val label = runCatching { Applic.app.getString(R.string.historyname) }.getOrDefault("History")
        return "$label ${historyReceivedCount.coerceAtLeast(0)}/${historyTotalCount.coerceAtLeast(historyReceivedCount)}"
    }

    private fun setStatus(status: String) {
        constatstatusstr = status
        UiRefreshBus.requestStatusRefresh()
    }

    private fun knownBleAddress(): String? =
        SibionicsConstants.normalizeBleAddress(mActiveDeviceAddress)
            ?: SibionicsConstants.normalizeBleAddress(record?.address)
            ?: SibionicsConstants.normalizeBleAddress(SibionicsRegistry.findRecord(Applic.app, SerialNumber)?.address)

    private fun hydrateBluetoothDeviceFromAddress(): Boolean {
        val address = knownBleAddress() ?: return false
        mActiveDeviceAddress = address
        if (mActiveBluetoothDevice?.address?.equals(address, ignoreCase = true) == true) return true
        val adapter = runCatching {
            (Applic.app?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull() ?: return false
        mActiveBluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        return mActiveBluetoothDevice != null
    }

    private fun beginAdvertisementRecovery(
        reason: String,
        failedDuringConnect: Boolean = true,
    ): Boolean {
        val address = knownBleAddress()
        if (!SibionicsSessionPolicy.shouldUseAdvertisementRecovery(
                failedDuringConnect = failedDuringConnect,
                isStopped = stop,
                isPaused = uiPaused,
                hasKnownAddress = address != null,
                recoveryAlreadyActive = advertisementRecovery.isActive,
            )
        ) {
            return advertisementRecovery.isActive
        }
        handler.removeCallbacks(reconnectRunnable)
        phase = Phase.IDLE
        setStatus(connectingStatus())
        Log.i(SibionicsConstants.TAG, "scan for saved sensor after $reason serial=$SerialNumber")
        return advertisementRecovery.start(
            address = address!!,
            timeoutMs = ADVERTISEMENT_RECOVERY_TIMEOUT_MS,
        )
    }

    private fun finishAdvertisementRecovery(advertisement: SibionicsAdvertisement?) {
        if (stop || uiPaused) return
        if (advertisement == null) {
            Log.w(SibionicsConstants.TAG, "saved sensor advertisement not seen serial=$SerialNumber")
            phase = Phase.IDLE
            scheduleReconnect("advertisement recovery timeout")
            return
        }
        pendingMatchedBleName = SibionicsConstants.normalizeBleName(advertisement.name)
        setDevice(advertisement.device)
        phase = Phase.IDLE
        Log.i(
            SibionicsConstants.TAG,
            "saved sensor advertisement recovered name=${pendingMatchedBleName.ifBlank { "unknown" }} serial=$SerialNumber",
        )
        connectDevice(0)
    }

    private fun scheduleReconnect(reason: String, delayMs: Long = RECONNECT_DELAY_MS) {
        if (stop || uiPaused) return
        Log.i(SibionicsConstants.TAG, "schedule reconnect in ${delayMs}ms: $reason")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun armConnectCallbackTimeout(requestedDelayMs: Long) {
        handler.removeCallbacks(connectCallbackTimeoutRunnable)
        handler.postDelayed(
            connectCallbackTimeoutRunnable,
            SibionicsSessionPolicy.connectCallbackTimeoutDelayMs(
                requestedDelayMs = requestedDelayMs,
                callbackTimeoutMs = CONNECT_CALLBACK_TIMEOUT_MS,
            ),
        )
    }

    private fun armSetupStageTimeout() {
        handler.removeCallbacks(setupStageTimeoutRunnable)
        handler.postDelayed(setupStageTimeoutRunnable, SETUP_STAGE_TIMEOUT_MS)
    }

    private fun settleConnectionPriority() {
        if (connectionPrioritySettled) return
        connectionPrioritySettled = true
        val connectedGatt = mBluetoothGatt ?: return
        handler.postDelayed({
            if (!stop && phase == Phase.STREAMING && mBluetoothGatt === connectedGatt) {
                runCatching {
                    connectedGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                }
                Log.d(SibionicsConstants.TAG, "settled BLE priority after streaming serial=$SerialNumber")
            }
        }, 2_000L)
    }

    private val reconnectRunnable = Runnable {
        if (!stop && !uiPaused) {
            prepareForReconnect()
            connectDevice(0)
        }
    }

    @Synchronized
    private fun prepareForReconnect() {
        notificationDispatcher.invalidateSession()
        handler.removeCallbacks(connectCallbackTimeoutRunnable)
        handler.removeCallbacks(setupStageTimeoutRunnable)
        handler.removeCallbacks(authTimeoutRunnable)
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.removeCallbacks(chineseProbeTimeoutRunnable)
        handler.removeCallbacks(chineseDataTimeoutRunnable)
        handler.removeCallbacks(streamingTimeoutRunnable)
        handler.removeCallbacks(chinesePollRunnable)
        val staleGatt = mBluetoothGatt
        service = null
        notifyChar = null
        writeChar = null
        batteryLevelChar = null
        resetBatterySession()
        mActiveBluetoothDevice = null
        phase = Phase.IDLE
        retiredGatt = staleGatt
        // Also cancels a delayed SuperGattCallback connection task. Closing only
        // the visible GATT can otherwise leave connectPending=true forever.
        closeGattTransport()
    }

    private fun scheduleAuthTimeout() {
        handler.removeCallbacks(authTimeoutRunnable)
        handler.postDelayed(authTimeoutRunnable, CHINESE_PROBE_TIMEOUT_MS)
    }

    private fun scheduleHandshakeTimeout() {
        handler.removeCallbacks(handshakeTimeoutRunnable)
        handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS)
    }

    private val handshakeTimeoutRunnable = Runnable {
        if (phase == Phase.ACTIVATING || phase == Phase.SYNCING_TIME || phase == Phase.REQUESTING_DATA) {
            setStatus("Handshake timeout")
            sendAuthPacket()
        }
    }

    private fun scheduleChineseProbeTimeout() {
        handler.removeCallbacks(chineseProbeTimeoutRunnable)
        handler.postDelayed(chineseProbeTimeoutRunnable, CHINESE_PROBE_TIMEOUT_MS)
    }

    private fun scheduleChineseDataTimeout() {
        handler.removeCallbacks(chineseDataTimeoutRunnable)
        handler.postDelayed(chineseDataTimeoutRunnable, CHINESE_DATA_TIMEOUT_MS)
    }

    private val chineseProbeTimeoutRunnable = Runnable {
        if (phase == Phase.PROBING_CHINESE) switchToV120("Chinese probe timeout")
    }

    private val chineseDataTimeoutRunnable = Runnable {
        if (protocolMode == SibionicsConstants.ProtocolMode.CHINESE) {
            sendChineseDataRequest(probing = false)
            scheduleChineseDataTimeout()
        }
    }

    private fun scheduleChinesePoll(delayMs: Long = CHINESE_POLL_INTERVAL_MS) {
        handler.removeCallbacks(chinesePollRunnable)
        handler.postDelayed(chinesePollRunnable, delayMs)
    }

    private val chinesePollRunnable = Runnable {
        if (!stop && protocolMode == SibionicsConstants.ProtocolMode.CHINESE) {
            if (batteryReadPurpose != null) {
                scheduleChinesePoll(delayMs = 1_000L)
            } else {
                sendChineseDataRequest(probing = false)
                scheduleChinesePoll()
            }
        }
    }

    private fun scheduleStreamingTimeout() {
        handler.removeCallbacks(streamingTimeoutRunnable)
        handler.postDelayed(streamingTimeoutRunnable, STREAMING_TIMEOUT_MS)
    }

    private val streamingTimeoutRunnable = Runnable {
        if (phase == Phase.STREAMING && System.currentTimeMillis() - latestReadingTimeMs > CURRENT_FRESH_MS) {
            setStatus(waitingForDataStatus())
            scheduleReconnect("streaming timeout")
        }
    }
}
