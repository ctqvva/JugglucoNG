package tk.glucodata.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucosePoint

/**
 * Libre 1/2 export their Stream as the Auto lane and their 15-minute History
 * as the Raw lane, so Data mode switches between them with no Libre-specific
 * chart code. These pin that a Raw lane present on one reading in fifteen
 * still draws as one joined line, as the main line and as the thin lane.
 */
class LibreHistoryLaneChartTests {

    private companion object {
        const val MINUTE = MainSensorOwnership.MINUTE_MS
        const val NOW = 1_800_000_000_000L
    }

    private val noCalibration = HistoryChartModelBuilder.Calibration { _, _, _, _ -> null }

    /** 46 minutes of stream; history on minutes 0, 15, 30 and 45. */
    private fun libre() = (0..45).map { m ->
        GlucosePoint(NOW + m * MINUTE + 20_000L, 100f + m, if (m % 15 == 0) 90f + m else 0f).also { it.sensorSerial = "L2" }
    }

    private fun build(viewMode: Int) = HistoryChartModelBuilder.build(
        listOf(HistoryChartModelBuilder.SeriesInput("L2", true, viewMode, 0, libre())),
        MainSensorOwnership.NONE,
        noCalibration,
    ).primary!!

    @Test
    fun historyModeDrawsTheHistoryAsOneJoinedMainLine() {
        val series = build(viewMode = 1)
        val main = series.runs.flatMap { it.points }.distinctBy { it.timestamp }
        assertEquals(listOf(90f, 105f, 120f, 135f), main.map { it.value })
        assertEquals(1, series.runs.size)
        // History+... modes only: plain History draws no Stream lane.
        assertTrue(series.secondaryLanes.isEmpty())
    }

    @Test
    fun streamPlusHistoryDrawsTheHistoryAsAJoinedThinLane() {
        val series = build(viewMode = 2)
        assertEquals(46, series.runs.flatMap { it.points }.distinctBy { it.timestamp }.size)
        val history = series.secondaryLanes.single { it.kind == ChartLaneKind.RAW }
        assertEquals(1, history.runs.size)
        assertEquals(4, history.runs.single().points.size)
    }

    @Test
    fun historyPlusStreamPutsTheStreamBesideTheHistory() {
        val series = build(viewMode = 3)
        assertEquals(4, series.runs.flatMap { it.points }.distinctBy { it.timestamp }.size)
        assertEquals(46, series.secondaryLanes.single { it.kind == ChartLaneKind.AUTO }.runs.flatMap { it.points }.size)
    }
}
