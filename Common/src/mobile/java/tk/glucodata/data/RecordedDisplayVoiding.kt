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

    /**
     * Auto-lane records drawn from a Libre 1/2 history row that a native re-sync
     * has since taken off the Auto line.
     *
     * Libre's own 15-minute history used to be exported into the same Auto
     * lane as its stream, so a minute could be presented -- and recorded -- with
     * the history value. It now has its own lane: the minute's Auto value is
     * the stream reading, or nothing where the stream covers it. A record left
     * holding the history number would keep drawing it on the Stream line.
     *
     * Voided only where the stored row the record came from was a history row
     * ([historyTimestamps]) and the minute's re-synced row is a different
     * reading or carries no Auto value. A calibration change, a scan or any
     * other rewrite leaves the record alone.
     */
    fun displacedHistoryMinutes(
        records: List<ReadingDisplay>,
        sensorSerial: String,
        rewritten: List<HistoryReading>,
        previousByMinute: Map<Long, HistoryReading>,
        historyTimestamps: Set<Long>,
    ): List<Long> {
        if (records.isEmpty() || rewritten.isEmpty() || historyTimestamps.isEmpty()) return emptyList()
        val byMinute = HashMap<Long, HistoryReading>(rewritten.size)
        for (reading in rewritten) {
            if (!tk.glucodata.SensorIdentity.matches(reading.sensorSerial, sensorSerial)) continue
            byMinute[ReadingDisplay.minuteOf(reading.timestamp)] = reading
        }
        val voided = ArrayList<Long>()
        for (record in records) {
            if (record.viewMode == 1 || record.viewMode == 3) continue
            if (!tk.glucodata.SensorIdentity.matches(record.sensorSerial, sensorSerial)) continue
            val before = previousByMinute[record.timestamp] ?: continue
            if (before.timestamp !in historyTimestamps) continue
            val after = byMinute[record.timestamp] ?: continue
            val autoGone = !after.value.isFinite() || after.value <= 0f
            if (after.timestamp != before.timestamp || autoGone) voided.add(record.timestamp)
        }
        return voided
    }

}
