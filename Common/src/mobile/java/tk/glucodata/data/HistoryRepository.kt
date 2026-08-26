package tk.glucodata.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import tk.glucodata.Applic
import tk.glucodata.BatteryTrace
import tk.glucodata.Natives
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.util.inDisplayUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Repository for managing the independent glucose history database.
 * Handles:
 * - Storing new readings from the native layer (tagged with sensor serial)
 * - Backfilling ALL existing history from ALL active sensors on first run
 * - Querying history for chart display (per-sensor or all)
 */
class HistoryRepository(context: Context = Applic.app) {
    
    private val database = HistoryDatabase.getInstance(context)
    private val dao = database.historyDao()

    private fun resolveQuerySensorSerials(sensorSerial: String?): List<String> =
        SensorIdentity.resolveRoomQuerySensorIds(sensorSerial)
            .ifEmpty {
                sensorSerial?.trim()?.takeIf { it.isNotEmpty() }?.let(::listOf) ?: emptyList()
            }

    private fun resolveDisplayQuerySensorSerials(sensorSerial: String?): List<String> {
        val resolved = LinkedHashSet<String>()
        resolveQuerySensorSerials(sensorSerial).forEach(resolved::add)
        IMPORTED_HISTORY_SENSOR_SERIALS.forEach(resolved::add)
        return resolved.toList()
    }
    
    companion object {
        private const val TAG = "HistoryRepo"
        private const val SENSOR_MINUTE_BUCKET_MS = 60_000L
        private const val NATIVE_BACKFILL_OVERLAP_MS = 6L * 60L * 60L * 1000L
        private const val HISTORY_COVERAGE_TOLERANCE_MS = 5L * 60L * 1000L
        private const val BACKFILL_RETRY_COOLDOWN_MS = 2L * 60L * 1000L
        private const val NATIVE_BACKFILL_INSERT_CHUNK = 1_000
        private const val DELETED_TIMESTAMP_QUERY_CHUNK = 900
        const val IMPORTED_SENSOR_SERIAL = "__imported_csv__"
        private val IMPORTED_HISTORY_SENSOR_SERIALS = listOf(
            IMPORTED_SENSOR_SERIAL,
            "imported",
            "unknown"
        )

        /** A row no sensor owns: imported, or stored before the serial was known. */
        fun isImportedHistorySerial(serial: String): Boolean = serial in IMPORTED_HISTORY_SENSOR_SERIALS
        private val TIME_FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        }
        private val backfillLock = ReentrantLock()
        private val backfillFinished = backfillLock.newCondition()
        private val backfilledSensorStartMs = HashMap<String, Long>()
        private val backfillInProgressStartMs = HashMap<String, Long>()
        private val backfillAttemptStartMs = HashMap<String, Long>()
        private val backfillAttemptWallMs = HashMap<String, Long>()
        
        /**
         * Reset per-sensor backfill tracking so [ensureBackfilled] re-checks native
         * history on the next subscription. Used when a vendor/driver history import
         * completes after an earlier empty native snapshot.
         */
        @JvmStatic
        fun resetBackfillFlag() {
            backfillLock.withLock {
                backfilledSensorStartMs.clear()
                backfillInProgressStartMs.clear()
                backfillAttemptStartMs.clear()
                backfillAttemptWallMs.clear()
                backfillFinished.signalAll()
            }
            Log.d(TAG, "backfill sensor tracking reset — ensureBackfilled() will re-run")
        }
        
        /**
         * Blocking version of getHistory for Java access.
         * This runs the suspend function on a blocking coroutine.
         * Should be called from a background thread.
         */
        @JvmStatic
        fun getHistoryBlocking(startTime: Long, isMmol: Boolean): List<GlucosePoint> {
            return kotlinx.coroutines.runBlocking {
                HistoryRepository()
                    .getDisplayHistory(SensorIdentity.resolveMainSensor(), startTime)
                    .inDisplayUnit(isMmol)
            }
        }
        
        const val HISTORY_SOURCE_NATIVE = 1
        const val GLUCODATA_SOURCE_AIDEX = 4
        
