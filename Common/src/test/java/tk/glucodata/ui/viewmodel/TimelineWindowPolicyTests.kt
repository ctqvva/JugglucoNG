package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.GlucosePoint

class TimelineWindowPolicyTests {
    private val hour = TimelineWindowPolicy.HOUR_MS
    private val now = 1_760_000_000_000L - (1_760_000_000_000L % hour) + 17 * 60_000L

    @Test
    fun aWindowIsTheViewportPlusAtLeastADayEachSideOnTheHour() {
        val window = TimelineWindowPolicy.windowFor(now - 3 * hour, now)
        assertEquals(0L, window.startMs % hour)
        assertEquals(0L, window.endMs % hour)
        assertTrue(window.startMs <= now - 3 * hour - TimelineWindowPolicy.MIN_MARGIN_MS)
        assertTrue(window.endMs >= now + TimelineWindowPolicy.MIN_MARGIN_MS)
        // And no more than an hour of snapping beyond that.
        assertTrue(window.startMs > now - 3 * hour - TimelineWindowPolicy.MIN_MARGIN_MS - hour)
        assertTrue(window.endMs < now + TimelineWindowPolicy.MIN_MARGIN_MS + hour)
    }

    @Test
    fun aLongViewportEarnsAMarginOfItsOwnLength() {
        val week = 7 * 24 * hour
        val window = TimelineWindowPolicy.windowFor(now - week, now)
        assertTrue(window.startMs <= now - 2 * week)
        assertTrue(window.endMs >= now + week)
    }

    @Test
    fun aPanWithinTheOuterHalfOfTheMarginAsksForNothing() {
        val viewportStart = now - 3 * hour
        val window = TimelineWindowPolicy.windowFor(viewportStart, now)
        // Pan back by ten hours: still deep inside a 24-hour margin.
        assertFalse(TimelineWindowPolicy.needsNewWindow(window, viewportStart - 10 * hour, now - 10 * hour))
        // Pan back by fourteen hours: past half the margin, ask.
        assertTrue(TimelineWindowPolicy.needsNewWindow(window, viewportStart - 14 * hour, now - 14 * hour))
        // No window yet: always ask.
        assertTrue(TimelineWindowPolicy.needsNewWindow(null, viewportStart, now))
    }

    @Test
    fun zoomingOutFarEnoughAsksEvenWithoutPanning() {
        val window = TimelineWindowPolicy.windowFor(now - 3 * hour, now)
        assertFalse(TimelineWindowPolicy.needsNewWindow(window, now - 12 * hour, now))
        assertTrue(TimelineWindowPolicy.needsNewWindow(window, now - 3 * 24 * hour, now))
    }

    @Test
    fun theLiveTailStartsTwoDaysBackAndReanchorsAfterThree() {
        val start = TimelineWindowPolicy.liveTailStart(now)
        assertEquals(0L, start % hour)
        assertTrue(now - start >= TimelineWindowPolicy.LIVE_TAIL_MS)
        assertTrue(now - start < TimelineWindowPolicy.LIVE_TAIL_MS + hour)
        assertFalse(TimelineWindowPolicy.liveTailNeedsReanchor(start, now + 20 * hour))
        assertTrue(TimelineWindowPolicy.liveTailNeedsReanchor(start, now + 30 * hour))
    }

    private fun point(ts: Long) = GlucosePoint(value = 100f, time = "", timestamp = ts)

    @Test
    fun twoStretchesBecomeOneAscendingListWithSharedPointsTakenOnce() {
        val a = (0L until 10L).map { point(it * 60_000L) }
        val b = (5L until 15L).map { point(it * 60_000L) }
        val merged = mergeSortedTimelines(a, b)
        assertEquals((0L until 15L).map { it * 60_000L }, merged.map { it.timestamp })
        val apart = (100L until 105L).map { point(it * 60_000L) }
        assertEquals(a + apart, mergeSortedTimelines(apart, a))
        assertEquals(a, mergeSortedTimelines(a, emptyList()))
        assertEquals(a, mergeSortedTimelines(emptyList(), a))
    }
}
