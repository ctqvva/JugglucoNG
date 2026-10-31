package tk.glucodata.data.prediction.analysis

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accuracy figures from hand-built forecasts whose errors are known, so every number the
 * screen shows can be checked by arithmetic rather than trusted.
 */
class PredictionAccuracyTests {

    private val t0 = 1_790_000_000_000L
    private val minute = 60_000L

    private fun forecast(
        origin: Long,
        values: FloatArray,
        baseline: Float = 100f,
        model: ForecastModel = ForecastModel.STANDARD,
        sensor: String = "S1",
        lane: Int = ForecastLane.AUTO,
        fallback: Boolean = false,
        planned: Int = 0,
    ) = RecordedForecastEntity(
        originTime = origin, recordedAt = origin, sensorSerial = sensor, lane = lane, model = model.storageId,
        modelVersion = 1, configHash = 0L, shown = model == ForecastModel.STANDARD, fallback = fallback,
        baselineMgdl = baseline, stepMinutes = 5, valuesMgdl = ForecastValues.encode(values),
        trendMgdlPerMin = null, plannedEntries = planned, journalEntries = planned,
    )

    /** A flat 100 mg/dL forecast out to two hours. */
    private fun flat(value: Float = 100f) = FloatArray(24) { value }

    /** One-minute readings of [value] from [from] to [to]. */
    private fun readings(from: Long, to: Long, value: (Long) -> Float = { 110f }, lane: Int = ForecastLane.AUTO): ActualGlucose.SensorLanes {
        val times = generateSequence(from) { it + minute }.takeWhile { it <= to }.toList().toLongArray()
        return ActualGlucose.SensorLanes(times, mapOf(lane to FloatArray(times.size) { value(times[it]) }))
    }

    @Test
    fun actualReadingsBeyondAShortForecastRemainVisibleWithoutBeingScored() {
        val short = forecast(t0, FloatArray(6) { 100f })
        val actual = ActualGlucose(mapOf("S1" to readings(t0, t0 + 130 * minute)))
        for (h in listOf(60, 120)) {
            val (outcome, value) = PredictionAccuracy.actualOutcome("S1", ForecastLane.AUTO, t0 + h * minute, actual)
            assertEquals(Outcome.RESOLVED, outcome)
            assertEquals(110f, value, 0f)
            assertEquals(Outcome.MISSING, PredictionAccuracy.outcome(short, h, actual).first)
        }
        assertEquals(Outcome.MISSING, PredictionAccuracy.actualOutcome("other", ForecastLane.AUTO, t0, actual, t0 + 60 * minute).first)
        assertEquals(Outcome.PENDING, PredictionAccuracy.actualOutcome("S1", ForecastLane.AUTO, t0 + 150 * minute, actual, t0 + 140 * minute).first)
    }

    @Test
    fun errorsBiasAndNoChangeAreComputedFromTheOutcomes() {
        // Actual is 110 everywhere; the forecast says 100: error −10 at every horizon, and
        // "no change" from a baseline of 100 is off by 10 as well.
        val actual = ActualGlucose(mapOf("S1" to readings(t0 - 60 * minute, t0 + 200 * minute)))
        val report = PredictionAccuracy.report(listOf(forecast(t0, flat())), actual, zone = ZoneOffset.UTC)
        val h30 = report.model(ForecastModel.STANDARD)!!.horizons.first { it.horizonMinutes == 30 }
        assertEquals(1, h30.count)
        assertEquals(10f, h30.meanAbsoluteError, 1e-4f)
        assertEquals(-10f, h30.bias, 1e-4f)
        assertEquals(10f, h30.rootMeanSquareError, 1e-4f)
        assertEquals(10f, h30.noChangeMeanAbsoluteError, 1e-4f)
    }

