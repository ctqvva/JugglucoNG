package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.TimelineRangeSummary

class TimelineRangeSummaryTrackerTests {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val origin = 1_760_000_000_000L

    /** A store with one reading a minute from [origin]; counts a stretch the way the merge would. */
    private fun count(stretch: LongRange, latestMs: Long): TimelineRangeSummary? {
        val first = maxOf(stretch.first, origin).let { ((it - origin + minute - 1) / minute) * minute + origin }
        val last = minOf(stretch.last, latestMs).let { ((it - origin) / minute) * minute + origin }
        if (first > last) return null
        return TimelineRangeSummary(((last - first) / minute).toInt() + 1, first, last)
    }

    private fun run(tracker: TimelineRangeSummaryTracker, latestMs: Long, rev: Long): Pair<TimelineRangeSummary?, TimelineRangeSummaryTracker.Plan> {
        val plan = tracker.plan(latestMs, rev)
        return tracker.apply(plan, plan.stretches.map { count(it, latestMs) }, rev) to plan
    }

    @Test
    fun aLiveRangeIsCountedOnceInFullThenAnHourAtATime() {
        val start = origin
        val end = origin + 100 * hour
        val tracker = TimelineRangeSummaryTracker(start, end)
        var latest = origin + 50 * hour
        val (first, firstPlan) = run(tracker, latest, 0L)
        assertEquals(count(start..end, latest), first)
        assertEquals(2, firstPlan.stretches.size)
        assertEquals(start, firstPlan.stretches[0].first)

        repeat(10) {
            latest += minute
            val (summary, plan) = run(tracker, latest, 0L)
            assertEquals(count(start..end, latest), summary)
            // Growth of the head by a minute, plus the hour of data behind the
            // newest reading: nothing near the whole range. The tail stretch
            // runs to the range's end, which is beyond the data; it is rows,
            // not span, that a stretch costs.
            assertTrue(plan.stretches.all { minOf(it.last, latest) - it.first <= hour + minute })
        }
    }

    @Test
    fun aRangeWhollyInThePastIsCountedOnceAndThenNeverQueriedAgain() {
        val start = origin
        val end = origin + 10 * hour
        val tracker = TimelineRangeSummaryTracker(start, end)
        val latest = origin + 50 * hour
        val (first, _) = run(tracker, latest, 0L)
        assertEquals(count(start..end, latest), first)
        val (again, plan) = run(tracker, latest + 5 * minute, 0L)
        assertEquals(first, again)
        assertTrue(plan.stretches.isEmpty())
    }

    @Test
    fun aRewriteThrowsTheHeadAway() {
        val start = origin
        val end = origin + 100 * hour
        val tracker = TimelineRangeSummaryTracker(start, end)
        run(tracker, origin + 50 * hour, 0L)
        val (_, plan) = run(tracker, origin + 50 * hour + minute, 1L)
        assertEquals(start, plan.stretches.first().first)
    }

    @Test
    fun aRangeWithNothingInItStaysNull() {
        val tracker = TimelineRangeSummaryTracker(origin - 10 * hour, origin - 5 * hour)
        val (summary, _) = run(tracker, origin + hour, 0L)
        assertNull(summary)
    }
}
