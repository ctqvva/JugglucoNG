package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tk.glucodata.chart.HistoryChartModelBuilder
import tk.glucodata.chart.MainSensorOwnership

class NotificationChartSmoothingTests {
    @Test fun smoothsTheSignalButPreservesRecordedValuesAndTheirOwners() {
        val points = listOf(100f, 100f, 220f, 100f, 100f).mapIndexed { i, value ->
            GlucosePoint(i*60_000L, value, value).also {
                it.sensorSerial = "A"
                it.sealedDisplayValue = 125f
                it.sealedDisplayViewMode = 0
            }
        }
        val smoothed = NotificationChartModelSource.smoothPoints(points, 3, false)
        assertNotEquals(points[2].value, smoothed[2].value)
        smoothed.forEach {
            assertEquals("A", it.sensorSerial)
            assertEquals(125f, it.sealedDisplayValue, 0.001f)
            assertEquals(0, it.sealedDisplayViewMode)
        }
        val model = HistoryChartModelBuilder.build(
            listOf(HistoryChartModelBuilder.SeriesInput("A", true, 0, 0, smoothed)),
            MainSensorOwnership.NONE, HistoryChartModelBuilder.Calibration { _, _, _, _ -> null },
        )
        assertEquals(125f, model.primary!!.valueAt(points[2].timestamp)!!, 0.001f)
    }

    @Test fun neverSmoothsAcrossSensorReplacement() {
        val points = (0..5).map { i ->
            GlucosePoint(i*60_000L, if (i < 3) 100f else 250f, 0f).also {
                it.sensorSerial = if (i < 3) "A" else "B"
            }
        }
        val smoothed = NotificationChartModelSource.smoothPoints(points, 5, false)
        assertEquals(points.map { it.value }, smoothed.map { it.value })
    }
}
