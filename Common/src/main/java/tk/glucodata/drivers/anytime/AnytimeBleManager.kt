// AnytimeBleManager.kt — BLE manager for Anytime / Yuwell CT3 transmitters.
//
// Lifecycle (CT3, the simplest case):
//
//   1. connectDevice → GATT connect
//   2. STATE_CONNECTED → request MTU 211, then discoverServices
//   3. onServicesDiscovered → find service (legacy 0xFFF0 or proprietary 0x1000)
//                            and enable notifications on the notify char (CCCD)
//   4. onDescriptorWrite:
//        if persisted bound flag is set: write reset
//        else if family ∈ CT3_PLUS / CT3_YUWELL / CT3_ULTRASONIC / CT4:
//             write transmitterFormal — handles voltage switch
//             CT4 + QR voltage mode 1 follows the official app and skips
//             modifyVoltage entirely, going straight to check
//        else: write check
//   5. RX 0x05 (check) → batt + IW + age check → write {0x03,...} setDate
//   6. RX 0x03/0x04 (setDate ack) → write {0x06} init
//   7. RX 0x06 (init ack) → mark bound, write {0x0F} lowPower, schedule pull
//   8. Steady state:
//        TX pushes {0x07, ...} N raw records each cadence → run algorithm
//        Phone may write {0x08, idLo, idHi} to backfill any missed ids
//   9. On disconnect: ReconnectManagement equivalent — exponential backoff
//
// Calibration:
//   - QR scan → AnytimeAlgorithm.decodeQr → store K/R; push {0x0B,K,R} only
//     for factory calibration QRs, not GS1/UDI package labels.
//   - Fingerstick {0x09, mmolInt, mmolFrac/10}
//
// Reset / unbind:
//   - {0x11} reset_request — answered with bind state; we do not drop GATT
//   - {0x0A} unbind_request — full session teardown

package tk.glucodata.drivers.anytime

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.security.SecureRandom
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorViewModeStore
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

