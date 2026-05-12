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
import java.util.UUID
import tk.glucodata.Applic
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
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
        private const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 1_500L
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2

        /** No-data watchdog multiplier applied to readingIntervalMinutes. */
        private const val NO_DATA_WATCHDOG_MULTIPLIER = 4L

        /** Watchdog after init — if no push/pull within 3.5× readingInterval, re-pull. */
        private const val PULL_FALLBACK_MULTIPLIER = 3L

        /** Backfill loop: gap between consecutive pulls when records keep arriving. */
        private const val HISTORY_PULL_BATCH_DELAY_MS = 10L

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

        /** Avoid duplicate Room rows when the same point arrives through live/backfill/native refresh. */
        private const val ROOM_HISTORY_NEAR_DUPLICATE_MS = 90_000L

        /** Legacy Yuwell JNI returns 0 until it has enough warmup history. */
        private const val NATIVE_HISTORY_WARMUP_RECORDS = 20

        /**
         * Fresh installs should become useful quickly, then continue filling older
         * history in the background. CT4 pulls are one-record-per-request, so doing
         * id=0..current before showing the recent tail feels broken.
         */
        private const val FRESH_AUTO_BACKFILL_RECORDS = 24

        /** Small pause between the quick recent tail and the older full-prefix fill. */
        private const val FRESH_OLDER_BACKFILL_DELAY_MS = 2_000L

        /** Some CT4 units do not ACK init; do not wait for the next 3-minute push if we can resume from cache. */
        private const val INIT_NO_ACK_STREAMING_GRACE_MS = 650L

        /** Tiny private cache for UI-only telemetry that is not part of the raw algorithm state. */
        private const val TELEMETRY_PREFS = "anytime_ble_telemetry"
        private const val TELEMETRY_BATTERY_VOLTS_PREFIX = "battery_volts_"

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
    @Volatile private var packetsSinceInit: Int = 0
    @Volatile private var bound: Boolean = false
    @Volatile private var reconnectReason: String = ""
    @Volatile private var serviceDiscoveryHandled: Boolean = false
    @Volatile private var serviceDiscoveryRetryCount: Int = 0
    @Volatile private var pendingCccdGatt: BluetoothGatt? = null
    @Volatile private var lastConnectRequestAtMs: Long = 0L
    @Volatile private var pendingFingerstickMgdl: Int = -1
    @Volatile private var pendingKrPush: Boolean = false
    @Volatile private var lastProtocolFrameAtMs: Long = 0L
    @Volatile private var lastProtocolFrameTag: String = ""
    @Volatile private var ct4HandshakeFallbackStep: Int = 0
    @Volatile private var postVoltagePlainControlFrames: Boolean = false
    // Native Yuwell algorithm inputs have no start-id field; the raw arrays must
    // represent glucose ids from 0..current. Keep the full session history.
    private val rawAlgorithmWindow = java.util.TreeMap<Int, AnytimeRawRecord>()
    private val pendingNativeRecomputeIds = java.util.TreeSet<Int>()

    /**
     * During the fresh recent-tail pass rolling native can be computed from a
     * still-growing window (e.g. id=589 before ids=590..593 are present). Keep
     * those ids and rewrite them once the whole tail range is loaded so the
     * chart settles on one final native lane instead of keeping transitional
     * rolling estimates.
     */
    private val freshRecentTailFinalRecomputeIds = java.util.TreeSet<Int>()

    /** True only when this process restored an existing Anytime session cache. */
    @Volatile private var restoredGlucoseState: Boolean = false

    /** Fresh sessions first get a short recent tail, then the older prefix in background. */
    @Volatile private var freshPostLiveBackfillStarted: Boolean = false
    @Volatile private var freshOlderBackfillStarted: Boolean = false
    @Volatile private var pendingFreshOlderBackfillStartId: Int = -1

    // ---- History backfill loop state ----
    @Volatile private var historyBackfillActive: Boolean = false
    @Volatile private var historyEmptyResponsesInARow: Int = 0
    @Volatile private var historyLastPulledId: Int = -1
    @Volatile private var historyPullInFlight: Boolean = false
    @Volatile private var historyStopBeforeId: Int = Int.MAX_VALUE
    @Volatile private var historyBackfillReason: String = ""

    override var viewMode: Int = 0

    // ---- Restore from persistence ----

    fun restoreFromPersistence(context: Context) {
        val id = SerialNumber ?: return
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
        saveCachedBatteryVolts(ctx, id, lastBatteryVolts)
        AnytimeRegistry.saveRawHistory(ctx, id, synchronized(rawAlgorithmWindow) { rawAlgorithmWindow.values.toList() })
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
        Log.d(TAG, "Backfill pull next id=$nextId")
        if (!writeFrame(pullGlucoseFrame(nextId), "pullGlucose(backfill)")) {
            historyPullInFlight = false
            handler.postDelayed(historyBackfillRunnable, HISTORY_PULL_BATCH_DELAY_MS)
        }
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
        if (!restoredGlucoseState) return 0
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

    private fun rememberFreshRecentTailForFinalRewrite(id: Int) {
        if (!isFreshRecentBackfillReason()) return
        val startId = pendingFreshOlderBackfillStartId
        if (startId < 0 || id < startId) return
        synchronized(freshRecentTailFinalRecomputeIds) {
            freshRecentTailFinalRecomputeIds.add(id)
        }
    }

    private fun hasContiguousRawHistoryThrough(targetId: Int): Boolean {
        return firstMissingRawHistoryIdThrough(targetId) == null
    }

    private fun hasContiguousRawTailThrough(targetId: Int, minCount: Int): Boolean {
        if (targetId < 0 || minCount <= 0) return false
        synchronized(rawAlgorithmWindow) {
            var count = 0
            var id = targetId
            while (id >= 0 && rawAlgorithmWindow.containsKey(id)) {
                count++
                if (count >= minCount) return true
                id--
            }
        }
        return false
    }

    /** Contiguous records ending at targetId, enough for rolling native without O(n²) copies. */
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
        val completedStopBefore = historyStopBeforeId
        stopHistoryBackfill()

        if (isFreshRecentBackfillReason(completedReason)) {
            // Fast UX pass: rewrite the recent tail once the tail itself is loaded.
            // These values may still be rolling/provisional because ids 0..recentStart-1
            // are not loaded yet, so they must remain pending for the final prefix pass.
            rewriteFreshRecentTailAfterRangeComplete(completedStopBefore)
        } else if (completedReason.startsWith("post-live-anchor(older-background)")) {
            // Final correctness pass after the older prefix exists.
            recomputePendingNativeReadings(
                context = Applic.app,
                intervalMs = profile.readingIntervalMinutes * 60L * 1000L,
            )
        }

        maybeStartPendingFreshOlderBackfill()
    }


    private fun rewriteFreshRecentTailAfterRangeComplete(completedStopBefore: Int) {
        if (!nativeAlgorithmExpected()) return
        val ids = synchronized(freshRecentTailFinalRecomputeIds) {
            freshRecentTailFinalRecomputeIds
                .filter { it < completedStopBefore }
                .also { done -> freshRecentTailFinalRecomputeIds.removeAll(done.toSet()) }
        }
        if (ids.isEmpty()) return

        val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
        if (intervalMs <= 0L) return
        val context = Applic.app
        var replaced = 0
        for (id in ids) {
            if (!hasContiguousRawHistoryThrough(id) && !hasContiguousRawTailThrough(id, NATIVE_HISTORY_WARMUP_RECORDS)) continue
            val rec = synchronized(rawAlgorithmWindow) { rawAlgorithmWindow[id] } ?: continue
            val sampleMs = when {
                glucoseTimelineStartAtMs > 0L -> glucoseTimelineStartAtMs + id.toLong() * intervalMs
                sensorStartAtMs > 0L -> sensorStartAtMs + id.toLong() * intervalMs
                else -> continue
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
            if (result.source == AnytimeAlgorithm.Source.NATIVE && result.errorCode == 0) {
                commitReading(
                    result = result,
                    sampleMs = sampleMs,
                    context = context,
                    live = id == lastGlucoseId || sampleMs >= lastGlucoseAtMs,
                    history = true,
                )
                // Keep rolling recent-tail native points pending until the older
                // prefix is loaded; otherwise the startup bump is never replaced.
                trackNativeRecomputeNeed(result)
                replaced++
            }
        }
        if (replaced > 0) {
            Log.i(TAG, "Rewrote $replaced fresh recent-tail point(s) after full tail range loaded")
            UiRefreshBus.requestStatusRefresh()
        }
    }

    private fun stopHistoryBackfill() {
        historyBackfillActive = false
        historyPullInFlight = false
        historyStopBeforeId = Int.MAX_VALUE
        historyBackfillReason = ""
        handler.removeCallbacks(historyBackfillRunnable)
    }

    private val serviceDiscoveryWatchdog = Runnable {
        if (stop || phase != Phase.DISCOVERING || serviceDiscoveryHandled) return@Runnable
        Log.w(TAG, "Service discovery wedged — reconnecting")
        recoverGattAndReconnect("service discovery timeout", ACTIVE_SESSION_RECONNECT_DELAY_MS)
    }

    private val serviceDiscoveryRetryRunnable: Runnable = Runnable {
        if (stop || serviceDiscoveryHandled) return@Runnable
        if (serviceDiscoveryRetryCount >= MAX_SERVICE_DISCOVERY_RETRIES) return@Runnable
        serviceDiscoveryRetryCount++
        val gatt = mBluetoothGatt ?: return@Runnable
        if (gatt.discoverServices()) {
            handler.postDelayed(serviceDiscoveryRetryRunnable, SERVICE_DISCOVERY_RETRY_DELAY_MS)
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
        pendingCccdGatt = null
        clearProtocolFrameTimeout()
        stopHistoryBackfill()
    }

    private fun clearGattReferences() {
        primaryService = null
        charNotify = null
        charWrite = null
        serviceDiscoveryHandled = false
        serviceDiscoveryRetryCount = 0
        mBluetoothGatt = null
        mActiveBluetoothDevice = null
    }

    private fun recoverGattAndReconnect(reason: String, delayMs: Long = ACTIVE_SESSION_RECONNECT_DELAY_MS) {
        if (stop) return
        val gatt = mBluetoothGatt
        Log.w(TAG, "Recovering GATT: $reason")
        clearGattCallbacks()
        phase = Phase.IDLE
        clearGattReferences()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        scheduleReconnect(reason, delayMs)
        UiRefreshBus.requestStatusRefresh()
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
        )

    private fun usesWideRawRecords(): Boolean = usesSummedFrames()

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
        if (usesSummedFrames()) AnytimeFrames.Builders.unbindSummed() else AnytimeFrames.Builders.unbind()

    private fun setDateFrame(): ByteArray =
        if (usesPlainControlFrames()) AnytimeFrames.Builders.setDate()
        else if (usesSummedFrames()) AnytimeFrames.Builders.setDateSummed()
        else AnytimeFrames.Builders.setDate()

    private fun pullGlucoseFrame(nextId: Int): ByteArray =
        if (usesPlainControlFrames()) AnytimeFrames.Builders.pullGlucose(nextId)
        else if (usesSummedFrames()) AnytimeFrames.Builders.pullGlucoseSummed(nextId)
        else AnytimeFrames.Builders.pullGlucose(nextId)

    private fun inputKrFrame(k: Float, r: Float): ByteArray =
        if (usesSummedFrames()) AnytimeFrames.Builders.inputKRSummed(k, r) else AnytimeFrames.Builders.inputKR(k, r)

    private fun inputBgFrame(mgdl: Int): ByteArray =
        if (usesSummedFrames()) AnytimeFrames.Builders.inputBgMgSummed(mgdl) else AnytimeFrames.Builders.inputBgMg(mgdl)

    private fun transmitterFormalFrame(): ByteArray =
        if (usesSummedFrames()) AnytimeFrames.Builders.transmitterFormalSummed() else AnytimeFrames.Builders.transmitterFormal()

    private fun usesPlainControlFrames(): Boolean = false

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
                phase = Phase.DISCOVERING
                runCatching { gatt.requestMtu(AnytimeConstants.DEFAULT_MTU) }
                handler.postDelayed({
                    if (phase == Phase.DISCOVERING && mBluetoothGatt === gatt && !serviceDiscoveryHandled) {
                        if (gatt.discoverServices()) {
                            handler.postDelayed(serviceDiscoveryRetryRunnable, SERVICE_DISCOVERY_RETRY_DELAY_MS)
                        }
                    }
                }, 250)
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
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        handler.removeCallbacks(serviceDiscoveryWatchdog)
        handler.removeCallbacks(serviceDiscoveryRetryRunnable)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "onServicesDiscovered failed status=$status")
            recoverGattAndReconnect("services discovery failed status=$status", ACTIVE_SESSION_RECONNECT_DELAY_MS)
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
        if (phase != Phase.HANDSHAKING) {
            Log.d(TAG, "Ignoring check response while phase=$phase")
            return
        }
        val status = AnytimeFrames.parseCheckResponse(data, profile.lowBatteryVolts)
        lastBatteryVolts = status.batteryVolts
        saveCachedBatteryVolts(Applic.app, SerialNumber, lastBatteryVolts)
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
                writeFrame(inputKrFrame(it.k, it.r), "inputKR(deferred)")
            }
        }
        writeFrame(lowPowerFrame(), "lowPower", expectResponse = false)
        armPullFallback()
        armNoDataWatchdog()
        // Restored sessions already have a usable id→time timeline, so resume
        // the missing tail immediately. Fresh sessions wait for the first real
        // live 0x07 anchor and then auto-fill only a short recent tail. Full
        // replay from id=0 remains available through requestHistoryBackfill().
        if (hasUsableHistoryTimeline()) {
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
            writeFrame(lowPowerFrame(), "lowPower(post-reset)", expectResponse = false)
            armPullFallback()
            armNoDataWatchdog()
            handler.postDelayed({ startHistoryBackfill("post-reset(reconnect)", fromId = reconnectBackfillStartId()) }, 750L)
            UiRefreshBus.requestStatusRefresh()
        } else {
            // Sensor lost binding — fall through to fresh handshake.
            writeFrame(checkFrame(), "check(post-reset)")
        }
    }

    private fun handleInputBgAck(data: ByteArray) {
        Log.i(TAG, "Fingerstick BG accepted: ${data.joinToHex()}")
        if (pendingFingerstickMgdl > 0 && lastGlucoseId >= 0) {
            lastReferenceBgMgdlTimes10 = pendingFingerstickMgdl * 10
            lastReferenceBgGlucoseId = lastGlucoseId
        }
        pendingFingerstickMgdl = -1
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
        val records = AnytimeFrames.parseRawRecords(data, usesWideRawRecords())
        val context = Applic.app
        val intervalMs = profile.readingIntervalMinutes * 60L * 1000L
        if (records.isEmpty()) {
            // Empty pull response — transmitter has nothing more to give.
            Log.d(TAG, "Empty raw frame (pull caught-up): ${data.joinToHex()}")
            if (!push) historyPullInFlight = false
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
            historyPullInFlight = false
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
            packetsSinceInit++
            synchronized(rawAlgorithmWindow) {
                rawAlgorithmWindow[rec.glucoseId] = rec
            }
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
            commitReading(result, sampleMs, context, live = push, history = !push)
            trackNativeRecomputeNeed(result)
            if (rec.glucoseId > lastGlucoseId) lastGlucoseId = rec.glucoseId
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

    private fun trackNativeRecomputeNeed(result: AnytimeAlgorithm.Result) {
        rememberFreshRecentTailForFinalRewrite(result.glucoseId)
        if (!nativeAlgorithmExpected()) return

        val id = result.glucoseId
        val hasFullPrefix = hasContiguousRawHistoryThrough(id)
        val hasRollingTail = hasContiguousRawTailThrough(id, NATIVE_HISTORY_WARMUP_RECORDS)

        synchronized(pendingNativeRecomputeIds) {
            if (id < NATIVE_HISTORY_WARMUP_RECORDS) {
                pendingNativeRecomputeIds.remove(id)
                return@synchronized
            }

            if (result.source == AnytimeAlgorithm.Source.NATIVE && result.errorCode == 0) {
                if (hasFullPrefix) {
                    pendingNativeRecomputeIds.remove(id)
                } else if (hasRollingTail) {
                    pendingNativeRecomputeIds.add(id)
                } else {
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
            commitReading(result, sampleMs, context, live = id == lastGlucoseId || sampleMs >= lastGlucoseAtMs, history = true)
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
    ) {
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
            return
        }
        val newest = sampleMs >= lastGlucoseAtMs
        if (newest) {
            lastGlucoseAtMs = sampleMs
            lastGlucoseMgdlTimes10 = result.mgdlTimes10
            lastRawMgdl = if (result.rawMgdl.isNaN()) result.mgdl else result.rawMgdl
            lastAlgorithmResult = result
        }
        Log.i(
            TAG,
            "BG id=%d %s mmol=%.2f mgdl=%.1f Iw=%.2fnA Ib=%.2fnA T=%.1fC trend=%d err=%d".format(
                result.glucoseId, result.source, result.mmol, result.mgdl,
                result.iwNa, result.ibNa, result.temperatureC, result.trend, result.errorCode,
            )
        )
        // Managed Anytime history is written through Room only.
        // Do not mirror computed readings into native SensorGlucoseData here:
        // Natives.addGlucoseStream() stores values in the legacy/native history path
        // and was producing duplicate phantom rows such as 25.9 (mmol*10) beside
        // the Room-managed 2.59 mmol/L point.
        mirrorReadingIntoRoom(sampleMs, result)
        if (live) {
            emitGlucose(result, sampleMs)
        } else if (history && newest) {
            Log.d(TAG, "Backfill stored newer Room point without live emit id=${result.glucoseId}")
        }
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
                nearDuplicateWindowMs = ROOM_HISTORY_NEAR_DUPLICATE_MS,
            )
            if (imported > 0) {
                val raw = if (result.rawMgdl.isNaN()) result.mgdl else result.rawMgdl
                Log.i(TAG, "Imported $imported Anytime ${result.source} point into Room history (rawLinear=${"%.1f".format(raw)} mg/dL)")
            }
        }.onFailure { Log.stack(TAG, "mirrorReadingIntoRoom", it) }
    }

    private fun emitGlucose(result: AnytimeAlgorithm.Result, sampleMs: Long) {
        val alarm = 0L
        val rateShort = 0 // we do not compute trend rate yet
        val mgdlTimes10 = result.mgdlTimes10.toLong() and 0xFFFFFFFFL
        val res = (alarm shl 48) or ((rateShort.toLong() and 0xFFFF) shl 32) or mgdlTimes10
        try {
            val raw = if (result.rawMgdl.isNaN()) result.mgdl else result.rawMgdl
            handleGlucoseResult(res, sampleMs, raw)
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
            writeFrame(inputKrFrame(parsed.k, parsed.r), "inputKR")
        } else {
            pendingKrPush = true
        }
        return true
    }

    override fun pushReferenceBg(mgdl: Int): Boolean {
        if (mgdl <= 0) return false
        pendingFingerstickMgdl = mgdl
        return if (phase == Phase.STREAMING) {
            writeFrame(inputBgFrame(mgdl), "inputBg($mgdl)")
            true
        } else {
            Log.w(TAG, "pushReferenceBg($mgdl) deferred — not streaming (phase=$phase)")
            false
        }
    }


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
    override fun getExpectedEndMs(): Long = getOfficialEndMs()
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

    override fun getDetailedBleStatus(): String = if (stop) {
        "Paused"
    } else when (phase) {
        Phase.IDLE -> "Idle"
        Phase.CONNECTING -> "Connecting"
        Phase.DISCOVERING -> "Discovering"
        Phase.HANDSHAKING -> "Handshaking"
        Phase.STREAMING -> if (lastGlucoseAtMs > 0L) {
            val ageMin = ((System.currentTimeMillis() - lastGlucoseAtMs) / 60000L).toInt()
            "Streaming • last reading ${ageMin}m ago"
        } else "Streaming (warming up)"
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
