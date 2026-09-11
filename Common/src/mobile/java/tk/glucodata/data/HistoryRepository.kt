package tk.glucodata.data

import android.content.Context
import android.util.Log
import tk.glucodata.chart.MainSensorOwnership
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
import tk.glucodata.CloneSensorRegistry
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.HistorySourceProvenance
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
    private val uncertaintyDao = database.readingUncertaintyDao()
    private val displayDao = database.readingDisplayDao()

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

        /**
         * How far ahead of the clock a reading may be before it is worth saying so.
         *
         * A sensor's sample can legitimately be dated a few seconds ahead — clocks
         * drift, the driver rounds to the second, the write lands after the sample
         * time it names. A whole minute ahead is not that; it means whatever
         * produced the row is working from a different clock than the phone, and
         * the reading will render under a minute label the user has not reached
         * yet.
         *
         * Kept out of the write path's decisions on purpose: the reading is still
         * stored exactly as given. Inventing or shifting a glucose timestamp to
         * make a label look right would be far worse than a wrong label.
         */
        private const val FUTURE_TIMESTAMP_REPORT_MS = 45_000L
        private val futureTimestampLastReportMs = HashMap<String, Long>()

        private var lastCompositionReportMs = 0L

        /**
         * Reports which sensor each of the newest points came from, once a minute.
         *
         * The dashboard line is merged across sensors, and the chart starts a new
         * segment whenever the sensor changes — so a line that alternates between
         * two sensors minute by minute draws as a row of disconnected fragments,
         * which looks exactly like missing data and is not. This says which of the
         * two is happening without needing a guess: if the tail is one serial, the
         * gaps are real dropouts; if it alternates, they are seams.
         */
        @JvmStatic
        fun reportRecentSensorComposition(points: List<GlucosePoint>) {
            if (points.isEmpty()) return
            val nowMs = System.currentTimeMillis()
            synchronized(futureTimestampLastReportMs) {
                if ((nowMs - lastCompositionReportMs) < 60_000L) return
                lastCompositionReportMs = nowMs
            }
            val tail = points.takeLast(12)
            val runs = StringBuilder()
            var currentSerial: String? = null
            var runLength = 0
            fun flush() {
                if (runLength > 0) runs.append(currentSerial ?: "null").append('x').append(runLength).append(' ')
            }
            tail.forEach { point ->
                if (point.sensorSerial != currentSerial) {
                    flush()
                    currentSerial = point.sensorSerial
                    runLength = 0
                }
                runLength++
            }
            flush()
            val newest = tail.last()
            Log.i(
                TAG,
                "dashboard tail: total=${points.size} newest=${formatMinute(newest.timestamp)} " +
                    "(${newest.timestamp}, serial=${newest.sensorSerial}) clock=${formatMinute(nowMs)} " +
                    "runs=[${runs.toString().trim()}]"
            )
        }

        /**
         * Reports a reading dated meaningfully ahead of the phone's clock, at most
         * once a minute per sensor, naming who wrote it.
         */
        @JvmStatic
        fun reportIfFutureTimestamp(sensorSerial: String, timestamp: Long, writer: String) {
            val nowMs = System.currentTimeMillis()
            val aheadMs = timestamp - nowMs
            if (aheadMs < FUTURE_TIMESTAMP_REPORT_MS) return
            synchronized(futureTimestampLastReportMs) {
                val last = futureTimestampLastReportMs[sensorSerial] ?: 0L
                if ((nowMs - last) < 60_000L) return
                futureTimestampLastReportMs[sensorSerial] = nowMs
            }
            Log.w(
                TAG,
                "future reading: sensor=$sensorSerial writer=$writer " +
                    "stamped=$timestamp now=$nowMs ahead=${aheadMs}ms " +
                    "(row will show as ${formatMinute(timestamp)} while the clock reads ${formatMinute(nowMs)})"
            )
        }

        /**
         * How much history the dashboard paints before the full timeline lands.
         *
         * Wide enough to fill the chart at its default range without a visible
         * second step, narrow enough that it is a few hundred rows rather than
         * tens of thousands.
         */
        private const val FIRST_PAINT_TAIL_MS = 12L * 60L * 60L * 1000L
        const val IMPORTED_SENSOR_SERIAL = "__imported_csv__"
        private val IMPORTED_HISTORY_SENSOR_SERIALS = listOf(
            IMPORTED_SENSOR_SERIAL,
            "imported",
            "unknown"
        )

        /** A row no sensor owns: imported, or stored before the serial was known. */
        fun isImportedHistorySerial(serial: String): Boolean = serial in IMPORTED_HISTORY_SENSOR_SERIALS

        /**
         * Formatted "HH:mm" per minute, reused across emissions.
         *
         * Every reading carries a preformatted time string and exactly one place
         * reads it — the chart tooltip. Room re-emits the whole table on each
         * insert, so a store with 18k readings ran 18k SimpleDateFormat calls,
         * each allocating a Date, once a minute forever, to display one of them.
         *
         * A cache rather than a hand-rolled formatter on purpose: the output is
         * byte-identical to what SimpleDateFormat produces for the current
         * locale, including non-ASCII digit shaping, so nothing on screen
         * changes. Timestamps repeat exactly across emissions, so after the
         * first pass this is essentially all hits.
         *
         * Cleared when the locale or time zone changes, since both change what
         * the same millisecond formats to.
         */
        private const val TIME_CACHE_CAPACITY = 8_192
        private val timeCacheLock = Any()
        private var timeCacheLocale: Locale? = null
        private var timeCacheZoneId: String? = null
        private var timeFormatter: SimpleDateFormat? = null
        private val timeCache = object : LinkedHashMap<Long, String>(1_024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
                size > TIME_CACHE_CAPACITY
        }

        /**
         * "HH:mm" for [timestamp], cached by minute.
         *
         * The formatter moved in here from a ThreadLocal because it is now
         * consulted only on a miss. The ThreadLocal also captured the locale at
         * first use per thread and never rebuilt, so a locale change left some
         * threads formatting in the old one; rebuilding it alongside the cache
         * fixes that as a side effect.
         */
        private fun formatMinute(timestamp: Long): String {
            val minute = Math.floorDiv(timestamp, 60_000L)
            val locale = Locale.getDefault()
            val zoneId = java.util.TimeZone.getDefault().id
            synchronized(timeCacheLock) {
                if (timeCacheLocale != locale || timeCacheZoneId != zoneId) {
                    timeCache.clear()
                    timeCacheLocale = locale
                    timeCacheZoneId = zoneId
                    timeFormatter = SimpleDateFormat("HH:mm", locale)
                } else {
                    timeCache[minute]?.let { return it }
                }
                val formatter = timeFormatter
                    ?: SimpleDateFormat("HH:mm", locale).also { timeFormatter = it }
                return formatter.format(Date(timestamp)).also { timeCache[minute] = it }
            }
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
                    .map { p ->
                        // Carry the record across. This bridge used to keep only
                        // (timestamp, value, rawValue), so every consumer behind
                        // it — the notification chart first among them — could
                        // only draw today's derivation of a line the user had
                        // already been shown.
                        tk.glucodata.GlucosePoint(p.timestamp, p.value, p.rawValue).also { out ->
                            out.sealedDisplayValue = p.sealedDisplayValue ?: Float.NaN
                            out.sealedDisplayViewMode = p.sealedDisplayViewMode ?: -1
                            out.sensorSerial = p.sensorSerial
                        }
                    }
            }
        }
        
        /**
         * Blocking ownership for shared/main renderers, so the notification
         * resolves who is main at each minute from the same record the
         * dashboard does rather than approximating it.
         */
        @Keep
        @JvmStatic
        fun getMainSensorOwnershipForNotification(startTime: Long): MainSensorOwnership {
            return kotlinx.coroutines.runBlocking {
                runCatching { HistoryRepository().mainSensorOwnership(startTime = startTime) }
                    .getOrDefault(MainSensorOwnership.NONE)
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
        fun storeReadingWithSourceAsync(
            timestamp: Long,
            value: Float,
            rawValue: Float,
            rate: Float,
            sensorSerial: String,
            source: String
        ) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                HistoryRepository().storeReading(timestamp, value, rawValue, rate, sensorSerial, source)
            }
        }

        @JvmStatic
        fun storeHistoryBatchAsync(
            sensorSerial: String,
            timestamps: LongArray,
            values: FloatArray,
            rawValues: FloatArray
        ) = storeHistoryBatchWithSourceAsync(
            sensorSerial,
            timestamps,
            values,
            rawValues,
            GlucoseReadingSource.SENSOR,
        )

        @JvmStatic
        fun storeHistoryBatchWithSourceAsync(
            sensorSerial: String,
            timestamps: LongArray,
            values: FloatArray,
            rawValues: FloatArray,
            source: String
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
                        rate = null,
                        source = source,
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
        ): Boolean = storeHistoryBatchWithSourceBlocking(
            sensorSerial,
            timestamps,
            values,
            rawValues,
            GlucoseReadingSource.SENSOR,
        )

        @Keep
        @JvmStatic
        fun storeHistoryBatchWithSourceBlocking(
            sensorSerial: String,
            timestamps: LongArray,
            values: FloatArray,
            rawValues: FloatArray,
            source: String
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
                                rate = null,
                                source = source,
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
    suspend fun storeReading(
        timestamp: Long,
        value: Float,
        rawValue: Float,
        rate: Float,
        sensorSerial: String? = null,
        source: String = GlucoseReadingSource.SENSOR,
    ) {
        // Don't store invalid readings
        if (value <= 0 && rawValue <= 0) return
        
        val rawSerial = sensorSerial
            ?: SensorIdentity.resolveMainSensor()
            ?: Natives.lastsensorname()
            ?: "unknown"
        val serial = SensorIdentity.resolveRoomStorageSensorId(rawSerial) ?: rawSerial
        // The sensor's own numbers, stored as measured. A calibrated projection
        // used to be written into `value` here, which made the stored reading a
        // function of whatever calibration happened to be configured at the
        // moment it arrived; the projection is now recorded beside it instead.
        val reading = HistoryReading(
            timestamp = timestamp,
            sensorSerial = serial,
            value = value,
            rawValue = rawValue,
            rate = rate,
            source = source,
        )
        reportIfFutureTimestamp(serial, timestamp, writer = "storeReading")
        withContext(Dispatchers.IO) {
            try {
                if (dao.isReadingDeleted(serial, timestamp) > 0) {
                    Log.d(TAG, "Skipped tombstoned reading for $serial at $timestamp")
                    return@withContext
                }
                database.withTransaction {
                    val bucketStart = (timestamp / SENSOR_MINUTE_BUCKET_MS) * SENSOR_MINUTE_BUCKET_MS
                    val existing = dao.getSensorReadingsInTimeRange(
                        sensorSerial = serial,
                        startTimeInclusive = bucketStart,
                        endTimeExclusive = bucketStart + SENSOR_MINUTE_BUCKET_MS,
                    ).firstOrNull { it.timestamp == timestamp }
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
                    dao.insert(
                        reading.copy(
                            source = HistorySourceProvenance.stableSource(existing?.source, reading.source),
                            firstStoredAt = HistorySourceProvenance.stableFirstStoredAt(
                                existing?.firstStoredAt,
                                reading.firstStoredAt,
                            ),
                        )
                    )
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
                filteredReadings.maxByOrNull { it.timestamp }?.let { newest ->
                    reportIfFutureTimestamp(newest.sensorSerial, newest.timestamp, writer = "storeReadings")
                }
                database.withTransaction {
                    val existingByKey = HashMap<Pair<String, Long>, HistoryReading>()
                    filteredReadings.groupBy(HistoryReading::sensorSerial).forEach { (serial, rows) ->
                        rows.map(HistoryReading::timestamp).distinct().chunked(DELETED_TIMESTAMP_QUERY_CHUNK)
                            .forEach { timestamps ->
                                dao.getSensorReadingsAtTimestamps(serial, timestamps).forEach { existing ->
                                    existingByKey[serial to existing.timestamp] = existing
                                }
                            }
                    }
                    val readingsWithStableProvenance = filteredReadings.map { incoming ->
                        val existing = existingByKey[incoming.sensorSerial to incoming.timestamp]
                        incoming.copy(
                            source = HistorySourceProvenance.stableSource(existing?.source, incoming.source),
                            firstStoredAt = HistorySourceProvenance.stableFirstStoredAt(
                                existing?.firstStoredAt,
                                incoming.firstStoredAt,
                            ),
                        )
                    }
                    dao.insertAll(readingsWithStableProvenance)
                }
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
                collapsedReadings.maxByOrNull { it.timestamp }?.let { newest ->
                    reportIfFutureTimestamp(
                        newest.sensorSerial,
                        newest.timestamp,
                        writer = "storeReadingsReplacingSensorBuckets"
                    )
                }
                val plan = HistoryBucketReplacement.planForCollapsedReadings(
                    collapsedReadings = collapsedReadings,
                    bucketDurationMs = bucketDurationMs,
                ) ?: return@withContext false
                database.withTransaction {
                    val existingProvenance = HashMap<Long, HistoryReading>()
                    for (range in plan.bucketRanges) {
                        val startTimeInclusive = range.firstBucketId * bucketDurationMs
                        val endTimeExclusive = (range.lastBucketId + 1L) * bucketDurationMs
                        dao.getSensorReadingsInTimeRange(
                            sensorSerial = sensorSerial,
                            startTimeInclusive = startTimeInclusive,
                            endTimeExclusive = endTimeExclusive,
                        ).forEach { existing -> existingProvenance[existing.timestamp] = existing }
                    }
                    val readingsWithStableSources = collapsedReadings.map { incoming ->
                        val existing = existingProvenance[incoming.timestamp]
                        incoming.copy(
                            source = HistorySourceProvenance.stableSource(
                                existing?.source,
                                incoming.source,
                            ),
                            firstStoredAt = HistorySourceProvenance.stableFirstStoredAt(
                                existing?.firstStoredAt,
                                incoming.firstStoredAt,
                            ),
                        )
                    }
                    deleteSensorRowsInBucketRanges(
                        sensorSerial = sensorSerial,
                        bucketDurationMs = bucketDurationMs,
                        bucketRanges = plan.bucketRanges
                    )
                    dao.insertAll(readingsWithStableSources)
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
        return withSealedDisplay(
            serials,
            startTime,
            withUncertainty(dao.getHistoryFlowForSensors(serials, startTime), serials, startTime) {
                readings, uncertainty ->
                mapReadings(mergeQueryReadings(readings, serial), uncertainty)
            }
        )
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
        return withSealedDisplay(
            serials,
            startTime,
            withUncertainty(dao.getHistoryFlowForSensors(serials, startTime), serials, startTime) {
                readings, uncertainty ->
                mapReadings(mergeQueryReadings(readings, serial), uncertainty)
            }
        )
    }

    /**
     * Joins a readings flow with the credible intervals recorded for the same
     * sensors, for the paths that actually draw a chart.
     *
     * This exists as one helper rather than an argument threaded through each
     * query because the last two attempts to attach uncertainty each wired up a
     * single method and missed the one the dashboard actually calls — the
     * feature then looks completely absent with nothing failing anywhere.
     * Anything that renders glucose should go through here; the stats and
     * export paths deliberately do not, and say so at their call site.
     */
    private fun withUncertainty(
        @Suppress("UNUSED_PARAMETER") readings: kotlinx.coroutines.flow.Flow<List<HistoryReading>>,
        serials: List<String>,
        startTime: Long,
        map: (List<HistoryReading>, Map<Long, ReadingUncertainty>) -> List<GlucosePoint>
    ): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> =
        uncertaintyDao.getReadingsWithUncertaintyFlow(serials, startTime)
            .map { joined -> map(joined.map { it.toReading() }, joined.indexedUncertainty()) }
            .flowOn(Dispatchers.IO)

    /**
     * The same idea as [withUncertainty], for the recorded display value: one
     * helper, so a display path cannot be wired up and quietly left out.
     *
     * A point that carries a sealed value shows it. That is the whole guarantee
     * — see [ReadingDisplay] — and it has to hold on every query that renders
     * glucose, not just the one that was in front of whoever added the feature.
     */
    private fun withSealedDisplay(
        serials: List<String>,
        startTime: Long,
        points: kotlinx.coroutines.flow.Flow<List<GlucosePoint>>
    ): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> =
        kotlinx.coroutines.flow.combine(
            points,
            displayDao.getFlow(startTime)
        ) { mapped, display ->
            if (display.isEmpty() ||
                !runCatching { CalibrationManager.shouldFreezeDisplayedValues() }.getOrDefault(false)
            ) {
                mapped
            } else {
                val nowMs = System.currentTimeMillis()
                val indexed = display.indexedDisplay()
                mapped.map { point ->
                    val sealed = indexed[displayKey(point.timestamp)]
                        ?.takeIf { it.isUsable && it.isSealedAt(nowMs) }
                        ?.takeIf { SensorIdentity.matches(point.sensorSerial, it.sensorSerial) }
                        ?: return@map point
                    point.copy(sealedDisplayValue = sealed.displayMgdl, sealedDisplayViewMode = sealed.viewMode)
                }
            }
        }.flowOn(Dispatchers.IO)

    private fun HistoryReadingWithUncertainty.toReading(): HistoryReading = HistoryReading(
        id = id,
        timestamp = timestamp,
        sensorSerial = sensorSerial,
        value = value,
        rawValue = rawValue,
        rate = rate,
    )

    private fun List<HistoryReadingWithUncertainty>.indexedUncertainty(): Map<Long, ReadingUncertainty> {
        val rows = HashMap<Long, ReadingUncertainty>()
        forEach { joined ->
            val lower = joined.lowerMgdl ?: return@forEach
            val upper = joined.upperMgdl ?: return@forEach
            val row = ReadingUncertainty(
                sensorSerial = joined.sensorSerial,
                timestamp = joined.timestamp,
                lowerMgdl = lower,
                upperMgdl = upper,
                intervalMass = joined.intervalMass ?: tk.glucodata.GlucoseUncertainty.DEFAULT_INTERVAL_MASS,
                confidence = joined.confidence,
                artifactProbability = joined.artifactProbability,
            )
            if (row.isUsable) rows[uncertaintyKey(row)] = row
        }
        return rows
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
        return withSealedDisplay(
            serials,
            startTime,
            withUncertainty(dao.getHistoryFlowForSensors(serials, startTime), serials, startTime) {
                readings, uncertainty ->
                mapReadings(readings, uncertainty)
            }
        )
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
                    sensorSerial = reading.sensorSerial,
                    source = reading.source,
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
        // Stats deliberately skip the uncertainty join (they consume the value
        // only), but not the display record: a statistic computed over numbers
        // the user was never shown would disagree with the chart it summarises.
        return kotlinx.coroutines.flow.combine(
            dao.getHistoryFlow(startTime),
            displayDao.getFlow(startTime),
        ) { readings, display ->
            val indexed = display.indexedDisplay()
            val freezeEnabled = indexed.isNotEmpty() &&
                runCatching { CalibrationManager.shouldFreezeDisplayedValues() }.getOrDefault(false)
            val nowMs = System.currentTimeMillis()
            mergeQueryReadings(readings, preferredSerial).map { reading ->
                mapReadingForStats(reading, indexed, freezeEnabled, nowMs)
            }
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
                    sensorSerial = it.sensorSerial,
                    source = it.source,
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
                mapReadings(
                    mergeQueryReadings(readings, serial),
                    uncertaintyFor(serials, startTime),
                    displayRecordsFor(startTime)
                )
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
                mapReadings(
                    mergeQueryReadings(readings, serial),
                    uncertaintyFor(serials, startTime),
                    displayRecordsFor(startTime)
                )
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
                val display = displayRecordsFor(startTime)
                val nowMs = System.currentTimeMillis()
                mergeQueryReadings(readings, preferredSerial).map { reading ->
                    mapReadingForStats(reading, display, display.isNotEmpty(), nowMs)
                }
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
                    sensorSerial = it.sensorSerial,
                    source = it.source,
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
                val uncertainty = runCatching {
                    uncertaintyDao.getForSensors(
                        readings.map { it.sensorSerial }.distinct(),
                        startTime,
                    )
                }.getOrDefault(emptyList())
                mapDisplayReadings(readings, preferredSerial, uncertainty.indexed())
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
    /**
     * A first paint for the dashboard: the recent tail, merged, fetched once.
     *
     * The chart's real input is the whole timeline, and it has to be — the merge
     * needs every sensor's rows to know which one wins, and the chart reads
     * "latest" off the end of the list it is handed. Querying a window instead
     * broke both. So this does not replace the full query; it lands before it,
     * and the full one overwrites it a moment later.
     *
     * That ordering is what makes a partial list safe here. The two properties
     * that a window breaks both hold for a *recent* one: the current sensor is
     * the one producing readings now, so its rows are present and the merge
     * suppresses correctly; and the newest reading in the store is by definition
     * inside it, so "latest" is the same value the full list would give. Neither
     * survives being generalised to an arbitrary window, which is the mistake
     * this is written to avoid repeating.
     *
     * Returns null when the store's newest reading is older than the tail — an
     * expired sensor, an offline review. Then the window holds no current-sensor
     * rows, the merge has nothing to suppress with, and the only correct first
     * paint is the full one.
     */
    suspend fun getDisplayHistoryFirstPaint(
        preferredSerial: String?,
        startTime: Long,
        tailMs: Long = FIRST_PAINT_TAIL_MS
    ): List<GlucosePoint>? = withContext(Dispatchers.IO) {
        try {
            val latest = dao.getLatestReading()?.timestamp ?: return@withContext null
            val tailStart = (latest - tailMs).coerceAtLeast(startTime)
            if (tailStart <= startTime) return@withContext null

            val readings = dao.getReadingsBetween(tailStart, Long.MAX_VALUE)
            if (readings.isEmpty()) return@withContext null

            val serials = readings.mapTo(LinkedHashSet()) { it.sensorSerial }.toList()
            mapDisplayReadings(
                readings,
                preferredSerial,
                uncertaintyFor(serials, tailStart),
                displayRecordsFor(tailStart)
            )
        } catch (e: Exception) {
            Log.e(TAG, "First-paint history query failed", e)
            null
        }
    }

    fun getDisplayHistoryFlow(
        preferredSerial: String?,
        startTime: Long = 0L
    ): kotlinx.coroutines.flow.Flow<List<GlucosePoint>> {
        return kotlinx.coroutines.flow.combine(
            dao.getHistoryFlow(startTime),
            uncertaintyDao.getFlow(startTime),
            displayDao.getFlow(startTime),
        ) { readings, uncertainty, display ->
            mapDisplayReadings(
                readings,
                preferredSerial,
                uncertainty.indexed(),
                display.indexedDisplay()
            )
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

    /**
     * @param uncertainty credible intervals keyed by [uncertaintyKey]. Callers
     *   that render a chart must pass these — see [withUncertainty]. Stats,
     *   export and notification paths pass nothing on purpose: they consume the
     *   value only, and joining a second table for them would be wasted work.
     */
    private fun mapReadings(
        readings: List<HistoryReading>,
        uncertainty: Map<Long, ReadingUncertainty> = emptyMap(),
        display: Map<Long, ReadingDisplay> = emptyMap()
    ): List<GlucosePoint> {
        val nowMs = System.currentTimeMillis()
        val freezeEnabled = display.isNotEmpty() &&
            runCatching { CalibrationManager.shouldFreezeDisplayedValues() }.getOrDefault(false)
        return readings.map { reading ->
            val sealed = if (!freezeEnabled) null else sealedRecordForLine(reading, display, nowMs)
            GlucosePoint(
                value = reading.value,
                time = formatTime(reading.timestamp),
                timestamp = reading.timestamp,
                rawValue = reading.rawValue,
                rate = reading.rate,
                sensorSerial = reading.sensorSerial,
                source = reading.source,
                uncertainty = uncertainty[uncertaintyKey(reading)]?.toGlucoseUncertainty(),
                sealedDisplayValue = sealed?.displayMgdl,
                sealedDisplayViewMode = sealed?.viewMode,
            )
        }
    }

    /**
     * Recorded main values since [startTime], indexed by [displayKey].
     *
     * Not scoped to a sensor: the record describes the minute the dashboard
     * drew, whichever sensor won it. Scoping this by serial is what made a
     * two-sensor store fall back to live derivation for every minute the other
     * sensor owned.
     */
    private suspend fun displayRecordsFor(startTime: Long): Map<Long, ReadingDisplay> {
        if (!runCatching { CalibrationManager.shouldFreezeDisplayedValues() }.getOrDefault(false)) {
            return emptyMap()
        }
        return runCatching { displayDao.getAllSince(startTime).indexedDisplay() }
            .getOrDefault(emptyMap())
    }

    private fun List<ReadingDisplay>.indexedDisplay(): Map<Long, ReadingDisplay> =
        if (isEmpty()) emptyMap() else associateBy { displayKey(it.timestamp) }

    /**
     * Intervals are keyed by sensor and **minute**, not by exact timestamp.
     *
     * A reading's millisecond does not survive the native round trip — the
     * driver writes `sampleMs / 1000` and the sync reads back `sec * 1000` — so
     * matching on equality silently dropped the interval for every reading
     * whose time was not already second-aligned, which is most of them. At one
     * reading a minute the bucket is still unambiguous.
     *
     * A reading with no matching row keeps a null uncertainty, which is what
     * every pre-existing reading has.
     */
    private fun uncertaintyKey(timestamp: Long, sensorSerial: String): Long =
        (timestamp / 60_000L) * 31L + sensorSerial.hashCode()

    private fun uncertaintyKey(reading: HistoryReading): Long =
        uncertaintyKey(reading.timestamp, reading.sensorSerial)

    private fun uncertaintyKey(row: ReadingUncertainty): Long =
        uncertaintyKey(row.timestamp, row.sensorSerial)

    private fun List<ReadingUncertainty>.indexed(): Map<Long, ReadingUncertainty> =
        if (isEmpty()) emptyMap() else associateBy { uncertaintyKey(it) }

    /** Blocking uncertainty lookup for the suspend query paths. */
    private suspend fun uncertaintyFor(
        serials: List<String>,
        startTime: Long
    ): Map<Long, ReadingUncertainty> =
        runCatching { uncertaintyDao.getForSensors(serials, startTime).indexed() }
            .getOrDefault(emptyMap())

    /**
     * Drops every stored interval for a sensor.
     *
     * Called when the algorithm changes away from one that estimates
     * uncertainty: the stored bands describe values that are about to be
     * replaced, and leaving them would draw a V2 ribbon around a stock line
     * until the rebuild happens to overwrite them.
     */
    suspend fun clearUncertaintyForSensor(serial: String) {
        val serials = resolveQuerySensorSerials(serial).ifEmpty { listOf(serial) }
        runCatching { uncertaintyDao.deleteForSensors(serials) }
            .onFailure { Log.w(TAG, "clearUncertaintyForSensor failed for $serial", it) }
    }

    /**
     * @param freezeEnabled must be the same answer the chart got. This used to
     *   apply the record unconditionally while [mapReadings] gated it on the
     *   setting, so turning the freeze off un-froze the line and left the
     *   statistics summarising numbers that were no longer on screen.
     */
    private fun mapReadingForStats(
        reading: HistoryReading,
        display: Map<Long, ReadingDisplay> = emptyMap(),
        freezeEnabled: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): GlucosePoint {
        return GlucosePoint(
            value = reading.value,
            time = "",
            timestamp = reading.timestamp,
            rawValue = reading.rawValue,
            rate = reading.rate,
            sensorSerial = reading.sensorSerial,
            source = reading.source,
            sealedDisplayValue = if (!freezeEnabled) {
                null
            } else {
                display[displayKey(reading.timestamp)]
                    ?.takeIf { it.isUsable && it.isSealedAt(nowMs) }
                    ?.displayMgdl
            },
        )
    }

    private fun mapDisplayReadings(
        readings: List<HistoryReading>,
        preferredSerial: String?,
        uncertainty: Map<Long, ReadingUncertainty> = emptyMap(),
        display: Map<Long, ReadingDisplay> = emptyMap()
    ): List<GlucosePoint> {
        // The merge decides ownership from today's facts, and the recorded
        // owner is deliberately NOT applied here. Substituting it per minute
        // shattered the main line: the record is sparse, so the series
        // alternated sensors at every boundary between a recorded minute and
        // an unrecorded one, and GlucosePointSegments breaks the line on a
        // sensor change — holes, fragments, single-minute dots. Freezing who
        // owns a minute is a rendering problem that has to understand the
        // record; it cannot be done by rewriting sensor identity underneath a
        // chart that was not told.
        return mapReadings(
            HistoryDisplayMerge.mergeReadings(readings, preferredSerial),
            uncertainty,
            display
        )
    }

    private fun mergeQueryReadings(
        readings: List<HistoryReading>,
        preferredSerial: String?
    ): List<HistoryReading> {
        return HistoryDisplayMerge.mergeReadings(readings, preferredSerial)
    }

    private fun formatTime(timestamp: Long): String = formatMinute(timestamp)
    
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
            val readingSource = if (CloneSensorRegistry.isCloneSensor(roomSerial)) {
                GlucoseReadingSource.forCloneTransport(
                    CloneSensorRegistry.transportForSensor(roomSerial)
                )
            } else {
                GlucoseReadingSource.SENSOR
            }
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
                        rate = 0f, // Rate not available from history
                        source = readingSource,
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





    /** One minute of the main line, as it was actually put in front of the user. */
    data class PresentedMinute(
        val minuteMs: Long,
        val displayMgdl: Float,
        val sensorSerial: String,
        val viewMode: Int,
    )

    /**
     * Records the main value for minutes the user has actually been shown.
     *
     * Presentation is the only writer. A background pass over stored readings
     * cannot produce a record, because the decision it would have to reproduce
     * is not reproducible: [HistoryDisplayMerge] ranks sensors by how recently
     * each last read and builds coverage segments out of whatever readings exist
     * at query time, so one sensor reading again tomorrow, or one late backfill
     * row landing inside a fifteen-minute gap, changes who owns a minute from
     * last week. Replaying it later yields a plausible answer with no way to
     * tell when it is wrong. Capturing the output as it is drawn has nothing to
     * reproduce.
     *
     * Only what was **visible** may be recorded. A minute the chart queried,
     * prefetched or held off-screen was presented to nobody, and recording it
     * would be the same backdating a background pass does, triggered by a scroll
     * instead. Enforcing that is the caller's job — see [PresentedMinuteRecorder].
     *
     * A minute older than the grace window freezes on first presentation:
     * nothing was on screen for it before, so the first time it is shown is the
     * only honest answer, and it must not move afterwards. A minute still inside
     * the window is revised, because it is still settling. Which of the two
     * happens is decided by the database rather than here — see
     * [ReadingDisplayDao.reviseIfUnsealed].
     *
     * @return how many minutes were newly recorded.
     */
    suspend fun recordPresentedMinutes(entries: List<PresentedMinute>): Int {
        if (entries.isEmpty()) return 0
        if (!runCatching { CalibrationManager.shouldFreezeDisplayedValues() }.getOrDefault(false)) {
            return 0
        }
        return withContext(Dispatchers.IO) {
            try {
                val nowMs = System.currentTimeMillis()
                val sealHorizon = nowMs - ReadingDisplay.DISPLAY_SEAL_GRACE_MS
                val fingerprints = HashMap<String, Long>()
                val rows = entries.mapNotNull { entry ->
                    if (!entry.displayMgdl.isFinite() || entry.displayMgdl <= 0f) return@mapNotNull null
                    val serial = entry.sensorSerial.trim().takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    ReadingDisplay(
                        timestamp = ReadingDisplay.minuteOf(entry.minuteMs),
                        sensorSerial = serial,
                        displayMgdl = entry.displayMgdl,
                        viewMode = entry.viewMode,
                        calibrationFingerprint = fingerprints.getOrPut(serial) {
                            runCatching {
                                CalibrationManager.getIntegratedCalibrationFingerprint(
                                    serial,
                                    entry.viewMode == 1 || entry.viewMode == 3
                                )
                            }.getOrDefault(0L)
                        },
                        recordedAt = nowMs,
                    )
                }
                if (rows.isEmpty()) return@withContext 0

                var inserted = 0
                rows.chunked(NATIVE_BACKFILL_INSERT_CHUNK).forEach { chunk ->
                    inserted += displayDao.sealAll(chunk).count { it >= 0L }
                }
                // Anything that already had a record and is still settling takes
                // the newer number. The database refuses the rest.
                rows.forEach { row ->
                    if (row.timestamp > sealHorizon) {
                        runCatching {
                            displayDao.reviseIfUnsealed(
                                timestamp = row.timestamp,
                                sensorSerial = row.sensorSerial,
                                displayMgdl = row.displayMgdl,
                                viewMode = row.viewMode,
                                calibrationFingerprint = row.calibrationFingerprint,
                                recordedAt = row.recordedAt,
                                sealHorizon = sealHorizon,
                            )
                        }
                    }
                }
                // No data refresh here. Recording a presentation changes nothing
                // that is displayed — the value written is the value already on
                // screen — and requesting one after every newly presented minute
                // reloaded the whole history each time the chart drew a new
                // minute, which rebuilt the chart, which presented, which
                // recorded. That loop is what a 200MB GC per cycle looked like.
                // Ownership catches up with the record on the next peer emission.
                // tk.glucodata.Log, not android.util.Log: only the former reaches
                // the trace ring the exported log is built from. A 20k-line trace
                // of a run where this had supposedly executed contained nothing
                // from this class, because nothing here could be seen.
                tk.glucodata.Log.i(
                    TAG,
                    "presented ${entries.size} minutes: recorded $inserted new, " +
                        "${rows.size - inserted} already held, horizon=$sealHorizon"
                )
                inserted
            } catch (e: Exception) {
                tk.glucodata.Log.e(TAG, "Failed recording presented minutes: ${e.message}")
                Log.e(TAG, "Failed recording presented minutes", e)
                0
            }
        }
    }

    /**
     * The main-sensor ownership for every minute since [startTime].
     *
     * Only sealed records carry an opinion; everywhere else the predicate says
     * nothing and the live merge stands. See [MainSensorOwnership].
     */
    suspend fun mainSensorOwnership(
        startTime: Long,
        nowMs: Long = System.currentTimeMillis()
    ): MainSensorOwnership {
        if (!runCatching { CalibrationManager.shouldFreezeDisplayedValues() }.getOrDefault(false)) {
            return MainSensorOwnership.NONE
        }
        val recorded = withContext(Dispatchers.IO) {
            runCatching {
                displayDao.mainLineOwners(startTime).associate { it.timestamp to it.sensorSerial }
            }.getOrDefault(emptyMap())
        }
        return MainSensorOwnership(recorded, nowMs)
    }

    /**
     * The recorded value for [reading], if the record is about this reading.
     *
     * A record says "sensor X showed V at minute M". V belongs on X's line and
     * nowhere else. Attached by minute alone it landed on whichever sensor's
     * reading the merge produced for that minute — so after a swap, the new
     * main's line snapped to the old main's recorded numbers wherever the
     * calibrated lane consulted the record, with a cliff where the records
     * stopped. Statistics are different: they want the main value shown at the
     * minute whoever showed it, and take the record without this check.
     */
    private fun sealedRecordForLine(
        reading: HistoryReading,
        display: Map<Long, ReadingDisplay>,
        nowMs: Long
    ): ReadingDisplay? {
        val record = display[displayKey(reading.timestamp)] ?: return null
        if (!record.isUsable || !record.isSealedAt(nowMs)) return null
        if (!SensorIdentity.matches(reading.sensorSerial, record.sensorSerial)) return null
        return record
    }

    /**
     * The recorded main value's key: the minute, and nothing else.
     *
     * It used to mix the sensor in — `(minute * 31) + sensorSerial.hashCode()`
     * — which made the record per-sensor and therefore unable to say which
     * sensor the dashboard actually showed. See [ReadingDisplay].
     */
    private fun displayKey(timestamp: Long): Long = ReadingDisplay.minuteOf(timestamp)

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
