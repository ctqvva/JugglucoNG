package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windowed sweep that gives every reading row its arrow. It replaced a
 * filter-per-row, so what matters is that it still picks exactly the same
 * window — the two-pointer walk is the kind of thing that quietly drifts by one.
 */
class TrendWindowsTests {

    private val minute = 60_000L

    private fun series(count: Int, startAt: Long = 0L, stepMs: Long = minute) =
        (0 until count).map { GlucosePoint(startAt + it * stepMs, 100f + it, 100f + it) }

    private fun sweepMatchesPerRow(history: List<GlucosePoint>, rows: List<GlucosePoint>) {
        val swept = TrendWindows.velocities(history, rows, useRaw = false, isMmol = false)
        rows.forEach { row ->
            val window = TrendWindows.windowFor(history, row.timestamp)
            val expected = if (window.size >= 2) {
                TrendAccess.calculateVelocity(window, false, false).takeIf { it.isFinite() } ?: 0f
            } else {
                0f
            }
            assertEquals("row at ${row.timestamp}", expected, swept[row.timestamp]!!, 1e-4f)
        }
    }

    @Test
    fun matchesThePerRowWindowItReplaced() {
        val history = series(120)
        sweepMatchesPerRow(history, history.takeLast(48).asReversed())
    }

    @Test
    fun matchesWhenRowsArePassedNewestFirst() {
        // The screens hand rows in descending order; the sweep sorts internally.
        val history = series(90)
        sweepMatchesPerRow(history, history.takeLast(20).asReversed())
    }

    @Test
    fun matchesAcrossAGapInTheHistory() {
        val history = series(30) + series(30, startAt = 30 * minute + 4L * 60L * minute)
        sweepMatchesPerRow(history, history.asReversed())
    }

    @Test
    fun matchesWhenRowsPredateAllHistory() {
        val history = series(20, startAt = 10L * 60L * minute)
        val rows = series(5)
        sweepMatchesPerRow(history, rows)
    }

    @Test
    fun aRowWithOneReadingBehindItReadsFlat() {
        val history = series(1)
        val velocities = TrendWindows.velocities(history, history, useRaw = false, isMmol = false)
        assertEquals(0f, velocities[history[0].timestamp]!!, 1e-6f)
    }

    @Test
    fun emptyInputsYieldNothing() {
        assertTrue(TrendWindows.velocities(emptyList(), series(3), false, false).isEmpty())
        assertTrue(TrendWindows.velocities(series(3), emptyList(), false, false).isEmpty())
    }

    @Test
    fun everyRowGetsAnAnswer() {
        val history = series(200)
        val rows = history.takeLast(48).asReversed()
        val velocities = TrendWindows.velocities(history, rows, false, false)
        assertEquals(rows.size, velocities.size)
        assertTrue(rows.all { velocities.containsKey(it.timestamp) })
    }
}
