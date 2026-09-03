package tk.glucodata.drivers

import java.util.Locale
import tk.glucodata.Applic
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.HistorySyncAccess
import tk.glucodata.Log
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus

/**
 * Shared bridge for cloud/virtual CGM sources that do not own a BLE packet stream.
 * Values are stored in Room as mg/dL and published through the same live path as
 * managed BLE sensors, so dashboard, notification history, widgets, and alerts can
 * consume them without source-specific UI code.
 */
object VirtualGlucoseSensorBridge {
    private const val TAG = "VirtualGlucose"
    private const val MMOL_TO_MGDL = 18.0182f
    private const val MIN_REASONABLE_TIMESTAMP_MS = 946_684_800_000L
    private const val MAX_FUTURE_TIMESTAMP_DRIFT_MS = 10L * 60L * 1000L

    data class Reading(
        val timestampMs: Long,
        val glucoseMgdl: Float,
        val autoMgdl: Float = Float.NaN,
        val calibratedMgdl: Float = Float.NaN,
        val rawMgdl: Float = Float.NaN,
        val rate: Float = Float.NaN,
    ) {
        val storageGlucoseMgdl: Float
            get() = autoMgdl.takeIf { it.isFinite() && it > 0f } ?: glucoseMgdl

        val primaryGlucoseMgdl: Float
            get() = calibratedMgdl.takeIf { it.isFinite() && it > 0f } ?: glucoseMgdl
    }

