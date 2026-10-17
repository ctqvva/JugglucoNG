package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings

/**
 * Without a calibration the primary lane's own forecast is the primary
 * forecast. It has to start where the main line ends — on that line's recorded
 * value — while a secondary lane's forecast keeps following that lane's own
 * values, never the main line's record.
 */
class PrimaryForecastRecordTests {

    private companion object {
        const val START = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val AUTO = 5f
        const val RAW = 4f
        const val RECORDED_AUTO = 7f
        const val RECORDED_RAW = 6f
    }

    private val settings = PredictiveSimulationSettings(enabled = true, trendMomentumEnabled = false)

    private fun flatRecordedHistory() = (0 until 12).map { i ->
        GlucosePoint(
            value = AUTO,
            time = "",
            timestamp = START + i * MINUTE,
            rawValue = RAW,
            sensorSerial = "X-222227KUFY",
            sealedAutoLaneValue = RECORDED_AUTO,
            sealedRawLaneValue = RECORDED_RAW
        )
    }

    private fun forecast(viewMode: Int) = buildPredictionSeriesForChart(
        points = flatRecordedHistory(),
        viewMode = viewMode,
        journalEntries = emptyList(),
        insulinPresetsById = emptyMap(),
        unit = "mmol/L",
        targetLow = 3.9f,
        targetHigh = 10f,
        settings = settings
    ).associateBy { it.kind }

    @Test
    fun rawPrimaryForecastStartsFromTheRecordAndTheAutoLaneStaysPlain() {
        val series = forecast(viewMode = 3)
        assertEquals(RECORDED_RAW, series.getValue(GlucosePredictionSeriesKind.RAW).points.first().value, 0.05f)
        assertEquals(AUTO, series.getValue(GlucosePredictionSeriesKind.AUTO).points.first().value, 0.05f)
    }

    @Test
    fun autoPrimaryForecastStartsFromTheRecordAndTheRawLaneStaysPlain() {
        val series = forecast(viewMode = 2)
        assertEquals(RECORDED_AUTO, series.getValue(GlucosePredictionSeriesKind.AUTO).points.first().value, 0.05f)
        assertEquals(RAW, series.getValue(GlucosePredictionSeriesKind.RAW).points.first().value, 0.05f)
    }
}
