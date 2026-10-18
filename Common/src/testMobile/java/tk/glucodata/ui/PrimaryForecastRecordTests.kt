package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings

/**
 * Without a calibration the primary lane's own forecast is the primary
 * forecast. It has to start where the main line ends — on the recorded value,
 * even one recorded in the other lane — while a secondary lane's forecast
 * keeps following that lane's own values.
 */
class PrimaryForecastRecordTests {

    private companion object {
        const val START = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val AUTO = 5f
        const val RAW = 4f
        const val RECORDED = 7f
    }

    private val settings = PredictiveSimulationSettings(enabled = true, trendMomentumEnabled = false)

    private fun flatRecordedHistory(start: Long = START) = (0 until 12).map { i ->
        GlucosePoint(
            value = AUTO,
            time = "",
            timestamp = start + i * MINUTE,
            rawValue = RAW,
            sensorSerial = "X-222227KUFY",
            sealedDisplayValue = RECORDED,
            sealedDisplayViewMode = 0
        )
    }

    private fun forecast(
        viewMode: Int,
        points: List<GlucosePoint> = flatRecordedHistory(),
        unit: String = "mmol/L"
    ) = buildPredictionSeriesForChart(
        points = points,
        viewMode = viewMode,
        journalEntries = emptyList(),
        insulinPresetsById = emptyMap(),
        unit = unit,
        targetLow = if (unit == "mmol/L") 3.9f else 70f,
        targetHigh = if (unit == "mmol/L") 10f else 180f,
        settings = settings
    ).associateBy { it.kind }

    @Test
    fun rawPrimaryForecastStartsFromTheRecordAndTheAutoLaneStaysPlain() {
        val series = forecast(viewMode = 3)
        assertEquals(RECORDED, series.getValue(GlucosePredictionSeriesKind.RAW).points.first().value, 0.05f)
        assertEquals(AUTO, series.getValue(GlucosePredictionSeriesKind.AUTO).points.first().value, 0.05f)
    }

    @Test
    fun autoPrimaryForecastStartsFromTheRecordAndTheRawLaneStaysPlain() {
        val series = forecast(viewMode = 2)
        assertEquals(RECORDED, series.getValue(GlucosePredictionSeriesKind.AUTO).points.first().value, 0.05f)
        assertEquals(RAW, series.getValue(GlucosePredictionSeriesKind.RAW).points.first().value, 0.05f)
    }

    @Test
    fun smoothingKeepsTheRecordedEndpointForRowsAndForecastsAfterALaneSwitch() {
        for (isMmol in listOf(false, true)) {
            val scale = if (isMmol) 1f else 18f
            val points = flatRecordedHistory(start = 1_700_000_000_000L).map {
                it.copy(value = AUTO * scale, rawValue = RAW * scale, sealedDisplayValue = RECORDED * scale)
            }
            for (collapse in listOf(false, true)) {
                val consumerHistory = buildSmoothedConsumerHistory(points, 5, collapse)
                val row = buildDisplayReadings(consumerHistory).first()
                assertEquals(
                    RECORDED * scale,
                    SealedGlucoseValue.calibratedFor(row, isRawMode = true, row.sensorSerial) ?: Float.NaN,
                    0.001f
                )
                val series = forecast(3, consumerHistory, if (isMmol) "mmol/L" else "mg/dL")
                assertEquals(RECORDED * scale, series.getValue(GlucosePredictionSeriesKind.RAW).points.first().value, 0.05f)
                assertEquals(AUTO * scale, series.getValue(GlucosePredictionSeriesKind.AUTO).points.first().value, 0.05f)
            }
        }
    }
}
