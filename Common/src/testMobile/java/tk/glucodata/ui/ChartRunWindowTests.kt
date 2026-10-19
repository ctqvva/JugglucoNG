package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.chart.ChartPointModel

/**
 * The draw path walks only the stretch of a run under the viewport. These pin
 * the two bounds to what the per-point `continue` used to select, duplicates
 * and edges included.
 */
class ChartRunWindowTests {
    private fun run(vararg timestamps: Long) = timestamps.map { ChartPointModel(it, 100f) }

    private fun expected(points: List<ChartPointModel>, start: Long, end: Long): List<Long> =
        points.filter { it.timestamp in start..end }.map { it.timestamp }

    private fun windowed(points: List<ChartPointModel>, start: Long, end: Long): List<Long> {
        val from = points.firstIndexAtOrAfter(start)
        val to = points.firstIndexAfter(end)
        return (from until to).map { points[it].timestamp }
    }

    @Test
    fun boundsSelectExactlyWhatThePerPointFilterSelected() {
        val points = run(10, 20, 20, 30, 40, 40, 40, 50, 60)
        for (start in listOf(0L, 5L, 10L, 15L, 20L, 21L, 40L, 55L, 60L, 61L, 100L)) {
            for (end in listOf(0L, 9L, 10L, 20L, 25L, 40L, 41L, 60L, 70L)) {
                assertEquals("start=$start end=$end", expected(points, start, end), windowed(points, start, end))
            }
        }
    }

    @Test
    fun emptyRunHasAnEmptyWindow() {
        assertEquals(emptyList<Long>(), windowed(emptyList(), 0L, 100L))
    }
}
