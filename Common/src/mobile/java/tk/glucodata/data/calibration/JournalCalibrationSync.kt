package tk.glucodata.data.calibration

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tk.glucodata.Applic
import tk.glucodata.NotificationHistorySource
import tk.glucodata.data.journal.JournalRepository

/**
 * Keeps the calibration table in step with the journal's blood-glucose entries
 * while "calibrate from journal BG" is on.
 *
 * The work is a reconciliation, not an append, because a journal entry can be
 * edited, re-timed or deleted after the calibration was derived from it. It is
 * coalesced: bulk importers (Nightscout, AAPS, a meter dumping its stored
 * history) write entries in tight loops, and each write must not cost a pass
 * over the journal and the sensor history.
 */
object JournalCalibrationSync {
    private const val TAG = "JournalCalibSync"

    /**
     * How far back entries are paired. A sensor session never outlives this, so
     * anything older can no longer pair with a reading from the current sensor.
     */
    private const val LOOKBACK_MS = 30L * 24L * 60L * 60L * 1000L

    /** Long enough to swallow an import loop, short enough to feel immediate. */
    private const val COALESCE_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    private val scheduleLock = Any()
    private var pending: Job? = null

    /** Called whenever journal entries change; cheap when the feature is off. */
    fun onJournalChanged() {
        if (!CalibrationManager.shouldCalibrateFromJournal()) return
        requestSync("journalChanged")
    }

    fun onSettingChanged(enabled: Boolean) {
        if (enabled) {
            requestSync("settingEnabled")
        } else {
            synchronized(scheduleLock) { pending?.cancel() }
            scope.launch {
                syncLock.withLock { CalibrationManager.purgeJournalCalibrations() }
            }
        }
    }

    /** Re-pairs on startup, so entries added while the app was gone are picked up. */
    fun onAppStart() {
        if (!CalibrationManager.shouldCalibrateFromJournal()) return
        requestSync("appStart")
    }

    fun requestSync(reason: String) {
        synchronized(scheduleLock) {
            pending?.cancel()
            pending = scope.launch {
                delay(COALESCE_MS)
                runCatching { syncNow(reason) }
                    .onFailure { Log.w(TAG, "Journal calibration sync failed ($reason)", it) }
            }
        }
    }

    suspend fun syncNow(reason: String) {
        syncLock.withLock {
            if (!CalibrationManager.shouldCalibrateFromJournal()) return
            val sensorId = CalibrationManager.getResolvedCurrentSensorId()
            if (sensorId.isBlank()) {
                Log.d(TAG, "Skipping sync ($reason): no current sensor")
                return
            }
            val now = System.currentTimeMillis()
            val since = now - LOOKBACK_MS
            val isMmol = Applic.unit == 1

            val history = runCatching {
                NotificationHistorySource.getDisplayHistory(since, isMmol, sensorId)
            }.getOrElse {
                Log.w(TAG, "Skipping sync ($reason): history unavailable", it)
                return
            }
            if (history.isEmpty()) {
                // Nothing to pair against. Planning on an empty history would read
                // as "no entry matches any more" and delete every derived point.
                Log.d(TAG, "Skipping sync ($reason): no stored history for $sensorId")
                return
            }

            val entries = runCatching {
                JournalRepository().getGlucoseEntriesSince(since)
            }.getOrElse {
                Log.w(TAG, "Skipping sync ($reason): journal unavailable", it)
                return
            }

            val plan = JournalCalibrationPolicy.plan(
                entries = entries.map { entry ->
                    JournalCalibrationPolicy.JournalBloodGlucose(
                        entryId = entry.id,
                        timestamp = entry.timestamp,
                        glucoseMgDl = entry.glucoseValueMgDl ?: 0f,
                    )
                },
                history = history.map { point ->
                    JournalCalibrationPolicy.SensorLanes(
                        timestamp = point.timestamp,
                        autoValue = point.value,
                        rawValue = point.rawValue,
                    )
                },
                existing = CalibrationManager.getCachedCalibrations(),
                sensorId = sensorId,
                isMmol = isMmol,
                nowMillis = now,
                matchesSensor = CalibrationManager::calibrationMatchesSensor,
            )
            CalibrationManager.applyJournalCalibrationPlan(plan, reason)
        }
    }
}
