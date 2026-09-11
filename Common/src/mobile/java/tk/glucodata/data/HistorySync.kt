package tk.glucodata.data

import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tk.glucodata.Applic
import tk.glucodata.BatteryTrace
import tk.glucodata.CloneSensorRegistry
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.Natives
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashSet

/**
 * Singleton that synchronizes native glucose history with the Room database.
 *
 * Multi-sensor: Iterates ALL active sensors via [Natives.activeSensors] and syncs
 * each one independently using [Natives.getGlucoseHistoryForSensor]. Each reading
 * is tagged with its sensor serial so data from different sensors coexists in the
 * same Room table without conflict.
 *
 * NO MORE clearAllTables() — sensor switching is handled by querying the appropriate
 * sensorSerial, not by destroying and rebuilding the database.
 */
object HistorySync {

    private const val TAG = "HistorySync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyRepository = HistoryRepository(Applic.app)

    // Track if we've done the initial full sync this session
    @Volatile
    private var initialSyncDone = false

    // Per-sensor backfill stability tracking.
    // Key = sensor serial, Value = (lastReadingCount, stableCount).
    private val sensorStability = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val sensorLastSyncTimeMs = ConcurrentHashMap<String, Long>()
    private val sensorSyncInProgress = ConcurrentHashMap.newKeySet<String>()
    private val sensorRecentSyncInProgress = ConcurrentHashMap.newKeySet<String>()
    private val sensorLastRecentSyncBucketMs = ConcurrentHashMap<String, Long>()
    private val sensorLastFullMergeTimeMs = ConcurrentHashMap<String, Long>()
    private val sensorDelayedFullMergePending = ConcurrentHashMap.newKeySet<String>()
    private val sensorResetPreserveUntilMs = ConcurrentHashMap<String, Long>()
    private const val STABLE_THRESHOLD = 2
    private const val STABLE_GROWTH_TOLERANCE = 2

    private val syncGate = Mutex()
    private val syncRequestLock = Any()
    @Volatile
    private var syncInProgress = false
    @Volatile
    private var pendingForceFull = false

    // Throttle: keep active-dashboard sync responsive enough for reconnect/backfill,
    // but still avoid hammering native history on every 3-second UI tick.
    @Volatile
    private var lastSyncTimeMs = 0L
    private const val MIN_FULL_SYNC_INTERVAL_MS = 5_000L
    private const val MIN_INCR_SYNC_INTERVAL_MS = 3_000L
    private const val MIN_FULL_MERGE_INTERVAL_MS = 30_000L

    // Incremental overlap: keep a generous window so reconnect/vendor repair work
    // does not leave permanent holes, while still avoiding all-history rescans.
    private const val INCREMENTAL_OVERLAP_MS = 6 * 60 * 60 * 1000L
    private const val RECENT_SYNC_BUCKET_MS = 60_000L
    private const val RECENT_SYNC_DEFAULT_LOOKBACK_MS = 5 * 60 * 1000L
    private const val RECENT_SYNC_MAX_LOOKBACK_MS = 30 * 60 * 1000L
    private const val DESTRUCTIVE_RESYNC_RESET_GAP_MS = 60 * 60 * 1000L
    private const val RESET_PRESERVE_WINDOW_MS = 10 * 60 * 1000L
    private const val NATIVE_SYNC_INSERT_CHUNK = 1_000

    /**
     * Sync data from native layer to Room for ALL active sensors.
     *
     * Adaptive sync strategy per sensor:
     * - Explicit force-full calls still sync all data from time 0.
     * - Ordinary calls sync from the stored Room tail when Room already has this sensor.
     * - Empty sensors keep doing full syncs until BLE backfill stabilizes
     *   (same reading count for [STABLE_THRESHOLD] consecutive full syncs).
     *
     * The DAO uses IGNORE on the (timestamp, sensorSerial) unique index so duplicates
     * from full syncs are handled efficiently.
     */
    @JvmOverloads
    fun syncFromNative(forceFull: Boolean = false) {
        BatteryTrace.bump(
            key = "history.sync.all.request",
            logEvery = 20L,
            detail = if (forceFull) "forceFull=true" else null
        )
        queueSync(forceFull, bypassThrottle = false)
    }

