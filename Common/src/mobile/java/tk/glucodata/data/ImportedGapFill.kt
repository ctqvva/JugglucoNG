package tk.glucodata.data

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Which imported rows may fill holes in a native sensor's stream.
 *
 * The durable reconcile used to ask "does this minute have a native row", which
 * is wrong for any sensor that does not sample every minute: on a five-minute
 * sensor four of every five minutes read as empty, so every imported row inside
 * a normal gap was retagged onto the native series. The chart draws retagged
 * rows as ordinary vertices, so one normal gap became four extra vertices — the
 * "ghost"/"jumps" shape reported after 1.2.0.
 *
 * The question the writer should ask is "is this a genuine missing native
 * reading". One native row covers one cadence step, so a gap no wider than the
 * cadence is normal coverage and gets no filler; a wider gap has `gap/cadence - 1`
 * missing slots and gets one filler per slot, the candidate nearest each
 * expected slot time. This is the same notion the display already assumes when
 * it refuses to break the line across a sub-17-minute gap.
 */
internal object ImportedGapFill {
    /** Below a minute the "cadence" is noise, not a sampling step. */
    private const val MIN_CADENCE_MS = 60_000L

    /**
     * The sensor's own sampling step, taken as the median gap so occasional
     * missed readings do not move it. Null when there are too few native rows to
     * tell, in which case the caller keeps its previous behaviour.
     */
    fun inferCadenceMs(nativeTimestampsAscending: List<Long>): Long? {
        if (nativeTimestampsAscending.size < 3) return null
        val gaps = ArrayList<Long>(nativeTimestampsAscending.size - 1)
        for (i in 1 until nativeTimestampsAscending.size) {
            val gap = nativeTimestampsAscending[i] - nativeTimestampsAscending[i - 1]
            if (gap > 0L) gaps.add(gap)
        }
        if (gaps.size < 2) return null
        gaps.sort()
        return gaps[gaps.size / 2].takeIf { it >= MIN_CADENCE_MS }
    }

    /**
     * Timestamps of the candidate imported rows to retag onto the native series.
     *
     * [nativeTimestampsAscending] and [candidateTimestampsAscending] must be
     * ascending; a candidate already inside a native minute is the caller's to
     * exclude, not this function's.
     */
    fun selectFillerTimestamps(
        nativeTimestampsAscending: List<Long>,
        candidateTimestampsAscending: List<Long>,
        cadenceMs: Long,
    ): Set<Long> {
        if (candidateTimestampsAscending.isEmpty() || nativeTimestampsAscending.size < 2 || cadenceMs <= 0L) {
            return emptySet()
        }
        val kept = HashSet<Long>()
        var cursor = 0
        for (i in 1 until nativeTimestampsAscending.size) {
            val previous = nativeTimestampsAscending[i - 1]
            val next = nativeTimestampsAscending[i]
            while (cursor < candidateTimestampsAscending.size && candidateTimestampsAscending[cursor] <= previous) {
                cursor++
            }
            val start = cursor
            while (cursor < candidateTimestampsAscending.size && candidateTimestampsAscending[cursor] < next) {
                cursor++
            }
            val end = cursor
            if (start >= end) continue
            val missing = ((next - previous).toDouble() / cadenceMs).roundToLong() - 1L
            if (missing <= 0L) continue
            for (slotIndex in 1..missing) {
                val slot = previous + slotIndex * cadenceMs
                if (slot >= next) break
                var best = -1
                var bestDistance = Long.MAX_VALUE
                for (j in start until end) {
                    val distance = abs(candidateTimestampsAscending[j] - slot)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = j
                    }
                }
                if (best >= 0) kept.add(candidateTimestampsAscending[best])
            }
        }
        return kept
    }
}