    @Test
    fun meanAbsoluteErrorAveragesOverForecasts() {
        val actual = ActualGlucose(mapOf("S1" to readings(t0 - 60 * minute, t0 + 400 * minute)))
        val forecasts = listOf(
            forecast(t0, flat(100f)),                    // −10
            forecast(t0 + 5 * minute, flat(130f)),       // +20
            forecast(t0 + 10 * minute, flat(110f)),      // 0
        )
        val h = PredictionAccuracy.report(forecasts, actual).model(ForecastModel.STANDARD)!!.horizons.first { it.horizonMinutes == 60 }
        assertEquals(3, h.count)
        assertEquals(10f, h.meanAbsoluteError, 1e-4f)
        assertEquals(10f / 3f, h.bias, 1e-4f)
    }

    @Test
    fun anOutcomeStillInTheFutureIsPendingNotAnError() {
        // Readings stop 40 minutes after the origin: 15 and 30 are resolved, 60 and 120 pending.
        val actual = ActualGlucose(mapOf("S1" to readings(t0 - 60 * minute, t0 + 40 * minute)))
        val horizons = PredictionAccuracy.report(listOf(forecast(t0, flat())), actual, nowMs = t0 + 41 * minute)
            .model(ForecastModel.STANDARD)!!.horizons
        assertEquals(1, horizons.first { it.horizonMinutes == 30 }.count)
        assertEquals(1, horizons.first { it.horizonMinutes == 60 }.pending)
        assertEquals(0, horizons.first { it.horizonMinutes == 60 }.count)
        assertEquals(1, horizons.first { it.horizonMinutes == 120 }.pending)
    }

    @Test
    fun aSensorThatStoppedLongAgoLeavesItsOutcomesMissingNotPending() {
        // The readings end 40 minutes in and it is now a day later: nothing more will arrive.
        val actual = ActualGlucose(mapOf("S1" to readings(t0 - 60 * minute, t0 + 40 * minute)))
        val nowMs = t0 + 24 * 60 * minute
        assertEquals(Outcome.MISSING, PredictionAccuracy.outcome(forecast(t0, flat()), 60, actual, nowMs).first)
        // Within the arrival allowance it is still pending.
        assertEquals(Outcome.PENDING, PredictionAccuracy.outcome(forecast(t0, flat()), 60, actual, t0 + 70 * minute).first)
    }

    @Test
    fun aGapAroundTheTargetIsMissingAndNotInterpolatedAcross() {
        // Readings every minute, but none between +20 and +50 minutes: the 30-minute outcome
        // falls in a 30-minute hole and cannot be scored; 60 can.
        val before = readings(t0 - 60 * minute, t0 + 20 * minute)
        val after = readings(t0 + 50 * minute, t0 + 200 * minute)
        val lanes = ActualGlucose.SensorLanes(
            before.times + after.times,
            mapOf(ForecastLane.AUTO to (before.lanes.getValue(ForecastLane.AUTO) + after.lanes.getValue(ForecastLane.AUTO))),
        )
        val horizons = PredictionAccuracy.report(listOf(forecast(t0, flat())), ActualGlucose(mapOf("S1" to lanes)))
            .model(ForecastModel.STANDARD)!!.horizons
        assertEquals(1, horizons.first { it.horizonMinutes == 30 }.missing)
        assertEquals(1, horizons.first { it.horizonMinutes == 60 }.count)
    }

    @Test
    fun aShortGapIsInterpolated() {
        // Five-minute cadence: the target sits between two readings 5 minutes apart.
        val times = generateSequence(t0 - 60 * minute) { it + 5 * minute }.takeWhile { it <= t0 + 200 * minute }
            .map { it + 2 * minute }.toList().toLongArray()
        val lanes = ActualGlucose.SensorLanes(times, mapOf(ForecastLane.AUTO to FloatArray(times.size) { 120f }))
        val (outcome, value) = PredictionAccuracy.outcome(forecast(t0, flat()), 30, ActualGlucose(mapOf("S1" to lanes)))
        assertEquals(Outcome.RESOLVED, outcome)
        assertEquals(120f, value, 1e-4f)
    }

