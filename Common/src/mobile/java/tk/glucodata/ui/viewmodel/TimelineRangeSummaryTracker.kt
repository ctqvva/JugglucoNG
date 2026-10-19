package tk.glucodata.ui.viewmodel

import tk.glucodata.data.TimelineRangeSummary

/**
 * Keeps a range's [TimelineRangeSummary] current without re-counting the
 * whole range every minute.
 *
 * A merged window is exactly the whole merge restricted to it, so a range can
 * be counted in parts. The tracker keeps a *head*: the summary of the range up
 * to a cut an hour behind the newest reading, which an append cannot change.
 * Each round it plans the stretches to count — whatever grows the head to its
 * new cut, and the tail after it — and folds them in. A rewrite of the store,
 * signalled by the index rebuild revision, throws the head away. So the range
 * selector's "N readings" over a year of data costs the year once and about
 * an hour a minute.
 */
class TimelineRangeSummaryTracker(val startMs: Long, val endMs: Long) {
    /** What to count this round; the first [headStretches] stretches join the head, the rest are the tail. */
    data class Plan(val stretches: List<LongRange>, val headStretches: Int, val newCutMs: Long?)

    private var head: TimelineRangeSummary? = null
    private var headCutMs: Long? = null
    private var headRevision = -1L

    fun plan(latestMs: Long, rewriteRevision: Long): Plan {
        val newCut = minOf(endMs, latestMs - HEAD_SETTLE_MS)
        val cut = headCutMs
        val headValid = cut != null && rewriteRevision == headRevision && cut <= newCut
        if (!headValid) {
            head = null
            headCutMs = null
            return if (newCut >= startMs) {
                Plan(listOfNotNull(startMs..newCut, tailAfter(newCut)), headStretches = 1, newCutMs = newCut)
            } else {
                Plan(listOf(startMs..endMs), headStretches = 0, newCutMs = null)
            }
        }
        val grow = if (newCut > cut!!) (cut + 1)..newCut else null
        return Plan(listOfNotNull(grow, tailAfter(newCut)), headStretches = if (grow != null) 1 else 0, newCutMs = newCut)
    }

    /** Folds the stretches counted for [plan] in; [counted] is index-aligned with `plan.stretches`. */
    fun apply(plan: Plan, counted: List<TimelineRangeSummary?>, rewriteRevision: Long): TimelineRangeSummary? {
        require(counted.size == plan.stretches.size) { "counted ${counted.size} of ${plan.stretches.size} stretches" }
        if (plan.newCutMs != null) {
            head = fold(listOf(head) + counted.take(plan.headStretches))
            headCutMs = plan.newCutMs
            headRevision = rewriteRevision
        }
        return fold(listOf(head) + counted.drop(plan.headStretches))
    }

    private fun tailAfter(cut: Long): LongRange? = if (cut < endMs) (cut + 1)..endMs else null

    private fun fold(parts: List<TimelineRangeSummary?>): TimelineRangeSummary? {
        val present = parts.filterNotNull()
        if (present.isEmpty()) return null
        return TimelineRangeSummary(
            readingCount = present.sumOf { it.readingCount },
            earliestMs = present.minOf { it.earliestMs },
            latestMs = present.maxOf { it.latestMs },
        )
    }

    companion object {
        /** How far behind the newest reading a count is taken as settled. */
        const val HEAD_SETTLE_MS = 60L * 60L * 1000L
    }
}