@SuppressLint("MissingPermission")
class AnytimeBleManager(
    serial: String,
    dataptr: Long,
) : SuperGattCallback(serial, dataptr, SENSOR_GEN), AnytimeDriver {

    companion object {
        private const val TAG = AnytimeConstants.TAG

        /** SuperGattCallback generation tag. Same as MQ/iCan/AiDex. */
        const val SENSOR_GEN = 0

        private const val ACTIVE_SESSION_RECONNECT_DELAY_MS = 2_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val SERVICE_DISCOVERY_HARD_RECOVERY_DELAY_MS = 5_000L
        private const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 1_500L
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2

        /** No-data watchdog multiplier applied to readingIntervalMinutes. */
        private const val NO_DATA_WATCHDOG_MULTIPLIER = 4L

        /** Watchdog after init — if no push/pull within 3.5× readingInterval, re-pull. */
        private const val PULL_FALLBACK_MULTIPLIER = 3L

        /** Backfill loop: gap between consecutive pulls when records keep arriving. */
        private const val HISTORY_PULL_BATCH_DELAY_MS = 10L

        /** Official CT2.5/CT3A/CT4 0x22 history request batch size. */
        private const val HISTORY_PULL_SERIES_COUNT = 15

        /** Do not let a lost streaming-history response wedge the backfill loop forever. */
        private const val HISTORY_PULL_TIMEOUT_MS = 8_000L

        /** How many empty pull responses in a row count as "caught up". */
        private const val HISTORY_EMPTY_RESPONSES_TO_STOP = 2

        /** Reset → reconnect grace period. */
        private const val RESET_RECONNECT_DELAY_MS = 700L

        /** Check / setDate / init each get a per-frame timeout. */
        private const val PROTOCOL_FRAME_TIMEOUT_MS = 8_000L

        /** Android may deliver CCCD callbacks late after Bluetooth toggles; do not write while descriptor op is still pending. */
        private const val CCCD_WRITE_TIMEOUT_MS = 4_000L

        /** Bound for manually recovering a half-open GATT that never reported STATE_DISCONNECTED. */
        private const val STALE_GATT_RECOVERY_MS = 20_000L

        /** Legacy Yuwell JNI returns 0 until it has enough warmup history. */
        private const val NATIVE_HISTORY_WARMUP_RECORDS = 20

        /** Keep the last calibration result visible in the Sensor card long enough to notice. */
        private const val CALIBRATION_STATUS_TTL_MS = 60L * 60L * 1000L
        private const val MAX_REFERENCE_CALIBRATION_RECORDS = 12

        /**
         * Fresh installs should become useful quickly, then continue filling older
         * history in the background. Full-prefix backfill can still be several
         * hundred records, so recent tail stays first even with batched pulls.
         */
        private const val FRESH_AUTO_BACKFILL_RECORDS = 24

        /** Small pause between the quick recent tail and the older full-prefix fill. */
        private const val FRESH_OLDER_BACKFILL_DELAY_MS = 2_000L

        /** Some CT4 units do not ACK init; do not wait for the next 3-minute push if we can resume from cache. */
        private const val INIT_NO_ACK_STREAMING_GRACE_MS = 650L

        /** Tiny private cache for UI-only telemetry that is not part of the raw algorithm state. */
        private const val TELEMETRY_PREFS = "anytime_ble_telemetry"
        private const val TELEMETRY_BATTERY_VOLTS_PREFIX = "battery_volts_"

        /** Refresh check-frame telemetry after reconnect settles, then hourly. */
        private const val TELEMETRY_CHECK_INTERVAL_MS = 60L * 60L * 1000L
        private const val TELEMETRY_CHECK_RETRY_DELAY_MS = 60_000L
        private const val TELEMETRY_CHECK_QUIET_MS = 10_000L
        private const val TELEMETRY_CHECK_AFTER_RECONNECT_LIVE_PUSHES = 2

        /** Check-frame age counter is seconds for CT4/Anytime v3 traces. */
        private const val SENSOR_AGE_COUNTER_TO_MS = 1_000L

        /** Values exposed through AnytimeCurrentSnapshot are mmol/L, while native history uses mg/dL. */
        private const val MGDL_TO_MMOLL = 1f / 18f
    }

    enum class Phase { IDLE, CONNECTING, DISCOVERING, HANDSHAKING, STREAMING }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    private val handlerThread = HandlerThread("Anytime-$serial").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    private var primaryService: BluetoothGattService? = null
    private var charNotify: BluetoothGattCharacteristic? = null
    private var charWrite: BluetoothGattCharacteristic? = null
    private var primaryServiceUuid: UUID = AnytimeConstants.SERVICE_PRIMARY

    @Volatile private var familyEntry: AnytimeConstants.FamilyEntry = AnytimeConstants.FAMILY_UNKNOWN
    @Volatile private var profile: AnytimeProfile = AnytimeProfileResolver.resolve()
    @Volatile private var qr: AnytimeQrCalibration? = null
    @Volatile private var voltageFlag: Int = 0
    @Volatile private var transmitterVersion: String = ""

    @Volatile private var lastGlucoseId: Int = -1
    @Volatile private var lastGlucoseAtMs: Long = 0L
    @Volatile private var lastGlucoseMgdlTimes10: Int = 0
    @Volatile private var lastRawMgdl: Float = Float.NaN
    @Volatile private var sensorStartAtMs: Long = 0L
    @Volatile private var glucoseTimelineStartAtMs: Long = 0L
    @Volatile private var warmupStartedAtMs: Long = 0L
    @Volatile private var lastBatteryVolts: Float = 0f
    @Volatile private var lastIwNa: Float = 0f
    @Volatile private var lastIbNa: Float = 0f
    @Volatile private var lastTemperatureC: Float = 0f
    /** Most recent algorithm output — for diagnostics (5 electrode voltages,
     *  IIR-filtered currents, sensitivity coefficient, K_BASE/K_AUTO). */
    @Volatile private var lastAlgorithmResult: AnytimeAlgorithm.Result? = null
    @Volatile private var lastReferenceBgMgdlTimes10: Int = 0
    @Volatile private var lastReferenceBgGlucoseId: Int = 0
    @Volatile private var lastReferenceAppliedGlucoseId: Int = 0
    @Volatile private var referenceCalibrationRecords: List<AnytimeReferenceCalibrationRecord> = emptyList()
    @Volatile private var calibrationStatusText: String = ""
    @Volatile private var calibrationStatusAtMs: Long = 0L
    @Volatile private var calibrationStatusClearAfterGlucoseId: Int = 0
    @Volatile private var lastAlgorithmCalibrationStatus: Int =
        AnytimeCalibrationPolicy.CALIBRATION_STATUS_UNKNOWN
    @Volatile private var packetsSinceInit: Int = 0
    @Volatile private var bound: Boolean = false
    @Volatile private var reconnectReason: String = ""
    @Volatile private var serviceDiscoveryHandled: Boolean = false
    @Volatile private var serviceDiscoveryRetryCount: Int = 0
    @Volatile private var serviceDiscoveryRequestInFlight: Boolean = false
    @Volatile private var pendingCccdGatt: BluetoothGatt? = null
    @Volatile private var lastConnectRequestAtMs: Long = 0L
    @Volatile private var pendingFingerstickMgdl: Int = -1
    @Volatile private var pendingFingerstickTargetGlucoseId: Int = -1
    @Volatile private var pendingKrPush: Boolean = false
    @Volatile private var lastProtocolFrameAtMs: Long = 0L
    @Volatile private var lastProtocolFrameTag: String = ""
    @Volatile private var ct4HandshakeFallbackStep: Int = 0
    @Volatile private var postVoltagePlainControlFrames: Boolean = false
    @Volatile private var ct5CipherKey: Int = -1
    @Volatile private var ct5RandomA: IntArray? = null
    @Volatile private var ct5RandomB: IntArray? = null
    @Volatile private var ct5TempId: String = ""
    @Volatile private var ct5VoltagePayload: Boolean = false
    @Volatile private var ct5ReconnectDateAfterIdentity: Boolean = false
    private val ct5Random = SecureRandom()
    // Native Yuwell algorithm inputs have no start-id field; the raw arrays must
    // represent glucose ids from 0..current. Keep the full session history.
    private val rawAlgorithmWindow = java.util.TreeMap<Int, AnytimeRawRecord>()
    private val pendingNativeRecomputeIds = java.util.TreeSet<Int>()

    /** True only when this process restored an existing Anytime session cache. */
    @Volatile private var restoredGlucoseState: Boolean = false

    /** Native/current.dat already existed even if AnytimeRegistry was wiped by delete/re-add. */
    @Volatile private var nativeBackingExistedAtRestore: Boolean = false

    /** Fresh sessions first get a short recent tail, then the older prefix in background. */
    @Volatile private var freshPostLiveBackfillStarted: Boolean = false
    @Volatile private var freshOlderBackfillStarted: Boolean = false
    @Volatile private var pendingFreshOlderBackfillStartId: Int = -1

    // ---- History backfill loop state ----
    @Volatile private var historyBackfillActive: Boolean = false
    @Volatile private var historyEmptyResponsesInARow: Int = 0
    @Volatile private var historyLastPulledId: Int = -1
    @Volatile private var historyPullInFlight: Boolean = false
    @Volatile private var historyPullInFlightWasLegacySeries: Boolean = false
    @Volatile private var legacySeriesHistorySupported: Boolean = true
    @Volatile private var historyStopBeforeId: Int = Int.MAX_VALUE
    @Volatile private var historyBackfillReason: String = ""
    @Volatile private var historyBackfillStartedAfterGlucoseId: Int = -1

    // If a long one-by-one history pull is interrupted by BLE loss, keep the
    // range and resume it after the next successful handshake/reset instead of
    // silently abandoning the backfill.
    @Volatile private var interruptedBackfillReason: String = ""
    @Volatile private var interruptedBackfillFromId: Int = -1
    @Volatile private var interruptedBackfillStopBeforeId: Int = Int.MAX_VALUE

    /** Count down live pushes after bound reconnect before probing battery telemetry. */
    @Volatile private var telemetryLivePushesUntilCheck: Int = -1

    @Volatile private var viewModeValue: Int = restoreInitialViewMode(serial, dataptr)

    override var viewMode: Int
        get() = viewModeValue
        set(value) {
            val normalized = ManagedSensorViewModeStore.sanitize(value)
            viewModeValue = normalized
            ManagedSensorViewModeStore.write(Applic.app, SerialNumber, normalized)
            applyViewModeToNative(normalized)
        }

    // ---- Restore from persistence ----

    fun restoreFromPersistence(context: Context) {
        val id = SerialNumber ?: return
        nativeBackingExistedAtRestore = hasExistingNativeBacking(context, id)
        viewMode = ManagedSensorViewModeStore.read(context, id, viewModeValue)
        val cachedDeviceName = AnytimeRegistry.loadDeviceName(context, id)
        familyEntry = AnytimeProfileResolver.familyEntry(cachedDeviceName)
        profile = AnytimeProfileResolver.resolve(cachedDeviceName)
        val k = AnytimeRegistry.loadKValue(context, id)
        val r = AnytimeRegistry.loadRValue(context, id)
        val rawQr = AnytimeRegistry.loadQrContent(context, id)
        if (rawQr.isNotBlank()) {
            qr = AnytimeAlgorithm.decodeQr(rawQr) ?: qr ?: synthesiseQr(rawQr, k, r)
        } else if (k > 0f || r > 0f) {
            qr = synthesiseQr("", k, r)
        }
        voltageFlag = AnytimeRegistry.loadVoltageFlag(context, id)
        transmitterVersion = AnytimeRegistry.loadTransmitterVersion(context, id)
        lastGlucoseId = AnytimeRegistry.loadLastGlucoseId(context, id)
        sensorStartAtMs = AnytimeRegistry.loadSensorStartAt(context, id)
        warmupStartedAtMs = AnytimeRegistry.loadWarmupStartedAt(context, id)
        bound = AnytimeRegistry.loadBound(context, id)
        lastReferenceBgMgdlTimes10 = AnytimeRegistry.loadReferenceBgMgdlTimes10(context, id)
        lastReferenceBgGlucoseId = AnytimeRegistry.loadReferenceBgGlucoseId(context, id)
        lastReferenceAppliedGlucoseId = AnytimeRegistry.loadReferenceBgAppliedGlucoseId(context, id)
        referenceCalibrationRecords = restoreReferenceCalibrationRecords(context, id)
        ct5CipherKey = AnytimeRegistry.loadCt5CipherKey(context, id)
        ct5RandomB = AnytimeRegistry.loadCt5RandomB(context, id)
        ct5TempId = AnytimeRegistry.loadCt5TempId(context, id)
        val rawHistory = AnytimeRegistry.loadRawHistory(context, id)
        synchronized(rawAlgorithmWindow) {
            rawAlgorithmWindow.clear()
            rawHistory.forEach { rawAlgorithmWindow[it.glucoseId] = it }
        }
        restoredGlucoseState = lastGlucoseId >= 0 || rawHistory.isNotEmpty()
        freshPostLiveBackfillStarted = false
        freshOlderBackfillStarted = false
        pendingFreshOlderBackfillStartId = -1
        lastBatteryVolts = loadCachedBatteryVolts(context, id)
    }

    private fun hasExistingNativeBacking(context: Context?, sensorId: String): Boolean {
        if (context == null || sensorId.isBlank()) return false
        val current = java.io.File(java.io.File(context.filesDir, "sensors/$sensorId"), "current.dat")
        return current.exists() && current.length() > 0L
    }

    private fun restoreInitialViewMode(sensorId: String, nativePtr: Long): Int {
        val nativeMode = if (nativePtr != 0L) {
            runCatching { Natives.getViewMode(nativePtr) }.getOrDefault(0)
        } else {
            0
        }
        return ManagedSensorViewModeStore.read(Applic.app, sensorId, nativeMode)
    }

    private fun applyViewModeToNative(mode: Int) {
        if (dataptr == 0L) return
        runCatching { Natives.setViewMode(dataptr, mode) }
            .onFailure { Log.stack(TAG, "applyViewModeToNative", it) }
    }

    private fun loadCachedBatteryVolts(context: Context?, sensorId: String): Float {
        if (context == null) return 0f
        return context
            .getSharedPreferences(TELEMETRY_PREFS, Context.MODE_PRIVATE)
            .getFloat(TELEMETRY_BATTERY_VOLTS_PREFIX + sensorId, 0f)
    }

    private fun saveCachedBatteryVolts(context: Context?, sensorId: String?, volts: Float) {
        if (context == null || sensorId.isNullOrBlank() || volts <= 0f) return
        context
            .getSharedPreferences(TELEMETRY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(TELEMETRY_BATTERY_VOLTS_PREFIX + sensorId, volts)
            .apply()
    }

    private fun synthesiseQr(raw: String, k: Float, r: Float): AnytimeQrCalibration =
        AnytimeQrCalibration(
            rawQr = raw,
            format = AnytimeQrCalibration.Format.B,
            k = if (k > 0f) k else 0.30f,
            r = if (r > 0f) r else 50f,
            lifeTime = AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS,
            productMonth = 0,
            productYear = 0,
            electrodeType = "",
            electrodeTecNo = "",
            enzymeTecNo = "",
            membraneTecNo = "",
            marketNo = "",
            serialNo = "",
            sensorNo = "",
            unitOrder = 0,
            voltageFlag = voltageFlag,
            calibrationCount = 0,
        )

    private fun restoreReferenceCalibrationRecords(
        context: Context,
        sensorId: String,
    ): List<AnytimeReferenceCalibrationRecord> {
        val persisted = AnytimeRegistry.loadReferenceBgHistory(context, sensorId)
        if (persisted.isNotEmpty()) {
            val latestApplied = persisted
                .firstOrNull { it.targetGlucoseId == lastReferenceBgGlucoseId }
                ?.appliedGlucoseId
                ?: 0
            if (latestApplied > lastReferenceAppliedGlucoseId) {
                lastReferenceAppliedGlucoseId = latestApplied
            }
            return persisted
        }
        if (lastReferenceBgMgdlTimes10 <= 0 || lastReferenceBgGlucoseId <= 0) return emptyList()
        return listOf(
            AnytimeReferenceCalibrationRecord(
                targetGlucoseId = lastReferenceBgGlucoseId,
                referenceMgdlTimes10 = lastReferenceBgMgdlTimes10,
                acceptedAtMs = 0L,
                appliedGlucoseId = lastReferenceAppliedGlucoseId,
            )
        )
    }

    private fun upsertReferenceCalibrationRecord(record: AnytimeReferenceCalibrationRecord) {
        if (record.targetGlucoseId <= 0 || record.referenceMgdlTimes10 <= 0) return
        referenceCalibrationRecords = (listOf(record) + referenceCalibrationRecords)
            .distinctBy { it.targetGlucoseId }
            .sortedWith(compareByDescending<AnytimeReferenceCalibrationRecord> { it.acceptedAtMs }
                .thenByDescending { it.targetGlucoseId })
            .take(MAX_REFERENCE_CALIBRATION_RECORDS)
    }

    private fun markReferenceCalibrationApplied(
        targetId: Int,
        sampleMs: Long,
        outputMgdlTimes10: Int,
    ) {
        if (targetId <= 0 || lastReferenceBgMgdlTimes10 <= 0) return
        var matched = false
        referenceCalibrationRecords = referenceCalibrationRecords.map { record ->
            if (record.targetGlucoseId == targetId) {
                matched = true
                record.copy(
                    appliedGlucoseId = targetId,
                    appliedAtMs = sampleMs,
                    outputMgdlTimes10 = outputMgdlTimes10,
                )
            } else {
                record
            }
        }
        if (!matched) {
            upsertReferenceCalibrationRecord(
                AnytimeReferenceCalibrationRecord(
                    targetGlucoseId = targetId,
                    referenceMgdlTimes10 = lastReferenceBgMgdlTimes10,
                    acceptedAtMs = 0L,
                    appliedGlucoseId = targetId,
                    appliedAtMs = sampleMs,
                    outputMgdlTimes10 = outputMgdlTimes10,
                )
            )
        }
    }

    private fun persistAlgorithmState() {
        val ctx = Applic.app ?: return
        val id = SerialNumber ?: return
        qr?.let {
            AnytimeRegistry.saveQrContent(ctx, id, it.rawQr)
            AnytimeRegistry.saveKValue(ctx, id, it.k)
            AnytimeRegistry.saveRValue(ctx, id, it.r)
            AnytimeRegistry.saveLifetimeDays(ctx, id, it.lifeTime)
        }
        AnytimeRegistry.saveVoltageFlag(ctx, id, voltageFlag)
        AnytimeRegistry.saveTransmitterVersion(ctx, id, transmitterVersion)
        AnytimeRegistry.saveBound(ctx, id, bound)
        AnytimeRegistry.saveLastGlucoseId(ctx, id, lastGlucoseId)
        AnytimeRegistry.saveSensorStartAt(ctx, id, sensorStartAtMs)
        AnytimeRegistry.saveWarmupStartedAt(ctx, id, warmupStartedAtMs)
        AnytimeRegistry.saveReferenceBgMgdlTimes10(ctx, id, lastReferenceBgMgdlTimes10)
        AnytimeRegistry.saveReferenceBgGlucoseId(ctx, id, lastReferenceBgGlucoseId)
        AnytimeRegistry.saveReferenceBgAppliedGlucoseId(ctx, id, lastReferenceAppliedGlucoseId)
        AnytimeRegistry.saveReferenceBgHistory(ctx, id, referenceCalibrationRecords)
        saveCachedBatteryVolts(ctx, id, lastBatteryVolts)
        AnytimeRegistry.saveRawHistory(ctx, id, synchronized(rawAlgorithmWindow) { rawAlgorithmWindow.values.toList() })
        AnytimeRegistry.saveCt5CipherKey(ctx, id, ct5CipherKey)
        AnytimeRegistry.saveCt5RandomB(ctx, id, ct5RandomB)
        AnytimeRegistry.saveCt5TempId(ctx, id, ct5TempId)
    }

    // ---- Reconnect / watchdog ----

    private val reconnectRunnable = Runnable {
        if (stop) return@Runnable
        Log.i(TAG, "Reconnect requested: $reconnectReason")
        connectDevice(0)
    }

    private val noDataWatchdog = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        val lastReadingMs = lastGlucoseAtMs
        if (lastReadingMs <= 0L) return@Runnable
        val elapsed = System.currentTimeMillis() - lastReadingMs
        if (elapsed < noDataWatchdogMs()) {
            armNoDataWatchdog()
            return@Runnable
        }
        Log.w(TAG, "No glucose for ${elapsed / 1000}s — forcing reconnect")
        recoverGattAndReconnect("no-data watchdog", ACTIVE_SESSION_RECONNECT_DELAY_MS)
    }

    private val pullFallbackRunnable = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        // If the transmitter went quiet, ask for the next id explicitly.
        if (lastGlucoseId >= 0) {
            writeFrame(pullGlucoseFrame(lastGlucoseId + 1), "pullGlucose(fallback)")
        }
        armPullFallback()
    }

    private val telemetryCheckRunnable = Runnable {
        if (stop || phase != Phase.STREAMING) return@Runnable
        if (historyBackfillActive || historyPullInFlight) {
            armTelemetryCheck(TELEMETRY_CHECK_RETRY_DELAY_MS)
            return@Runnable
        }
        val quietForMs = System.currentTimeMillis() - lastProtocolFrameAtMs
        if (lastProtocolFrameAtMs > 0L && quietForMs < TELEMETRY_CHECK_QUIET_MS) {
            armTelemetryCheck(TELEMETRY_CHECK_RETRY_DELAY_MS)
            return@Runnable
        }
        if (writeFrame(checkFrame(), "check(periodic-telemetry)", expectResponse = true)) {
            armTelemetryCheck(TELEMETRY_CHECK_INTERVAL_MS)
        } else {
            armTelemetryCheck(TELEMETRY_CHECK_RETRY_DELAY_MS)
        }
    }

    private fun armTelemetryCheck(delayMs: Long = TELEMETRY_CHECK_INTERVAL_MS) {
        handler.removeCallbacks(telemetryCheckRunnable)
        if (!stop) handler.postDelayed(telemetryCheckRunnable, delayMs)
    }

    private fun requestTelemetryAfterReconnectLivePushes() {
        telemetryLivePushesUntilCheck = TELEMETRY_CHECK_AFTER_RECONNECT_LIVE_PUSHES
        armTelemetryCheck(TELEMETRY_CHECK_INTERVAL_MS)
    }

    private fun maybeRunReconnectTelemetryAfterLivePush() {
        if (telemetryLivePushesUntilCheck <= 0) return
        telemetryLivePushesUntilCheck--
        if (telemetryLivePushesUntilCheck == 0) {
            telemetryLivePushesUntilCheck = -1
            armTelemetryCheck(1_000L)
        }
    }

    private val historyBackfillRunnable: Runnable = Runnable {
        if (stop || !historyBackfillActive) return@Runnable
        if (phase != Phase.STREAMING) return@Runnable
        if (historyPullInFlight) return@Runnable
        var nextId = nextBackfillIdSkippingCached((historyLastPulledId + 1).coerceAtLeast(0))
        if (nextId >= historyStopBeforeId) {
            Log.i(TAG, "Backfill range complete ($historyBackfillReason, nextId=$nextId stopBefore=$historyStopBeforeId)")
            finishHistoryBackfill()
            return@Runnable
        }
        if (nextId >= familyEntry.endNumber) {
            Log.i(TAG, "Backfill complete (lastId=$lastGlucoseId, endNumber=${familyEntry.endNumber})")
            finishHistoryBackfill()
            return@Runnable
        }
        historyLastPulledId = nextId - 1
        historyPullInFlight = true
        val count = historyPullCount(nextId)
        historyPullInFlightWasLegacySeries = count > 1 && !isCt5()
        Log.d(TAG, "Backfill pull next id=$nextId count=$count")
        armHistoryPullTimeout()
        if (!writeFrame(pullGlucoseFrame(nextId, count), "pullGlucose(backfill,count=$count)")) {
            clearHistoryPullTimeout()
            historyPullInFlight = false
            historyPullInFlightWasLegacySeries = false
            handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
        }
    }

    private val historyPullTimeoutRunnable = Runnable {
        if (stop || !historyBackfillActive || !historyPullInFlight) return@Runnable
        val retryId = (historyLastPulledId + 1).coerceAtLeast(0)
        Log.w(TAG, "History pull timeout at id=$retryId series=$historyPullInFlightWasLegacySeries")
        if (historyPullInFlightWasLegacySeries && legacySeriesHistorySupported) {
            legacySeriesHistorySupported = false
            Log.w(TAG, "Disabling 0x22 batched history for this session; falling back to 0x08 single-record pulls")
        }
        historyPullInFlight = false
        historyPullInFlightWasLegacySeries = false
        handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
    }

    private fun armHistoryPullTimeout() {
        handler.removeCallbacks(historyPullTimeoutRunnable)
        handler.postDelayed(historyPullTimeoutRunnable, HISTORY_PULL_TIMEOUT_MS)
    }

    private fun clearHistoryPullTimeout() {
        handler.removeCallbacks(historyPullTimeoutRunnable)
    }

    private val protocolFrameTimeoutRunnable = Runnable {
        if (stop) return@Runnable
        val tag = lastProtocolFrameTag
        val elapsed = System.currentTimeMillis() - lastProtocolFrameAtMs
        Log.w(TAG, "Protocol timeout after $tag (${elapsed}ms)")
        if (tag.startsWith("pullGlucose(backfill)")) {
            historyPullInFlight = false
            if (historyBackfillActive && phase == Phase.STREAMING && !stop) {
                handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
            }
            return@Runnable
        }
        if (tag.startsWith("setDate")) {
            Log.w(TAG, "setDate ACK timeout; keeping GATT alive")
            clearProtocolFrameTimeout()
            return@Runnable
        }
        if (tag.startsWith("init(after-setDate")) {
            Log.w(TAG, "init ACK timeout after best-effort setDate; keeping GATT alive and waiting for raw push")
            clearProtocolFrameTimeout()
            return@Runnable
        }
        if (phase != Phase.HANDSHAKING) return@Runnable
        if (familyEntry.family == AnytimeConstants.Family.CT4 && tryCt4HandshakeFallback()) {
            return@Runnable
        }
        recoverGattAndReconnect("protocol timeout after $tag", ACTIVE_SESSION_RECONNECT_DELAY_MS)
    }

    private fun nextBackfillIdSkippingCached(fromId: Int): Int {
        var id = fromId.coerceAtLeast(0)
        // Avoid re-requesting raw records already restored from AnytimeRegistry.
        // This is the main app-restart speed path: once a full/partial backfill
        // has been cached, reconnect resumes at the first real gap instead of
        // replaying id=0..N again.
        synchronized(rawAlgorithmWindow) {
            while (id < historyStopBeforeId && rawAlgorithmWindow.containsKey(id)) {
                id++
            }
        }
        return id
    }

    /**
     * Start (or resume) the history backfill loop.
     * Clean/manual backfill can explicitly start from 0. Reconnect backfill must
     * start from the first id after the newest id we have already seen, otherwise
     * we either replay the whole session or immediately stop on old empty frames.
     */
    private fun startHistoryBackfill(
        reason: String,
        fromId: Int,
        stopBeforeId: Int = Int.MAX_VALUE,
    ) {
        if (phase != Phase.STREAMING) return
        if (historyBackfillActive) {
            Log.d(TAG, "Backfill already active; ignoring $reason from id=$fromId")
            return
        }
        val startId = fromId.coerceAtLeast(0)
        val stopId = stopBeforeId.coerceAtLeast(startId)
        if (startId >= stopId) {
            Log.i(TAG, "Skipping empty backfill range ($reason, start=$startId stopBefore=$stopId)")
            maybeStartPendingFreshOlderBackfill()
            return
        }
        Log.i(TAG, "Starting history backfill ($reason) from id=$startId stopBefore=${if (stopId == Int.MAX_VALUE) "∞" else stopId.toString()}")
        historyBackfillActive = true
        historyBackfillReason = reason
        historyStopBeforeId = stopId
        historyBackfillStartedAfterGlucoseId = lastGlucoseId
        historyEmptyResponsesInARow = 0
        historyLastPulledId = startId - 1
        historyPullInFlight = false
        handler.postDelayed(historyBackfillRunnable, 250L)
    }

    private fun reconnectBackfillStartId(): Int {
        val nextId = (lastGlucoseId + 1).coerceAtLeast(0)
        if (lastGlucoseId <= 0) return 0
        val needsNativeHistory = qr?.isFactoryCalibration == true
        if (!needsNativeHistory) return nextId
        val firstMissing = firstMissingRawHistoryIdThrough(lastGlucoseId)
        if (firstMissing == null) return nextId
        Log.i(
            TAG,
            "Raw JNI history is incomplete after restore; seeding native history from id=$firstMissing " +
                    "(lastId=$lastGlucoseId cached=${synchronized(rawAlgorithmWindow) { rawAlgorithmWindow.size }})"
        )
        return firstMissing
    }

    private fun postInitBackfillStartId(): Int {
        if (!restoredGlucoseState) {
            val startId = (lastGlucoseId + 1).coerceAtLeast(0)
            if (startId > 0 && nativeBackingExistedAtRestore) {
                Log.i(TAG, "Existing native backing detected without AnytimeRegistry; post-init auto-backfill starts at id=$startId instead of replaying from zero")
                return startId
            }
            return 0
        }
        val startId = reconnectBackfillStartId()
        Log.i(TAG, "Restored Anytime state; post-init backfill will resume from id=$startId instead of replaying from zero")
        return startId
    }

    private fun hasUsableHistoryTimeline(): Boolean =
        glucoseTimelineStartAtMs > 0L || sensorStartAtMs > 0L

    private fun freshAutoBackfillStartId(anchorId: Int): Int =
        (anchorId - FRESH_AUTO_BACKFILL_RECORDS + 1).coerceAtLeast(0)

    private fun maybeStartFreshPostLiveBackfill(anchorId: Int) {
        if (restoredGlucoseState || freshPostLiveBackfillStarted || historyBackfillActive) return
        if (anchorId < 0 || !hasUsableHistoryTimeline()) return
        freshPostLiveBackfillStarted = true
        val recentStartId = freshAutoBackfillStartId(anchorId)
        pendingFreshOlderBackfillStartId = recentStartId
        Log.i(
            TAG,
            "Fresh live anchor id=$anchorId; auto-backfilling recent $FRESH_AUTO_BACKFILL_RECORDS " +
                    "records first (id=$recentStartId..$anchorId), then older history in background"
        )
        handler.postDelayed({
            startHistoryBackfill(
                reason = "post-live-anchor(recent)",
                fromId = recentStartId,
                stopBeforeId = anchorId + 1,
            )
        }, 250L)
    }

    private fun maybeStartPendingFreshOlderBackfill() {
        if (stop || phase != Phase.STREAMING) return
        if (restoredGlucoseState || freshOlderBackfillStarted) return
        val recentStartId = pendingFreshOlderBackfillStartId
        if (recentStartId <= 0) return
        if (nativeBackingExistedAtRestore) {
            freshOlderBackfillStarted = true
            Log.i(TAG, "Existing native backing detected without AnytimeRegistry; skipping automatic older history replay id=0..${recentStartId - 1}")
            return
        }
        freshOlderBackfillStarted = true
        handler.postDelayed({
            if (!stop && phase == Phase.STREAMING && !historyBackfillActive) {
                Log.i(TAG, "Recent tail loaded; continuing automatic older history backfill id=0..${recentStartId - 1}")
                startHistoryBackfill(
                    reason = "post-live-anchor(older-background)",
                    fromId = 0,
                    stopBeforeId = recentStartId,
                )
            }
        }, FRESH_OLDER_BACKFILL_DELAY_MS)
    }

    private fun isFreshRecentBackfillReason(reason: String = historyBackfillReason): Boolean =
        reason.startsWith("post-live-anchor(recent)")

    private fun isReconnectCatchupBackfillReason(reason: String = historyBackfillReason): Boolean =
        reason.startsWith("post-reset(reconnect)") || reason.startsWith("interrupted")

    private fun shouldSkipBackfillLiveRace(glucoseId: Int): Boolean {
        if (!historyBackfillActive || !isReconnectCatchupBackfillReason()) return false
        val startedAfter = historyBackfillStartedAfterGlucoseId
        if (startedAfter < 0) return false
        return glucoseId > startedAfter && glucoseId <= lastGlucoseId
    }

    private fun hasContiguousRawHistoryThrough(targetId: Int): Boolean {
        return firstMissingRawHistoryIdThrough(targetId) == null
    }

    /** Contiguous records ending at targetId, enough for native input without O(n²) copies. */
    private fun rawRecordsForAlgorithm(targetId: Int): List<AnytimeRawRecord> =
        synchronized(rawAlgorithmWindow) {
            val target = rawAlgorithmWindow[targetId] ?: return@synchronized emptyList()
            var startId = targetId
            while (startId > 0 && rawAlgorithmWindow.containsKey(startId - 1)) {
                startId--
            }
            val out = ArrayList<AnytimeRawRecord>(targetId - startId + 1)
            for (id in startId..targetId) {
                out.add(rawAlgorithmWindow[id] ?: break)
            }
            if (out.isEmpty()) listOf(target) else out
        }

    private fun firstMissingRawHistoryIdThrough(targetId: Int): Int? {
        if (targetId < 0) return null
        return synchronized(rawAlgorithmWindow) {
            if (rawAlgorithmWindow.size < targetId + 1) {
                for (id in 0..targetId) {
                    if (!rawAlgorithmWindow.containsKey(id)) return@synchronized id
                }
            }
            for (id in 0..targetId) {
                if (!rawAlgorithmWindow.containsKey(id)) return@synchronized id
            }
            null
        }
    }

    private fun finishHistoryBackfill() {
        val completedReason = historyBackfillReason
        stopHistoryBackfill()

        if (isFreshRecentBackfillReason(completedReason)) {
            // The vendor algorithm has glucose-id/lifetime dependent coefficients
            // (K_AUTO/K_BASE). A remapped rolling tail makes late-life samples look
            // like first-day samples, so recent-tail data stays provisional until
            // ids 0..current are contiguous and the full-prefix pass can replace it.
            Log.i(TAG, "Recent tail loaded; waiting for older prefix before native rewrite")
        } else if (completedReason.startsWith("post-live-anchor(older-background)")) {
            // Final correctness pass after the older prefix exists.
            recomputePendingNativeReadings(
                context = Applic.app,
                intervalMs = profile.readingIntervalMinutes * 60L * 1000L,
            )
        }

        maybeStartPendingFreshOlderBackfill()
    }
    private fun rememberInterruptedBackfill() {
        if (!historyBackfillActive) return
        val next = nextBackfillIdSkippingCached((historyLastPulledId + 1).coerceAtLeast(0))
        if (next >= historyStopBeforeId) return
        interruptedBackfillReason = historyBackfillReason.ifBlank { "interrupted" }
        interruptedBackfillFromId = next
        interruptedBackfillStopBeforeId = historyStopBeforeId
        Log.i(
            TAG,
            "Remembering interrupted backfill ($interruptedBackfillReason) " +
                    "from id=$interruptedBackfillFromId stopBefore=${if (interruptedBackfillStopBeforeId == Int.MAX_VALUE) "∞" else interruptedBackfillStopBeforeId.toString()}"
        )
    }

    private fun maybeResumeInterruptedBackfill(): Boolean {
        val from = interruptedBackfillFromId
        if (from < 0) return false
        if (historyBackfillActive || phase != Phase.STREAMING) return false
        val reason = interruptedBackfillReason.ifBlank { "interrupted" }
        val stopBefore = interruptedBackfillStopBeforeId
        interruptedBackfillReason = ""
        interruptedBackfillFromId = -1
        interruptedBackfillStopBeforeId = Int.MAX_VALUE
        Log.i(TAG, "Resuming interrupted backfill ($reason) from id=$from")
        startHistoryBackfill("$reason(resumed)", fromId = from, stopBeforeId = stopBefore)
        return true
    }

    private fun stopHistoryBackfill(rememberForReconnect: Boolean = false) {
        if (rememberForReconnect) rememberInterruptedBackfill()
        historyBackfillActive = false
        historyPullInFlight = false
        historyPullInFlightWasLegacySeries = false
        historyStopBeforeId = Int.MAX_VALUE
        historyBackfillReason = ""
        historyBackfillStartedAfterGlucoseId = -1
        clearHistoryPullTimeout()
        handler.removeCallbacks(historyBackfillRunnable)
    }

    private val serviceDiscoveryWatchdog = Runnable {
        if (stop || phase != Phase.DISCOVERING || serviceDiscoveryHandled) return@Runnable
        Log.w(TAG, "Service discovery wedged — resetting GATT before reconnect")
        recoverGattAndReconnect(
            reason = "service discovery timeout",
            delayMs = SERVICE_DISCOVERY_HARD_RECOVERY_DELAY_MS,
            refreshGattCache = true,
        )
    }

    private val serviceDiscoveryRetryRunnable: Runnable = Runnable {
        if (stop || serviceDiscoveryHandled) return@Runnable
        if (serviceDiscoveryRetryCount >= MAX_SERVICE_DISCOVERY_RETRIES) return@Runnable
        val gatt = mBluetoothGatt ?: return@Runnable
        discoverServicesOrRetry(gatt, "retry")
    }

    private fun discoverServicesOrRetry(gatt: BluetoothGatt, reason: String) {
        if (stop || serviceDiscoveryHandled || mBluetoothGatt !== gatt || phase != Phase.DISCOVERING) return
        if (serviceDiscoveryRequestInFlight) {
            Log.d(TAG, "discoverServices($reason) skipped; request already in flight")
            return
        }
        serviceDiscoveryRequestInFlight = true
        serviceDiscoveryRetryCount++
        val started = runCatching { gatt.discoverServices() }
            .onFailure { Log.stack(TAG, "discoverServices($reason)", it) }
            .getOrDefault(false)
        Log.d(TAG, "discoverServices($reason) started=$started attempt=$serviceDiscoveryRetryCount")
        if (!started) {
            serviceDiscoveryRequestInFlight = false
            if (serviceDiscoveryRetryCount < MAX_SERVICE_DISCOVERY_RETRIES + 1) {
                handler.postDelayed(serviceDiscoveryRetryRunnable, SERVICE_DISCOVERY_RETRY_DELAY_MS)
            } else {
                recoverGattAndReconnect(
                    reason = "discoverServices returned false",
                    delayMs = SERVICE_DISCOVERY_HARD_RECOVERY_DELAY_MS,
                    refreshGattCache = true,
                )
            }
        }
    }

    private val cccdWriteTimeoutRunnable: Runnable = Runnable {
        val gatt = pendingCccdGatt ?: return@Runnable
        if (stop || mBluetoothGatt !== gatt || phase != Phase.DISCOVERING) return@Runnable
        Log.w(TAG, "CCCD write callback timed out; closing stale GATT before retry")
        recoverGattAndReconnect("CCCD write timeout", ACTIVE_SESSION_RECONNECT_DELAY_MS)
    }

    private fun isBluetoothAdapterReady(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.isEnabled
    }

    private fun clearGattCallbacks() {
        handler.removeCallbacks(serviceDiscoveryWatchdog)
        handler.removeCallbacks(serviceDiscoveryRetryRunnable)
        handler.removeCallbacks(cccdWriteTimeoutRunnable)
        handler.removeCallbacks(noDataWatchdog)
        handler.removeCallbacks(pullFallbackRunnable)
        handler.removeCallbacks(telemetryCheckRunnable)
        telemetryLivePushesUntilCheck = -1
        pendingCccdGatt = null
        clearProtocolFrameTimeout()
        stopHistoryBackfill(rememberForReconnect = true)
    }

    private fun clearGattReferences() {
        primaryService = null
        charNotify = null
        charWrite = null
        serviceDiscoveryHandled = false
        serviceDiscoveryRetryCount = 0
        serviceDiscoveryRequestInFlight = false
        mBluetoothGatt = null
        mActiveBluetoothDevice = null
    }

    private fun recoverGattAndReconnect(
        reason: String,
        delayMs: Long = ACTIVE_SESSION_RECONNECT_DELAY_MS,
        refreshGattCache: Boolean = false,
    ) {
        if (stop) return
        val gatt = mBluetoothGatt
        Log.w(TAG, "Recovering GATT: $reason")
        clearGattCallbacks()
        phase = Phase.IDLE
        clearGattReferences()
        if (refreshGattCache && gatt != null) {
            refreshGattCache(gatt, reason)
        }
        runCatching { gatt?.disconnect() }
            .onFailure { Log.stack(TAG, "recoverGattAndReconnect(disconnect:$reason)", it) }
        runCatching { gatt?.close() }
            .onFailure { Log.stack(TAG, "recoverGattAndReconnect(close:$reason)", it) }
        scheduleReconnect(reason, delayMs)
        UiRefreshBus.requestStatusRefresh()
    }

    private fun refreshGattCache(gatt: BluetoothGatt, reason: String) {
        runCatching {
            val refreshMethod = BluetoothGatt::class.java.getMethod("refresh")
            refreshMethod.isAccessible = true
            refreshMethod.invoke(gatt) as? Boolean ?: false
        }.onSuccess { refreshed ->
            Log.d(TAG, "BluetoothGatt.refresh($reason)=$refreshed")
        }.onFailure { t ->
            Log.d(TAG, "BluetoothGatt.refresh($reason) unavailable: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun shouldForceStaleGattReconnect(now: Long): Boolean {
        if (phase == Phase.IDLE) return false
        if (mBluetoothGatt == null) return true
        if (phase == Phase.CONNECTING || phase == Phase.DISCOVERING) {
            return lastConnectRequestAtMs > 0L && now - lastConnectRequestAtMs > STALE_GATT_RECOVERY_MS
        }
        if (phase == Phase.HANDSHAKING) {
            val sinceFrame = if (lastProtocolFrameAtMs > 0L) now - lastProtocolFrameAtMs else Long.MAX_VALUE
            return sinceFrame > STALE_GATT_RECOVERY_MS
        }
        return false
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

    private fun noDataWatchdogMs(): Long =
        NO_DATA_WATCHDOG_MULTIPLIER * profile.readingIntervalMinutes * 60L * 1000L

    private fun armNoDataWatchdog() {
        handler.removeCallbacks(noDataWatchdog)
        if (lastGlucoseAtMs > 0L) {
            handler.postDelayed(noDataWatchdog, noDataWatchdogMs())
        }
    }

    private fun armPullFallback() {
        handler.removeCallbacks(pullFallbackRunnable)
        val delayMs = PULL_FALLBACK_MULTIPLIER * profile.readingIntervalMinutes * 60L * 1000L
        handler.postDelayed(pullFallbackRunnable, delayMs)
    }

    private fun usesSummedFrames(): Boolean =
        familyEntry.family in setOf(
            AnytimeConstants.Family.CT2_5,
            AnytimeConstants.Family.CT3_PLUS,
            AnytimeConstants.Family.CT3_YUWELL,
            AnytimeConstants.Family.CT3_ULTRASONIC,
            AnytimeConstants.Family.CT4,
            AnytimeConstants.Family.CT5,
        )

    private fun isCt5(): Boolean = familyEntry.family == AnytimeConstants.Family.CT5

    private fun usesWideRawRecords(): Boolean = usesSummedFrames() && !isCt5()

    private fun supportsLegacySeriesHistory(): Boolean =
        legacySeriesHistorySupported && usesWideRawRecords()

    private fun historyPullCount(nextId: Int): Int {
        if (isCt5()) return HISTORY_PULL_SERIES_COUNT
        if (!supportsLegacySeriesHistory()) return 1
        val stopRemaining = (historyStopBeforeId - nextId).coerceAtLeast(1)
        return HISTORY_PULL_SERIES_COUNT.coerceAtMost(stopRemaining)
    }

    private fun checkFrame(): ByteArray =
        if (usesSummedFrames()) AnytimeFrames.Builders.checkSummed() else AnytimeFrames.Builders.check()

    private fun initFrame(): ByteArray =
        if (usesPlainControlFrames()) AnytimeFrames.Builders.init()
        else if (usesSummedFrames()) AnytimeFrames.Builders.initSummed()
        else AnytimeFrames.Builders.init()

    private fun lowPowerFrame(): ByteArray =
        if (usesPlainControlFrames()) AnytimeFrames.Builders.lowPower()
        else if (usesSummedFrames()) AnytimeFrames.Builders.lowPowerSummed()
        else AnytimeFrames.Builders.lowPower()

    private fun resetFrame(): ByteArray =
        if (usesSummedFrames()) AnytimeFrames.Builders.resetSummed() else AnytimeFrames.Builders.reset()

    private fun unbindFrame(): ByteArray =
        if (isCt5()) AnytimeFrames.Builders.ct5Unbind(ct5TempId.ifBlank { generateCt5TempId() })
        else if (usesSummedFrames()) AnytimeFrames.Builders.unbindSummed() else AnytimeFrames.Builders.unbind()

    private fun setDateFrame(): ByteArray =
        if (isCt5()) AnytimeFrames.Builders.ct5GetDate()
        else if (usesPlainControlFrames()) AnytimeFrames.Builders.setDate()
        else if (usesSummedFrames()) AnytimeFrames.Builders.setDateSummed()
        else AnytimeFrames.Builders.setDate()

    private fun pullGlucoseFrame(nextId: Int, count: Int = 1): ByteArray =
        if (isCt5()) AnytimeFrames.Builders.ct5PullGlucoseSeries(nextId, count.coerceAtLeast(1))
        else if (count > 1 && supportsLegacySeriesHistory()) AnytimeFrames.Builders.pullGlucoseSeriesSummed(nextId, count)
        else if (usesPlainControlFrames()) AnytimeFrames.Builders.pullGlucose(nextId)
        else if (usesSummedFrames()) AnytimeFrames.Builders.pullGlucoseSummed(nextId)
        else AnytimeFrames.Builders.pullGlucose(nextId)

    private fun inputKrFrame(k: Float, r: Float): ByteArray =
        if (isCt5()) {
            if (ct5CipherKey in 0..255) {
                AnytimeFrames.Builders.ct5SetParameters(k, r, ct5CipherKey, ct5TempId.ifBlank { generateCt5TempId() })
            } else {
                ByteArray(0)
            }
        } else if (usesSummedFrames()) AnytimeFrames.Builders.inputKRSummed(k, r) else AnytimeFrames.Builders.inputKR(k, r)

    private fun inputBgFrame(mgdl: Int): ByteArray =
        if (isCt5()) AnytimeFrames.Builders.ct5InputBgMg(mgdl)
        else if (usesSummedFrames()) AnytimeFrames.Builders.inputBgMgSummed(mgdl) else AnytimeFrames.Builders.inputBgMg(mgdl)

    private fun transmitterFormalFrame(): ByteArray =
        if (usesSummedFrames()) AnytimeFrames.Builders.transmitterFormalSummed() else AnytimeFrames.Builders.transmitterFormal()

    private fun usesPlainControlFrames(): Boolean = false

    private fun generateCt5TempId(): String {
        val generated = (1000 + ct5Random.nextInt(9000)).toString()
        ct5TempId = generated
        return generated
    }

    private fun generateCt5RandomBytes(): IntArray =
        IntArray(4) { ct5Random.nextInt(256) }

    private fun armProtocolFrameTimeout(tag: String) {
        if (phase != Phase.HANDSHAKING) return
        lastProtocolFrameTag = tag
        lastProtocolFrameAtMs = System.currentTimeMillis()
        handler.removeCallbacks(protocolFrameTimeoutRunnable)
        handler.postDelayed(protocolFrameTimeoutRunnable, PROTOCOL_FRAME_TIMEOUT_MS)
    }

    private fun clearProtocolFrameTimeout() {
        handler.removeCallbacks(protocolFrameTimeoutRunnable)
        lastProtocolFrameTag = ""
    }

    private fun tryCt4HandshakeFallback(): Boolean {
        val timedOutTag = lastProtocolFrameTag
        if (timedOutTag.startsWith("setDate")) {
            return false
        }
        if (timedOutTag.startsWith("init")) {
            return false
        }
        if (!timedOutTag.startsWith("transmitterFormal") && !timedOutTag.startsWith("check")) {
            Log.i(TAG, "CT4 fallback skipped after $timedOutTag")
            return false
        }
        ct4HandshakeFallbackStep++
        return when (ct4HandshakeFallbackStep) {
            1 -> {
                Log.i(TAG, "CT4 fallback: sending summed check")
                writeFrame(AnytimeFrames.Builders.checkSummed(), "check(ct4-fallback-sum)")
                true
            }
            2 -> {
                Log.i(TAG, "CT4 fallback: requesting plain formal version")
                writeFrame(AnytimeFrames.Builders.transmitterFormal(), "transmitterFormal(ct4-fallback-plain)")
                true
            }
            3 -> {
                Log.i(TAG, "CT4 fallback: sending plain check")
                writeFrame(AnytimeFrames.Builders.check(), "check(ct4-fallback-plain)")
                true
            }
            else -> false
        }
    }

    // ---- BLE lifecycle ----

    override fun getService(): UUID = primaryServiceUuid

    @Synchronized
    override fun connectDevice(delayMillis: Long): Boolean {
        if (stop) return false
        val now = System.currentTimeMillis()
        if (!isBluetoothAdapterReady()) {
            if (phase != Phase.IDLE || mBluetoothGatt != null) {
                recoverGattAndReconnect("Bluetooth adapter is off", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            } else {
                scheduleReconnect("Bluetooth adapter is off", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            }
            return false
        }
        resolveActiveDeviceFromStoredAddress()
        if (phase == Phase.CONNECTING || phase == Phase.DISCOVERING ||
            phase == Phase.HANDSHAKING || phase == Phase.STREAMING
        ) {
            if (shouldForceStaleGattReconnect(now)) {
                recoverGattAndReconnect("stale $phase before connectDevice", 250L)
                return true
            }
            val hasGatt = mBluetoothGatt != null
            val hasDevice = mActiveBluetoothDevice != null || !mActiveDeviceAddress.isNullOrBlank()
            if (hasGatt || hasDevice) return true
        }
        lastConnectRequestAtMs = now
        phase = Phase.CONNECTING
        val scheduled = super.connectDevice(delayMillis)
        if (!scheduled && phase == Phase.CONNECTING) {
            phase = Phase.IDLE
            scheduleReconnect("connectDevice returned false", ACTIVE_SESSION_RECONNECT_DELAY_MS)
        }
        return scheduled
    }

    private fun resolveActiveDeviceFromStoredAddress() {
        if (mActiveBluetoothDevice != null) return
        val address = mActiveDeviceAddress
            ?.trim()
            ?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
            ?: return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        mActiveBluetoothDevice = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (mActiveBluetoothDevice != null) {
            Log.i(TAG, "Resolved active BLE device from stored address $address")
        }
    }

    override fun matchDeviceName(deviceName: String?, address: String?): Boolean {
        val trimmed = deviceName?.trim().orEmpty()
        val knownAddress = mActiveDeviceAddress?.takeIf { it.isNotBlank() }
        if (knownAddress != null && address != null && address.equals(knownAddress, ignoreCase = true)) return true
        if (!address.isNullOrBlank() &&
            AnytimeConstants.canonicalSensorId(address).equals(SerialNumber, ignoreCase = true)
        ) {
            return true
        }
        if (trimmed.isEmpty()) return false
        val advertisedCanonical = AnytimeConstants.canonicalSensorId(trimmed)
        if (advertisedCanonical.equals(SerialNumber, ignoreCase = true)) return true
        return AnytimeConstants.isAnytimeDevice(trimmed)
    }

    override fun setDeviceAddress(address: String?) {
        super.setDeviceAddress(address)
        val normalized = address?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return
        val ctx = Applic.app ?: return
        val sensorId = SerialNumber ?: return
        AnytimeRegistry.ensureSensorRecord(
            context = ctx,
            sensorId = sensorId,
            address = normalized,
            displayName = familyEntry.family.displayName.ifBlank { AnytimeConstants.DEFAULT_DISPLAY_NAME },
        )
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (stop) return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Connected to ${gatt.device?.address}")
                cancelReconnect()
                clearGattCallbacks()
                mBluetoothGatt = gatt
                mActiveBluetoothDevice = gatt.device
                connectTime = System.currentTimeMillis()
                serviceDiscoveryHandled = false
                serviceDiscoveryRetryCount = 0
                serviceDiscoveryRequestInFlight = false
                phase = Phase.DISCOVERING
                val mtuStarted = runCatching { gatt.requestMtu(AnytimeConstants.DEFAULT_MTU) }
                    .onFailure { Log.stack(TAG, "requestMtu", it) }
                    .getOrDefault(false)
                Log.d(TAG, "requestMtu(${AnytimeConstants.DEFAULT_MTU}) started=$mtuStarted")
                // Do not depend on onMtuChanged: after an interrupted write/reconnect
                // Android can report STATE_CONNECTED but never deliver MTU callback.
                // Start service discovery shortly anyway, and retry if discoverServices()
                // returns false instead of waiting for the watchdog loop.
                handler.postDelayed({ discoverServicesOrRetry(gatt, "connected-fallback") }, 350L)
                handler.postDelayed(serviceDiscoveryWatchdog, SERVICE_DISCOVERY_TIMEOUT_MS)
                UiRefreshBus.requestStatusRefresh()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Disconnected (status=$status)")
                phase = Phase.IDLE
                clearGattCallbacks()
                runCatching { gatt.close() }
                clearGattReferences()
                if (!stop) scheduleReconnect("GATT disconnect status=$status")
                UiRefreshBus.requestStatusRefresh()
            }
            else -> if (phase == Phase.CONNECTING && status != BluetoothGatt.GATT_SUCCESS) {
                phase = Phase.IDLE
            }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.d(TAG, "MTU=$mtu status=$status")
        if (status == BluetoothGatt.GATT_SUCCESS && phase == Phase.DISCOVERING && mBluetoothGatt === gatt && !serviceDiscoveryHandled) {
            discoverServicesOrRetry(gatt, "mtu")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (mBluetoothGatt !== gatt) {
            Log.d(TAG, "Ignoring services callback from stale GATT")
            return
        }
        if (serviceDiscoveryHandled) {
            Log.d(TAG, "Ignoring duplicate services callback")
            return
        }
        serviceDiscoveryRequestInFlight = false
        handler.removeCallbacks(serviceDiscoveryWatchdog)
        handler.removeCallbacks(serviceDiscoveryRetryRunnable)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "onServicesDiscovered failed status=$status")
            recoverGattAndReconnect(
                reason = "services discovery failed status=$status",
                delayMs = SERVICE_DISCOVERY_HARD_RECOVERY_DELAY_MS,
                refreshGattCache = true,
            )
            return
        }
        serviceDiscoveryHandled = true
        // Try proprietary 0x1000 first, fall back to legacy 0xFFF0.
        var svc: BluetoothGattService? = gatt.getService(AnytimeConstants.SERVICE_PRIMARY)
        if (svc != null) {
            primaryServiceUuid = AnytimeConstants.SERVICE_PRIMARY
            charNotify = svc.getCharacteristic(AnytimeConstants.CHAR_NOTIFY_PRIMARY)
            charWrite = svc.getCharacteristic(AnytimeConstants.CHAR_WRITE_PRIMARY)
        } else {
            svc = gatt.getService(AnytimeConstants.SERVICE_LEGACY_CT2)
            if (svc != null) {
                primaryServiceUuid = AnytimeConstants.SERVICE_LEGACY_CT2
                charNotify = svc.getCharacteristic(AnytimeConstants.CHAR_NOTIFY_LEGACY)
                charWrite = svc.getCharacteristic(AnytimeConstants.CHAR_WRITE_LEGACY)
            }
        }
        primaryService = svc
        val notify = charNotify
        val write = charWrite
        if (svc == null || notify == null || write == null) {
            Log.e(TAG, "Required Anytime characteristics not found")
            recoverGattAndReconnect("missing characteristics", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            return
        }
        Log.i(
            TAG,
            "GATT service=${svc.uuid} notify=${notify.uuid} props=0x%02X write=${write.uuid} props=0x%02X".format(
                notify.properties,
                write.properties,
            )
        )
        gatt.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(AnytimeConstants.CCCD)
        if (cccd != null) {
            cccd.value = if ((notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            Log.d(TAG, "Writing CCCD=${cccd.value.joinToHex()}")
            val started = runCatching { gatt.writeDescriptor(cccd) }.getOrDefault(false)
            if (!started) {
                Log.w(TAG, "writeDescriptor returned false; starting handshake without descriptor callback")
                pendingCccdGatt = null
                beginHandshake(gatt, "cccd-write-false")
            } else {
                pendingCccdGatt = gatt
                handler.removeCallbacks(cccdWriteTimeoutRunnable)
                handler.postDelayed(cccdWriteTimeoutRunnable, CCCD_WRITE_TIMEOUT_MS)
            }
        } else {
            Log.w(TAG, "Notify characteristic has no CCCD descriptor")
            beginHandshake(gatt, "missing-cccd")
        }
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        Log.d(TAG, "onDescriptorWrite ${descriptor.uuid} status=$status")
        if (mBluetoothGatt !== gatt) {
            Log.d(TAG, "Ignoring descriptor callback from stale GATT")
            return
        }
        pendingCccdGatt = null
        handler.removeCallbacks(cccdWriteTimeoutRunnable)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "onDescriptorWrite status=$status — retry reconnect")
            recoverGattAndReconnect("descriptor write failed status=$status", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            return
        }
        beginHandshake(gatt, "cccd-ok")
    }

    private fun beginHandshake(gatt: BluetoothGatt, reason: String) {
        if (stop || mBluetoothGatt !== gatt) return
        if (phase == Phase.STREAMING || phase == Phase.HANDSHAKING) return
        phase = Phase.HANDSHAKING
        ct4HandshakeFallbackStep = 0
        postVoltagePlainControlFrames = false
        Log.i(TAG, "Starting Anytime handshake ($reason)")

        val cachedName = SerialNumber?.let { AnytimeRegistry.loadDeviceName(Applic.app, it) }.orEmpty()
        val activeName = gatt.device?.name.orEmpty()
        val resolvedName = cachedName.ifBlank { activeName }
        familyEntry = AnytimeProfileResolver.familyEntry(resolvedName)
        if (familyEntry.family == AnytimeConstants.Family.UNKNOWN && activeName.isNotBlank()) {
            familyEntry = AnytimeProfileResolver.familyEntry(activeName)
        }
        profile = AnytimeProfileResolver.resolve(resolvedName.ifBlank { activeName })

        when {
            familyEntry.family == AnytimeConstants.Family.CT5 && bound -> {
                val randomB = ct5RandomB
                if (randomB != null && randomB.size == 4 && ct5CipherKey in 0..255) {
                    Log.i(TAG, "CT5 bound session — sending identity check")
                    writeFrame(AnytimeFrames.Builders.ct5CheckId(randomB), "ct5-checkID")
                } else {
                    Log.w(TAG, "CT5 bound flag exists but identity material is missing; starting fresh bind")
                    bound = false
                    persistAlgorithmState()
                    writeFrame(setDateFrame(), "ct5-getDate(fresh)")
                }
            }
            familyEntry.family == AnytimeConstants.Family.CT5 -> {
                Log.i(TAG, "CT5 family — starting getDate/check/setID/querySSN handshake")
                writeFrame(setDateFrame(), "ct5-getDate(fresh)")
            }
            bound -> {
                Log.i(TAG, "Already bound — sending reset to confirm session")
                writeFrame(resetFrame(), "reset")
            }
            familyEntry.family in setOf(
                AnytimeConstants.Family.CT3_PLUS,
                AnytimeConstants.Family.CT3_YUWELL,
                AnytimeConstants.Family.CT3_ULTRASONIC,
                AnytimeConstants.Family.CT4,
            ) -> {
                if (familyEntry.family == AnytimeConstants.Family.CT4 && qr == null) {
                    Log.w(TAG, "CT4 QR calibration is missing; continuing BLE handshake but glucose computation may stay unavailable")
                }
                if (isCt4VoltageOne()) {
                    Log.i(TAG, "CT4 voltage mode 1 — skipping formal voltage switch and sending check")
                    writeFrame(checkFrame(), "check(ct4-voltage1)")
                    return
                }
                Log.i(TAG, "Family ${familyEntry.family} — requesting formal version first")
                writeFrame(transmitterFormalFrame(), "transmitterFormal")
            }
            else -> {
                writeFrame(checkFrame(), "check")
            }
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        Log.d(TAG, "onCharacteristicWrite ${characteristic.uuid} status=$status")
        if (status != BluetoothGatt.GATT_SUCCESS && phase == Phase.HANDSHAKING) {
            recoverGattAndReconnect("write failed status=$status", ACTIVE_SESSION_RECONNECT_DELAY_MS)
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        val data = characteristic.value ?: return
        handleCharacteristicChanged(characteristic, data)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        handleCharacteristicChanged(characteristic, value)
    }

    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ) {
        if (stop) return
        if (characteristic.uuid != charNotify?.uuid) return
        if (data.isEmpty()) return
        lastProtocolFrameAtMs = System.currentTimeMillis()
        val opcode = data[0]
        Log.d(TAG, "RX op=0x%02X bytes=%s".format(opcode.toInt() and 0xFF, data.joinToHex()))
        clearProtocolFrameTimeout()
        try {
            dispatch(opcode, data)
        } catch (t: Throwable) {
            Log.stack(TAG, "dispatch op=0x%02X".format(opcode.toInt() and 0xFF), t)
        }
    }

    private fun dispatch(opcode: Byte, data: ByteArray) {
        if (isCt5() && dispatchCt5(opcode, data)) return
        when (opcode) {
            AnytimeConstants.RX_VERSION -> Log.d(TAG, "RX 0x01 version: ${data.joinToHex()}")
            AnytimeConstants.RX_SET_DATE_ACK_A,
            AnytimeConstants.RX_SET_DATE_ACK_B -> {
                Log.d(TAG, "RX setDate ack")
                if (phase == Phase.HANDSHAKING) {
                    writeFrame(initFrame(), "init")
                } else {
                    Log.d(TAG, "Ignoring setDate ack while phase=$phase")
                }
            }
            AnytimeConstants.RX_CHECK -> handleCheckResponse(data)
            AnytimeConstants.RX_INIT -> handleInitResponse()
            AnytimeConstants.RX_PUSH_GLUCOSE -> handleGlucoseFrame(data, push = true)
            AnytimeConstants.RX_PULL_GLUCOSE -> handleGlucoseFrame(data, push = false)
            AnytimeConstants.RX_SERIES -> handleGlucoseFrame(data, push = false)
            AnytimeConstants.RX_INPUT_BG_ACK -> handleInputBgAck(data)
            AnytimeConstants.RX_UNBIND_ACK -> handleUnbindAck()
            AnytimeConstants.RX_INPUT_KR_ACK -> handleInputKrAck()
            AnytimeConstants.RX_COMPUTED_GLUCOSE -> handleComputedGlucose(data)
            AnytimeConstants.RX_LOW_POWER_ACK -> Log.d(TAG, "low-power ack")
            AnytimeConstants.RX_RESET -> handleResetResponse(data)
            AnytimeConstants.RX_MODIFY_VOLTAGE -> handleVoltageAck(data)
            AnytimeConstants.RX_TRANSMITTER_FORMAL -> handleFormalVersion(data)
            else -> Log.d(TAG, "Unhandled opcode 0x%02X len=%d".format(opcode.toInt() and 0xFF, data.size))
        }
    }

    private fun dispatchCt5(opcode: Byte, data: ByteArray): Boolean {
        when (opcode) {
            AnytimeConstants.RX_SET_DATE_ACK_A,
            AnytimeConstants.RX_SET_DATE_ACK_B -> handleCt5DateResponse(data)
            AnytimeConstants.RX_CHECK -> handleCt5CheckResponse(data)
            AnytimeConstants.TX_CT5_SET_ID -> handleCt5SetIdResponse(data)
            AnytimeConstants.RX_CT5_CHECK_ID -> handleCt5CheckIdResponse(data)
            AnytimeConstants.RX_CT5_PUSH_GLUCOSE -> handleCt5CurrentGlucose(data)
            AnytimeConstants.RX_CT5_SERIES -> handleCt5Series(data)
            AnytimeConstants.RX_CT5_SET_PARAMETERS -> handleCt5SetParametersResponse(data)
            AnytimeConstants.RX_CT5_QUERY_SSN -> handleCt5QuerySsnResponse(data)
            AnytimeConstants.RX_INIT -> handleInitResponse()
            AnytimeConstants.RX_LOW_POWER_ACK -> Log.d(TAG, "CT5 low-power ack")
            AnytimeConstants.RX_INPUT_BG_ACK -> handleInputBgAck(data)
            AnytimeConstants.RX_UNBIND_ACK -> handleUnbindAck()
            else -> return false
        }
        return true
    }

    // ---- Handshake handlers ----

    private fun handleFormalVersion(data: ByteArray) {
        val version = AnytimeFrames.parseFormalVersion(data)
        if (version.isNotEmpty()) {
            transmitterVersion = version
            Log.i(TAG, "Transmitter version: $version")
            persistAlgorithmState()
        }
        // Voltage switch: vendor firmware branches V13xx/V14xx need the QR-derived voltage mode.
        val needsVoltageSwitch = Regex("""V1[34]\d{2}""", RegexOption.IGNORE_CASE).containsMatchIn(version)
        if (isCt4VoltageOne()) {
            Log.i(TAG, "CT4 voltage mode 1 — skipping modifyVoltage after formal version")
            writeFrame(checkFrame(), "check(ct4-voltage1-post-formal)")
            return
        }
        if (needsVoltageSwitch && qr != null) {
            voltageFlag = qr?.voltageFlag ?: voltageFlag
            writeFrame(AnytimeFrames.Builders.modifyVoltage(voltageFlag), "modifyVoltage($voltageFlag)")
        } else {
            writeFrame(checkFrame(), "check")
        }
    }

    private fun isCt4VoltageOne(): Boolean =
        familyEntry.family == AnytimeConstants.Family.CT4 && ((qr?.voltageFlag ?: voltageFlag) == 1)

    private fun handleVoltageAck(data: ByteArray) {
        val echoed = if (data.size >= 2) data[1].toInt() and 0xFF else -1
        Log.i(TAG, "Voltage switch ack: $echoed")
        if (phase != Phase.HANDSHAKING) {
            Log.d(TAG, "Ignoring voltage ack while phase=$phase")
            return
        }
        writeFrame(checkFrame(), "check(post-voltage)")
    }

    private fun handleCheckResponse(data: ByteArray) {
        val status = AnytimeFrames.parseCheckResponse(data, profile.lowBatteryVolts)
        lastBatteryVolts = status.batteryVolts
        saveCachedBatteryVolts(Applic.app, SerialNumber, lastBatteryVolts)
        if (phase != Phase.HANDSHAKING) {
            Log.d(TAG, "Check telemetry updated while phase=$phase (battery=${status.batteryVolts}V iw=${status.workingElectrodeCurrentNa}nA)")
            armTelemetryCheck(TELEMETRY_CHECK_INTERVAL_MS)
            UiRefreshBus.requestStatusRefresh()
            return
        }
        if (!status.isHealthy) {
            Log.w(TAG, "Check failed: ${status.failure} (battery=${status.batteryVolts}V iw=${status.workingElectrodeCurrentNa}nA)")
            if (status.failure == AnytimeCheckStatus.CheckFailure.LOW_BATTERY) {
                runCatching { mBluetoothGatt?.disconnect() }
            }
            return
        }
        Log.i(TAG, "Check OK (battery=${status.batteryVolts}V iw=${status.workingElectrodeCurrentNa}nA age=${status.sensorAgeReadings})")
        // Do not derive Started from the check-frame age field. On CT4 it jumps
        // wildly between reconnects/delete-readd attempts; the monotonic glucose
        // id plus the 3-minute cadence is the stable timeline source.
        // Some CT4 transmitters do not reliably ACK setDate. Send it as a
        // best-effort clock update, then continue the documented handshake with
        // init without arming an init timeout; otherwise we disconnect just
        // before the next raw 0x07 push arrives.
        writeFrame(setDateFrame(), "setDate", expectResponse = false)
        handler.postDelayed({
            if (!stop && phase == Phase.HANDSHAKING && !lastProtocolFrameTag.startsWith("init")) {
                writeFrame(initFrame(), "init(after-setDate-best-effort)", expectResponse = false)
                handler.postDelayed({
                    if (!stop && phase == Phase.HANDSHAKING) {
                        if (hasUsableHistoryTimeline() || lastGlucoseId >= 0) {
                            enterStreaming("Init sent without ACK")
                        } else {
                            Log.i(TAG, "Init ACK missing and no cached timeline; waiting for first live 0x07")
                        }
                    }
                }, INIT_NO_ACK_STREAMING_GRACE_MS)
            }
        }, 250L)
    }

    private fun handleInitResponse() {
        enterStreaming("Init OK")
    }

    private fun enterStreaming(reason: String) {
        if (phase == Phase.STREAMING) return
        Log.i(TAG, "$reason — entering streaming")
        bound = true
        phase = Phase.STREAMING
        clearProtocolFrameTimeout()
        // Do not invent a start time here. CT4 start/history timestamps must be
        // anchored from a real live glucose id; otherwise fresh installs display
        // a fake Started time and all pulled history is shifted.
        if (sensorStartAtMs > 0L) {
            ensureNativeSensorShell()
        } else {
            Log.d(TAG, "Entering streaming without anchored start; native shell will be created after first live 0x07")
        }
        persistAlgorithmState()
        if (pendingKrPush) {
            pendingKrPush = false
            qr?.takeIf { it.isFactoryCalibration }?.let {
                if (isCt5() && ct5CipherKey !in 0..255) {
                    pendingKrPush = true
                    Log.w(TAG, "CT5 deferred K/R still waiting for cipher key")
                } else {
                    writeFrame(inputKrFrame(it.k, it.r), if (isCt5()) "ct5-setParameters(deferred)" else "inputKR(deferred)")
                }
            }
        }
        writeFrame(lowPowerFrame(), "lowPower", expectResponse = false)
        armPullFallback()
        armNoDataWatchdog()
        armTelemetryCheck(TELEMETRY_CHECK_INTERVAL_MS)
        // Restored sessions already have a usable id→time timeline, so resume
        // the missing tail immediately. Fresh sessions wait for the first real
        // live 0x07 anchor and then auto-fill only a short recent tail. Full
        // replay from id=0 remains available through requestHistoryBackfill().
        if (maybeResumeInterruptedBackfill()) {
            // resumed the exact interrupted range
        } else if (hasUsableHistoryTimeline()) {
            handler.postDelayed({ startHistoryBackfill("post-init", fromId = postInitBackfillStartId()) }, 750L)
        } else {
            Log.i(TAG, "Fresh Anytime session has no live timeline yet; waiting for first 0x07 before history backfill")
        }
        UiRefreshBus.requestStatusRefresh()
    }

    private fun handleResetResponse(data: ByteArray) {
        val parsed = AnytimeFrames.parseResetResponse(data)
        if (parsed == null) {
            Log.w(TAG, "Bad reset response")
            scheduleReconnect("reset response malformed", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            return
        }
        Log.i(TAG, "Reset response: bound=${parsed.isBound}")
        bound = parsed.isBound
        persistAlgorithmState()
        if (parsed.isBound) {
            // Sensor confirms session is alive — proceed to streaming and pull
            // any history we missed while disconnected.

            phase = Phase.STREAMING
            // Do not send check(post-reset-telemetry) here. It races with lowPower/history
            // on the single Android GATT write slot and caused an endless loop:
            // TX check(post-reset-telemetry) -> writeCharacteristic(lowPower)=false -> reconnect.
            // Battery telemetry is refreshed from normal check frames and restored from cache;
            // preserving the data path is more important than probing voltage on every reconnect.
            writeFrame(lowPowerFrame(), "lowPower(post-reset)", expectResponse = false)
            armPullFallback()
            armNoDataWatchdog()
            requestTelemetryAfterReconnectLivePushes()
            if (!maybeResumeInterruptedBackfill()) {
                handler.postDelayed({ startHistoryBackfill("post-reset(reconnect)", fromId = reconnectBackfillStartId()) }, 500L)
            }
            UiRefreshBus.requestStatusRefresh()
        } else {
            // Sensor lost binding — fall through to fresh handshake.
            writeFrame(checkFrame(), "check(post-reset)")
        }
    }

    private fun handleInputBgAck(data: ByteArray) {
        Log.i(TAG, "Fingerstick BG accepted: ${data.joinToHex()}")
        if (pendingFingerstickMgdl > 0 && pendingFingerstickTargetGlucoseId > 0) {
            lastReferenceBgMgdlTimes10 = pendingFingerstickMgdl * 10
            lastReferenceBgGlucoseId = pendingFingerstickTargetGlucoseId
            lastReferenceAppliedGlucoseId = 0
            upsertReferenceCalibrationRecord(
                AnytimeReferenceCalibrationRecord(
                    targetGlucoseId = lastReferenceBgGlucoseId,
                    referenceMgdlTimes10 = lastReferenceBgMgdlTimes10,
                    acceptedAtMs = System.currentTimeMillis(),
                )
            )
            Log.i(TAG, "Fingerstick BG ${pendingFingerstickMgdl}mg/dL will calibrate glucose id=$lastReferenceBgGlucoseId")
            setCalibrationStatus(
                resId = R.string.anytime_calibration_accepted_status,
                fallback = "Calibration accepted; applies from reading #$lastReferenceBgGlucoseId",
                lastReferenceBgGlucoseId,
                clearAfterGlucoseId = lastReferenceBgGlucoseId + 1,
            )
        }
        pendingFingerstickMgdl = -1
        pendingFingerstickTargetGlucoseId = -1
        persistAlgorithmState()
        UiRefreshBus.requestStatusRefresh()
    }

    private fun setCalibrationStatus(
        resId: Int,
        fallback: String,
        vararg args: Any,
        clearAfterGlucoseId: Int = 0,
    ) {
        val localized = Applic.app?.let { context ->
            runCatching { context.getString(resId, *args) }.getOrNull()
        }
        calibrationStatusText = localized ?: fallback
        calibrationStatusAtMs = System.currentTimeMillis()
        calibrationStatusClearAfterGlucoseId = clearAfterGlucoseId
        UiRefreshBus.requestStatusRefresh()
    }

    private fun clearCalibrationStatus() {
        calibrationStatusText = ""
        calibrationStatusAtMs = 0L
        calibrationStatusClearAfterGlucoseId = 0
        UiRefreshBus.requestStatusRefresh()
    }

    private fun visibleCalibrationStatus(): String {
        val status = calibrationStatusText
        if (status.isBlank()) return ""
        val clearAfter = calibrationStatusClearAfterGlucoseId
        if (clearAfter > 0 && lastGlucoseId >= clearAfter) {
            clearCalibrationStatus()
            return ""
        }
        val ageMs = System.currentTimeMillis() - calibrationStatusAtMs
        return if (ageMs <= CALIBRATION_STATUS_TTL_MS) status else ""
    }

    private fun maybeMarkReferenceConsumed(result: AnytimeAlgorithm.Result, sampleMs: Long) {
        val targetId = lastReferenceBgGlucoseId
        if (targetId <= 0 || result.glucoseId < targetId || lastReferenceAppliedGlucoseId >= targetId) return
        lastReferenceAppliedGlucoseId = targetId
        markReferenceCalibrationApplied(
            targetId = targetId,
            sampleMs = sampleMs,
            outputMgdlTimes10 = result.mgdlTimes10,
        )
        val enteredMgdl = lastReferenceBgMgdlTimes10 / 10f
        Log.i(
            TAG,
            "Fingerstick BG reference ${"%.1f".format(enteredMgdl)}mg/dL consumed at " +
                    "glucose id=$targetId by ${result.source}; algorithm output=${"%.1f".format(result.mgdl)}mg/dL"
        )
//        setCalibrationStatus(
//            resId = R.string.anytime_calibration_used_status,
//            fallback = "Calibration used at reading #$targetId: ${"%.0f".format(result.mgdl)} mg/dL",
//            targetId,
//            result.mgdl,
//        )
        persistAlgorithmState()
    }

    private fun handleUnbindAck() {
        Log.i(TAG, "Unbind ack received — closing GATT")
        bound = false
        persistAlgorithmState()
        runCatching { mBluetoothGatt?.disconnect() }
    }

    private fun handleInputKrAck() {
        Log.i(TAG, "K/R upload ack")
    }

    private fun handleCt5DateResponse(data: ByteArray) {
        if (!AnytimeFrames.verifySum(data)) {
            Log.w(TAG, "Bad CT5 date response: ${data.joinToHex()}")
            return
        }
        if (phase != Phase.HANDSHAKING) {
            Log.d(TAG, "Ignoring CT5 date response while phase=$phase")
            return
        }
        if (ct5ReconnectDateAfterIdentity && bound && ct5CipherKey in 0..255) {
            ct5ReconnectDateAfterIdentity = false
            enterStreaming("CT5 reconnect date OK")
        } else {
            ct5ReconnectDateAfterIdentity = false
            writeFrame(checkFrame(), "ct5-check")
        }
    }

    private fun handleCt5CheckResponse(data: ByteArray) {
        if (phase != Phase.HANDSHAKING) {
            Log.d(TAG, "Ignoring CT5 check response while phase=$phase")
            return
        }
        if (data.size != 20 || !AnytimeFrames.verifySum(data)) {
            Log.w(TAG, "Bad CT5 check response: ${data.joinToHex()}")
            return
        }
        ct5TempId = generateCt5TempId()
        val randomA = generateCt5RandomBytes()
        val randomB = generateCt5RandomBytes()
        ct5RandomA = randomA
        ct5RandomB = randomB
        persistAlgorithmState()
        Log.i(TAG, "CT5 check OK — sending setID")
        writeFrame(AnytimeFrames.Builders.ct5SetId(randomA, randomB), "ct5-setID")
    }

    private fun handleCt5SetIdResponse(data: ByteArray) {
        val randomA = ct5RandomA
        if (randomA == null || randomA.size != 4) {
            Log.w(TAG, "CT5 setID response without randomA")
            return
        }
        val key = AnytimeFrames.ct5SessionKeyFromSetIdResponse(data, randomA)
        if (key !in 0..255) {
            Log.w(TAG, "CT5 setID failed: ${data.joinToHex()}")
            return
        }
        ct5CipherKey = key
        persistAlgorithmState()
        Log.i(TAG, "CT5 session cipher established")
        writeFrame(AnytimeFrames.Builders.ct5QuerySsn(), "ct5-querySSN")
    }

    private fun handleCt5CheckIdResponse(data: ByteArray) {
        val ok = data.size >= 6 &&
                data[0] == AnytimeConstants.RX_CT5_CHECK_ID &&
                AnytimeFrames.verifySum(data) &&
                (data[5].toInt() and 0xFF) == 1
        if (ok) {
            Log.i(TAG, "CT5 identity check OK")
            ct5ReconnectDateAfterIdentity = true
            writeFrame(setDateFrame(), "ct5-getDate(reconnect)")
        } else {
            Log.w(TAG, "CT5 identity check failed; restarting fresh handshake")
            bound = false
            ct5CipherKey = -1
            ct5RandomB = null
            ct5ReconnectDateAfterIdentity = false
            persistAlgorithmState()
            writeFrame(setDateFrame(), "ct5-getDate(fresh-after-checkID)")
        }
    }

    private fun handleCt5QuerySsnResponse(data: ByteArray) {
        val key = ct5CipherKey
        if (key !in 0..255) {
            Log.w(TAG, "CT5 querySSN response before cipher key")
            return
        }
        val decoded = AnytimeFrames.parseCt5QuerySsnResponse(data, key).orEmpty()
        val candidates = buildList {
            if (decoded.isNotBlank()) add(decoded)
            val alnum = decoded.filter { it.isLetterOrDigit() }
            if (alnum.isNotBlank() && alnum != decoded) add(alnum)
            if (data.size > 2) {
                val noSum = AnytimeFrames.ct5Encode(data.copyOfRange(1, data.size - 1), key)
                    .toString(Charsets.US_ASCII)
                    .trim { it <= ' ' || it.code > 0x7E }
                if (noSum.isNotBlank()) add(noSum)
                val noSumAlnum = noSum.filter { it.isLetterOrDigit() }
                if (noSumAlnum.isNotBlank() && noSumAlnum != noSum) add(noSumAlnum)
            }
        }.distinct()
        val parsed = candidates.firstNotNullOfOrNull { candidate ->
            AnytimeAlgorithm.decodeQr(candidate)
        }
        if (parsed != null) {
            qr = parsed
            voltageFlag = parsed.voltageFlag
            Log.i(TAG, "CT5 QR/KR decoded from transmitter: K=${parsed.k} R=${parsed.r} life=${parsed.lifeTime}d")
            persistAlgorithmState()
        } else {
            Log.w(TAG, "CT5 querySSN did not decode with local QR parser; raw='$decoded'")
        }
        val calibration = parsed ?: qr
        if (calibration == null) {
            Log.w(TAG, "CT5 setup cannot continue without K/R calibration")
            return
        }
        if (ct5TempId.isBlank()) ct5TempId = generateCt5TempId()
        writeFrame(
            AnytimeFrames.Builders.ct5SetParameters(calibration.k, calibration.r, key, ct5TempId),
            "ct5-setParameters",
        )
    }

    private fun handleCt5SetParametersResponse(data: ByteArray) {
        if (data.size != 14 || !AnytimeFrames.verifySum(data)) {
            Log.w(TAG, "Bad CT5 setParameters response: ${data.joinToHex()}")
            return
        }
        Log.i(TAG, "CT5 K/R parameters accepted")
        writeFrame(initFrame(), "ct5-init")
    }

    // ---- Glucose pipeline ----

    private fun handleGlucoseFrame(data: ByteArray, push: Boolean) {
        if (phase == Phase.HANDSHAKING && push) {
            val records = AnytimeFrames.parseRawRecords(data, usesWideRawRecords())
            val firstLiveId = records.maxOfOrNull { it.glucoseId }
            if (firstLiveId != null) {
                val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
                updateTimelineFromLiveGlucoseId(firstLiveId, System.currentTimeMillis(), intervalMs)
            }
            enterStreaming("Raw glucose during handshake")
        }
        handleRawGlucose(data, push)
    }

    private fun handleRawGlucose(data: ByteArray, push: Boolean) {
        val records = if (!push && data.firstOrNull() == AnytimeConstants.RX_SERIES) {
            AnytimeFrames.parseWideRawSeriesRecords(data)
        } else {
            AnytimeFrames.parseRawRecords(data, usesWideRawRecords())
        }
        val context = Applic.app
        val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
        if (records.isEmpty()) {
            // Empty pull response — transmitter has nothing more to give.
            Log.d(TAG, "Empty raw frame (pull caught-up): ${data.joinToHex()}")
            if (!push) {
                clearHistoryPullTimeout()
                historyPullInFlight = false
                historyPullInFlightWasLegacySeries = false
            }
            if (historyBackfillActive) {
                historyEmptyResponsesInARow++
                if (historyEmptyResponsesInARow >= HISTORY_EMPTY_RESPONSES_TO_STOP) {
                    Log.i(TAG, "Backfill caught up at id=$lastGlucoseId after $historyEmptyResponsesInARow empty responses")
                    finishHistoryBackfill()
                } else {
                    handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
                }
            }
            return
        }
        historyEmptyResponsesInARow = 0
        if (!push) {
            clearHistoryPullTimeout()
            historyPullInFlight = false
            historyPullInFlightWasLegacySeries = false
            records.maxOfOrNull { it.glucoseId }?.let { maxId ->
                if (maxId > historyLastPulledId) historyLastPulledId = maxId
            }
        }
        val now = System.currentTimeMillis()
        val anchorId = records.maxOfOrNull { it.glucoseId } ?: -1
        val anchorMs = if (push && anchorId >= 0) now else 0L
        if (push && anchorMs > 0L && anchorId >= 0) {
            updateTimelineFromLiveGlucoseId(anchorId, anchorMs, intervalMs)
            maybeStartFreshPostLiveBackfill(anchorId)
        }
        for (rec in records) {
            synchronized(rawAlgorithmWindow) {
                rawAlgorithmWindow[rec.glucoseId] = rec
            }
            if (!push && shouldSkipBackfillLiveRace(rec.glucoseId)) {
                Log.i(
                    TAG,
                    "Skipping duplicate backfill id=${rec.glucoseId}; live push already " +
                            "processed during $historyBackfillReason"
                )
                synchronized(pendingNativeRecomputeIds) { pendingNativeRecomputeIds.remove(rec.glucoseId) }
                continue
            }
            packetsSinceInit++
            lastIwNa = rec.iwNa
            lastIbNa = rec.ibNa
            lastTemperatureC = rec.temperatureC
            val sampleMs = if (push && anchorMs > 0L && anchorId >= rec.glucoseId) {
                // Live pushes are wall-clock anchored. The transmitter glucose id is monotonic,
                // but CT4 cadence/profile metadata can be wrong early in a new session; anchoring
                // live packets to sensorStartAtMs makes fresh readings appear several minutes old
                // and trips the loss-of-sensor alarm.
                anchorMs - (anchorId - rec.glucoseId).toLong() * intervalMs
            } else if (glucoseTimelineStartAtMs > 0L) {
                glucoseTimelineStartAtMs + rec.glucoseId.toLong() * intervalMs
            } else if (sensorStartAtMs > 0L) {
                sensorStartAtMs + rec.glucoseId.toLong() * intervalMs
            } else {
                now - rec.indexInPacket * intervalMs
            }
            anchorSensorTimelineIfNeeded(rec.glucoseId, sampleMs, intervalMs)
            val result = AnytimeAlgorithm.compute(
                record = rec,
                qr = qr,
                family = familyEntry,
                sensorIdName = algorithmTransmitterName(context),
                sampleTimeMs = sampleMs,
                lastReferenceBgMgdlTimes10 = lastReferenceBgMgdlTimes10,
                lastReferenceBgGlucoseId = lastReferenceBgGlucoseId,
                sessionPacketsSinceInit = packetsSinceInit,
                recentRecords = rawRecordsForAlgorithm(rec.glucoseId),
                sensorStartTimeMs = glucoseTimelineStartAtMs.takeIf { it > 0L } ?: sensorStartAtMs,
            )
            val skipHistoryImport = shouldSkipStartupRoomImport(result, push = push, history = !push)
            val committed = commitReading(
                result = result,
                sampleMs = sampleMs,
                context = context,
                live = push,
                history = !push,
                skipHistoryImport = skipHistoryImport,
            )
            if (committed) {
                maybeMarkReferenceConsumed(result, sampleMs)
            }
            trackNativeRecomputeNeed(result)
            if (rec.glucoseId > lastGlucoseId) lastGlucoseId = rec.glucoseId
        }
        if (push) {
            maybeRunReconnectTelemetryAfterLivePush()
        }
        if (!push && !isFreshRecentBackfillReason() && !historyBackfillReason.startsWith("post-live-anchor(older-background)")) {
            recomputePendingNativeReadings(context, intervalMs)
        }
        persistAlgorithmState()
        armNoDataWatchdog()
        armPullFallback()
        // Chain the backfill loop: if this was a non-empty pull response, keep
        // pulling until empty (i.e. caught up to live cadence).
        if (historyBackfillActive) {
            handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
        }
        UiRefreshBus.requestStatusRefresh()
    }

    private fun shouldSkipStartupRoomImport(
        result: AnytimeAlgorithm.Result,
        push: Boolean,
        history: Boolean,
    ): Boolean {
        if (!nativeAlgorithmExpected()) return false
        if (result.source == AnytimeAlgorithm.Source.NATIVE && result.errorCode == 0) return false

        // During the fresh recent-tail pass LINEAR points are only a temporary
        // fallback before full-prefix native can recompute them. Importing them
        // created a visible startup bump/curve on the dashboard.
        if (history && isFreshRecentBackfillReason()) return true

        // The very first live point on a clean start can also be LINEAR because
        // the full raw prefix is not loaded yet. Keep it for hero/notification,
        // but do not anchor it into Room; the older-prefix pass will import the
        // native current point once ids 0..current are contiguous.
        if (push && freshPostLiveBackfillStarted && !hasContiguousRawHistoryThrough(result.glucoseId)) return true

        return false
    }

    private fun trackNativeRecomputeNeed(result: AnytimeAlgorithm.Result) {
        if (!nativeAlgorithmExpected()) return

        val id = result.glucoseId
        val hasFullPrefix = hasContiguousRawHistoryThrough(id)
        synchronized(pendingNativeRecomputeIds) {
            if (id < NATIVE_HISTORY_WARMUP_RECORDS) {
                pendingNativeRecomputeIds.remove(id)
                return@synchronized
            }

            if (result.source == AnytimeAlgorithm.Source.NATIVE && result.errorCode == 0) {
                if (hasFullPrefix) {
                    pendingNativeRecomputeIds.remove(id)
                } else {
                    // Rolling-native is useful for the live/current value, but it is
                    // provisional for history until ids 0..id are contiguous.
                    pendingNativeRecomputeIds.add(id)
                }
                return@synchronized
            }

            pendingNativeRecomputeIds.add(id)
        }
    }

    private fun nativeAlgorithmExpected(): Boolean =
        qr?.isFactoryCalibration == true && AnytimeAlgorithm.isNativeAvailable

    private fun recomputePendingNativeReadings(context: Context?, intervalMs: Long) {
        if (!nativeAlgorithmExpected() || intervalMs <= 0L) return
        val ids = synchronized(pendingNativeRecomputeIds) { pendingNativeRecomputeIds.toList() }
        if (ids.isEmpty()) return
        for (id in ids) {
            if (!hasContiguousRawHistoryThrough(id)) continue
            val rec = synchronized(rawAlgorithmWindow) { rawAlgorithmWindow[id] } ?: continue
            val sampleMs = if (glucoseTimelineStartAtMs > 0L) {
                glucoseTimelineStartAtMs + id.toLong() * intervalMs
            } else if (sensorStartAtMs > 0L) {
                sensorStartAtMs + id.toLong() * intervalMs
            } else {
                continue
            }
            val result = AnytimeAlgorithm.compute(
                record = rec,
                qr = qr,
                family = familyEntry,
                sensorIdName = algorithmTransmitterName(context),
                sampleTimeMs = sampleMs,
                lastReferenceBgMgdlTimes10 = lastReferenceBgMgdlTimes10,
                lastReferenceBgGlucoseId = lastReferenceBgGlucoseId,
                sessionPacketsSinceInit = packetsSinceInit,
                recentRecords = rawRecordsForAlgorithm(rec.glucoseId),
                sensorStartTimeMs = glucoseTimelineStartAtMs.takeIf { it > 0L } ?: sensorStartAtMs,
            )
            if (result.source != AnytimeAlgorithm.Source.NATIVE || result.errorCode != 0) {
                // Future records do not change the input prefix used for this id;
                // retrying it on every new backfill point only creates quadratic
                // legacy-native warning spam.
                synchronized(pendingNativeRecomputeIds) { pendingNativeRecomputeIds.remove(id) }
                continue
            }
            Log.i(TAG, "Replacing provisional Anytime point id=$id with full-prefix native algorithm output")
            // Full-prefix recompute is also a Room/history replacement only.
            // Never emit recomputed backfill as a live/current point: the reading
            // row is backed by the native current stream and will show duplicates.
            commitReading(result, sampleMs, context, live = false, history = true)
            synchronized(pendingNativeRecomputeIds) { pendingNativeRecomputeIds.remove(id) }
        }
    }

    private fun handleComputedGlucose(data: ByteArray) {
        val rec = AnytimeFrames.parseComputedRecord(data) ?: run {
            Log.w(TAG, "Bad computed-glucose frame: ${data.joinToHex()}")
            return
        }
        val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
        anchorSensorTimelineIfNeeded(rec.glucoseId, System.currentTimeMillis(), intervalMs)
        val sampleMs = if (glucoseTimelineStartAtMs > 0L) {
            glucoseTimelineStartAtMs + rec.glucoseId.toLong() * intervalMs
        } else if (sensorStartAtMs > 0L) {
            sensorStartAtMs + rec.glucoseId.toLong() * intervalMs
        } else {
            System.currentTimeMillis()
        }
        val result = AnytimeAlgorithm.fromComputedRecord(rec, qr, familyEntry)
        commitReading(result, sampleMs, Applic.app, live = true, history = false)
        if (rec.glucoseId > lastGlucoseId) lastGlucoseId = rec.glucoseId
        persistAlgorithmState()
        armNoDataWatchdog()
        armPullFallback()
        UiRefreshBus.requestStatusRefresh()
    }

    private fun handleCt5CurrentGlucose(data: ByteArray) {
        writeFrame(AnytimeFrames.Builders.ct5PushAck(), "ct5-pushAck", expectResponse = false)
        val key = ct5CipherKey
        if (key !in 0..255) {
            Log.w(TAG, "CT5 live push before cipher key: ${data.joinToHex()}")
            return
        }
        val rec = AnytimeFrames.parseCt5CurrentRecord(data, key) ?: run {
            Log.w(TAG, "Bad CT5 live frame: ${data.joinToHex()}")
            return
        }
        ct5VoltagePayload = data.size == 19
        if (phase == Phase.HANDSHAKING) {
            enterStreaming("CT5 glucose during handshake")
        }
        val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
        val now = System.currentTimeMillis()
        updateTimelineFromLiveGlucoseId(rec.glucoseId, now, intervalMs)
        maybeStartFreshPostLiveBackfill(rec.glucoseId)
        val sampleMs = if (glucoseTimelineStartAtMs > 0L) {
            glucoseTimelineStartAtMs + rec.glucoseId.toLong() * intervalMs
        } else {
            now
        }
        val result = AnytimeAlgorithm.fromComputedRecord(rec, qr, familyEntry)
        commitReading(result, sampleMs, Applic.app, live = true, history = false)
        if (rec.glucoseId > lastGlucoseId) lastGlucoseId = rec.glucoseId
        persistAlgorithmState()
        armNoDataWatchdog()
        armPullFallback()
        maybeRunReconnectTelemetryAfterLivePush()
        UiRefreshBus.requestStatusRefresh()
    }

    private fun handleCt5Series(data: ByteArray) {
        val key = ct5CipherKey
        if (key !in 0..255) {
            Log.w(TAG, "CT5 series before cipher key: ${data.joinToHex()}")
            if (historyBackfillActive) historyPullInFlight = false
            return
        }
        val payloadLen = (data.size - 4).coerceAtLeast(0)
        val preferVoltage = ct5VoltagePayload ||
                (payloadLen > 0 && payloadLen % AnytimeConstants.CT5_VOLTAGE_CHUNK_SIZE == 0 &&
                        payloadLen % AnytimeConstants.CT5_RAW_CHUNK_SIZE != 0)
        val preferred = AnytimeFrames.parseCt5SeriesRecords(data, key, voltage = preferVoltage)
        val records = if (preferred.isNotEmpty()) {
            ct5VoltagePayload = preferVoltage
            preferred
        } else {
            val fallbackVoltage = !preferVoltage
            AnytimeFrames.parseCt5SeriesRecords(data, key, voltage = fallbackVoltage).also {
                if (it.isNotEmpty()) ct5VoltagePayload = fallbackVoltage
            }
        }
        if (historyBackfillActive) historyPullInFlight = false
        if (records.isEmpty()) {
            Log.d(TAG, "Empty CT5 series frame: ${data.joinToHex()}")
            if (historyBackfillActive) {
                historyEmptyResponsesInARow++
                if (historyEmptyResponsesInARow >= HISTORY_EMPTY_RESPONSES_TO_STOP) {
                    Log.i(TAG, "CT5 backfill caught up at id=$lastGlucoseId")
                    finishHistoryBackfill()
                } else {
                    handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
                }
            }
            return
        }

        historyEmptyResponsesInARow = 0
        records.maxOfOrNull { it.glucoseId }?.let { maxId ->
            if (maxId > historyLastPulledId) historyLastPulledId = maxId
        }
        val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
        val now = System.currentTimeMillis()
        val maxId = records.maxOf { it.glucoseId }
        for (rec in records) {
            lastIwNa = rec.iwNa
            lastIbNa = rec.ibNa
            lastTemperatureC = rec.temperatureC
            val sampleMs = when {
                glucoseTimelineStartAtMs > 0L -> glucoseTimelineStartAtMs + rec.glucoseId.toLong() * intervalMs
                sensorStartAtMs > 0L -> sensorStartAtMs + rec.glucoseId.toLong() * intervalMs
                else -> now - (maxId - rec.glucoseId).toLong() * intervalMs
            }
            anchorSensorTimelineIfNeeded(rec.glucoseId, sampleMs, intervalMs)
            val result = AnytimeAlgorithm.fromComputedRecord(rec, qr, familyEntry)
            if (shouldSkipBackfillLiveRace(rec.glucoseId)) {
                Log.i(
                    TAG,
                    "Skipping duplicate CT5 backfill id=${rec.glucoseId}; live push already " +
                            "processed during $historyBackfillReason"
                )
                continue
            }
            commitReading(result, sampleMs, Applic.app, live = false, history = true)
            if (rec.glucoseId > lastGlucoseId) lastGlucoseId = rec.glucoseId
        }
        persistAlgorithmState()
        armNoDataWatchdog()
        armPullFallback()
        if (historyBackfillActive) {
            handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
        }
        UiRefreshBus.requestStatusRefresh()
    }

    private fun updateTimelineFromLiveGlucoseId(glucoseId: Int, sampleMs: Long, intervalMs: Long) {
        if (glucoseId < 0 || intervalMs <= 0L) return
        val anchoredStartMs = (sampleMs - glucoseId.toLong() * intervalMs).coerceAtLeast(1L)
        val oldTimelineStart = glucoseTimelineStartAtMs
        val oldSensorStart = sensorStartAtMs
        glucoseTimelineStartAtMs = anchoredStartMs

        // The CT4 check-frame "age" field is not stable after a delete/re-add.
        // A live glucose id with the known cadence is the only start estimate that
        // stays aligned with backfilled ids, so prefer it when it materially
        // disagrees with the check-derived value.
        val driftMs = kotlin.math.abs(sensorStartAtMs - anchoredStartMs)
        var changed = false
        if (sensorStartAtMs <= 0L || driftMs > intervalMs * 2L) {
            sensorStartAtMs = anchoredStartMs
            sensorstartmsec = anchoredStartMs
            if (warmupStartedAtMs == 0L || kotlin.math.abs(warmupStartedAtMs - anchoredStartMs) > intervalMs * 2L) {
                warmupStartedAtMs = anchoredStartMs
            }
            changed = true
            Log.i(TAG, "Sensor start from live glucose id=$glucoseId (oldStart=$oldSensorStart newStart=$anchoredStartMs)")
        }
        if (oldTimelineStart != anchoredStartMs) {
            changed = true
            Log.i(TAG, "Glucose timeline from live id=$glucoseId (oldStart=$oldTimelineStart newStart=$anchoredStartMs)")
        }
        if (changed) {
            ensureNativeSensorShell()
            persistAlgorithmState()
        }
    }

    private fun anchorSensorTimelineIfNeeded(glucoseId: Int, sampleMs: Long, intervalMs: Long) {
        if (glucoseId < 0 || intervalMs <= 0L) return
        val projectedMs = if (glucoseTimelineStartAtMs > 0L) {
            glucoseTimelineStartAtMs + glucoseId.toLong() * intervalMs
        } else {
            Long.MAX_VALUE
        }
        val futureToleranceMs = (intervalMs / 2L).coerceAtLeast(30_000L)
        if (projectedMs <= sampleMs + futureToleranceMs) return

        val anchoredStartMs = (sampleMs - glucoseId.toLong() * intervalMs).coerceAtLeast(1L)
        val oldStart = glucoseTimelineStartAtMs
        glucoseTimelineStartAtMs = anchoredStartMs
        Log.i(
            TAG,
            "Anchoring glucose timeline from glucose id=$glucoseId " +
                    "(oldStart=$oldStart newStart=$anchoredStartMs)"
        )
    }

    private fun commitReading(
        result: AnytimeAlgorithm.Result,
        sampleMs: Long,
        context: Context?,
        live: Boolean,
        history: Boolean,
        skipHistoryImport: Boolean = false,
    ): Boolean {
        if (result.errorCode != 0 || result.mgdlTimes10 < 170) {
            Log.w(
                TAG,
                "Dropping invalid BG id=%d source=%s mgdl=%.1f err=%d Iw=%.2f Ib=%.2f T=%.1f".format(
                    result.glucoseId,
                    result.source,
                    result.mgdl,
                    result.errorCode,
                    result.iwNa,
                    result.ibNa,
                    result.temperatureC,
                )
            )
            return false
        }
        val newest = sampleMs >= lastGlucoseAtMs
        if (result.calibrationStatus != AnytimeCalibrationPolicy.CALIBRATION_STATUS_UNKNOWN) {
            lastAlgorithmCalibrationStatus = result.calibrationStatus
        }
        if (newest) {
            lastGlucoseAtMs = sampleMs
            lastGlucoseMgdlTimes10 = result.mgdlTimes10
            lastRawMgdl = if (result.rawMgdl.isNaN()) result.mgdl else result.rawMgdl
            lastAlgorithmResult = result
        }
        Log.i(
            TAG,
            "BG id=%d %s mmol=%.2f mgdl=%.1f Iw=%.2fnA Ib=%.2fnA T=%.1fC trend=%d err=%d cal=%s".format(
                result.glucoseId, result.source, result.mmol, result.mgdl,
                result.iwNa, result.ibNa, result.temperatureC, result.trend, result.errorCode,
                AnytimeCalibrationPolicy.calibrationStatusName(result.calibrationStatus),
            )
        )
        // Managed Anytime history is written through Room only.
        // Do not mirror computed readings into native SensorGlucoseData here:
        // Natives.addGlucoseStream() stores values in the legacy/native history path
        // and was producing duplicate phantom rows such as 25.9 (mmol*10) beside
        // the Room-managed 2.59 mmol/L point.
        if (!skipHistoryImport) {
            mirrorReadingIntoRoom(sampleMs, result)
        } else {
            Log.d(TAG, "Skipping startup provisional Room import id=${result.glucoseId} source=${result.source}")
        }
        if (live) {
            emitGlucose(result, sampleMs)
        } else if (history && newest) {
            Log.d(TAG, "Backfill stored newer Room point without live emit id=${result.glucoseId}")
        }
        return true
    }

    private fun ensureNativeSensorShell() {
        val canonical = SerialNumber ?: return
        runCatching {
            val startSec = (sensorStartAtMs / 1000L).coerceAtLeast(1L)
            Natives.ensureSensorShell(canonical, startSec)
            if (dataptr == 0L) {
                dataptr = runCatching { Natives.getdataptr(canonical) }.getOrDefault(0L)
            }
        }.onFailure { Log.stack(TAG, "ensureNativeSensorShell", it) }
    }

    // Intentionally unused: managed Anytime history must not be mirrored into
    // native SensorGlucoseData. Current/live updates go through emitGlucose();
    // history rows go through VirtualGlucoseSensorBridge.importHistory().

    private fun mirrorReadingIntoRoom(sampleMs: Long, result: AnytimeAlgorithm.Result) {
        val name = SerialNumber ?: return
        runCatching {
            val imported = VirtualGlucoseSensorBridge.importHistory(
                sensorSerial = name,
                readings = listOf(
                    VirtualGlucoseSensorBridge.Reading(
                        timestampMs = sampleMs,
                        glucoseMgdl = result.mgdl,
                        rawMgdl = if (result.rawMgdl.isNaN()) result.mgdl else result.rawMgdl,
                    )
                ),
                logLabel = "Anytime ${result.source}",
            )
            if (imported > 0) {
                val raw = if (result.rawMgdl.isNaN()) result.mgdl else result.rawMgdl
                Log.i(TAG, "Imported $imported Anytime ${result.source} point into Room history (rawLinear=${"%.1f".format(raw)} mg/dL)")
            }
        }.onFailure { Log.stack(TAG, "mirrorReadingIntoRoom", it) }
    }

    private fun emitGlucose(result: AnytimeAlgorithm.Result, sampleMs: Long) {
        try {
            val displayValue = if (Applic.unit == 1) result.mmol else result.mgdl
            SuperGattCallback.processExternalCurrentReading(
                SerialNumber,
                displayValue,
                0f,
                sampleMs,
                SENSOR_GEN,
            )
        } catch (t: Throwable) {
            Log.stack(TAG, "emitGlucose", t)
        }
    }

    private fun algorithmTransmitterName(context: Context?): String {
        val id = SerialNumber.orEmpty()
        val cachedName = if (context != null && id.isNotBlank()) {
            AnytimeRegistry.loadDeviceName(context, id)
        } else {
            ""
        }
        val activeName = mBluetoothGatt?.device?.name.orEmpty()
        val familyPrefix = familyEntry.prefix.takeUnless { it == AnytimeConstants.FAMILY_UNKNOWN.prefix }.orEmpty()
        return listOf(cachedName, activeName, id, familyPrefix)
            .map { it.trim() }
            .firstOrNull { candidate ->
                candidate.isNotBlank() &&
                        AnytimeConstants.resolveFamily(candidate).family != AnytimeConstants.Family.UNKNOWN
            }
            ?: familyPrefix.ifBlank { id }
    }

    // ---- Frame writer ----

    private fun writeFrame(bytes: ByteArray, tag: String, expectResponse: Boolean = true): Boolean {
        if (bytes.isEmpty()) {
            Log.w(TAG, "writeFrame($tag) skipped empty frame")
            return false
        }
        val gatt = mBluetoothGatt ?: return false
        val ch = charWrite ?: return false
        ch.value = bytes
        ch.writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val ok = runCatching { gatt.writeCharacteristic(ch) }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "writeCharacteristic($tag) returned false bytes=${bytes.joinToHex()}")
            if (!tag.startsWith("pullGlucose(backfill)")) {
                recoverGattAndReconnect("writeCharacteristic($tag) returned false", ACTIVE_SESSION_RECONNECT_DELAY_MS)
            }
        } else {
            Log.d(TAG, "TX $tag bytes=${bytes.joinToHex()}")
            if (expectResponse) {
                armProtocolFrameTimeout(tag)
            }
        }
        return ok
    }

    // ---- AnytimeDriver implementation ----

    override fun setQrCalibration(rawQr: String): Boolean {
        val parsed = AnytimeAlgorithm.decodeQr(rawQr) ?: run {
            Log.w(TAG, "QR decode failed: $rawQr")
            return false
        }
        qr = parsed
        voltageFlag = parsed.voltageFlag
        persistAlgorithmState()
        // If actively streaming, push K/R now; otherwise queue for next init.
        // GS1/UDI package labels do not contain factory K/R, so they must not
        // be sent to the transmitter as calibration coefficients.
        if (!parsed.isFactoryCalibration) {
            pendingKrPush = false
            Log.i(TAG, "QR is product/UDI metadata only; using linear fallback defaults without inputKR")
        } else if (phase == Phase.STREAMING) {
            if (isCt5() && ct5CipherKey !in 0..255) {
                pendingKrPush = true
                Log.w(TAG, "CT5 K/R update deferred — cipher key is not established")
            } else {
                writeFrame(inputKrFrame(parsed.k, parsed.r), if (isCt5()) "ct5-setParameters(qr-update)" else "inputKR")
            }
        } else {
            pendingKrPush = true
        }
        return true
    }

    override fun pushReferenceBg(mgdl: Int): Boolean {
        if (mgdl <= 0) return false
        if (lastGlucoseId < 0) {
            Log.w(TAG, "pushReferenceBg($mgdl) rejected — waiting for first glucose id")
            setCalibrationStatus(
                resId = R.string.anytime_calibration_waiting_for_reading_status,
                fallback = "Calibration unavailable; waiting for first reading",
            )
            return false
        }
        val ageHours = getSensorAgeHours()
        if (!AnytimeCalibrationPolicy.canAcceptManualCalibration(ageHours)) {
            val ageLabel = if (ageHours >= 0) "${ageHours}h" else "unknown"
            Log.w(
                TAG,
                "pushReferenceBg($mgdl) rejected — manual calibration unavailable before " +
                        "${AnytimeCalibrationPolicy.MANUAL_CALIBRATION_MIN_AGE_HOURS}h (age=$ageLabel)"
            )
            setCalibrationStatus(
                resId = R.string.anytime_calibration_after_24h_status,
                fallback = "Calibration unavailable before 24h",
            )
            return false
        }
        if (!AnytimeCalibrationPolicy.canAcceptAlgorithmCalibrationStatus(lastAlgorithmCalibrationStatus)) {
            Log.w(
                TAG,
                "pushReferenceBg($mgdl) rejected — native calibration status is " +
                        AnytimeCalibrationPolicy.calibrationStatusName(lastAlgorithmCalibrationStatus)
            )
            setCalibrationStatus(
                resId = R.string.anytime_calibration_not_allowed_status,
                fallback = "Calibration not allowed by sensor right now",
            )
            return false
        }
        pendingFingerstickMgdl = mgdl
        // Official Anytime stores a calibration entered at current glucose id N
        // as an event for the next algorithm sample, N+1.
        pendingFingerstickTargetGlucoseId = lastGlucoseId + 1
        return if (phase == Phase.STREAMING) {
            val ok = writeFrame(inputBgFrame(mgdl), "inputBg($mgdl)")
            if (ok) {
                setCalibrationStatus(
                    resId = R.string.anytime_calibration_sent_status,
                    fallback = "Calibration sent; waiting for sensor",
                )
            } else {
                pendingFingerstickMgdl = -1
                pendingFingerstickTargetGlucoseId = -1
                setCalibrationStatus(
                    resId = R.string.anytime_calibration_send_failed_status,
                    fallback = "Calibration send failed",
                )
            }
            ok
        } else {
            Log.w(TAG, "pushReferenceBg($mgdl) deferred — not streaming (phase=$phase)")
            pendingFingerstickMgdl = -1
            pendingFingerstickTargetGlucoseId = -1
            setCalibrationStatus(
                resId = R.string.anytime_calibration_not_streaming_status,
                fallback = "Calibration unavailable while sensor is not streaming",
            )
            false
        }
    }

    override fun getReferenceCalibrationRecords(): List<AnytimeReferenceCalibrationRecord> =
        referenceCalibrationRecords


    override fun softDisconnect() {
        Log.i(TAG, "softDisconnect requested")
        setPause(true)
        cancelReconnect()
        clearGattCallbacks()
        val gatt = mBluetoothGatt
        phase = Phase.IDLE
        clearGattReferences()
        runCatching { gatt?.disconnect() }
            .onFailure { Log.stack(TAG, "softDisconnect(disconnect)", it) }
        runCatching { gatt?.close() }
            .onFailure { Log.stack(TAG, "softDisconnect(closeGatt)", it) }
        runCatching { close() }
            .onFailure { Log.stack(TAG, "softDisconnect(close)", it) }
        UiRefreshBus.requestStatusRefresh()
    }

    override fun softReconnect() {
        Log.i(TAG, "softReconnect requested")
        setPause(false)
        cancelReconnect()
        clearGattCallbacks()
        val gatt = mBluetoothGatt
        phase = Phase.IDLE
        clearGattReferences()
        runCatching { gatt?.disconnect() }
            .onFailure { Log.stack(TAG, "softReconnect(disconnect)", it) }
        runCatching { gatt?.close() }
            .onFailure { Log.stack(TAG, "softReconnect(closeGatt)", it) }
        runCatching { close() }
            .onFailure { Log.stack(TAG, "softReconnect(close)", it) }
        if (dataptr != 0L) {
            runCatching { Natives.unfinishSensor(dataptr) }
                .onFailure { Log.stack(TAG, "softReconnect(unfinishSensor)", it) }
        }
        handler.postDelayed({
            if (!stop) connectDevice(0)
        }, 250L)
        UiRefreshBus.requestStatusRefresh()
    }

    override fun terminateManagedSensor(wipeData: Boolean) {
        Log.i(TAG, "terminateManagedSensor requested wipeData=$wipeData")
        setPause(true)
        cancelReconnect()
        clearGattCallbacks()
        val gatt = mBluetoothGatt
        phase = Phase.IDLE
        clearGattReferences()
        runCatching { gatt?.disconnect() }
            .onFailure { Log.stack(TAG, "terminateManagedSensor(disconnect)", it) }
        runCatching { gatt?.close() }
            .onFailure { Log.stack(TAG, "terminateManagedSensor(closeGatt)", it) }
        runCatching { close() }
            .onFailure { Log.stack(TAG, "terminateManagedSensor(close)", it) }
        if (dataptr != 0L) {
            runCatching { Natives.finishfromSensorptr(dataptr) }
                .onFailure { Log.stack(TAG, "terminateManagedSensor(finishfromSensorptr)", it) }
            dataptr = 0L
        }
        if (wipeData) {
            Applic.app?.let { ctx ->
                runCatching { AnytimeRegistry.removeSensor(ctx, SerialNumber) }
                    .onFailure { Log.stack(TAG, "terminateManagedSensor(removeSensor)", it) }
            }
        }
        UiRefreshBus.requestStatusRefresh()
    }

    override fun requestTransmitterReset(): Boolean {
        if (phase != Phase.STREAMING && phase != Phase.HANDSHAKING) return false
        writeFrame(resetFrame(), "reset(user)")
        return true
    }

    override fun requestUnbind(): Boolean {
        writeFrame(unbindFrame(), "unbind(user)")
        bound = false
        persistAlgorithmState()
        stopHistoryBackfill()
        return true
    }

    override fun isUiEnabled(): Boolean = !stop

    override fun requestHistoryBackfill(): Boolean {
        if (phase != Phase.STREAMING) {
            Log.w(TAG, "requestHistoryBackfill ignored — phase=$phase")
            return false
        }
        startHistoryBackfill("user-requested", fromId = 0)
        return true
    }

    override fun getCurrentSnapshot(maxAgeMillis: Long): AnytimeCurrentSnapshot? {
        if (lastGlucoseAtMs == 0L) return null
        if (System.currentTimeMillis() - lastGlucoseAtMs > maxAgeMillis) return null
        return AnytimeCurrentSnapshot(
            timeMillis = lastGlucoseAtMs,
            glucoseValue = if (Applic.unit == 1) {
                lastGlucoseMgdlTimes10 * MGDL_TO_MMOLL / 10f
            } else {
                lastGlucoseMgdlTimes10 / 10f
            },
            rawValue = if (lastRawMgdl.isNaN()) {
                lastIwNa
            } else if (Applic.unit == 1) {
                lastRawMgdl * MGDL_TO_MMOLL
            } else {
                lastRawMgdl
            },
            rate = Float.NaN,
            sensorGen = SENSOR_GEN,
        )
    }

    override fun getStartTimeMs(): Long = sensorStartAtMs
    override fun getOfficialEndMs(): Long =
        if (sensorStartAtMs <= 0L) 0L else sensorStartAtMs + profile.ratedLifetimeMs()
    override fun getExpectedEndMs(): Long = 0L
    override fun isSensorExpired(): Boolean {
        val end = getOfficialEndMs()
        return end > 0L && System.currentTimeMillis() > end
    }
    override fun getSensorRemainingHours(): Int {
        val end = getOfficialEndMs()
        if (end <= 0L) return -1
        val ms = end - System.currentTimeMillis()
        if (ms <= 0L) return 0
        return (ms / 3_600_000L).toInt()
    }
    override fun getSensorAgeHours(): Int {
        if (sensorStartAtMs <= 0L) return -1
        return ((System.currentTimeMillis() - sensorStartAtMs) / 3_600_000L).toInt()
    }
    override fun getReadingIntervalMinutes(): Int = profile.readingIntervalMinutes

    override val vendorFirmwareVersion: String get() = transmitterVersion
    override val vendorModelName: String get() = familyEntry.family.displayName

    override val batteryMillivolts: Int get() = (lastBatteryVolts * 1000f).toInt()
    override val batteryPercent: Int
        get() = AnytimeFrames.batteryPercent(lastBatteryVolts, profile.lowBatteryVolts)

    override fun matchesManagedSensorId(sensorId: String?): Boolean =
        AnytimeConstants.matchesCanonicalOrKnownNativeAlias(sensorId, SerialNumber)

    override fun hasNativeSensorBacking(): Boolean = true

    override fun shouldUseNativeHistorySync(): Boolean = false

    private fun historyProgressStatus(): String {
        val next = nextBackfillIdSkippingCached((historyLastPulledId + 1).coerceAtLeast(0))
        val stopExclusive = when {
            historyStopBeforeId != Int.MAX_VALUE -> historyStopBeforeId
            lastGlucoseId >= 0 -> lastGlucoseId + 1
            else -> familyEntry.endNumber
        }.coerceAtLeast(1)
        val done = next.coerceIn(0, stopExclusive)
        return "Fetching history $done/$stopExclusive"
    }

    override fun getDetailedBleStatus(): String {
        val base = if (stop) {
            "Paused"
        } else when (phase) {
            Phase.IDLE -> "Idle"
            Phase.CONNECTING -> "Connecting"
            Phase.DISCOVERING -> "Discovering"
            Phase.HANDSHAKING -> "Handshaking"
            Phase.STREAMING -> if (historyBackfillActive) {
                historyProgressStatus()
            } else "Connected"
        }
        val calibrationStatus = visibleCalibrationStatus()
        return if (calibrationStatus.isBlank()) base else "$base - $calibrationStatus"
    }

    /**
     * Format the rich algorithm-internal state for a debug pane. Returns null if
     * we have no readings yet or are running on the linear-fallback path (where
     * none of the diagnostic fields are populated).
     */
    fun getAlgorithmDiagnostics(): String? {
        val r = lastAlgorithmResult ?: return null
        if (r.source == AnytimeAlgorithm.Source.LINEAR) {
            return "Linear fallback · K=${qr?.k ?: 0f} R=${qr?.r ?: 0f}\n" +
                    "Iw=${"%.2f".format(r.iwNa)} nA · Ib=${"%.2f".format(r.ibNa)} nA · T=${"%.1f".format(r.temperatureC)}°C"
        }
        val voltagesLine = if (r.weVoltageMv != Int.MIN_VALUE) {
            "WE=${r.weVoltageMv}mV BE=${r.beVoltageMv}mV RE=${r.reVoltageMv}mV CE=${r.ceVoltageMv}mV B=${r.bVoltageMv}mV"
        } else ""
        val iirLine = if (!r.iw30Iir.isNaN() || !r.iw48Iir.isNaN()) {
            "Iw30IIR=${"%.2f".format(r.iw30Iir)} Iw48IIR=${"%.2f".format(r.iw48Iir)}"
        } else ""
        val kLine = if (!r.kBase.isNaN() || !r.kAuto.isNaN()) {
            "K_BASE=${"%.3f".format(r.kBase)} K_AUTO=${"%.3f".format(r.kAuto)} sens=${"%.3f".format(r.sensitivityCoefficient)}"
        } else ""
        return listOf(
            "Vendor algorithm",
            "Iw=${"%.2f".format(r.iwNa)} nA · Ib=${"%.2f".format(r.ibNa)} nA · T=${"%.1f".format(r.temperatureC)}°C",
            voltagesLine,
            iirLine,
            kLine,
            "Trend=${r.trend} Err=${r.errorCode} Warn=${r.warnCode}",
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
}

// ---- Local helpers ----

private fun ByteArray.joinToHex(): String =
    joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