    @JvmStatic
    @JvmOverloads
    fun syncSensorFromNative(serial: String, forceFull: Boolean = false) {
        val canonicalSerial = SensorIdentity.resolveAppSensorId(serial) ?: serial
        if (canonicalSerial.isBlank()) return
        if (!SensorIdentity.shouldUseNativeHistorySync(canonicalSerial)) return

        val now = System.currentTimeMillis()
        val (_, stableCount) = sensorStability[canonicalSerial] ?: Pair(-1, 0)
        val sensorStable = stableCount >= STABLE_THRESHOLD
        val minInterval = if (sensorStable && initialSyncDone) MIN_INCR_SYNC_INTERVAL_MS else MIN_FULL_SYNC_INTERVAL_MS
        val lastSensorSync = sensorLastSyncTimeMs[canonicalSerial] ?: 0L

        if (!forceFull && (now - lastSensorSync) < minInterval) {
            return
        }
        if (!sensorSyncInProgress.add(canonicalSerial)) {
            return
        }

        BatteryTrace.bump(
            key = "history.sync.sensor.request",
            logEvery = 20L,
            detail = "serial=$canonicalSerial forceFull=$forceFull"
        )

        scope.launch {
            try {
                syncGate.withLock {
                    doSyncSensor(canonicalSerial, forceFull)
                    sensorLastSyncTimeMs[canonicalSerial] = System.currentTimeMillis()
                    if (!initialSyncDone) {
                        initialSyncDone = true
                    }
                }
            } finally {
                sensorSyncInProgress.remove(canonicalSerial)
            }
        }
    }

    @JvmStatic
    fun syncRecentSensorFromNative(serial: String, anchorTimeMs: Long) {
        val canonicalSerial = SensorIdentity.resolveAppSensorId(serial) ?: serial
        if (canonicalSerial.isBlank() || anchorTimeMs <= 0L) return
        if (!SensorIdentity.shouldUseNativeHistorySync(canonicalSerial)) return

        val currentBucket = (anchorTimeMs / RECENT_SYNC_BUCKET_MS) * RECENT_SYNC_BUCKET_MS
        val previousBucket = sensorLastRecentSyncBucketMs[canonicalSerial]
        if (previousBucket != null && previousBucket >= currentBucket) {
            return
        }
        if (!sensorRecentSyncInProgress.add(canonicalSerial)) {
            return
        }

        BatteryTrace.bump(
            key = "history.sync.sensor.live.request",
            logEvery = 20L,
            detail = "serial=$canonicalSerial bucket=$currentBucket"
        )

        scope.launch {
            try {
                syncGate.withLock {
                    val startMs = resolveRecentSyncStartMs(currentBucket, previousBucket)
                    val readings = doSyncSensorWindow(canonicalSerial, startMs / 1000L)
                    if (readings > 0) {
                        sensorLastRecentSyncBucketMs[canonicalSerial] = currentBucket
                    }
                }
            } finally {
                sensorRecentSyncInProgress.remove(canonicalSerial)
            }
        }
    }

    private fun resolveRecentSyncStartMs(currentBucket: Long, previousBucket: Long?): Long {
        val defaultStart = (currentBucket - RECENT_SYNC_DEFAULT_LOOKBACK_MS).coerceAtLeast(0L)
        if (previousBucket == null || previousBucket <= 0L) {
            return defaultStart
        }
        val earliestNeeded = (previousBucket - RECENT_SYNC_BUCKET_MS).coerceAtLeast(0L)
        val boundedEarliest = earliestNeeded.coerceAtLeast((currentBucket - RECENT_SYNC_MAX_LOOKBACK_MS).coerceAtLeast(0L))
        return minOf(defaultStart, boundedEarliest)
    }

