package tk.glucodata.data

import kotlin.math.abs

/**
 * Which recorded main values a driver's own rewrite of its history has made void.
 *
 * A [ReadingDisplay] row freezes the number the user was shown so that a later
 * change of *calibration settings* cannot move a minute already on screen. It
 * is not a defence against the sensor itself. A driver that integrates the
 * user's calibration into its own algorithm — Sibionics — answers a fingerstick
 * by replaying its DSP over the whole session and replacing every
 * `history_readings` row it owns. Those rows are the sensor's number, the thing
 * the record was drawn from; once they change, a record holding the old number
 * no longer describes anything the sensor ever said. Left in place, it pinned
 * every minute past the grace window to the pre-calibration line, so the
 * calibration appeared to do nothing.
 *
 * So the record is voided — deleted, never rewritten — exactly where the
 * sensor's number for that minute, in the lane the record came from, is no
 * longer what was recorded. A minute whose number did not change keeps its
 * record, and with it the decision about which sensor owned the minute. The
 * next presentation records the new number the way it records any minute seen
 * for the first time.
 *
 * Pure so it can be pinned by tests. The caller decides, per lane, whether the
 * driver integrates calibration at all — a lane the app's own calibration is
 * applied to holds a record that legitimately differs from the stored number,
 * and must not be voided by this.
 */
object RecordedDisplayVoiding {
    /** Below this a rebuilt value is the recorded one; DSP replays are bit-stable. */
    const val TOLERANCE_MGDL = 0.01f

    fun minutesToVoid(
        records: List<ReadingDisplay>,
        sensorSerial: String,
        rewritten: List<HistoryReading>,
        autoLaneIntegrated: Boolean,
        rawLaneIntegrated: Boolean,
    ): List<Long> {
        if (records.isEmpty() || rewritten.isEmpty()) return emptyList()
        if (!autoLaneIntegrated && !rawLaneIntegrated) return emptyList()
        // The last reading in a minute is the one a bucket replace keeps.
        val byMinute = HashMap<Long, HistoryReading>(rewritten.size)
        for (reading in rewritten) {
            if (!tk.glucodata.SensorIdentity.matches(reading.sensorSerial, sensorSerial)) continue
            byMinute[ReadingDisplay.minuteOf(reading.timestamp)] = reading
        }
        if (byMinute.isEmpty()) return emptyList()
        val first = byMinute.keys.min()
        val last = byMinute.keys.max()
        val voided = ArrayList<Long>()
        for (record in records) {
            if (record.timestamp < first || record.timestamp > last) continue
            if (!tk.glucodata.SensorIdentity.matches(record.sensorSerial, sensorSerial)) continue
            if (!recordStandsFor(record, byMinute[record.timestamp], autoLaneIntegrated, rawLaneIntegrated)) {
                voided.add(record.timestamp)
            }
        }
        return voided
    }

    /**
     * Whether [record] still describes the number [reading] holds in the
     * record's lane — the one question both the rebuild-time voiding and the
     * read-time check ask, so they cannot answer it differently.
     *
     * True whenever the lane is not integrated: the record then holds the
     * app's own calibration of the number and is allowed to differ from it.
     * On an integrated lane the record can only ever equal the stored number
     * or be stale, so a missing reading or a different number is stale.
     */
    fun recordStandsFor(
        record: ReadingDisplay,
        reading: HistoryReading?,
        autoLaneIntegrated: Boolean,
        rawLaneIntegrated: Boolean,
    ): Boolean {
        val rawLane = record.viewMode == 1 || record.viewMode == 3
        if (if (rawLane) !rawLaneIntegrated else !autoLaneIntegrated) return true
        val current = when {
            reading == null -> Float.NaN
            rawLane -> reading.rawValue
            else -> reading.value
        }
        return current.isFinite() && current > 0f && abs(current - record.displayMgdl) <= TOLERANCE_MGDL
    }
}
