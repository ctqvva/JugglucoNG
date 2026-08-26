package tk.glucodata.data

import tk.glucodata.Natives
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.BatteryTrace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import android.util.Log
import android.os.SystemClock
import tk.glucodata.Applic
import tk.glucodata.SensorIdentity
import tk.glucodata.ui.util.inDisplayUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Repository that bridges native glucose data with the independent Room history database.
 * New readings are stored in Room for long-term history while still using native data for
 * real-time display and calibration.
 *
 * Multi-sensor: current/live reads stay pinned to the selected sensor. Dashboard history
 * queries use that same sensor's Room history directly so the dashboard never switches to a
 * broad merged timeline just because older data is visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlucoseRepository {
    
    private val historyRepository = HistoryRepository(Applic.app)
    
    /**
     * Reactive sensor serial. Updated by [refreshSensorSerial] (called from
     * DashboardViewModel.onResume / refreshData). Flows that depend on the
     * current sensor use [flatMapLatest] on this so they automatically
     * re-subscribe when the main sensor changes.
     */
    private val _currentSerial = MutableStateFlow(
        SensorIdentity.resolveMainSensor() ?: ""
    )
    val currentSerial = _currentSerial.asStateFlow()
    
    /**
     * Call this whenever the main sensor may have changed (resume, sensor switch, etc.).
     * If the serial actually changed, all flows keyed on [_currentSerial] will restart.
     */
    fun refreshSensorSerial(preferredSerial: String? = null) {
        val current = _currentSerial.value
        val nativeCurrent = SensorIdentity.resolveMainSensor()
            ?.takeIf { it.isNotBlank() }
        val resolved = preferredSerial?.takeIf { it.isNotBlank() }
            ?: nativeCurrent
            ?: current.takeIf { it.isNotBlank() }
            ?: ""

        if (resolved != current) {
            Log.d(TAG, "Sensor serial changed: '$current' → '$resolved'")
            _currentSerial.value = resolved
        }
    }

    /**
     * Fold overlapping imported history into the current real sensor immediately.
     * Used for the "import after the sensor is already connected" order; the
     * backfill path handles the opposite order. No-op when there is no current
     * sensor (nothing to fold into yet).
     */
    suspend fun reconcileImportedIntoCurrentSensor() {
        val serial = SensorIdentity.resolveMainSensor()?.takeIf { it.isNotBlank() } ?: return
        historyRepository.reconcileImportedIntoSensor(serial)
    }

    companion object {
        private const val TAG = "GlucoseRepo"
        private const val ONE_SHOT_SYNC_MIN_INTERVAL_MS = 5_000L
    }

    @Volatile
    private var lastOneShotSyncStartedAtMs = 0L

    /**
     * Get the current reading for the main sensor.
     * OBSERVES the Room Database (Single Source of Truth) filtered by main sensor serial.
     * Live updates are pushed into Room from real sensor events; targeted native recovery
     * remains explicit in higher layers (startup/reconnect/manual repair) instead of being
     * hidden inside the current-reading subscription.
     *
     * Reactive: Uses [flatMapLatest] on [_currentSerial] so when the main sensor
     * changes, the flow automatically re-subscribes to the new sensor's data.
     */
    fun getCurrentReading(): Flow<GlucosePoint?> = channelFlow {
        _currentSerial.collectLatest { serial ->
            if (serial.isEmpty()) {
                Log.w(TAG, "getCurrentReading: no main sensor serial, returning empty flow")
                send(null)
                return@collectLatest
            }

            historyRepository.getLatestReadingFlowForSensor(serial).collect { point ->
                val isMmol = (Natives.getunit() == 1)
                send(point?.inDisplayUnit(isMmol))
            }
        }
    }

    suspend fun syncLatestNativeReadingOnce() {
        val serial = _currentSerial.value.takeIf { it.isNotBlank() }
            ?: SensorIdentity.resolveMainSensor()
            ?: Natives.lastsensorname()
        if (!SensorIdentity.shouldUseNativeHistorySync(serial)) {
            Log.d(TAG, "syncLatestNativeReadingOnce skipped for driver-owned Room sensor '$serial'")
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        synchronized(this) {
            if ((nowMs - lastOneShotSyncStartedAtMs) < ONE_SHOT_SYNC_MIN_INTERVAL_MS) {
                Log.d(TAG, "syncLatestNativeReadingOnce skipped — last run was ${(nowMs - lastOneShotSyncStartedAtMs)}ms ago")
                return
            }
            lastOneShotSyncStartedAtMs = nowMs
        }
        BatteryTrace.bump("glucose.native.one_shot_sync", logEvery = 20L)
        pollNativeAndStore()
    }

    /** Timestamp (ms) of the last reading we stored, to avoid redundant writes. */
    private var lastStoredTimestampMs = 0L

    /**
     * Poll the main sensor's latest data and store it in Room, tagged with sensor serial.
     *
     * IMPORTANT: We use ONLY [Natives.getGlucoseHistory] here, which reads from
     * the user-selected main sensor ([infoblockptr()->current] in C++).
     * The reading is tagged with [Natives.lastsensorname] to identify which sensor
     * produced it.
     */
    private suspend fun pollNativeAndStore() {
        try {
            BatteryTrace.bump("glucose.native.poll_once", logEvery = 20L)
            // Get the main sensor serial for tagging
            val sensorSerial = Natives.lastsensorname() ?: "unknown"
            val canonicalSerial = SensorIdentity.resolveAppSensorId(sensorSerial) ?: sensorSerial
            if (canonicalSerial.isBlank()) return
            
            // Fetch the last 2 minutes of main-sensor history.
            // getGlucoseHistory uses infoblockptr()->current — main sensor only.
            val nowSec = System.currentTimeMillis() / 1000L
            val rawHistory = Natives.getGlucoseHistory(nowSec - 120)
            if (rawHistory == null || rawHistory.size < 3) return

            val lastIdx = rawHistory.size - 3
            val timeSec = rawHistory[lastIdx]
            val timestampMs = timeSec * 1000L

            // Skip if we already stored this exact reading
            if (timestampMs == lastStoredTimestampMs) return

            val valueMgdl = rawHistory[lastIdx + 1] / 10f
            val rawValueMgdl = rawHistory[lastIdx + 2] / 10f
            if (valueMgdl <= 0f && rawValueMgdl <= 0f) return

            var rate = 0f
            if (rawHistory.size >= 6) {
                val prevIdx = rawHistory.size - 6
                val prevTimeSec = rawHistory[prevIdx]
                val prevValueMgdl = rawHistory[prevIdx + 1] / 10f
                val dtMin = (timeSec - prevTimeSec) / 60f
                if (dtMin > 0f && prevValueMgdl > 0f) {
                    rate = (valueMgdl - prevValueMgdl) / dtMin
                }
            }

            historyRepository.storeReading(
                timestamp = timestampMs,
                value = valueMgdl,
                rawValue = rawValueMgdl,
                rate = rate,
                sensorSerial = canonicalSerial
            )
            lastStoredTimestampMs = timestampMs
        } catch (e: Exception) {
            Log.e(TAG, "Error polling native glucose: ${e.message}")
        }
    }

    private fun resolveDisplayPreferredSerial(explicitSerial: String? = null): String? {
        val preferred = (SensorIdentity.resolveAppSensorId(explicitSerial) ?: explicitSerial)
            ?.takeIf { it.isNotBlank() }
            ?: _currentSerial.value.takeIf { it.isNotBlank() }
        if (!preferred.isNullOrBlank()) {
            return preferred
        }
        return SensorIdentity.resolveMainSensor()
    }

    private suspend fun loadDisplayHistory(
        preferredSerial: String?,
        startTime: Long
    ): List<GlucosePoint> {
        val resolvedSerial = preferredSerial?.takeIf { it.isNotBlank() }
        return if (resolvedSerial != null) {
            historyRepository.getHistoryForDisplaySensor(resolvedSerial, startTime)
        } else {
            historyRepository.getDisplayHistory(null, startTime)
        }
    }

    private fun observeDisplayHistory(
        preferredSerial: String?,
        startTime: Long
    ): Flow<List<GlucosePoint>> {
        val resolvedSerial = preferredSerial?.takeIf { it.isNotBlank() }
        return if (resolvedSerial != null) {
            historyRepository.getHistoryFlowForDisplaySensor(resolvedSerial, startTime)
        } else {
            historyRepository.getDisplayHistoryFlow(null, startTime)
        }
    }

    /**
     * Dedicated merged multi-sensor flow for the History browse screen.
     *
     * The dashboard chart deliberately narrows to the current sensor (so the
     * live chart and `shouldRequestHistoryRecovery` see only that sensor's tail
     * — see commits c812ddb9 and 30e4d11f). The History screen needs the full
     * cross-sensor timeline so the previous sensor's calibrated readings, CSV
     * imports, and older device data remain visible after a sensor swap.
     *
     * Merge prefers the current sensor inside a 5-min overlap window; older
     * sensor rows only fill genuine gaps.
     */
    fun getMergedHistoryFlowRaw(startTime: Long = 0L): Flow<List<GlucosePoint>> {
        return _currentSerial.flatMapLatest { serial ->
            val preferredSerial = resolveDisplayPreferredSerial(serial)
            channelFlow {
                launch {
                    historyRepository.ensureBackfilled(preferredSerial, startTime)
                }
                historyRepository.getDisplayHistoryFlow(preferredSerial, startTime).collect { points ->
                    send(points)
                }
            }
        }
    }

    /**
     * Get ALL history from the Room database for the main sensor.
     * No time limit - fetches everything available for the main sensor.
     */
    suspend fun getAllHistory(): List<GlucosePoint> {
        val isMmol = (Natives.getunit() == 1)
        val preferredSerial = resolveDisplayPreferredSerial()
        historyRepository.ensureBackfilled(preferredSerial, 0L)
        return loadDisplayHistory(preferredSerial, 0L).inDisplayUnit(isMmol)
    }

    /**
     * Get history since startTime, converting if needed.
     * Filters by main sensor serial.
     */
    suspend fun getHistory(startTime: Long, isMmol: Boolean): List<GlucosePoint> {
        val preferredSerial = resolveDisplayPreferredSerial()
        historyRepository.ensureBackfilled(preferredSerial, startTime)
        return loadDisplayHistory(preferredSerial, startTime).inDisplayUnit(isMmol)
    }

    /**
     * Get the merged display timeline used by History/Stats-style views. This
     * keeps readable/CSV exports aligned with screen-level history after a
     * sensor swap instead of narrowing export output to only the active sensor.
     */
    suspend fun getMergedHistory(startTime: Long, isMmol: Boolean): List<GlucosePoint> {
        val preferredSerial = resolveDisplayPreferredSerial()
        historyRepository.ensureBackfilled(preferredSerial, startTime)
        return historyRepository.getDisplayHistory(preferredSerial, startTime).inDisplayUnit(isMmol)
    }

    /**
     * Get history as a Flow for reactive updates.
     * Follows the selected sensor's Room history directly when we know which sensor is
     * active, and only falls back to the merged display timeline when there is no current
     * sensor yet.
     */
    fun getHistoryFlow(startTime: Long = 0L, isMmol: Boolean): Flow<List<GlucosePoint>> {
        return _currentSerial.flatMapLatest { serial ->
            val preferredSerial = resolveDisplayPreferredSerial(serial)
            channelFlow {
                launch {
                    historyRepository.ensureBackfilled(preferredSerial, startTime)
                }
                observeDisplayHistory(preferredSerial, startTime).collect { points ->
                    send(points)
                }
            }
        }.map { list -> list.inDisplayUnit(isMmol) }
    }

    /**
     * Get history as a Flow in RAW mg/dL (no conversion).
     * Uses the current sensor's Room history when available.
     */
    fun getHistoryFlowRaw(startTime: Long = 0L): Flow<List<GlucosePoint>> {
        return _currentSerial.flatMapLatest { serial ->
            val preferredSerial = resolveDisplayPreferredSerial(serial)
            channelFlow {
                launch {
                    historyRepository.ensureBackfilled(preferredSerial, startTime)
                }
                observeDisplayHistory(preferredSerial, startTime).collect { points ->
                    send(points)
                }
            }
        }
    }

    /**
     * The dashboard chart's line: the same merged cross-sensor timeline the
     * History screen draws, over the same unbounded range.
     *
     * It used to follow the current sensor's serial alone, so a swapped-out
     * sensor — no longer in `activeSensors()`, so its serial resolved to nothing
     * — silently left the dashboard while History, stats and export all still
     * showed it. This is deliberately the identical call History makes, because
     * the two disagreeing about the same readings is the bug.
     *
     * Bounding this to the visible window is not available, and the reason is
     * worth writing down because it looks like an obvious optimisation:
     *
     *  - [HistoryDisplayMerge] decides which sensor wins by building the
     *    preferred sensor's coverage segments *from the rows it is given*. Hand
     *    it a window containing none of that sensor's rows — any span older than
     *    the current sensor — and it suppresses nothing, so every other sensor's
     *    rows draw raw. The merge is only correct over the whole timeline.
     *  - The chart takes "latest reading" to mean the last point in this list.
     *    Bounded, that is the newest point *in the window*, which breaks
     *    back-to-now, the auto-scroll and the prediction anchor.
     *
     * Both are properties of the whole list, so the list has to be the whole
     * timeline. Cost belongs in how points are mapped, not in how few are read.
     */
    fun getDashboardHistoryFlowRaw(startTime: Long): Flow<List<GlucosePoint>> {
        return _currentSerial.flatMapLatest { serial ->
            val preferredSerial = resolveDisplayPreferredSerial(serial)
            channelFlow {
                launch {
                    historyRepository.ensureBackfilled(preferredSerial, startTime)
                }
                // Coarse to fine over one authoritative query, rather than a
                // narrower query: paint the recent tail as soon as it is ready,
                // then replace it with the full timeline. The tail is a superset
                // of what the default range draws and contains the newest
                // reading, so the first frame is already correct at the live
                // edge; the full list follows and is what panning reads, so
                // scrolling back never waits on Room.
                historyRepository.getDisplayHistoryFirstPaint(preferredSerial, startTime)
                    ?.let { firstPaint ->
                        BatteryTrace.bump(
                            key = "dashboard.history.first_paint",
                            logEvery = 20L,
                            detail = "size=${firstPaint.size}"
                        )
                        send(firstPaint)
                    }
                historyRepository.getDisplayHistoryFlow(preferredSerial, startTime)
                    .collect { points ->
                        HistoryRepository.reportRecentSensorComposition(points)
                        send(points)
                    }
            }
        }
    }

    /**
     * The current sensor's own recent rows — never merged, never widened.
     *
     * The dashboard needs two different lists and used to conflate them. The
     * chart wants every sensor that covers the window; the history-recovery
     * check, the hero value, the trend arrow and the Δ readout want *this*
     * sensor and nothing else. Feeding the merged list to the recovery check
     * would let a retired sensor's newer-looking tail convince it that the live
     * sensor is up to date, and no native re-sync would ever be requested.
     */
    fun getCurrentSensorTailFlowRaw(startTime: Long): Flow<List<GlucosePoint>> {
        return _currentSerial.flatMapLatest { serial ->
            val preferredSerial = resolveDisplayPreferredSerial(serial)
            if (preferredSerial == null) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                historyRepository.getHistoryFlowForSensor(preferredSerial, startTime)
            }
        }
    }

    /**
     * Legacy synchronous method - fetches ALL history from native layer.
     * Used for initial load and when Room hasn't been populated yet.
     */
    fun getHistory(): List<GlucosePoint> {
        return kotlinx.coroutines.runBlocking {
            val preferredSerial = resolveDisplayPreferredSerial()
            historyRepository.ensureBackfilled(preferredSerial, 0L)
            val isMmol = (Natives.getunit() == 1)
            loadDisplayHistory(preferredSerial, 0L).inDisplayUnit(isMmol)
        }
    }

    fun getUnit(): String {
        return when (Natives.getunit()) {
            1 -> "mmol/L"
            2 -> "mg/dL"
            else -> "mmol/L"
        }
    }

    /**
     * Efficiently get recent history from Native layer for Widgets/Glance.
     * Fetches only the requested duration to minimize memory usage in AppWidget process.
     */
    fun getRecentHistory(durationMs: Long = 24 * 60 * 60 * 1000L): List<GlucosePoint> {
        return kotlinx.coroutines.runBlocking {
            try {
                val preferredSerial = resolveDisplayPreferredSerial()
                val startMs = (System.currentTimeMillis() - durationMs).coerceAtLeast(0L)
                historyRepository.ensureBackfilled(preferredSerial, startMs)
                val isMmol = (Natives.getunit() == 1)
                loadDisplayHistory(preferredSerial, startMs).inDisplayUnit(isMmol)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching recent history", e)
                emptyList()
            }
        }.sortedBy { it.timestamp }
    }
}
