package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard's Room query is bounded to what the chart can draw. These pin
 * the two properties that makes safe: the query always covers the visible
 * window, and panning does not re-query on every frame.
 */
class DashboardChartWindowPolicyTests {
    private companion object {
        const val MINUTE_MS = 60L * 1000L
        const val HOUR_MS = 60L * MINUTE_MS
        /** An arbitrary "now" that is not itself on the 30-minute grid. */
        const val NOW = 1_700_000_000_000L
    }

    @Test
    fun queryWindowAlwaysContainsTheVisibleWindow() {
        for (spanHours in listOf(1L, 3L, 6L, 12L, 24L, 72L)) {
            val visible = ChartWindow(startMs = NOW - spanHours * HOUR_MS, endMs = NOW)
            val query = DashboardChartWindowPolicy.queryWindow(visible)

            assertTrue(
                "query must start at or before the visible window (span=${spanHours}h)",
                query.startMs <= visible.startMs
            )
            assertTrue(
                "query must end at or after the visible window (span=${spanHours}h)",
                query.endMs >= visible.endMs
            )
        }
    }

    @Test
    fun aSmallPanDoesNotChangeTheQuery() {
        val visible = ChartWindow(startMs = NOW - 3 * HOUR_MS, endMs = NOW)
        val nudged = ChartWindow(
            startMs = visible.startMs + MINUTE_MS,
            endMs = visible.endMs + MINUTE_MS
        )

        assertEquals(
            DashboardChartWindowPolicy.queryWindow(visible),
            DashboardChartWindowPolicy.queryWindow(nudged)
        )
    }

    @Test
    fun panningFarEnoughDoesChangeTheQuery() {
        val visible = ChartWindow(startMs = NOW - 3 * HOUR_MS, endMs = NOW)
        val moved = ChartWindow(
            startMs = visible.startMs - 12 * HOUR_MS,
            endMs = visible.endMs - 12 * HOUR_MS
        )

        assertNotEquals(
            DashboardChartWindowPolicy.queryWindow(visible),
            DashboardChartWindowPolicy.queryWindow(moved)
        )
    }

    @Test
    fun theQueryStaysBoundedRatherThanReachingBackToZero() {
        val visible = ChartWindow(startMs = NOW - 3 * HOUR_MS, endMs = NOW)
        val query = DashboardChartWindowPolicy.queryWindow(visible)

        // The bug this replaces queried from 0, i.e. the whole store on every
        // emission. A 3h window must stay within a day of itself.
        assertTrue(
            "3h window queried ${(query.endMs - query.startMs) / HOUR_MS}h",
            (query.endMs - query.startMs) <= 24 * HOUR_MS
        )
    }

    @Test
    fun aWindowNearEpochDoesNotProduceANegativeStart() {
        val query = DashboardChartWindowPolicy.queryWindow(
            ChartWindow(startMs = 0L, endMs = HOUR_MS)
        )

        assertTrue("start was ${query.startMs}", query.startMs >= 0L)
    }
}

/**
 * The escape hatch for the failure bounding the query introduced: a store whose
 * newest reading predates the visible window would otherwise render nothing, and
 * stay that way, because an empty chart never scrolls to the data.
 */
class DashboardHistoryWindowFallbackTests {
    private companion object {
        const val HOUR_MS = 60L * 60L * 1000L
        const val NOW = 1_700_000_000_000L
    }

    @Test
    fun dataInsideTheWindowDoesNotTriggerTheFallback() {
        assertFalse(
            tk.glucodata.data.DashboardHistoryWindowPolicy.shouldUseOldTailFallback(
                latestTimestamp = NOW - HOUR_MS,
                windowStartMs = NOW - 3 * HOUR_MS
            )
        )
    }

    @Test
    fun anExpiredSensorsOlderTailTriggersTheFallback() {
        assertTrue(
            tk.glucodata.data.DashboardHistoryWindowPolicy.shouldUseOldTailFallback(
                latestTimestamp = NOW - 10L * 24L * HOUR_MS,
                windowStartMs = NOW - 3 * HOUR_MS
            )
        )
    }

    @Test
    fun anEmptyStoreDoesNotTriggerTheFallback() {
        // Nothing stored at all is the "waiting for data" state, not an old tail.
        assertFalse(
            tk.glucodata.data.DashboardHistoryWindowPolicy.shouldUseOldTailFallback(
                latestTimestamp = 0L,
                windowStartMs = NOW - 3 * HOUR_MS
            )
        )
    }

    @Test
    fun theFallbackWindowEndsAtTheNewestStoredReadingAndKeepsTheSpan() {
        val latest = NOW - 10L * 24L * HOUR_MS
        val (start, end) = tk.glucodata.data.DashboardHistoryWindowPolicy.fallbackWindow(
            latestTimestamp = latest,
            windowSpanMs = 3 * HOUR_MS
        )

        assertEquals(latest, end)
        assertEquals(latest - 3 * HOUR_MS, start)
    }

    @Test
    fun theFallbackWindowStaysBoundedEvenForADegenerateSpan() {
        val (start, end) = tk.glucodata.data.DashboardHistoryWindowPolicy.fallbackWindow(
            latestTimestamp = NOW,
            windowSpanMs = 0L
        )

        assertTrue("span was ${end - start}", (end - start) >= HOUR_MS)
    }
}