        @JvmStatic
        fun storeReadingAsync(timestamp: Long, valueMmol: Float, source: Int) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val valueMgDl = GlucoseFormatter.mmolToMg(valueMmol)
                    // Use main sensor serial for source tagging
                    val serial = SensorIdentity.resolveMainSensor() ?: Natives.lastsensorname() ?: "unknown"
                    HistoryRepository().storeReading(
                        timestamp = timestamp,
                        value = valueMgDl,
                        rawValue = valueMgDl,
                        rate = 0f,
                        sensorSerial = serial
                    )
                    Log.d(TAG, "Stored reading: $valueMgDl mg/dL from source $source [$serial]")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to store reading", e)
                }
            }
        }

        /**
         * Blocking bridge for main/shared code that needs the persisted tail for one sensor.
         * Used by HistorySyncAccess from non-suspending Java/main paths.
         */
        @JvmStatic
        fun getLatestTimestampForSensorBlocking(sensorSerial: String): Long {
            val resolvedSerial = sensorSerial.takeIf { it.isNotBlank() }
                ?: return 0L
            return kotlinx.coroutines.runBlocking {
                HistoryRepository().getLatestTimestampForSensor(resolvedSerial)
            }
        }

        /**
         * Resolved by name from [tk.glucodata.HistorySyncAccess], so the name must survive R8.
         * The manual keep list in proguard-rules.my did not cover this one: in every minified
         * build the lookup threw, the bridge answered "no rows", and the Ottai driver read that
         * as "nothing stored" and re-pulled the sensor's entire history on every reconnect.
         * @Keep pins it at the declaration, where it cannot drift away from the caller.
         */
        @Keep
        @JvmStatic
        fun getHistoryTimestampsForSensorBlocking(
            sensorSerial: String,
            startTime: Long,
            endTime: Long
        ): LongArray? {
            val resolvedSerial = sensorSerial.takeIf { it.isNotBlank() }
                ?: return LongArray(0)
            if (endTime < startTime) return LongArray(0)
            // null propagates "the query failed" all the way to the caller; an empty array here
            // means the store really holds nothing for this window.
            return kotlinx.coroutines.runBlocking {
                HistoryRepository()
                    .getHistoryTimestampsForSensorOrNull(resolvedSerial, startTime, endTime)
                    ?.toLongArray()
            }
        }

        /** Resolved by name from [tk.glucodata.HistorySyncAccess] — see [getHistoryTimestampsForSensorBlocking]. */
        @Keep
        @JvmStatic
        fun deleteReadingsForSensorAfterBlocking(sensorSerial: String, timestampExclusive: Long): Int {
            val resolvedSerial = sensorSerial.takeIf { it.isNotBlank() }
                ?: return 0
            if (timestampExclusive <= 0L) return 0
            return kotlinx.coroutines.runBlocking {
                HistoryRepository().deleteReadingsForSensorAfter(resolvedSerial, timestampExclusive)
            }
        }
        
        /**
         * Blocking version for Notify.java that returns tk.glucodata.GlucosePoint.
         * Filters by main sensor serial.
         */
        @JvmStatic
        fun getHistoryForNotification(startTime: Long, isMmol: Boolean): List<tk.glucodata.GlucosePoint> {
            return kotlinx.coroutines.runBlocking {
                val serial = SensorIdentity.resolveMainSensor() ?: ""
                val repo = HistoryRepository()
                val uiPoints = if (serial.isNotEmpty()) {
                    repo.getHistoryForDisplaySensor(serial, startTime)
                } else {
                    Log.w(TAG, "getHistoryForNotification: no main sensor serial, returning empty list")
                    emptyList()
                }
                uiPoints.inDisplayUnit(isMmol).map { p ->
                    tk.glucodata.GlucosePoint(p.timestamp, p.value, p.rawValue)
                }
            }
        }

        /**
         * Blocking version for shared/main code that needs the same Room-backed
         * history for a specific sensor as the dashboard rows.
         */
        @JvmStatic
        fun getHistoryForNotificationForSensor(
            sensorSerial: String?,
            startTime: Long,
            isMmol: Boolean
        ): List<tk.glucodata.GlucosePoint> {
            return kotlinx.coroutines.runBlocking {
                val serial = SensorIdentity.resolveAppSensorId(sensorSerial)
                    ?: SensorIdentity.resolveMainSensor()
                    ?: ""
                if (serial.isEmpty()) {
                    Log.w(TAG, "getHistoryForNotificationForSensor: no sensor serial, returning empty list")
                    return@runBlocking emptyList()
                }
                HistoryRepository()
                    .getHistoryForDisplaySensor(serial, startTime)
                    .inDisplayUnit(isMmol)
                    .map { p -> tk.glucodata.GlucosePoint(p.timestamp, p.value, p.rawValue) }
            }
        }
        
        /**
         * Blocking version for Notify.java returning raw mg/dL.
         * Filters by main sensor serial.
         */
        @JvmStatic
        fun getHistoryRawForNotification(startTime: Long): List<tk.glucodata.GlucosePoint> {
            return kotlinx.coroutines.runBlocking {
                val serial = SensorIdentity.resolveMainSensor() ?: ""
                val repo = HistoryRepository()
                val uiPoints = if (serial.isNotEmpty()) {
                    repo.getHistoryForDisplaySensor(serial, startTime)
                } else {
                    Log.w(TAG, "getHistoryRawForNotification: no main sensor serial, returning empty list")
                    emptyList()
                }
                uiPoints.map { p ->
                    tk.glucodata.GlucosePoint(p.timestamp, p.value, p.rawValue)
                }
            }
        }

        /**
         * Backfill arrives one BLE notification at a time — roughly sixteen readings each,
         * ten times a second on a fast sensor. Writing a Room transaction per notification
         * meant a delete-range plus an insert ten times a second, each one previously on a
         * freshly created CoroutineScope, for the whole length of the connection.
         *
         * Batches are therefore accumulated per sensor and flushed once the burst pauses, or
         * once enough readings have piled up that waiting no longer pays. Live readings do
         * not come through here, so nothing user-visible is delayed by the coalescing window.
         */
        private const val HISTORY_BATCH_COALESCE_MS = 400L
        private const val HISTORY_BATCH_MAX_PENDING = 512

        private val historyBatchScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        )
        private val historyBatchLock = Any()
        private val pendingHistoryBatches = HashMap<String, ArrayList<HistoryReading>>()
        private val historyBatchFlushJobs = HashMap<String, kotlinx.coroutines.Job>()

        private fun enqueueHistoryBatch(roomSerial: String, readings: List<HistoryReading>) {
            val flushNow: List<HistoryReading>?
            synchronized(historyBatchLock) {
                val pending = pendingHistoryBatches.getOrPut(roomSerial) { ArrayList() }
                pending.addAll(readings)
                if (pending.size >= HISTORY_BATCH_MAX_PENDING) {
                    historyBatchFlushJobs.remove(roomSerial)?.cancel()
                    flushNow = pendingHistoryBatches.remove(roomSerial)
                } else {
                    flushNow = null
                    if (!historyBatchFlushJobs.containsKey(roomSerial)) {
                        historyBatchFlushJobs[roomSerial] = historyBatchScope.launch {
                            kotlinx.coroutines.delay(HISTORY_BATCH_COALESCE_MS)
                            val batch = synchronized(historyBatchLock) {
                                historyBatchFlushJobs.remove(roomSerial)
                                pendingHistoryBatches.remove(roomSerial)
                            }
                            writeHistoryBatch(roomSerial, batch)
                        }
                    }
                }
            }
            if (flushNow != null) {
                historyBatchScope.launch { writeHistoryBatch(roomSerial, flushNow) }
            }
        }

        private suspend fun writeHistoryBatch(roomSerial: String, batch: List<HistoryReading>?) {
            if (batch.isNullOrEmpty()) return
            try {
                HistoryRepository().storeReadingsReplacingSensorBuckets(
                    sensorSerial = roomSerial,
                    readings = batch,
                    bucketDurationMs = SENSOR_MINUTE_BUCKET_MS,
                )
                UiRefreshBus.requestDataRefresh()
            } catch (e: Exception) {
                Log.e(TAG, "Failed storing history batch for $roomSerial", e)
            }
        }

        /**
         * Async helper for Java callers (e.g. AiDexProbe) to store readings without blocking.
         * Launches a coroutine in IO scope.
         */
        @JvmStatic
        fun storeReadingAsync(timestamp: Long, value: Float, rawValue: Float, rate: Float) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                HistoryRepository().storeReading(timestamp, value, rawValue, rate)
            }
        }

        /**
         * Async helper that includes sensor serial. Preferred over the 4-arg variant.
         */
        @JvmStatic
        fun storeReadingAsync(timestamp: Long, value: Float, rawValue: Float, rate: Float, sensorSerial: String) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                HistoryRepository().storeReading(timestamp, value, rawValue, rate, sensorSerial)
            }
        }

        @JvmStatic
        fun storeHistoryBatchAsync(
            sensorSerial: String,
            timestamps: LongArray,
            values: FloatArray,
            rawValues: FloatArray
        ) {
            val roomSerial = SensorIdentity.resolveRoomStorageSensorId(sensorSerial) ?: sensorSerial
            if (roomSerial.isBlank()) return
            if (timestamps.isEmpty()) return
            if (timestamps.size != values.size || timestamps.size != rawValues.size) {
                Log.w(
                    TAG,
                    "storeHistoryBatchAsync rejected mismatched arrays for $roomSerial " +
                        "(timestamps=${timestamps.size}, values=${values.size}, raw=${rawValues.size})"
                )
                return
            }

            val readings = ArrayList<HistoryReading>(timestamps.size)
            for (index in timestamps.indices) {
                val timestamp = timestamps[index]
                val value = values[index]
                val rawValue = rawValues[index]
                if (timestamp <= 0L) continue
                if ((!value.isFinite() || value <= 0f) && (!rawValue.isFinite() || rawValue <= 0f)) continue
                readings.add(
                    HistoryReading(
                        timestamp = timestamp,
                        sensorSerial = roomSerial,
                        value = if (value.isFinite()) value else 0f,
                        rawValue = if (rawValue.isFinite()) rawValue else 0f,
                        rate = null
                    )
                )
            }
            if (readings.isEmpty()) return
            enqueueHistoryBatch(roomSerial, readings)
        }

        /** Resolved by name from [tk.glucodata.HistorySyncAccess] — see [getHistoryTimestampsForSensorBlocking]. */
        @Keep
        @JvmStatic
        fun storeHistoryBatchBlocking(
            sensorSerial: String,
            timestamps: LongArray,
            values: FloatArray,
            rawValues: FloatArray
        ): Boolean {
            val roomSerial = SensorIdentity.resolveRoomStorageSensorId(sensorSerial) ?: sensorSerial
            if (roomSerial.isBlank()) return false
            if (timestamps.isEmpty()) return true
            if (timestamps.size != values.size || timestamps.size != rawValues.size) {
                Log.w(
                    TAG,
                    "storeHistoryBatchBlocking rejected mismatched arrays for $roomSerial " +
                        "(timestamps=${timestamps.size}, values=${values.size}, raw=${rawValues.size})"
                )
                return false
            }

            return try {
                kotlinx.coroutines.runBlocking {
                    val readings = ArrayList<HistoryReading>(timestamps.size)
                    for (index in timestamps.indices) {
                        val timestamp = timestamps[index]
                        val value = values[index]
                        val rawValue = rawValues[index]
                        if (timestamp <= 0L) continue
                        if ((!value.isFinite() || value <= 0f) && (!rawValue.isFinite() || rawValue <= 0f)) continue
                        readings.add(
                            HistoryReading(
                                timestamp = timestamp,
                                sensorSerial = roomSerial,
                                value = if (value.isFinite()) value else 0f,
                                rawValue = if (rawValue.isFinite()) rawValue else 0f,
                                rate = null
                            )
                        )
                    }
                    HistoryRepository().storeReadingsReplacingSensorBuckets(
                        sensorSerial = roomSerial,
                        readings = readings,
                        bucketDurationMs = SENSOR_MINUTE_BUCKET_MS,
                    )
                }.also { stored ->
                    if (stored) {
                        UiRefreshBus.requestDataRefresh()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed storing blocking history batch for $roomSerial", e)
                false
            }
        }
    }
    
    /**
     * Store a new glucose reading in the history database.
     * Values should be in mg/dL (will be converted on display).
     * Uses main sensor serial if none specified.
     */
    suspend fun storeReading(timestamp: Long, value: Float, rawValue: Float, rate: Float, sensorSerial: String? = null) {
        // Don't store invalid readings
        if (value <= 0 && rawValue <= 0) return
        
        val rawSerial = sensorSerial
            ?: SensorIdentity.resolveMainSensor()
            ?: Natives.lastsensorname()
            ?: "unknown"
        val serial = SensorIdentity.resolveRoomStorageSensorId(rawSerial) ?: rawSerial
        val storedValue = maybeProjectCalibratedValueForStorage(
            sensorSerial = serial,
            timestamp = timestamp,
            value = value,
            rawValue = rawValue
        )
        val reading = HistoryReading(
            timestamp = timestamp,
            sensorSerial = serial,
            value = storedValue,
            rawValue = rawValue,
            rate = rate
        )
        withContext(Dispatchers.IO) {
            try {
                if (dao.isReadingDeleted(serial, timestamp) > 0) {
                    Log.d(TAG, "Skipped tombstoned reading for $serial at $timestamp")
                    return@withContext
                }
                database.withTransaction {
                    deleteSensorRowsInBucketRanges(
                        sensorSerial = serial,
                        bucketDurationMs = SENSOR_MINUTE_BUCKET_MS,
                        bucketRanges = listOf(
                            HistoryBucketReplacement.BucketRange(
                                firstBucketId = timestamp / SENSOR_MINUTE_BUCKET_MS,
                                lastBucketId = timestamp / SENSOR_MINUTE_BUCKET_MS
                            )
                        )
                    )
                    dao.insert(reading)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error storing reading", e)
            }
        }
    }

    private fun resolveSensorViewMode(sensorSerial: String): Int {
        return try {
            val snapshot = Natives.getSensorUiSnapshot(sensorSerial)
            if (snapshot != null && snapshot.size >= 2) snapshot[1].toInt() else 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun maybeProjectCalibratedValueForStorage(
        sensorSerial: String,
        timestamp: Long,
        value: Float,
        rawValue: Float
    ): Float {
        if (!CalibrationManager.shouldOverwriteSensorValues()) return value

        val viewMode = resolveSensorViewMode(sensorSerial)
        if (viewMode == 1 || viewMode == 3) return value
        if (viewMode != 0 && viewMode != 2) return value
        if (!CalibrationManager.hasActiveCalibration(false, sensorSerial)) return value

        val baseValue = value
        if (!baseValue.isFinite() || baseValue <= 0f) return value

        val calibrated = CalibrationManager.getCalibratedValue(
            value = baseValue,
            timestamp = timestamp,
            isRawMode = false,
            sensorIdOverride = sensorSerial
        )
        return if (calibrated.isFinite() && calibrated > 0f) calibrated else value
    }
    
    /**
     * Store multiple readings at once (used for backfill).
     * Readings must already have sensorSerial set.
     */
    suspend fun storeReadings(readings: List<HistoryReading>) {
        if (readings.isEmpty()) return
        
        withContext(Dispatchers.IO) {
            try {
                val filteredReadings = filterDeletedReadings(readings)
                if (filteredReadings.isEmpty()) {
                    Log.d(TAG, "Skipped ${readings.size} tombstoned readings")
                    return@withContext
                }
                dao.insertAll(filteredReadings)
                BatteryTrace.bump("room.history.insert_batch", logEvery = 20L, detail = "size=${filteredReadings.size}")
                // Only log small batches (likely genuine new data, not re-syncs)
                if (filteredReadings.size <= 10) {
                    Log.d(TAG, "Stored ${filteredReadings.size} readings")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error storing readings batch", e)
            }
        }
    }

    /**
     * Some history imports provide a canonical timestamp for a coarse sensor bucket
     * after an earlier provisional row was already stored for that same bucket.
     * Replace older rows in those buckets before inserting the canonical batch.
     */
    suspend fun storeReadingsReplacingSensorBuckets(
        sensorSerial: String,
        readings: List<HistoryReading>,
        bucketDurationMs: Long,
    ): Boolean {
        if (sensorSerial.isBlank() || readings.isEmpty()) return false

        return withContext(Dispatchers.IO) {
            try {
                val filteredReadings = filterDeletedReadings(readings)
                if (filteredReadings.isEmpty()) {
                    Log.d(TAG, "Skipped bucket replace for $sensorSerial — all readings were tombstoned")
                    return@withContext false
                }
                val collapsedReadings = HistoryBucketReplacement.collapseReadings(
                    readings = filteredReadings,
                    bucketDurationMs = bucketDurationMs,
                )
                if (collapsedReadings.isEmpty()) return@withContext false
                val plan = HistoryBucketReplacement.planForCollapsedReadings(
                    collapsedReadings = collapsedReadings,
                    bucketDurationMs = bucketDurationMs,
                ) ?: return@withContext false
                database.withTransaction {
                    deleteSensorRowsInBucketRanges(
                        sensorSerial = sensorSerial,
                        bucketDurationMs = bucketDurationMs,
                        bucketRanges = plan.bucketRanges
                    )
                    dao.insertAll(collapsedReadings)
                }
                BatteryTrace.bump(
                    "room.history.replace_bucket_batch",
                    logEvery = 20L,
                    detail = "serial=$sensorSerial size=${collapsedReadings.size} bucket=${bucketDurationMs}"
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error replacing bucket history batch for $sensorSerial", e)
                false
            }
        }
    }

    private suspend fun deleteSensorRowsInBucketRanges(
        sensorSerial: String,
        bucketDurationMs: Long,
        bucketRanges: List<HistoryBucketReplacement.BucketRange>
    ): Int {
        if (sensorSerial.isBlank() || bucketDurationMs <= 0L || bucketRanges.isEmpty()) return 0

        var deleted = 0
        for (range in bucketRanges) {
            if (range.firstBucketId > range.lastBucketId) continue
            val startTimeInclusive = range.firstBucketId * bucketDurationMs
            val endTimeExclusive = (range.lastBucketId + 1L) * bucketDurationMs
            if (endTimeExclusive <= startTimeInclusive) continue

            deleted += dao.deleteSensorRowsInTimeRange(
                sensorSerial = sensorSerial,
                startTimeInclusive = startTimeInclusive,
                endTimeExclusive = endTimeExclusive
            )
        }
        return deleted
    }

    /**
     * Physically fold imported readings into a real sensor once that sensor's
     * native history has been backfilled. Scoped to the sensor's coverage
     * window so unrelated imported data (different time period) is left alone.
     *
     * Within the window:
     *  - imported readings whose minute bucket the sensor already covers are
     *    dropped (the live sensor is authoritative on overlap);
     *  - imported readings that fill gaps are retagged to the sensor serial so
     *    they become part of that sensor's history.
     *
     * This mirrors the display-time merge in [HistoryDisplayMerge] but makes it
     * durable in the database.
     */
    suspend fun reconcileImportedIntoSensor(serial: String) {
        withContext(Dispatchers.IO) {
            try {
                val nativeSerials = resolveQuerySensorSerials(serial)
                if (nativeSerials.isEmpty()) return@withContext
                val importedSerials = IMPORTED_HISTORY_SENSOR_SERIALS.filterNot { it in nativeSerials }
                if (importedSerials.isEmpty()) return@withContext

                // Cheap guard: nothing to reconcile if there are no imported rows at all.
                if (dao.getCountForSensors(importedSerials) == 0) return@withContext

                val oldest = dao.getOldestTimestampForSensors(nativeSerials) ?: return@withContext
                val latest = dao.getLatestReadingForSensors(nativeSerials)?.timestamp ?: return@withContext
                if (oldest <= 0L || latest < oldest) return@withContext

                val importedInWindow = dao.getReadingsSinceForSensors(importedSerials, oldest)
                    .filter { it.timestamp <= latest }
                if (importedInWindow.isEmpty()) return@withContext

                val nativeBuckets = HashSet<Long>()
                dao.getTimestampsForSensors(nativeSerials, oldest, latest)
                    .forEach { nativeBuckets.add(it / SENSOR_MINUTE_BUCKET_MS) }

                val roomSerial = SensorIdentity.resolveRoomStorageSensorId(serial) ?: serial
                val gapFillers = importedInWindow
                    .filter { (it.timestamp / SENSOR_MINUTE_BUCKET_MS) !in nativeBuckets }
                    .map { it.copy(id = 0L, sensorSerial = roomSerial) }
                val retagReadings = filterDeletedReadings(gapFillers)

                database.withTransaction {
                    for (importedSerial in importedSerials) {
                        dao.deleteSensorRowsInTimeRange(
                            sensorSerial = importedSerial,
                            startTimeInclusive = oldest,
                            endTimeExclusive = latest + 1L
                        )
                    }
                    if (retagReadings.isNotEmpty()) {
                        dao.insertAll(retagReadings)
                    }
                }
                Log.d(
                    TAG,
                    "Reconciled imported history into $roomSerial: retagged ${retagReadings.size}, " +
                        "dropped ${importedInWindow.size - gapFillers.size}, window=$oldest..$latest"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error reconciling imported history into $serial", e)
            }
        }
    }

    private suspend fun filterDeletedReadings(readings: List<HistoryReading>): List<HistoryReading> {
        if (readings.isEmpty()) return emptyList()

        val deletedBySensor = mutableMapOf<String, MutableSet<Long>>()
        readings.groupBy(HistoryReading::sensorSerial).forEach { (sensorSerial, sensorReadings) ->
            val timestamps = sensorReadings.map(HistoryReading::timestamp).distinct()
            if (timestamps.isEmpty()) return@forEach
            val deletedTimestamps = LinkedHashSet<Long>()
            timestamps.chunked(DELETED_TIMESTAMP_QUERY_CHUNK).forEach { chunk ->
                deletedTimestamps.addAll(dao.getDeletedTimestampsForSensor(sensorSerial, chunk))
            }
            if (deletedTimestamps.isNotEmpty()) {
                deletedBySensor[sensorSerial] = deletedTimestamps
            }
        }

        if (deletedBySensor.isEmpty()) {
            return readings
        }

        return readings.filterNot { reading ->
            deletedBySensor[reading.sensorSerial]?.contains(reading.timestamp) == true
        }
    }

    // ── Per-sensor query methods (for dashboard, chart, current reading) ──
    
    /**
     * Get history for a specific sensor as a Flow (Raw mg/dL).
     */
    fun getHistoryFlowForSensor(serial: String, startTime: Long = 0L): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> {
        val serials = resolveQuerySensorSerials(serial)
        if (serials.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return dao.getHistoryFlowForSensors(serials, startTime).map { readings ->
            mapReadings(mergeQueryReadings(readings, serial))
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Display/history UI query for one live sensor plus CSV imports. Imported
     * readings are intentionally kept out of sync/latest cursors so native
     * backfill and current-glucose code cannot mistake them for sensor data.
     */
    fun getHistoryFlowForDisplaySensor(
        serial: String,
        startTime: Long = 0L
    ): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> {
        val serials = resolveDisplayQuerySensorSerials(serial)
        if (serials.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return dao.getHistoryFlowForSensors(serials, startTime).map { readings ->
            mapReadings(mergeQueryReadings(readings, serial))
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Display/history UI query for a bounded set of live sensors. This keeps
     * peer dashboard charts off the all-sensor Room stream while preserving
     * each sensor's rows for per-minute grouping and tooltips.
     */
    fun getHistoryFlowForDisplaySensors(
        sensorIds: List<String>,
        startTime: Long = 0L
    ): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> {
        val serials = sensorIds
            .flatMap { resolveDisplayQuerySensorSerials(it) }
            .distinct()
        if (serials.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return dao.getHistoryFlowForSensors(serials, startTime).map { readings ->
            mapReadings(readings)
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Stats-only flow optimized for large datasets:
     * - No per-point time formatting
     * - No extra sorting/distinct pass (DAO already returns ASC by timestamp)
     */
    fun getHistoryFlowForStatsSensor(
        serial: String,
        startTime: Long
    ): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> {
        val serials = resolveQuerySensorSerials(serial)
        if (serials.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return dao.getHistoryFlowForSensors(serials, startTime).map { readings ->
            mergeQueryReadings(readings, serial).map { reading ->
                GlucosePoint(
                    value = reading.value,
                    time = "",
                    timestamp = reading.timestamp,
                    rawValue = reading.rawValue,
                    rate = reading.rate,
                    sensorSerial = reading.sensorSerial
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Stats should cover the persisted historical timeline, including imported
     * CSV data and previous sensors. This uses all Room history and only applies
     * merge preference to overlapping timestamps, so imported/old sensor data is
     * visible without changing live sensor or native sync behavior.
     */
    fun getDisplayHistoryFlowForStats(
        preferredSerial: String?,
        startTime: Long
    ): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> {
        return dao.getHistoryFlow(startTime).map { readings ->
            mergeQueryReadings(readings, preferredSerial).map(::mapReadingForStats)
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Get the latest reading for a specific sensor as a reactive Flow.
     */
    fun getLatestReadingFlowForSensor(serial: String): kotlinx.coroutines.flow.Flow<GlucosePoint?> {
        val serials = resolveQuerySensorSerials(serial)
        if (serials.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(null)
        }
        return dao.getLatestReadingFlowForSensors(serials).map { reading ->
            reading?.let {
                GlucosePoint(
                    value = it.value,
                    time = formatTime(it.timestamp),
                    timestamp = it.timestamp,
                    rawValue = it.rawValue,
                    rate = it.rate,
                    sensorSerial = it.sensorSerial
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Get history for a specific sensor (suspend, Raw mg/dL).
     */
    suspend fun getHistoryForSensor(serial: String, startTime: Long): List<GlucosePoint> {
        val serials = resolveQuerySensorSerials(serial)
        if (serials.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val readings = dao.getReadingsSinceForSensors(serials, startTime)
                mapReadings(mergeQueryReadings(readings, serial))
            } catch (e: Exception) {
                Log.e(TAG, "Error getting history for sensor $serial", e)
                emptyList()
            }
        }
    }

    suspend fun getHistoryForDisplaySensor(serial: String, startTime: Long): List<GlucosePoint> {
        val serials = resolveDisplayQuerySensorSerials(serial)
        if (serials.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val readings = dao.getReadingsSinceForSensors(serials, startTime)
                mapReadings(mergeQueryReadings(readings, serial))
            } catch (e: Exception) {
                Log.e(TAG, "Error getting display history for sensor $serial", e)
                emptyList()
            }
        }
    }

    suspend fun getDisplayHistoryForStats(preferredSerial: String?, startTime: Long): List<GlucosePoint> {
        return withContext(Dispatchers.IO) {
            try {
                val readings = dao.getReadingsSince(startTime)
                mergeQueryReadings(readings, preferredSerial).map(::mapReadingForStats)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting stats display history", e)
                emptyList()
            }
        }
    }

    suspend fun getHistoryTimestampsForSensor(
        serial: String,
        startTime: Long,
        endTime: Long
    ): List<Long> = getHistoryTimestampsForSensorOrNull(serial, startTime, endTime) ?: emptyList()

    /**
     * Stored timestamps in the window, or null when the query could not be answered.
     *
     * Callers diff this against what a sensor reports and treat an empty list as "nothing is
     * stored" — a conclusion that costs a full history re-download. A failed query must not
     * masquerade as that answer, so it is reported as null and the caller decides.
     */
    suspend fun getHistoryTimestampsForSensorOrNull(
        serial: String,
        startTime: Long,
        endTime: Long
    ): List<Long>? {
        val serials = resolveQuerySensorSerials(serial)
        if (serials.isEmpty() || endTime < startTime) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                dao.getTimestampsForSensors(serials, startTime, endTime)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting history timestamps for sensor $serial", e)
                null
            }
        }
    }

    /**
     * Get the timestamp of the latest stored reading for a specific sensor.
     * Returns 0 if no readings exist for that sensor.
     */
    suspend fun getLatestTimestampForSensor(serial: String): Long {
        val serials = resolveQuerySensorSerials(serial)
        if (serials.isEmpty()) return 0L
        return withContext(Dispatchers.IO) {
            try {
                dao.getLatestReadingForSensors(serials)?.timestamp ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Error getting latest timestamp for sensor $serial", e)
                0L
            }
        }
    }

    suspend fun getOldestTimestampForSensor(serial: String): Long {
        val serials = resolveQuerySensorSerials(serial)
        if (serials.isEmpty()) return 0L
        return withContext(Dispatchers.IO) {
            try {
                dao.getOldestTimestampForSensors(serials) ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Error getting oldest timestamp for sensor $serial", e)
                0L
            }
        }
    }

    suspend fun getOldestDisplayTimestamp(): Long {
        return withContext(Dispatchers.IO) {
            try {
                dao.getOldestTimestamp() ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Error getting oldest display timestamp", e)
                0L
            }
        }
    }

    suspend fun getReadingCountForSensor(serial: String): Int {
        val serials = resolveQuerySensorSerials(serial)
        if (serials.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            try {
                dao.getCountForSensors(serials)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting count for sensor $serial", e)
                0
            }
        }
    }

    // ── All-sensor query methods (for export, legacy compatibility) ──
    
    /**
     * Get history as a Flow for reactive updates (Raw mg/dL, all sensors).
     */
    fun getHistoryFlow(startTime: Long = 0L): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> {
        return dao.getHistoryFlow(startTime).map { readings ->
            mapReadings(readings)
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Get the latest reading as a reactive Flow (any sensor).
     * Legacy: use getLatestReadingFlowForSensor() for per-sensor queries.
     */
    fun getLatestReadingFlow(): kotlinx.coroutines.flow.Flow<GlucosePoint?> {
        return dao.getLatestReadingFlow().map { reading ->
            reading?.let {
                GlucosePoint(
                    value = it.value,
                    time = formatTime(it.timestamp),
                    timestamp = it.timestamp,
                    rawValue = it.rawValue,
                    rate = it.rate,
                    sensorSerial = it.sensorSerial
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Get history for chart display (Raw mg/dL, all sensors).
     * @param startTime Start time in milliseconds (0 = all data)
     */
    suspend fun getHistory(startTime: Long): List<GlucosePoint> {
        return withContext(Dispatchers.IO) {
            try {
                val readings = dao.getReadingsSince(startTime)
                mapReadings(readings)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting history", e)
                emptyList()
            }
        }
    }

    /**
     * Get history in raw mg/dL (no conversion, all sensors).
     */
    suspend fun getHistoryRaw(startTime: Long): List<GlucosePoint> {
        return withContext(Dispatchers.IO) {
            try {
                val readings = dao.getReadingsSince(startTime)
                mapReadings(readings)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting raw history", e)
                emptyList()
            }
        }
    }

    /**
     * Get display history as a merged multi-sensor timeline.
     * Preserves older non-conflicting rows while preferring the currently selected
     * sensor when multiple sensors have readings at the same timestamp.
     */
    suspend fun getDisplayHistory(preferredSerial: String?, startTime: Long): List<GlucosePoint> {
        return withContext(Dispatchers.IO) {
            try {
                val readings = dao.getReadingsSince(startTime)
                mapDisplayReadings(readings, preferredSerial)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting display history", e)
                emptyList()
            }
        }
    }

    /**
     * Reactive display-history flow using the same merged multi-sensor timeline
     * as the dashboard and chart.
     */
    fun getDisplayHistoryFlow(
        preferredSerial: String?,
        startTime: Long = 0L
    ): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> {
        return dao.getHistoryFlow(startTime).map { readings ->
            mapDisplayReadings(readings, preferredSerial)
        }.flowOn(Dispatchers.IO)
    }

    
    /**
     * Get the count of stored readings (all sensors).
     */
    suspend fun getReadingCount(): Int {
        return withContext(Dispatchers.IO) {
            try {
                dao.getCount()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting count", e)
                0
            }
        }
    }
    
    /**
     * Get the timestamp of the latest stored reading (any sensor).
     * Returns 0 if no readings exist.
     */
    suspend fun getLatestTimestamp(): Long {
        return withContext(Dispatchers.IO) {
            try {
                dao.getLatestReading()?.timestamp ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Error getting latest timestamp", e)
                0L
            }
        }
    }

    private fun mapReadings(readings: List<HistoryReading>): List<GlucosePoint> {
        return readings.map { reading ->
            GlucosePoint(
                value = reading.value,
                time = formatTime(reading.timestamp),
                timestamp = reading.timestamp,
                rawValue = reading.rawValue,
                rate = reading.rate,
                sensorSerial = reading.sensorSerial
            )
        }
    }

    private fun mapReadingForStats(reading: HistoryReading): GlucosePoint {
        return GlucosePoint(
            value = reading.value,
            time = "",
            timestamp = reading.timestamp,
            rawValue = reading.rawValue,
            rate = reading.rate,
            sensorSerial = reading.sensorSerial
        )
    }

    private fun mapDisplayReadings(
        readings: List<HistoryReading>,
        preferredSerial: String?
    ): List<GlucosePoint> {
        return mapReadings(HistoryDisplayMerge.mergeReadings(readings, preferredSerial))
    }

    private fun mergeQueryReadings(
        readings: List<HistoryReading>,
        preferredSerial: String?
    ): List<HistoryReading> {
        return HistoryDisplayMerge.mergeReadings(readings, preferredSerial)
    }

    private fun formatTime(timestamp: Long): String =
        requireNotNull(TIME_FORMATTER.get()).format(Date(timestamp))
    
    /**
     * Backfill native history for any sensor that the current UI/session needs and
     * has not yet been merged into Room during this process lifetime.
     */
    suspend fun ensureBackfilled(preferredSerial: String? = null, startTime: Long = 0L) {
        val preferred = (SensorIdentity.resolveAppSensorId(preferredSerial) ?: preferredSerial)
            ?.takeIf { it.isNotBlank() }
        val sensorsToCheck = if (preferred != null) {
            linkedSetOf(preferred)
        } else {
            linkedSetOfSensors(
                Natives.activeSensors(),
                Natives.lastsensorname()
            )
        }.filter { sensor ->
            sensor != IMPORTED_SENSOR_SERIAL && SensorIdentity.shouldUseNativeHistorySync(sensor)
        }
        if (sensorsToCheck.isEmpty()) {
            Log.d(TAG, "No sensors for backfill")
            return
        }

        withContext(Dispatchers.IO) {
            val requestedStart = startTime.coerceAtLeast(0L)
            Log.d(TAG, "Merging native history into Room for sensors=$sensorsToCheck start=$requestedStart")
            for (serial in sensorsToCheck) {
                var shouldBackfill = false
                while (!shouldBackfill) {
                    var alreadyCovered = false
                    backfillLock.withLock {
                        val coveredStart = backfilledSensorStartMs[serial]
                        val lastAttemptStart = backfillAttemptStartMs[serial]
                        val lastAttemptWall = backfillAttemptWallMs[serial] ?: 0L
                        val recentAttemptCovers = lastAttemptStart != null &&
                            lastAttemptStart <= requestedStart &&
                            (System.currentTimeMillis() - lastAttemptWall) < BACKFILL_RETRY_COOLDOWN_MS
                        if (coveredStart != null && coveredStart <= requestedStart) {
                            alreadyCovered = true
                        } else if (recentAttemptCovers) {
                            alreadyCovered = true
                        } else if (!backfillInProgressStartMs.containsKey(serial)) {
                            backfillInProgressStartMs[serial] = requestedStart
                            backfillAttemptStartMs[serial] = requestedStart
                            backfillAttemptWallMs[serial] = System.currentTimeMillis()
                            shouldBackfill = true
                        } else {
                            backfillFinished.awaitUninterruptibly()
                        }
                    }
                    if (alreadyCovered) {
                        break
                    }
                }
                if (!shouldBackfill) {
                    continue
                }
                val success = backfillSensor(serial, startTime)
                backfillLock.withLock {
                    backfillInProgressStartMs.remove(serial)
                    if (success) {
                        val previousStart = backfilledSensorStartMs[serial]
                        backfilledSensorStartMs[serial] = if (previousStart == null) {
                            requestedStart
                        } else {
                            minOf(previousStart, requestedStart)
                        }
                    }
                    backfillFinished.signalAll()
                }
                if (success) {
                    // Fold any overlapping imported history into this real sensor.
                    reconcileImportedIntoSensor(serial)
                }
            }
        }
    }

    /**
     * Backfill a single sensor's data from the native layer.
     */
    private suspend fun backfillSensor(serial: String, requestedStartTimeMs: Long): Boolean {
        try {
            val roomSerial = SensorIdentity.resolveRoomStorageSensorId(serial) ?: serial
            val startSec = resolveNativeBackfillStartSec(serial, requestedStartTimeMs)
            val rawHistory = loadNativeHistory(serial, startSec)
            if (rawHistory == null) {
                Log.d(TAG, "Native history for $serial returned null from start=$startSec")
                return false
            }

            val readings = ArrayList<HistoryReading>(NATIVE_BACKFILL_INSERT_CHUNK)
            var storedCount = 0
            for (i in rawHistory.indices step 3) {
                if (i + 2 >= rawHistory.size) break

                val timeSec = rawHistory[i]
                val valueAutoRaw = rawHistory[i + 1]
                val valueRawRaw = rawHistory[i + 2]

                // Values from native are in mg/dL * 10
                if (timeSec < startSec) continue
                val value = valueAutoRaw / 10f
                val rawValue = valueRawRaw / 10f

                if (value > 0 || rawValue > 0) {
                    readings.add(HistoryReading(
                        timestamp = timeSec * 1000L,
                        sensorSerial = roomSerial,
                        value = value,
                        rawValue = rawValue,
                        rate = 0f  // Rate not available from history
                    ))
                    if (readings.size >= NATIVE_BACKFILL_INSERT_CHUNK) {
                        storedCount += insertBackfillChunk(serial, readings)
                        readings.clear()
                    }
                }
            }

            if (readings.isNotEmpty()) {
                storedCount += insertBackfillChunk(serial, readings)
                readings.clear()
            }
            if (storedCount > 0) {
                Log.d(TAG, "Backfilled $storedCount readings from native for sensor $serial start=$startSec")
            } else {
                Log.d(TAG, "Backfill for $serial completed with 0 readings")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error backfilling sensor $serial", e)
            return false
        }
    }

    private suspend fun insertBackfillChunk(serial: String, readings: List<HistoryReading>): Int {
        if (readings.isEmpty()) return 0
        val filteredReadings = filterDeletedReadings(readings)
        if (filteredReadings.isEmpty()) return 0
        val roomSerial = filteredReadings.firstOrNull()?.sensorSerial
            ?: SensorIdentity.resolveRoomStorageSensorId(serial)
            ?: serial
        val collapsedCount = HistoryBucketReplacement
            .collapseReadings(filteredReadings, SENSOR_MINUTE_BUCKET_MS)
            .size
        return if (storeReadingsReplacingSensorBuckets(roomSerial, filteredReadings, SENSOR_MINUTE_BUCKET_MS)) {
            collapsedCount
        } else {
            0
        }
    }

    private suspend fun resolveNativeBackfillStartSec(serial: String, requestedStartTimeMs: Long): Long {
        val requestedStart = requestedStartTimeMs.coerceAtLeast(0L)
        val oldest = getOldestTimestampForSensor(serial)
        val latest = getLatestTimestampForSensor(serial)
        val startMs = when {
            latest <= 0L -> requestedStart
            requestedStart > 0L && (oldest <= 0L || oldest > requestedStart + HISTORY_COVERAGE_TOLERANCE_MS) -> requestedStart
            else -> (latest - NATIVE_BACKFILL_OVERLAP_MS).coerceAtLeast(0L)
        }
        return startMs / 1000L
    }

    private fun loadNativeHistory(serial: String, startSec: Long): LongArray? {
        val queryNames = SensorIdentity.resolveNativeHistorySensorNames(serial)
            .ifEmpty { listOf(serial) }
        for (queryName in queryNames) {
            val exact = try {
                Natives.getGlucoseHistoryForSensor(queryName, startSec)
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
     * Delete all Room history for a specific sensor.
     * Used before re-syncing after localReplay — since the DAO uses IGNORE on
     * conflict, recalibrated values with unchanged timestamps would be silently
     * skipped. Deleting first forces a clean re-insert.
     */
    suspend fun deleteForSensor(serial: String) {
        withContext(Dispatchers.IO) {
            try {
                dao.deleteForSensor(serial)
                Log.d(TAG, "Deleted Room data for sensor $serial")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting data for sensor $serial", e)
            }
        }
    }

    suspend fun deleteReading(timestamp: Long, sensorSerial: String): Int {
        if (timestamp <= 0L || sensorSerial.isBlank()) return 0
        val serials = resolveQuerySensorSerials(sensorSerial).ifEmpty { listOf(sensorSerial) }
        if (serials.isEmpty()) return 0

        return withContext(Dispatchers.IO) {
            try {
                val deletedAt = System.currentTimeMillis()
                var removedCount = 0
                database.withTransaction {
                    dao.insertDeletedReadings(
                        serials.map { serial ->
                            DeletedHistoryReading(
                                timestamp = timestamp,
                                sensorSerial = serial,
                                deletedAt = deletedAt
                            )
                        }
                    )
                    removedCount = dao.deleteReadingsAtTimestamp(serials, timestamp)
                }
                UiRefreshBus.requestDataRefresh()
                Log.d(TAG, "Deleted reading at $timestamp for serials=$serials removed=$removedCount")
                removedCount
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting reading at $timestamp for $sensorSerial", e)
                0
            }
        }
    }

    suspend fun deleteReadingsForSensorAfter(sensorSerial: String, timestampExclusive: Long): Int {
        if (timestampExclusive <= 0L || sensorSerial.isBlank()) return 0
        val serials = resolveQuerySensorSerials(sensorSerial).ifEmpty { listOf(sensorSerial) }
        if (serials.isEmpty()) return 0

        return withContext(Dispatchers.IO) {
            try {
                val removedCount = dao.deleteReadingsForSensorsAfter(serials, timestampExclusive)
                if (removedCount > 0) {
                    UiRefreshBus.requestDataRefresh()
                    Log.w(TAG, "Deleted $removedCount future readings for serials=$serials after $timestampExclusive")
                }
                removedCount
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting future readings for $sensorSerial after $timestampExclusive", e)
                0
            }
        }
    }

    suspend fun rewriteSensorValuesWithCalibration(
        sensorSerial: String,
        isRawMode: Boolean,
        startTimestamp: Long = 0L
    ): Int {
        if (sensorSerial.isBlank()) return 0
        if (!CalibrationManager.hasActiveCalibration(isRawMode, sensorSerial)) return 0
        val effectiveStartTimestamp = if (CalibrationManager.shouldLockPastHistory()) {
            startTimestamp.coerceAtLeast(0L)
        } else {
            0L
        }

        return withContext(Dispatchers.IO) {
            try {
                val readings = dao.getReadingsSinceForSensor(sensorSerial, effectiveStartTimestamp)
                var updated = 0
                var mirrored = 0
                readings.forEach { reading ->
                    val baseValue = if (isRawMode) reading.rawValue else reading.value
                    if (!baseValue.isFinite() || baseValue <= 0f) return@forEach
                    val calibrated = CalibrationManager.getCalibratedValue(
                        value = baseValue,
                        timestamp = reading.timestamp,
                        isRawMode = isRawMode,
                        sensorIdOverride = sensorSerial
                    )
                    if (!calibrated.isFinite() || calibrated <= 0f) return@forEach
                    val currentStored = if (isRawMode) reading.rawValue else reading.value
                    if (kotlin.math.abs(calibrated - currentStored) < 0.01f) return@forEach
                    val changed = if (isRawMode) {
                        dao.updateRawValueAtTime(
                            sensorSerial = sensorSerial,
                            timestamp = reading.timestamp,
                            rawValue = calibrated
                        )
                    } else {
                        dao.updateValueAtTime(
                            sensorSerial = sensorSerial,
                            timestamp = reading.timestamp,
                            value = calibrated
                        )
                    }
                    if (changed > 0) {
                        updated += changed
                        val pushed = runCatching {
                            val tsSec = reading.timestamp / 1000L
                            if (isRawMode) {
                                Natives.addRawGlucoseStream(tsSec, calibrated, sensorSerial)
                            } else {
                                Natives.addGlucoseStream(tsSec, calibrated, sensorSerial)
                            }
                            true
                        }.getOrDefault(false)
                        if (pushed) mirrored += changed
                    }
                }
                if (mirrored > 0) {
                    runCatching { Natives.wakebackup() }
                }
                if (updated > 0) {
                    Log.d(
                        TAG,
                        "Rewrote $updated readings with calibrated values for $sensorSerial (start=$effectiveStartTimestamp, mirrored=$mirrored)"
                    )
                }
                updated
            } catch (e: Exception) {
                Log.e(TAG, "Failed rewriting calibrated values for $sensorSerial", e)
                0
            }
        }
    }

    private fun linkedSetOfSensors(
        activeSensors: Array<String?>?,
        mainSensor: String?,
        preferredSerial: String? = null
    ): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        activeSensors?.forEach { serial ->
            (SensorIdentity.resolveAppSensorId(serial) ?: serial)
                ?.takeIf { it.isNotBlank() }
                ?.let(result::add)
        }
        (SensorIdentity.resolveAppSensorId(mainSensor) ?: mainSensor)
            ?.takeIf { it.isNotBlank() }
            ?.let(result::add)
        (SensorIdentity.resolveAppSensorId(preferredSerial) ?: preferredSerial)
            ?.takeIf { it.isNotBlank() }
            ?.let(result::add)
        return result
    }
}
