package tk.glucodata.data

import kotlin.math.abs

/**
 * A driver may replace its derived history after an algorithm/calibration edit.
 * Identify records whose recorded lane's source actually changed. The caller
 * must also respect the display lock: changed source does not make a locked
 * presentation invalid while keeping displayed values is enabled.
 *
 * The displayed number is not that source: it can include graph smoothing or
 * software calibration. Comparing the record against the new sensor value
 * invalidated smoothed records even on an unchanged replay, and using that
 * comparison while reading accepted isolated equal-valued minutes as spikes.
 * A read cannot prove a rewrite; only the transaction holding before/after rows can.
 */
object RecordedDisplayVoiding {
    const val TOLERANCE_MGDL = 0.01f

    fun changedMinutes(
        records: List<ReadingDisplay>,
        sensorSerial: String,
        previous: List<HistoryReading>,
        rewritten: List<HistoryReading>,
        autoLaneRewritten: Boolean,
        rawLaneRewritten: Boolean,
    ): List<Long> {
        if (records.isEmpty() || previous.isEmpty() || rewritten.isEmpty()) return emptyList()
        if (!autoLaneRewritten && !rawLaneRewritten) return emptyList()
        fun index(readings: List<HistoryReading>): Map<Long, HistoryReading> =
            HistoryBucketReplacement.collapseReadings(
                readings.filter { tk.glucodata.SensorIdentity.matches(it.sensorSerial, sensorSerial) },
                ReadingDisplay.MINUTE_MS,
            ).associateBy { ReadingDisplay.minuteOf(it.timestamp) }
        val before = index(previous)
        val after = index(rewritten)
        return records.mapNotNull { record ->
            if (!tk.glucodata.SensorIdentity.matches(record.sensorSerial, sensorSerial)) return@mapNotNull null
            val raw = record.viewMode == 1 || record.viewMode == 3
            if (if (raw) !rawLaneRewritten else !autoLaneRewritten) return@mapNotNull null
            // Gaps outside the replacement buckets remain in Room; a first
            // insertion with no old source is not proof that a record is stale.
            val old = before[record.timestamp] ?: return@mapNotNull null
            val new = after[record.timestamp] ?: return@mapNotNull null
            val oldValue = if (raw) old.rawValue else old.value
            val newValue = if (raw) new.rawValue else new.value
            record.timestamp.takeIf {
                !oldValue.isFinite() || !newValue.isFinite() || abs(oldValue - newValue) > TOLERANCE_MGDL
            }
        }
    }
}