    private fun queueSync(forceFull: Boolean, bypassThrottle: Boolean) {
        val now = System.currentTimeMillis()

        synchronized(syncRequestLock) {
            if (syncInProgress) {
                if (forceFull) {
                    pendingForceFull = true
                }
                return
            }

            // Determine if ALL sensors have stabilized for throttle interval selection
            val allStable = sensorStability.values.all { (_, stable) -> stable >= STABLE_THRESHOLD }
            val minInterval = if (allStable && initialSyncDone) MIN_INCR_SYNC_INTERVAL_MS else MIN_FULL_SYNC_INTERVAL_MS

            // Throttle: skip if called too frequently (unless it's the first sync, forced,
            // or a queued rerun that arrived while the previous sync was still running).
            if (!bypassThrottle && !forceFull && initialSyncDone && (now - lastSyncTimeMs) < minInterval) {
                return
            }

            syncInProgress = true
        }

        scope.launch {
            try {
                doSyncAllSensors(forceFull)
            } finally {
                var rerunForceFull = false
                synchronized(syncRequestLock) {
                    syncInProgress = false
                    rerunForceFull = pendingForceFull
                    pendingForceFull = false
                }
                if (rerunForceFull) {
                    queueSync(rerunForceFull, bypassThrottle = true)
                }
            }
        }
    }

    /**
     * Sync all active sensors. Iterates [Natives.activeSensors] and syncs each one
     * via [Natives.getGlucoseHistoryForSensor].
     */
    private suspend fun doSyncAllSensors(forceFull: Boolean) {
        syncGate.withLock {
            try {
                BatteryTrace.bump(
                    key = "history.sync.all.run",
                    logEvery = 20L,
                    detail = if (forceFull) "forceFull=true" else null
                )
                val sensorsToSync = linkedSetOfSensors(
                    Natives.activeSensors(),
                    Natives.lastsensorname()
                ).filter(SensorIdentity::shouldUseNativeHistorySync)
                if (sensorsToSync.isEmpty()) {
                    Log.w(TAG, "No active sensors and no main sensor — nothing to sync")
                    lastSyncTimeMs = 0L
                    return@withLock
                }

                for (serial in sensorsToSync) {
                    doSyncSensor(serial, forceFull)
                }

                lastSyncTimeMs = System.currentTimeMillis()
                if (!initialSyncDone) {
                    initialSyncDone = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing all sensors: ${e.message}")
            }
        }
    }

    private fun linkedSetOfSensors(activeSensors: Array<String?>?, mainSensor: String?): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        activeSensors?.forEach { serial ->
            (SensorIdentity.resolveAppSensorId(serial) ?: serial)
                ?.takeIf { it.isNotBlank() }
                ?.let(result::add)
        }
        (SensorIdentity.resolveAppSensorId(mainSensor) ?: mainSensor)
            ?.takeIf { it.isNotBlank() }
            ?.let(result::add)
        return result
    }