    @Test
    fun anotherSensorsReadingsNeverScoreAForecast() {
        val actual = ActualGlucose(mapOf("S2" to readings(t0 - 60 * minute, t0 + 200 * minute)))
        val (outcome, _) = PredictionAccuracy.outcome(forecast(t0, flat(), sensor = "S1"), 30, actual, nowMs = t0)
        assertEquals(Outcome.PENDING, outcome)
    }

    @Test
    fun aForecastIsScoredInItsOwnLane() {
        val times = generateSequence(t0 - 60 * minute) { it + minute }.takeWhile { it <= t0 + 200 * minute }.toList().toLongArray()
        val lanes = ActualGlucose.SensorLanes(times, mapOf(
            ForecastLane.AUTO to FloatArray(times.size) { 110f },
            ForecastLane.RAW to FloatArray(times.size) { 90f },
        ))
        val actual = ActualGlucose(mapOf("S1" to lanes))
        assertEquals(110f, PredictionAccuracy.outcome(forecast(t0, flat(), lane = ForecastLane.AUTO), 30, actual).second, 0f)
        assertEquals(90f, PredictionAccuracy.outcome(forecast(t0, flat(), lane = ForecastLane.RAW), 30, actual).second, 0f)
    }

    @Test
    fun pairedComparisonUsesOnlyMomentsBothModelsHave() {
        val actual = ActualGlucose(mapOf("S1" to readings(t0 - 60 * minute, t0 + 400 * minute)))
        val forecasts = listOf(
            forecast(t0, flat(100f)),                                               // standard −10
            forecast(t0, flat(108f), model = ForecastModel.ADAPTIVE),               // adaptive −2
            forecast(t0 + 5 * minute, flat(150f)),                                  // standard only: +40, not paired
        )
        val report = PredictionAccuracy.report(forecasts, actual)
        val paired = report.paired(30)!!
        assertEquals(1, paired.count)
        assertEquals(10f, paired.standardMeanAbsoluteError, 1e-4f)
        assertEquals(2f, paired.adaptiveMeanAbsoluteError, 1e-4f)
        assertEquals(10f, paired.noChangeMeanAbsoluteError, 1e-4f)
        // The unpaired forecast still counts toward the standard model's own figures.
        assertEquals(2, report.model(ForecastModel.STANDARD)!!.horizons.first { it.horizonMinutes == 30 }.count)
    }

    @Test
    fun dailyFiguresGroupByTheOriginsDay() {
        val day = 24 * 60 * minute
        val actual = ActualGlucose(mapOf("S1" to readings(t0 - 60 * minute, t0 + 2 * day)))
        val forecasts = listOf(forecast(t0, flat(100f)), forecast(t0 + day, flat(130f)))
        val daily = PredictionAccuracy.report(forecasts, actual, zone = ZoneOffset.UTC).daily
        assertEquals(2, daily.size)
        assertEquals(listOf(10f, 20f), daily.map { it.meanAbsoluteError })
    }

    @Test
    fun fallbacksAndPlannedTreatmentsAreCounted() {
        val actual = ActualGlucose(mapOf("S1" to readings(t0 - 60 * minute, t0 + 200 * minute)))
        val report = PredictionAccuracy.report(
            listOf(
                forecast(t0, flat(), planned = 2),
                forecast(t0, flat(), model = ForecastModel.ADAPTIVE, fallback = true),
            ),
            actual,
        )
        assertEquals(1, report.model(ForecastModel.ADAPTIVE)!!.fallbacks)
        assertEquals(1, report.withPlannedTreatments)
    }

    @Test
    fun noForecastsMeansNoModelsRatherThanZeroErrors() {
        val report = PredictionAccuracy.report(emptyList(), ActualGlucose(emptyMap()))
        assertTrue(report.models.isEmpty())
        assertTrue(report.paired.all { it.count == 0 && it.standardMeanAbsoluteError.isNaN() })
    }
}
