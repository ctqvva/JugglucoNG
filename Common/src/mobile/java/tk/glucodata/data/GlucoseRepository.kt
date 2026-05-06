package tk.glucodata.data

import tk.glucodata.Natives
import tk.glucodata.ui.GlucosePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.util.Log
import android.os.SystemClock
import tk.glucodata.Applic
import tk.glucodata.BatteryTrace
import tk.glucodata.SensorIdentity
import tk.glucodata.ui.util.inDisplayUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Repository that bridges native glucose data with the independent Room history database.
 * New readings are stored in Room for long-term history while still using native data for
 * real-time display and calibration.
 *
 * Multi-sensor: current/live reads stay pinned to the selected sensor. History/chart queries
 * prefer that same sensor's Room history directly; only when no current sensor is known do we
 * fall back to the broader merged display timeline.
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
        SensorIdentity.resolveAvailableMainSensor(
            selectedMain = SensorIdentity.resolveMainSensor(),
            preferredSensorId = null,
            activeSensors = Natives.activeSensors()
        ) ?: ""
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
            ?: SensorIdentity.resolveAvailableMainSensor(
                selectedMain = nativeCurrent,
                preferredSensorId = current.takeIf { it.isNotBlank() },
                activeSensors = Natives.activeSensors()
            )
            ?: current.takeIf { it.isNotBlank() }
            ?: ""

        if (resolved != current) {
            Log.d(TAG, "Sensor serial changed: '$current' → '$resolved'")
            _currentSerial.value = resolved
        }
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
        return SensorIdentity.resolveAvailableMainSensor(
            selectedMain = SensorIdentity.resolveMainSensor(),
            preferredSensorId = null,
            activeSensors = Natives.activeSensors()
        )
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
