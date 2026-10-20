package tk.glucodata

import java.util.TreeSet

/**
 * Remembers what the xDrip broadcast has already carried to AAPS, and picks the readings of a
 * history batch that fell into a gap and still need to go.
 *
 * The live path sends only the newest reading, so after a disconnect the sensor's backfill lands in
 * the database but never reaches AAPS. xDrip sends every missed value under its own timestamp; this
 * follows its rules:
 *
 * - A reading counts as already delivered when something was sent within 5/6 of the sensor's sample
 *   period of it (xDrip's `DexCollectionType.getCurrentDeduplicationPeriod`). Sensors here sample
 *   every 1 to 5 minutes, and nothing says which, so the period is measured from the history itself.
 * - The caller hands batches over a little after they are stored, so the live reading of the same
 *   moment has gone out first (xDrip processes backfill the same way, after a delay).
 * - A reading that reached the phone on time was the live reading of its moment and went out live,
 *   whichever build was running then. Only readings that arrived late are a gap. The record of
 *   what was sent cannot tell that on its own: a build that does not keep it (an older release
 *   installed over a nightly) sends live without a trace, and the next build re-sent a whole day of
 *   delivered readings, each under a second from its live copy (#686).
 *
 * What was sent survives a restart as [Coverage]: the floor below which nothing is backfilled, plus
 * the stretches that were sent without a gap. A gap that was still open when the app stopped is not
 * covered, so it is filled once its history arrives, and what was already sent is not sent again.
 *
 * A sensor is only known once a live send has been recorded (or coverage restored), so non-main
 * sensors, which never live-send, are never backfilled, and enabling the broadcast does not dump the
 * existing history.
 */
