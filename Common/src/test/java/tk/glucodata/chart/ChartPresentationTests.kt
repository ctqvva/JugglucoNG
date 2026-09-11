package tk.glucodata.chart

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.GlucosePoint

class ChartPresentationTests {
    private val minute = MainSensorOwnership.MINUTE_MS
    private fun point(t: Long, id: String, value: Float) = GlucosePoint(t, value, value).also { it.sensorSerial = id }
    private fun input(id: String, primary: Boolean, vararg points: GlucosePoint) =
        HistoryChartModelBuilder.SeriesInput(id, primary, 0, 0, points.toList())
    private val noCalibration = HistoryChartModelBuilder.Calibration { _, _, _, _ -> null }

    @Test fun recordsTheActualSensorOfMergedHistoryAndOnlyTheVisibleMinutes() {
        val model = HistoryChartModelBuilder.build(
            listOf(input("B", true, point(0, "A", 90f), point(minute, "A", 100f), point(2*minute, "B", 200f))),
            MainSensorOwnership.NONE, noCalibration,
        )
        assertEquals(listOf(PresentedChartValue(minute, 100f, "A", 0)), model.presentedMainValues(1L, minute))
    }

    @Test fun borrowedBoundaryDoesNotReplaceThePeerWhoWasActuallyMain() {
        val model = HistoryChartModelBuilder.build(
            listOf(
                input("A", true, point(minute, "A", 100f), point(2*minute, "A", 110f)),
                input("B", false, point(minute, "B", 200f), point(2*minute, "B", 210f)),
            ),
            MainSensorOwnership(mapOf(minute to "B"), 10*MainSensorOwnership.SEAL_GRACE_MS), noCalibration,
        )
        assertEquals(
            listOf(PresentedChartValue(minute, 200f, "B", 0)),
            model.presentedMainValues(minute, minute),
        )
    }

    @Test fun multipleSamplesInOneMinuteProduceOneRecord() {
        val model = HistoryChartModelBuilder.build(
            listOf(input("A", true, point(minute+1, "A", 100f), point(minute+30_000, "A", 110f))),
            MainSensorOwnership.NONE, noCalibration,
        )
        assertEquals(listOf(PresentedChartValue(minute, 100f, "A", 0)), model.presentedMainValues(0, 2*minute))
    }
    @Test fun boundsIncludeCalibratedPeersAndPreviewButExcludeOffscreenPoints() {
        val model = HistoryChartModel(listOf(
            ChartSeriesModel("A", true, 0, 0,
                listOf(ChartRun(ChartLook.MAIN, listOf(ChartPointModel(minute, 100f)))),
                listOf(ChartLane(ChartLaneKind.CALIBRATION_PREVIEW,
                    listOf(ChartRun(ChartLook.SECONDARY, listOf(ChartPointModel(minute, 300f))))))),
            ChartSeriesModel("B", false, 0, 0,
                listOf(ChartRun(ChartLook.SECONDARY,
                    listOf(ChartPointModel(0, 450f), ChartPointModel(minute, 50f))))),
        ))
        assertEquals(50f to 300f, model.visibleValueRange(minute, minute))
        assertEquals(null to null, model.visibleValueRange(2*minute, 3*minute))
    }

}
