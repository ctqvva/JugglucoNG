package tk.glucodata.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A journal mark pinned to the curve sits on the line as drawn, between readings too. */
class ChartLineValueNearTests {

    private companion object {
        const val MINUTE = 60_000L
        const val NOW = 1_800_000_000_000L
    }

    private fun series(vararg runs: List<Pair<Long, Float>>) = ChartSeriesModel(
        sensorId = "A",
        isPrimary = true,
        viewMode = 0,
        colorArgb = 0,
        runs = runs.map { points -> ChartRun(ChartLook.MAIN, points.map { (ts, v) -> ChartPointModel(ts, v) }) }
    )

    @Test
    fun interpolatesBetweenFiveMinuteReadings() {
        val model = series(listOf(NOW to 6f, NOW + 5 * MINUTE to 8f))
        assertEquals(7f, model.lineValueNear(NOW + 150_000L, 10 * MINUTE)!!, 0.0001f)
    }

    @Test
    fun exactReadingWins() {
        val model = series(listOf(NOW to 6f, NOW + 5 * MINUTE to 8f))
        assertEquals(8f, model.lineValueNear(NOW + 5 * MINUTE, 10 * MINUTE)!!, 0f)
    }

    @Test
    fun acrossAGapTakesTheNearerSideWithinReach() {
        val model = series(listOf(NOW to 6f), listOf(NOW + 60 * MINUTE to 9f))
        assertEquals(6f, model.lineValueNear(NOW + 4 * MINUTE, 10 * MINUTE)!!, 0f)
        assertEquals(9f, model.lineValueNear(NOW + 55 * MINUTE, 10 * MINUTE)!!, 0f)
    }

    @Test
    fun nothingNearbyIsNull() {
        val model = series(listOf(NOW to 6f, NOW + 60 * MINUTE to 9f))
        assertNull(model.lineValueNear(NOW + 30 * MINUTE, 10 * MINUTE))
    }
}