class XdripBackfillTracker(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    /** [storedAtMs]: when the reading first reached the phone; 0 when unknown, taken as late. */
    data class Candidate(val timeMs: Long, val mgdl: Double, val rate: Float, val storedAtMs: Long = 0L) {
        val arrivedLive: Boolean
            get() = storedAtMs > 0L && storedAtMs - timeMs <= LIVE_ARRIVAL_MS
    }

    data class Sensor(val startWatermarkMs: Long, val sensorGen: Int, val sensorStartMs: Long)

    /** What a sensor has had sent, in a form that can be persisted. */
    data class Coverage(
        val floorMs: Long,
        val sensorGen: Int,
        val sensorStartMs: Long,
        val ranges: List<LongRange>,
    )

    private class State(var startWatermarkMs: Long, var sensorGen: Int, var sensorStartMs: Long) {
        val sent = TreeSet<Long>()
        var restored: List<LongRange> = emptyList()
        var periodMs = 0L
        var spacingMs = 0L
    }

    private val states = HashMap<String, State>()

    /** Restore a sensor from persisted coverage. Readings at or before its floor are never backfilled. */
    @Synchronized
    fun restore(sensorId: String, coverage: Coverage) {
        if (coverage.floorMs <= 0L || states.containsKey(sensorId)) return
        states[sensorId] = State(coverage.floorMs, coverage.sensorGen, coverage.sensorStartMs).also {
            it.restored = coverage.ranges.filter { range -> range.last > coverage.floorMs }
        }
    }

    /** A live reading went out. The first one of an unknown sensor fixes where backfill may start. */
    @Synchronized
    fun recordSent(sensorId: String, timeMs: Long, sensorGen: Int, sensorStartMs: Long) {
        if (timeMs <= 0L) return
        val state = states.getOrPut(sensorId) { State(timeMs, sensorGen, sensorStartMs) }
        state.sensorGen = sensorGen
        if (sensorStartMs > 0L) state.sensorStartMs = sensorStartMs
        state.sent.add(timeMs)
        prune(state, timeMs)
    }

    @Synchronized
    fun sensor(sensorId: String): Sensor? =
        states[sensorId]?.let { Sensor(it.startWatermarkMs, it.sensorGen, it.sensorStartMs) }

    /**
     * The stretches sent without a gap, to persist. Two sends belong to one stretch when they are no
     * further apart than one and a half sample periods (or collapse intervals), so a reconnect gap
     * that has not been backfilled yet stays outside every stretch.
     */
    @Synchronized
    fun coverage(sensorId: String, nowMs: Long): Coverage? {
        val state = states[sensorId] ?: return null
        val joinMs = maxOf(period(state), state.spacingMs) * 3 / 2
        val times = TreeSet<Long>(state.sent)
        val ranges = ArrayList<LongRange>()
        state.restored.forEach { ranges.add(it) }
        var start = -1L
        var end = -1L
        for (time in times) {
            if (start < 0L) {
                start = time
            } else if (time - end > joinMs) {
                ranges.add(start..end)
                start = time
            }
            end = time
        }
        if (start >= 0L) ranges.add(start..end)
        val floor = maxOf(state.startWatermarkMs, nowMs - windowMs)
        return Coverage(floor, state.sensorGen, state.sensorStartMs, merge(ranges, joinMs).filter { it.last > floor })
    }

    /**
     * The readings of [batch] to send, oldest first. A reading is skipped when it arrived live, is
     * outside the window or in the future, is at or before the sensor's start watermark, inside a
     * stretch restored as already sent, or within the deduplication distance of something sent:
     * 5/6 of the sample period, or [spacingMs] (the collapse interval) when that is longer. The ones
     * returned are recorded as sent.
     */
    @Synchronized
    fun pick(
        sensorId: String,
        batch: List<Candidate>,
        nowMs: Long,
        spacingMs: Long = 0L,
    ): List<Candidate> {
        val state = states[sensorId] ?: return emptyList()
        val sorted = batch.sortedBy(Candidate::timeMs)
        measurePeriod(sorted)?.let { state.periodMs = it }
        state.spacingMs = spacingMs
        val period = period(state)
        val dedupMs = maxOf(period - period / 6, spacingMs)
        val lowerBound = maxOf(nowMs - windowMs, state.startWatermarkMs)
        // An on-time row went out live, so it also covers a late row of the same moment: Libre 3's
        // 5-minute history reaches the phone about 19 minutes after the minute reading it sits next to.
        for (candidate in sorted) {
            if (candidate.arrivedLive && candidate.timeMs > lowerBound && candidate.timeMs <= nowMs) {
                state.sent.add(candidate.timeMs)
            }
        }
        val picked = ArrayList<Candidate>()
        for (candidate in sorted) {
            if (candidate.timeMs <= lowerBound || candidate.timeMs > nowMs) continue
            if (!(candidate.mgdl.isFinite() && candidate.mgdl > 0.0)) continue
            if (candidate.arrivedLive) continue
            if (isRestoredAsSent(state.restored, candidate.timeMs, dedupMs)) continue
            if (hasSentWithin(state.sent, candidate.timeMs, dedupMs)) continue
            state.sent.add(candidate.timeMs)
            picked.add(candidate)
        }
        return picked
    }

    private fun period(state: State): Long = if (state.periodMs > 0L) state.periodMs else DEFAULT_PERIOD_MS

    /** Median spacing of consecutive readings, clamped to the 1-5 minute range sensors use. */
    private fun measurePeriod(sorted: List<Candidate>): Long? {
        val gaps = sorted.zipWithNext { a, b -> b.timeMs - a.timeMs }
            .filter { it in MIN_PERIOD_MS / 2..MAX_PERIOD_MS * 2 }
            .sorted()
        if (gaps.isEmpty()) return null
        return gaps[gaps.size / 2].coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
    }

    private fun isRestoredAsSent(ranges: List<LongRange>, timeMs: Long, distanceMs: Long): Boolean =
        ranges.any { timeMs > it.first - distanceMs && timeMs < it.last + distanceMs }

    private fun hasSentWithin(sent: TreeSet<Long>, timeMs: Long, distanceMs: Long): Boolean {
        val before = sent.floor(timeMs)
        if (before != null && timeMs - before < distanceMs) return true
        val after = sent.ceiling(timeMs)
        return after != null && after - timeMs < distanceMs
    }

    private fun merge(ranges: List<LongRange>, joinMs: Long): List<LongRange> {
        val merged = ArrayList<LongRange>()
        for (range in ranges.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first - last.last <= joinMs) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    private fun prune(state: State, newestMs: Long) {
        val cutoff = newestMs - windowMs
        while (state.sent.isNotEmpty() && state.sent.first() < cutoff) state.sent.pollFirst()
        state.restored = state.restored.filter { it.last >= cutoff }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1000L
        const val MIN_PERIOD_MS = 60_000L
        const val MAX_PERIOD_MS = 5L * 60_000L
        const val DEFAULT_PERIOD_MS = MIN_PERIOD_MS

        /**
         * A reading stored no later than this after its own time arrived live. Generous, because a
         * driver may file its live reading under the sensor's history time up to a minute earlier;
         * a gap row this fresh is the one next to the reconnect reading and deduplicated by it anyway.
         */
        const val LIVE_ARRIVAL_MS = 2L * 60_000L
    }
}
