package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.ui.buildPredictionSeriesForChart

class NotificationPredictionHistoryTests {
    @Test
    fun notificationForecastStartsFromTheRecordAfterSwitchingLanes() {
        for (isMmol in listOf(false, true)) {
            val auto = if (isMmol) 5f else 90f
            val raw = if (isMmol) 4f else 72f
            val recorded = if (isMmol) 7f else 126f
            for (mode in 0..3) {
                val source = (0 until 12).map { i ->
                    GlucosePoint(1_800_000_000_000L + i * 60_000L, auto, raw).also {
                        it.sensorSerial = "X-222227KUFY"
                        it.sealedDisplayValue = recorded
                        it.sealedDisplayViewMode = if (mode % 2 == 0) 1 else 0
                    }
                }
                val history = buildNotificationPredictionHistory(source, "fallback-sensor")
                val series = buildPredictionSeriesForChart(
                    points = history,
                    viewMode = mode,
                    journalEntries = emptyList(),
                    insulinPresetsById = emptyMap(),
                    unit = if (isMmol) "mmol/L" else "mg/dL",
                    targetLow = if (isMmol) 3.9f else 70f,
                    targetHigh = if (isMmol) 10f else 180f,
                    settings = PredictiveSimulationSettings(enabled = true, trendMomentumEnabled = false)
                ).associateBy { it.kind }
                val primaryKind = if (mode % 2 == 0) GlucosePredictionSeriesKind.AUTO else GlucosePredictionSeriesKind.RAW
                assertEquals(recorded, series.getValue(primaryKind).points.first().value, 0.05f)
                assertEquals("X-222227KUFY", history.last().sensorSerial)
                assertEquals(source.last().sealedDisplayViewMode, history.last().sealedDisplayViewMode)
                if (mode == 2) {
                    assertEquals(raw, series.getValue(GlucosePredictionSeriesKind.RAW).points.first().value, 0.05f)
                } else if (mode == 3) {
                    assertEquals(auto, series.getValue(GlucosePredictionSeriesKind.AUTO).points.first().value, 0.05f)
                }
            }
        }
    }

    @Test
    fun historyWithoutARecordUsesTheFallbackSensorAndKeepsTheSourceValues() {
        val point = GlucosePoint(1_800_000_000_000L, 5f, 4f)
        val history = buildNotificationPredictionHistory(listOf(point), "fallback-sensor").single()
        assertEquals("fallback-sensor", history.sensorSerial)
        assertEquals(5f, history.value, 0.001f)
        assertEquals(4f, history.rawValue, 0.001f)
        assertNull(history.sealedDisplayValue)
        assertNull(history.sealedDisplayViewMode)
    }
}