    /**
     * Sync a single sensor's native data into Room.
     */
    private suspend fun doSyncSensor(serial: String, forceFull: Boolean) {
        try {
            if (!SensorIdentity.shouldUseNativeHistorySync(serial)) {
                Log.d(TAG, "Skipping native sync for driver-owned Room sensor $serial")
                return
            }
            BatteryTrace.bump(
                key = "history.sync.sensor.run",
                logEvery = 20L,
                detail = "serial=$serial forceFull=$forceFull"
            )
            val (lastCount, stableCount) = sensorStability[serial] ?: Pair(-1, 0)
            val sensorStable = stableCount >= STABLE_THRESHOLD
            val latestStoredTimestamp = if (forceFull) 0L else historyRepository.getLatestTimestampForSensor(serial)

            val startSec: Long
            val isFullSync: Boolean

            if (forceFull || (latestStoredTimestamp <= 0L && (!initialSyncDone || !sensorStable))) {
                // Full sync: first sync, forced, or backfill still in progress for this sensor
                startSec = 0L
                isFullSync = true
            } else {
                // Backfill done for this sensor: incremental only
                val startMs = if (latestStoredTimestamp > INCREMENTAL_OVERLAP_MS) {
                    latestStoredTimestamp - INCREMENTAL_OVERLAP_MS
                } else {
                    0L
                }
                startSec = startMs / 1000L
                isFullSync = false
            }

            val currentCount = doSyncSensorWindow(serial, startSec)
            if (currentCount > 0) {
                if (isFullSync) {
                    Log.i(TAG, "Full sync [$serial]: ${currentCount} readings (lastCount=$lastCount, stable=$stableCount)")
                } else if (currentCount > 10) {
                    Log.i(TAG, "Incremental sync [$serial]: ${currentCount} readings")
                }
            }

            // Track backfill progress per sensor
            if (isFullSync) {
                val growth = if (lastCount >= 0) currentCount - lastCount else Int.MAX_VALUE
                if (growth in 0..STABLE_GROWTH_TOLERANCE) {
                    val newStable = stableCount + 1
                    sensorStability[serial] = Pair(currentCount, newStable)
                    if (newStable >= STABLE_THRESHOLD) {
                        Log.i(TAG, "Backfill stabilized [$serial] after $newStable near-stable full syncs ($currentCount readings, growth=$growth)")
                    }
                } else {
                    // New data arrived — reset stability counter for this sensor
                    sensorStability[serial] = Pair(currentCount, 0)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error syncing sensor $serial: ${e.message}")
        }
    }

    private suspend fun doSyncSensorWindow(serial: String, startSec: Long): Int {
        val rawHistory = loadNativeHistory(serial, startSec)
        if (rawHistory == null) {
            Log.d(TAG, "getGlucoseHistoryForSensor($serial, $startSec) returned null — skipping")
            return 0
        }

        val roomSerial = SensorIdentity.resolveRoomStorageSensorId(serial) ?: serial
        val readingSource = GlucoseReadingSource.forCloneTransport(
            CloneSensorRegistry.transportForSensor(serial)
        ).takeIf { CloneSensorRegistry.isCloneSensor(serial) } ?: GlucoseReadingSource.SENSOR
        val readings = ArrayList<HistoryReading>(NATIVE_SYNC_INSERT_CHUNK)
        var parsedCount = 0
        var storedAny = false

        for (i in rawHistory.indices step 3) {
            if (i + 2 >= rawHistory.size) break

            val timeSec = rawHistory[i]
            val timeMs = timeSec * 1000L

            val valueAutoRaw = rawHistory[i + 1]
            val valueRawRaw = rawHistory[i + 2]

            val value = valueAutoRaw / 10f
            val rawValue = valueRawRaw / 10f

            if (value > 0 || rawValue > 0) {
                readings.add(
                    HistoryReading(
                        timestamp = timeMs,
                        sensorSerial = roomSerial,
                        value = value,
                        rawValue = rawValue,
                        rate = null,
                        source = readingSource,
                    )
                )
                parsedCount++
                if (readings.size >= NATIVE_SYNC_INSERT_CHUNK) {
                    val stored = historyRepository.storeReadingsReplacingSensorBuckets(
                        sensorSerial = roomSerial,
                        readings = readings,
                        bucketDurationMs = RECENT_SYNC_BUCKET_MS,
                    )
                    readings.clear()
                    storedAny = storedAny || stored
                }
            }
        }

        if (readings.isNotEmpty()) {
            val stored = historyRepository.storeReadingsReplacingSensorBuckets(
                sensorSerial = roomSerial,
                readings = readings,
                bucketDurationMs = RECENT_SYNC_BUCKET_MS,
            )
            readings.clear()
            storedAny = storedAny || stored
        }
        if (storedAny) {
            UiRefreshBus.requestDataRefresh()
            UiRefreshBus.requestStatusRefresh()
        }
        return parsedCount
    }

    private fun loadNativeHistory(serial: String, startSec: Long): LongArray? {
        for (nativeName in SensorIdentity.resolveNativeHistorySensorNames(serial).ifEmpty { listOf(serial) }) {
            val exact = try {
                Natives.getGlucoseHistoryForSensor(nativeName, startSec)
            } catch (_: Throwable) {
                null
            }
            if (exact != null) {
                return exact
            }
        }
        return null
    }

    /**
     * Force a full resync of ALL active sensors.
     * Useful after vendor history download or sensor data changes.
     *
     * Multi-sensor: NO LONGER clears the entire Room DB. Instead, resets sync
     * state and does a full re-sync for every active sensor. Data from all sensors
     * coexists via the sensorSerial column. The DAO's IGNORE strategy handles
     * duplicates efficiently.
     */
    fun forceFullSync() {
        Log.i(TAG, "forceFullSync() called — resetting sync state for all sensors (NO clearAllTables)")
        lastSyncTimeMs = 0L
        initialSyncDone = false
        sensorStability.clear()
        sensorLastSyncTimeMs.clear()
        syncFromNative(forceFull = true)
    }

    /**
     * Force a full resync of a SPECIFIC sensor with DELETE-then-INSERT.
     *
     * After localReplay(), the native polls[] have been overwritten with
     * recalibrated values, but the Room DB still has the OLD values.
     * Since the DAO uses IGNORE on conflict (timestamp + sensorSerial),
     * a plain re-sync would silently skip the updated values.
     *
     * Fix: DELETE all Room rows for this sensor first, then re-insert from native.
     * This triggers Room's Flow observers so the chart redraws instantly.
     */
    fun forceFullSyncForSensor(serial: String) {
        Log.i(TAG, "forceFullSyncForSensor($serial) — full Room/native resync requested")
        if (!SensorIdentity.shouldUseNativeHistorySync(serial)) {
            Log.i(TAG, "forceFullSyncForSensor($serial) skipped: driver-owned Room history")
            return
        }
        sensorStability.remove(serial)
        sensorLastSyncTimeMs.remove(serial)
        val legacyAliases = SensorIdentity.resolveNativeHistorySensorNames(serial)
        legacyAliases.forEach {
            sensorStability.remove(it)
            sensorLastSyncTimeMs.remove(it)
        }
        lastSyncTimeMs = 0L  // Allow immediate sync
        scope.launch {
            syncGate.withLock {
                val preserveExistingHistory = shouldPreserveExistingHistoryOnFullResync(serial, legacyAliases)
                if (!preserveExistingHistory) {
                    try {
                        historyRepository.deleteForSensor(serial)
                        legacyAliases.forEach { historyRepository.deleteForSensor(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting Room data for $serial before resync: ${e.message}")
                    }
                }
                doSyncSensor(serial, forceFull = true)
            }
        }
    }

    /**
     * Force a full resync of a SPECIFIC sensor without deleting existing Room rows first.
     *
     * Use this for ordinary sensor switching/history import completion where native history
     * should merge into Room and overwrite only conflicting timestamps. This avoids wiping
     * older Room history when native currently exposes only a recent tail.
     */
    fun mergeFullSyncForSensor(serial: String) {
        val canonicalSerial = SensorIdentity.resolveAppSensorId(serial) ?: serial
        Log.i(TAG, "mergeFullSyncForSensor($canonicalSerial) — full non-destructive Room/native merge requested")
        if (canonicalSerial.isBlank()) {
            return
        }
        if (!SensorIdentity.shouldUseNativeHistorySync(canonicalSerial)) {
            Log.i(TAG, "mergeFullSyncForSensor($canonicalSerial) skipped: driver-owned Room history")
            return
        }

        val now = System.currentTimeMillis()
        val lastFullMerge = sensorLastFullMergeTimeMs[canonicalSerial] ?: 0L
        val elapsedSinceFullMerge = now - lastFullMerge
        if (elapsedSinceFullMerge < MIN_FULL_MERGE_INTERVAL_MS) {
            val delayMs = MIN_FULL_MERGE_INTERVAL_MS - elapsedSinceFullMerge
            Log.d(TAG, "mergeFullSyncForSensor($canonicalSerial) delayed: recent full merge ${elapsedSinceFullMerge}ms ago")
            scheduleDelayedFullMerge(canonicalSerial, delayMs)
            return
        }
        if (!sensorSyncInProgress.add(canonicalSerial)) {
            Log.d(TAG, "mergeFullSyncForSensor($canonicalSerial) delayed: sync already in progress")
            scheduleDelayedFullMerge(canonicalSerial, MIN_FULL_MERGE_INTERVAL_MS)
            return
        }
        sensorLastFullMergeTimeMs[canonicalSerial] = now

        sensorStability.remove(canonicalSerial)
        sensorLastSyncTimeMs.remove(canonicalSerial)
        SensorIdentity.resolveNativeHistorySensorNames(canonicalSerial).forEach {
            sensorStability.remove(it)
            sensorLastSyncTimeMs.remove(it)
        }
        lastSyncTimeMs = 0L
        scope.launch {
            try {
                syncGate.withLock {
                    doSyncSensor(canonicalSerial, forceFull = true)
                    sensorLastSyncTimeMs[canonicalSerial] = System.currentTimeMillis()
                }
            } finally {
                sensorSyncInProgress.remove(canonicalSerial)
            }
        }
    }

    private fun scheduleDelayedFullMerge(serial: String, delayMs: Long) {
        if (!sensorDelayedFullMergePending.add(serial)) {
            return
        }
        scope.launch {
            try {
                delay(delayMs.coerceAtLeast(1L))
                sensorDelayedFullMergePending.remove(serial)
                mergeFullSyncForSensor(serial)
            } finally {
                sensorDelayedFullMergePending.remove(serial)
            }
        }
    }

    /** Resolved by name from [tk.glucodata.HistorySyncAccess], so the name must survive R8. */
    @Keep
    fun markSensorReset(serial: String) {
        if (serial.isBlank()) return
        val deadline = System.currentTimeMillis() + RESET_PRESERVE_WINDOW_MS
        sensorResetPreserveUntilMs[serial] = deadline
        SensorIdentity.resolveNativeHistorySensorNames(serial).forEach { sensorResetPreserveUntilMs[it] = deadline }
    }

    private suspend fun shouldPreserveExistingHistoryOnFullResync(
        serial: String,
        legacyAliases: List<String>
    ): Boolean {
        val candidateSerials = linkedSetOf(serial, *legacyAliases.toTypedArray()).filterNotNull()
        val now = System.currentTimeMillis()
        val resetMarked = candidateSerials.any { candidate ->
            val deadline = sensorResetPreserveUntilMs[candidate] ?: return@any false
            deadline >= now
        }
        if (!resetMarked) {
            return false
        }
        var roomOldest = Long.MAX_VALUE
        var roomNewest = 0L
        var roomCount = 0

        for (candidate in candidateSerials) {
            val count = historyRepository.getReadingCountForSensor(candidate)
            if (count <= 0) continue
            roomCount += count
            val oldest = historyRepository.getOldestTimestampForSensor(candidate)
            if (oldest > 0L) {
                roomOldest = minOf(roomOldest, oldest)
            }
            val latest = historyRepository.getLatestTimestampForSensor(candidate)
            if (latest > 0L) {
                roomNewest = maxOf(roomNewest, latest)
            }
        }

        if (roomCount <= 0 || roomOldest == Long.MAX_VALUE) {
            return false
        }

        val nativeHistory = loadNativeHistory(serial, 0L)
        if (nativeHistory == null || nativeHistory.size < 3) {
            clearResetPreserveMarkers(candidateSerials)
            Log.w(
                TAG,
                "forceFullSyncForSensor($serial): preserving Room history because native history is empty/truncated"
            )
            return true
        }

        val nativeOldest = nativeHistory.first() * 1000L
        val nativeNewest = nativeHistory[nativeHistory.size - 3] * 1000L
        val nativeLooksReset = nativeOldest > (roomOldest + DESTRUCTIVE_RESYNC_RESET_GAP_MS) &&
            nativeNewest < roomNewest
        if (nativeLooksReset) {
            clearResetPreserveMarkers(candidateSerials)
            Log.w(
                TAG,
                "forceFullSyncForSensor($serial): preserving Room history because native history appears reset " +
                    "(roomOldest=$roomOldest nativeOldest=$nativeOldest roomNewest=$roomNewest nativeNewest=$nativeNewest roomCount=$roomCount nativeCount=${nativeHistory.size / 3})"
            )
        }
        return nativeLooksReset
    }

    private fun clearResetPreserveMarkers(candidateSerials: Collection<String>) {
        for (candidate in candidateSerials) {
            sensorResetPreserveUntilMs.remove(candidate)
        }
    }
}