    @JvmStatic
    fun importHistory(
        sensorSerial: String,
        readings: List<Reading>,
        logLabel: String = "virtual",
        backfill: Boolean = true,
        nearDuplicateWindowMs: Long = 0L,
        source: String = GlucoseReadingSource.SENSOR,
    ): Int {
        if (sensorSerial.isBlank() || readings.isEmpty()) return 0
        val nowMs = System.currentTimeMillis()
        val validReadings = readings.filter { isUsableHistoryReading(it, nowMs) }
        val skippedInvalid = readings.size - validReadings.size
        if (skippedInvalid > 0) {
            Log.w(TAG, "Skipped $skippedInvalid invalid $logLabel history points for $sensorSerial")
        }
        if (validReadings.isEmpty()) return 0
        val latestRoomTimestamp = if (backfill) 0L else HistorySyncAccess.getLatestTimestampForSensor(sensorSerial)
        val existingTimestamps = if (nearDuplicateWindowMs > 0L) {
            val minTimestamp = validReadings.minOf { it.timestampMs }
            val maxTimestamp = validReadings.maxOf { it.timestampMs }
            HistorySyncAccess.getHistoryTimestampsForSensor(
                sensorSerial = sensorSerial,
                startTime = (minTimestamp - nearDuplicateWindowMs).coerceAtLeast(1L),
                endTime = maxTimestamp + nearDuplicateWindowMs,
            ).sortedArray()
        } else {
            LongArray(0)
        }
        val deduped = LinkedHashMap<Long, Reading>()
        validReadings
            .asSequence()
            .filter { it.timestampMs > latestRoomTimestamp }
            .filterNot {
                existingTimestamps.isNotEmpty() &&
                    hasNearbyTimestamp(existingTimestamps, it.timestampMs, nearDuplicateWindowMs)
            }
            .forEach { deduped[it.timestampMs] = it }
        val skippedAsNearDuplicate = validReadings.size - deduped.size
        if (skippedAsNearDuplicate > 0 && nearDuplicateWindowMs > 0L) {
            Log.i(
                TAG,
                "Skipped $skippedAsNearDuplicate $logLabel history points near existing $sensorSerial readings"
            )
        }
        if (deduped.isEmpty()) return 0

        val ordered = deduped.values.sortedBy { it.timestampMs }
        val timestamps = LongArray(ordered.size)
        val values = FloatArray(ordered.size)
        val rawValues = FloatArray(ordered.size)
        ordered.forEachIndexed { index, reading ->
            timestamps[index] = reading.timestampMs
            values[index] = reading.storageGlucoseMgdl
            rawValues[index] = reading.rawMgdl.takeIf { it.isFinite() && it > 0f } ?: Float.NaN
        }
        if (!HistorySyncAccess.storeSensorHistoryBatchWithSourceBlocking(
                sensorSerial,
                timestamps,
                values,
                rawValues,
                source,
            )
        ) {
            return 0
        }
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "Imported %d %s history points for %s",
                ordered.size,
                logLabel,
                sensorSerial,
            ),
        )
        return ordered.size
    }

    @JvmStatic
    fun pruneFutureHistory(
        sensorSerial: String,
        logLabel: String = "virtual",
    ): Int {
        if (sensorSerial.isBlank()) return 0
        val cutoff = System.currentTimeMillis() + MAX_FUTURE_TIMESTAMP_DRIFT_MS
        val removed = HistorySyncAccess.deleteReadingsForSensorAfter(sensorSerial, cutoff)
        if (removed > 0) {
            Log.w(TAG, "Removed $removed future $logLabel history rows for $sensorSerial after $cutoff")
        }
        return removed
    }

    private fun hasNearbyTimestamp(
        sortedTimestamps: LongArray,
        timestampMs: Long,
        windowMs: Long,
    ): Boolean {
        if (sortedTimestamps.isEmpty() || windowMs <= 0L) return false
        var low = 0
        var high = sortedTimestamps.size - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val candidate = sortedTimestamps[mid]
            when {
                candidate < timestampMs -> low = mid + 1
                candidate > timestampMs -> high = mid - 1
                else -> return true
            }
        }
        if (low < sortedTimestamps.size && kotlin.math.abs(sortedTimestamps[low] - timestampMs) <= windowMs) {
            return true
        }
        if (low > 0 && kotlin.math.abs(sortedTimestamps[low - 1] - timestampMs) <= windowMs) {
            return true
        }
        return false
    }

    @JvmStatic
    fun publishCurrent(
        sensorSerial: String,
        reading: Reading,
        sensorGen: Int,
        logLabel: String = "virtual",
        source: String = GlucoseReadingSource.SENSOR,
    ) {
        if (sensorSerial.isBlank()) return
        if (!isUsableCurrentReading(reading, System.currentTimeMillis())) {
            Log.w(TAG, "Ignored invalid $logLabel current for $sensorSerial at ${reading.timestampMs}")
            return
        }

        val rawMgdl = reading.rawMgdl.takeIf { it.isFinite() && it > 0f } ?: 0f
        val rate = reading.rate.takeIf { it.isFinite() } ?: 0f
        HistorySyncAccess.storeCurrentReadingWithSourceAsync(
            reading.timestampMs,
            reading.storageGlucoseMgdl,
            rawMgdl,
            rate,
            sensorSerial,
            source,
        )

        val primaryMgdl = reading.primaryGlucoseMgdl
        val glucoseDisplay = if (Applic.unit == 1) {
            primaryMgdl / MMOL_TO_MGDL
        } else {
            primaryMgdl
        }
        SuperGattCallback.processExternalCurrentReading(
            sensorSerial,
            glucoseDisplay,
            rate,
            reading.timestampMs,
            sensorGen,
        )
        Log.d(
            TAG,
            String.format(
                Locale.US,
                "Published %s current for %s: %.1f mg/dL at %d",
                logLabel,
                sensorSerial,
                primaryMgdl,
                reading.timestampMs,
            ),
        )
        UiRefreshBus.requestDataRefresh()
    }

    private fun isUsableCurrentReading(reading: Reading, nowMs: Long): Boolean =
        isPlausibleTimestamp(reading.timestampMs, nowMs) &&
            reading.storageGlucoseMgdl.isFinite() &&
            reading.storageGlucoseMgdl > 0f

    private fun isUsableHistoryReading(reading: Reading, nowMs: Long): Boolean =
        isPlausibleTimestamp(reading.timestampMs, nowMs) &&
            (
                (reading.storageGlucoseMgdl.isFinite() && reading.storageGlucoseMgdl > 0f) ||
                    (reading.rawMgdl.isFinite() && reading.rawMgdl > 0f)
            )

    private fun isPlausibleTimestamp(timestampMs: Long, nowMs: Long): Boolean =
        timestampMs in MIN_REASONABLE_TIMESTAMP_MS..(nowMs + MAX_FUTURE_TIMESTAMP_DRIFT_MS)
}
