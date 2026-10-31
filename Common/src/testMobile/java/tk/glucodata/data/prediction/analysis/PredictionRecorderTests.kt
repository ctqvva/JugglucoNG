package tk.glucodata.data.prediction.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.prediction.GlucosePredictionPoint
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.buildGlucosePrediction
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.buildPredictionSeriesForChart
import tk.glucodata.ui.util.GlucoseFormatter

/**
 * The recorder's own decisions — when to record, which line is the forecast, how a curve becomes
 * a record — and the promise that recording and the adaptive switch leave the standard forecast
 * exactly as it was.
 */
class PredictionRecorderTests {

    private val t0 = 1_790_000_000_000L
    private val minute = 60_000L

    @Test
    fun recordsALiveReadingAtMostEveryFiveMinutes() {
        assertTrue(PredictionRecorder.shouldQueue(readingTimeMs = t0, nowMs = t0 + 10_000L, lastQueuedMs = 0L))
        assertFalse("four minutes later", PredictionRecorder.shouldQueue(t0 + 4 * minute, t0 + 4 * minute, lastQueuedMs = t0))
        assertTrue("five minutes later", PredictionRecorder.shouldQueue(t0 + 5 * minute, t0 + 5 * minute, lastQueuedMs = t0))
    }

    @Test
    fun aReplayedOldReadingIsNotAForecastMoment() {
        assertFalse(PredictionRecorder.shouldQueue(readingTimeMs = t0, nowMs = t0 + 30 * minute, lastQueuedMs = 0L))
    }

    private fun series(kind: GlucosePredictionSeriesKind) =
        GlucosePredictionSeries(kind, listOf(GlucosePredictionPoint(t0, 5f, 1f), GlucosePredictionPoint(t0 + 5 * minute, 5f, 0.8f)))

    @Test
    fun theRecordedForecastIsTheOneTheChartDrawsAsMain() {
        val auto = series(GlucosePredictionSeriesKind.AUTO)
        val raw = series(GlucosePredictionSeriesKind.RAW)
        val calibrated = series(GlucosePredictionSeriesKind.CALIBRATED)
        assertEquals(auto, PredictionRecorder.primarySeries(listOf(raw, auto), viewMode = 2))
        assertEquals(raw, PredictionRecorder.primarySeries(listOf(raw, auto), viewMode = 3))
        assertEquals(calibrated, PredictionRecorder.primarySeries(listOf(raw, auto, calibrated), viewMode = 0))
        assertNull(PredictionRecorder.primarySeries(listOf(raw), viewMode = 0))
        assertEquals(ForecastLane.CALIBRATED_RAW, PredictionRecorder.laneOf(GlucosePredictionSeriesKind.CALIBRATED, viewMode = 1))
        assertEquals(ForecastLane.CALIBRATED_AUTO, PredictionRecorder.laneOf(GlucosePredictionSeriesKind.CALIBRATED, viewMode = 0))
    }

    @Test
    fun aCurveIsStoredInMgdlOnItsFiveMinuteGrid() {
        val points = listOf(
            GlucosePredictionPoint(t0, 5f, 1f),
            GlucosePredictionPoint(t0 + 5 * minute, 5.5f, 0.9f),
            // 10 minutes is missing: the record must say so rather than shift the rest.
            GlucosePredictionPoint(t0 + 15 * minute, 6f, 0.8f),
        )
        val entity = PredictionRecorder.entity(
            GlucosePredictionSeries(GlucosePredictionSeriesKind.AUTO, points), ForecastModel.STANDARD, 1, 7L,
            shown = true, origin = t0, recordedAt = t0 + 20_000L, sensor = "S1", lane = ForecastLane.AUTO,
            isMmol = true, trendMgdlPerMin = 1f, plannedEntries = 0, journalEntries = 0,
        )
        val m = GlucoseFormatter.MGDL_PER_MMOL
        assertEquals(5f * m, entity.baselineMgdl, 1e-3f)
        assertArrayEquals(floatArrayOf(5.5f * m, Float.NaN, 6f * m), entity.values, 1e-3f)
        assertEquals(6f * m, entity.valueAt(15), 1e-3f)
        assertTrue(entity.valueAt(10).isNaN())
    }

    private fun history(isMmol: Boolean) = (0 until 70).map { i ->
        val mgdl = 120f + i * 0.8f
        val v = if (isMmol) mgdl / GlucoseFormatter.MGDL_PER_MMOL else mgdl
        GlucosePoint(value = v, time = "", timestamp = t0 + i * minute, rawValue = v, sensorSerial = "S1")
    }

    private val settings = PredictiveSimulationSettings(enabled = true, trendMomentumEnabled = true)

    @Test
    fun withAdaptiveOffTheChartForecastIsTheStandardOneUnchanged() {
        val history = history(isMmol = false)
        val series = buildPredictionSeriesForChart(history, 0, emptyList(), emptyMap(), "mg/dL", 70f, 180f, settings)
        val auto = series.single { it.kind == GlucosePredictionSeriesKind.AUTO }
        val direct = buildGlucosePrediction(history, emptyList(), emptyMap(), "mg/dL", 70f, 180f, settings)
        assertEquals(direct, auto.points)
        assertFalse(auto.adaptiveFallback)
    }

    @Test
    fun adaptiveWithoutAModelFallsBackToTheStandardForecastWithoutMomentum() {
        // No trained model here (and no app context), so the adaptive line must be its fallback,
        // flagged as such — never an empty chart, never the momentum curve.
        val history = history(isMmol = false)
        val series = buildPredictionSeriesForChart(history, 0, emptyList(), emptyMap(), "mg/dL", 70f, 180f, settings.copy(adaptive = true))
        val auto = series.single { it.kind == GlucosePredictionSeriesKind.AUTO }
        val withoutMomentum = buildGlucosePrediction(history, emptyList(), emptyMap(), "mg/dL", 70f, 180f, settings.copy(trendMomentumEnabled = false))
        assertTrue(auto.adaptiveFallback)
        assertEquals(withoutMomentum, auto.points)
    }
}
