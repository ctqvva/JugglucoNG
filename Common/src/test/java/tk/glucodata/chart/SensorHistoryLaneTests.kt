package tk.glucodata.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucoseChartGap
import tk.glucodata.GlucosePoint

/**
 * A Libre 1/2's own 15-minute history is a lane beside its stream, never part
 * of the stream's line. These pin that it is drawn only when supplied, that it
 * reads the Auto calibration the stream reads, and that it breaks at real gaps.
 */
class SensorHistoryLaneTests {

    private companion object {
        const val MINUTE = MainSensorOwnership.MINUTE_MS
        const val NOW = 1_800_000_000_000L // minute-aligned
        const val AUTO_MODE = 0
    }

    private fun point(ts: Long, value: Float, serial: String = "L2") =
        GlucosePoint(ts, value, 0f).also { it.sensorSerial = serial }

    private val noCalibration = HistoryChartModelBuilder.Calibration { _, _, _, _ -> null }

    private fun stream(vararg values: Float) = values.mapIndexed { i, v -> point(NOW + i * MINUTE, v) }

    private fun build(
        history: List<GlucosePoint>,
        calibration: HistoryChartModelBuilder.Calibration = noCalibration,
        streamPoints: List<GlucosePoint> = stream(6.0f, 6.4f, 5.8f, 6.2f),
    ) = HistoryChartModelBuilder.build(
        listOf(
            HistoryChartModelBuilder.SeriesInput(
                "L2", isPrimary = true, viewMode = AUTO_MODE, colorArgb = 0,
                points = streamPoints, historyPoints = history,
            )
        ),
        MainSensorOwnership.NONE,
        calibration,
    )

    private fun historyLane(model: HistoryChartModel) =
        model.primary!!.secondaryLanes.firstOrNull { it.kind == ChartLaneKind.SENSOR_HISTORY }

    @Test
    fun noHistorySuppliedMeansNoHistoryLane() {
        val model = build(emptyList())
        assertNull(historyLane(model))
        assertTrue(model.primary!!.secondaryLanes.isEmpty())
    }

    @Test
    fun historyIsItsOwnLaneAndLeavesTheStreamLineAlone() {
        val model = build(listOf(point(NOW + MINUTE, 6.1f)))
        val lane = historyLane(model)!!
        assertEquals(listOf(6.1f), lane.runs.flatMap { it.points }.map { it.value })
        // The stream's line is exactly the stream.
        assertEquals(listOf(6.0f, 6.4f, 5.8f, 6.2f), model.primary!!.runs.flatMap { it.points }.map { it.value }.distinct())
        assertTrue(lane.runs.all { run -> run.points.all { it.look == ChartLook.SECONDARY } })
    }

    @Test
    fun historyReadsTheAutoCalibrationTheStreamReads() {
        val calibration = HistoryChartModelBuilder.Calibration { v, _, isRaw, _ -> if (!isRaw) v + 1f else null }
        val model = build(listOf(point(NOW + MINUTE, 6.1f)), calibration)
        assertEquals(7.1f, historyLane(model)!!.runs.single().points.single().value, 0.001f)
        assertEquals(7.4f, model.primary!!.valueAt(NOW + MINUTE)!!, 0.001f)
    }

    @Test
    fun quarterHourSamplesJoinButAMissingSlotBreaksTheLane() {
        val quarter = 15 * MINUTE
        val history = listOf(
            point(NOW, 6.0f),
            point(NOW + quarter, 6.2f),
            point(NOW + 2 * quarter, 6.4f),
            // One slot missing: 30 minutes is a hole, not a join.
            point(NOW + 4 * quarter, 6.8f),
        )
        assertTrue(2 * quarter > GlucoseChartGap.THRESHOLD_MS)
        val runs = historyLane(build(history))!!.runs
        assertEquals(2, runs.size)
        assertEquals(3, runs[0].points.size)
        assertEquals(1, runs[1].points.size)
    }

    @Test
    fun theCursorReportsTheHistoryValueInItsMinute() {
        val model = build(listOf(point(NOW + MINUTE + 20_000L, 6.1f)))
        val lanes = model.primary!!.cursorAt(NOW + MINUTE)!!.lanes
        assertEquals(listOf(ChartLaneValue(ChartLaneKind.SENSOR_HISTORY, 6.1f)), lanes)
        assertTrue(model.primary!!.cursorAt(NOW + 3 * MINUTE)!!.lanes.isEmpty())
    }

    @Test
    fun theVisibleRangeIncludesTheHistoryLane() {
        val model = build(listOf(point(NOW + MINUTE, 9.5f)))
        val (_, high) = model.visibleValueRange(NOW, NOW + 4 * MINUTE)
        assertEquals(9.5f, high!!, 0.001f)
    }
}
