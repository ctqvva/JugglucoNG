package tk.glucodata.drivers.sibionics

import kotlin.math.abs
import tk.glucodata.drivers.sibionics.SibionicsGs3Protocol.RECORD_INTERVAL_MS

internal data class SibionicsGs3Reading(
    val index: Int,
    val sampleMs: Long,
    val glucoseMgdl: Float,
    val trend: SibionicsProtocol.Trend,
)

internal object SibionicsGs3ReadingTime {
    private const val ANCHOR_TOLERANCE_MS = 3 * RECORD_INTERVAL_MS

    fun restoreLastLiveIndex(savedIndex: Int, startTimeMs: Long, lastReadingTimeMs: Long): Int {
        if (savedIndex >= 0) return savedIndex
        if (startTimeMs <= 0 || lastReadingTimeMs < startTimeMs) return -1
        // Early GS3 builds persisted the live reading's timestamp and the index-zero time.
        return ((lastReadingTimeMs - startTimeMs) / RECORD_INTERVAL_MS).toInt()
    }

    fun isNewLiveRecord(record: SibionicsGs3Protocol.Record, lastLiveIndex: Int): Boolean =
        record.ageRecords == 0 && record.index > lastLiveIndex

    fun anchor(
        previous: Long,
        reference: SibionicsGs3Protocol.Record,
        lastLiveIndex: Int,
        now: Long,
    ): Long {
        val candidate = now - (reference.ageRecords.toLong() + reference.index) * RECORD_INTERVAL_MS
        if (previous <= 0) return candidate
        if (isNewLiveRecord(reference, lastLiveIndex) && abs(candidate - previous) > ANCHOR_TOLERANCE_MS) {
            return candidate
        }
        return previous
    }
}
