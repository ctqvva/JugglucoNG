package tk.glucodata.ui.stats

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tile dragging and the dashboard strip's window — the two pieces of stats behaviour that
 * are plain logic rather than layout, and the two that were quietly wrong.
 */
class StatsDragAndWindowTests {

    /** A two-column grid of 160x100 tiles with a 12 unit gutter, laid out in root space. */
    private fun gridBounds(metrics: List<StatsMetric>): Map<StatsMetric, Rect> =
        metrics.mapIndexed { index, metric ->
            val column = index % 2
            val row = index / 2
            val left = column * 172f
            val top = row * 112f
            metric to Rect(left, top, left + 160f, top + 100f)
        }.toMap()

    private fun stateOver(order: List<StatsMetric>): Pair<MetricDragState, MutableList<List<StatsMetric>>> {
        val recorded = mutableListOf<List<StatsMetric>>()
        var current = order
        val state = MetricDragState(
            orderOf = { current },
            onReordered = { next ->
                current = next
                recorded += next
            }
        )
        gridBounds(order).forEach { (metric, rect) -> state.reportBounds(metric, rect) }
        return state to recorded
    }

    @Test
    fun aTileDraggedTwoSlotsEndsUpTwoSlotsAway() {
        // The whole point of the fix: dragging used to manage exactly one swap and then
        // stop responding, because the dragged tile's own reported bounds followed the
        // finger and the hit-test point ran away at twice the drag distance.
        val order = listOf(
            StatsMetric.AVERAGE,
            StatsMetric.GMI,
            StatsMetric.MEDIAN,
            StatsMetric.IQR
        )
        val (state, recorded) = stateOver(order)
        state.start(StatsMetric.AVERAGE)

        // Straight down past the second row, in the small steps a real drag delivers.
        repeat(12) { state.onDrag(Offset(0f, 10f)) }

        assertTrue("expected at least one reorder", recorded.isNotEmpty())
        val finalOrder = recorded.last()
        assertTrue(
            "AVERAGE should have travelled past the first row, was $finalOrder",
            finalOrder.indexOf(StatsMetric.AVERAGE) >= 2
        )
    }

    @Test
    fun aTileTakesTheSlotItIsNearestTo() {
        // Including from the gutter between the two columns, which containment hit-testing
        // treated as dead space.
        val order = listOf(StatsMetric.AVERAGE, StatsMetric.GMI)
        val (state, recorded) = stateOver(order)
        state.start(StatsMetric.AVERAGE)
        state.onDrag(Offset(120f, 0f))

        assertEquals(listOf(StatsMetric.GMI, StatsMetric.AVERAGE), recorded.lastOrNull())
    }

    @Test
    fun aTileShortOfTheMidpointStaysPut() {
        val order = listOf(StatsMetric.AVERAGE, StatsMetric.GMI)
        val (state, recorded) = stateOver(order)
        state.start(StatsMetric.AVERAGE)
        state.onDrag(Offset(6f, 4f))
        state.onDrag(Offset(50f, 0f))

        assertTrue("still nearer its own slot, should not reorder, got $recorded", recorded.isEmpty())
    }

    @Test
    fun aHiddenTileStopsBeingADropTarget() {
        val order = listOf(StatsMetric.AVERAGE, StatsMetric.GMI)
        val (state, recorded) = stateOver(order)
        state.forget(StatsMetric.GMI)
        state.start(StatsMetric.AVERAGE)
        state.onDrag(Offset(180f, 0f))

        assertTrue("a forgotten tile must not capture a drop, got $recorded", recorded.isEmpty())
    }

    @Test
    fun theStripsWindowStaysOpenAtTheTop() {
        // Pinning the end to "now" froze the chips: a reading that arrived after the range
        // was computed fell outside it and stopped counting.
        PinnedWindow.entries.forEach { window ->
            assertEquals(
                "$window should be open-ended",
                Long.MAX_VALUE,
                window.resolveRange().endMillis
            )
        }
    }

    @Test
    fun todayStartsAtMidnightAndTheRestCountBack() {
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(midnight, PinnedWindow.TODAY.resolveRange().startMillis)

        val dayMs = 24L * 60L * 60L * 1000L
        val now = System.currentTimeMillis()
        listOf(
            PinnedWindow.D1 to 1L,
            PinnedWindow.D3 to 3L,
            PinnedWindow.D14 to 14L,
            PinnedWindow.D30 to 30L
        ).forEach { (window, days) ->
            val start = window.resolveRange().startMillis
            val expected = now - days * dayMs
            assertTrue(
                "$window started at $start, expected about $expected",
                kotlin.math.abs(start - expected) < 5_000L
            )
        }
    }

    @Test
    fun cyclingTheWindowNeverLeavesTheList() {
        // The pill advances by one and wraps; nothing else drives it any more.
        val entries = PinnedWindow.entries
        assertEquals(
            listOf(
                PinnedWindow.TODAY,
                PinnedWindow.D1,
                PinnedWindow.D3,
                PinnedWindow.D14,
                PinnedWindow.D30
            ),
            entries.toList()
        )
        var window = PinnedWindow.TODAY
        repeat(entries.size + 1) {
            window = entries[(entries.indexOf(window) + 1) % entries.size]
        }
        assertEquals(PinnedWindow.D1, window)
    }
}
