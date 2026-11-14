package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.GlucosePoint
import tk.glucodata.logic.TrendEngine
import tk.glucodata.alerts.ForecastLowConfidencePolicy.Decision

class ForecastLowReturnToBaselineTests {
    private val start = 1_700_000_000_000L
    private val minute = 60_000L

    // Synthetic reproduction of the described shape, not a recorded patient trace:
    // quiet 118 baseline, ten-minute rise to 158, ten-minute return to 118.
    private fun excursion() = List(11) { 118f } +
        (1..10).map { 118f + 4f * it } + (1..10).map { 158f - 4f * it }

    private fun feed(
        state: ForecastLowReturnToBaselineState, values: List<Float>,
        startIndex: Int = 0, sensor: String? = "sensor", generation: Int = 1,
        viewMode: Int = 0, mmol: Boolean = false
    ) {
        values.forEachIndexed { index, value ->
            state.observe(start + (startIndex + index) * minute,
                value / if (mmol) 18.0182f else 1f, sensor, generation, viewMode, mmol)
        }
    }

    private fun decide(
        baseline: Float?, glucose: Float = 118f, rate: Float = -3f,
        journal: FloatArray? = floatArrayOf(0f, 0f, 0f, 0f, 0f),
        p: ForecastLowConfidencePolicy = ForecastLowConfidencePolicy(), seconds: Long = 0,
        sensitivity: Float = 50f
    ) = p.evaluate(
        StandardGlucoseAlertCondition(glucose, glucose + rate * 20, 70f), rate,
        start + seconds * 1000, start + seconds * 1000, "sensor", 1, 20, false, false,
        journal?.getOrNull(3) ?: Float.NaN, sensitivity,
        journal?.getOrNull(0) ?: Float.NaN, baseline
    )

    @Test fun symmetricTenMinuteReturnDoesNotAlertAtZeroIobWithRealTrendEstimator() {
        val state = ForecastLowReturnToBaselineState()
        val policy = ForecastLowConfidencePolicy()
        val timerOnly = ForecastLowConfidencePolicy()
        val points = mutableListOf<GlucosePoint>()
        val config = AlertConfig(AlertType.PRE_LOW, enabled = true, threshold = 70f, forecastMinutes = 20)
        var forecasts = 0
        var timerOnlyWouldAlert = false
        // Include flattening at baseline to exercise the estimator's lingering negative rate.
        (excursion() + List(10) { 118f }).forEachIndexed { index, value ->
            val time = start + index * minute
            points.add(GlucosePoint(time, value, value))
            state.observe(time, value, "sensor", 1, 0, false)
            val rate = TrendEngine.calculateTrend(points, false, false).velocity
            val condition = StandardGlucoseAlertEvaluator.resolveActive(value, rate,
                mapOf(AlertType.PRE_LOW to config), listOf(AlertType.PRE_LOW), false, { true }
            )[AlertType.PRE_LOW]
            if (condition != null && ForecastLowSuppression.hasDownwardArrow(rate)) forecasts++
            val oldDecision = timerOnly.evaluate(condition, rate, time, time, "sensor", 1, 20, false, false, 0f, 50f)
            timerOnlyWouldAlert = timerOnlyWouldAlert || oldDecision.eligible
            val decision = policy.evaluate(condition, rate, time, time, "sensor", 1, 20, false, false,
                0f, 50f, 0f, state.recentRiseBaselineMgdl(time))
            assertFalse("minute=$index value=$value rate=$rate decision=$decision", decision.eligible)
        }
        assertTrue("The fixture must actually exercise forecast lows", forecasts >= 2)
        assertTrue("One-minute confirmation must reproduce the reported design flaw", timerOnlyWouldAlert)
    }

    @Test fun returnEvidenceIsObservedBeforeTheForecastCandidate() {
        val state = ForecastLowReturnToBaselineState()
        feed(state, excursion())
        assertEquals(118f, state.recentRiseBaselineMgdl(start + 30 * minute)!!, 0.001f)
        assertEquals(Decision.RETURNING_TO_BASELINE, decide(state.recentRiseBaselineMgdl(start + 30 * minute)))
    }

    @Test fun meaningfulInsulinBypassesReturnToBaselineDeferral() {
        assertEquals(Decision.INSULIN_SUPPORTED,
            decide(118f, journal = floatArrayOf(1f, 0f, 0f, 1f, 0f)))
    }

    @Test fun insulinWaitingToActCannotBeMistakenForNoInsulin() {
        val p = ForecastLowConfidencePolicy()
        val waiting = floatArrayOf(1f, 0f, 0f, 0f, 0f)
        assertEquals(Decision.CONFIRMING, decide(118f, journal = waiting, p = p))
        assertEquals(Decision.CONFIRMED, decide(118f, glucose = 116f, journal = waiting, p = p, seconds = 60))
    }

    @Test fun absentShortOrInvalidJournalNeverAuthorizesBaselineDeferral() {
        for (journal in listOf(null, floatArrayOf(), floatArrayOf(0f, 0f, 0f),
            floatArrayOf(Float.NaN, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, Float.NaN),
            floatArrayOf(-1f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, Float.POSITIVE_INFINITY))) {
            val p = ForecastLowConfidencePolicy()
            assertEquals(Decision.CONFIRMING, decide(118f, journal = journal, p = p))
            assertEquals(Decision.CONFIRMED, decide(118f, glucose = 116f, journal = journal, p = p, seconds = 60))
        }
    }

    @Test fun continuingBelowBaselineReleasesWithoutAnotherConfirmationWait() {
        assertEquals(Decision.RETURNING_TO_BASELINE, decide(118f, glucose = 115f))
        assertEquals(Decision.BELOW_BASELINE, decide(118f, glucose = 114f))
    }

    @Test fun nearLowAndImminentLowOverrideTheShapeEvenWithoutInsulin() {
        assertEquals(Decision.NEAR_THRESHOLD, decide(100f, glucose = 84f, rate = -1f))
        assertEquals(Decision.IMMINENT, decide(118f, glucose = 118f, rate = -5f))
    }

    @Test fun deferralExpiresFifteenMinutesAfterPeakWithoutSlidingThePeak() {
        val state = ForecastLowReturnToBaselineState()
        feed(state, excursion() + List(5) { 118f })
        assertNotNull(state.recentRiseBaselineMgdl(start + 35 * minute))
        feed(state, listOf(118f), startIndex = 36)
        assertNull(state.recentRiseBaselineMgdl(start + 36 * minute))
    }

    @Test fun earlierHigherDescentCannotHideARecentLowerRise() {
        val state = ForecastLowReturnToBaselineState()
        val descent = (0 until 10).map { 178f - 6f * it }
        feed(state, descent + excursion().take(30))
        val now = start + 39 * minute
        assertEquals(118f, state.recentRiseBaselineMgdl(now)!!, 0.001f)
        val p = ForecastLowConfidencePolicy()
        assertEquals(Decision.RETURNING_TO_BASELINE,
            decide(state.recentRiseBaselineMgdl(now), glucose = 122f, p = p, seconds = 39 * 60))
        feed(state, listOf(118f), startIndex = 40)
        assertEquals(Decision.RETURNING_TO_BASELINE,
            decide(state.recentRiseBaselineMgdl(now + minute), p = p, seconds = 40 * 60))
    }

    @Test fun schedulerReleasesConfirmedForecastAfterPeakExpiresWithoutANewReading() {
        val state = ForecastLowReturnToBaselineState()
        val p = ForecastLowConfidencePolicy()
        val values = listOf(118f, 118f, 118f, 128f, 148f, 158f, 138f, 118f, 118f)
        fun evaluate(now: Long, reading: Long) = p.evaluate(
            StandardGlucoseAlertCondition(118f, 58f, 70f), -1f,
            reading, now, "sensor", 1, 60, false, false,
            classicIob = 0f, iobNext30 = 0f,
            recentRiseBaselineMgdl = state.recentRiseBaselineMgdl(now)
        )
        values.forEachIndexed { index, value ->
            val time = start + index * 5 * minute
            state.observe(time, value, "sensor", 1, 0, false)
            if (index == 7) assertEquals(Decision.RETURNING_TO_BASELINE, evaluate(time, time))
        }
        val reading = start + 40 * minute
        assertEquals(Decision.RETURNING_TO_BASELINE, evaluate(reading, reading))
        assertEquals(Decision.CONFIRMED, evaluate(reading + 1, reading))
        assertEquals(Decision.CONFIRMED, evaluate(reading + 4 * minute, reading))
    }

    @Test fun equalPeakPlateauDoesNotMoveTheExpiryForward() {
        val state = ForecastLowReturnToBaselineState()
        feed(state, excursion().take(21) + List(5) { 158f } + List(10) { 118f })
        assertNotNull(state.recentRiseBaselineMgdl(start + 35 * minute))
        assertNull(state.recentRiseBaselineMgdl(start + 35 * minute + 1))
    }

    @Test fun replacementSensorOrGenerationCanBuildShapeWithOlderFreshTimestamps() {
        for (generationChange in listOf(false, true)) {
            val state = ForecastLowReturnToBaselineState()
            feed(state, listOf(118f), startIndex = 35)
            // The replacement starts only one minute behind the previous
            // sensor, so its first sample is fresh at handover. Its six-minute
            // quiet segment needs those first samples to establish a baseline.
            feed(state, excursion().drop(5), startIndex = 34,
                sensor = if (generationChange) "sensor" else "replacement",
                generation = if (generationChange) 2 else 1)
            assertEquals(118f, state.recentRiseBaselineMgdl(start + 59 * minute)!!, 0.001f)
        }
    }

    @Test fun sustainedFallWithoutAPrecedingRiseHasNoBaselineExcuse() {
        val state = ForecastLowReturnToBaselineState()
        feed(state, List(11) { 158f } + (1..10).map { 158f - 4f * it })
        assertNull(state.recentRiseBaselineMgdl(start + 30 * minute))
        val p = ForecastLowConfidencePolicy()
        assertFalse(decide(null, p = p).eligible)
        assertEquals(Decision.CONFIRMED, decide(null, glucose = 115f, p = p, seconds = 60))
    }

    @Test fun noisyBaselineAndSmallRiseCannotAuthorizeDeferral() {
        for (values in listOf(
            List(11) { if (it % 2 == 0) 110f else 125f } + (1..10).map { 125f + 4f * it } + listOf(140f),
            List(11) { 118f } + (1..10).map { 118f + it } + listOf(120f))) {
            val state = ForecastLowReturnToBaselineState()
            feed(state, values)
            assertNull(state.recentRiseBaselineMgdl(start + 30 * minute))
        }
    }

    @Test fun tooShortBaselineAndRestartFailOpen() {
        val state = ForecastLowReturnToBaselineState()
        feed(state, excursion().drop(10))
        assertNull(state.recentRiseBaselineMgdl(start + 30 * minute))
        val known = ForecastLowReturnToBaselineState()
        feed(known, excursion())
        known.clear()
        feed(known, listOf(118f), 31)
        assertNull(known.recentRiseBaselineMgdl(start + 31 * minute))
    }

    @Test fun sensorGenerationViewModeAndUnitSwitchesDiscardOldShape() {
        repeat(4) { mode ->
            val state = ForecastLowReturnToBaselineState()
            feed(state, excursion())
            feed(state, listOf(118f), 31, sensor = if (mode == 0) "new-sensor" else "sensor",
                generation = if (mode == 1) 2 else 1, viewMode = if (mode == 2) 1 else 0, mmol = mode == 3)
            assertNull(state.recentRiseBaselineMgdl(start + 31 * minute))
        }
    }

    @Test fun missingIdentityInvalidReadingOrGapDiscardsShape() {
        repeat(3) { mode ->
            val state = ForecastLowReturnToBaselineState()
            feed(state, excursion())
            feed(state, listOf(if (mode == 0) Float.NaN else 118f), if (mode == 1) 37 else 31,
                sensor = if (mode == 2) null else "sensor")
            assertNull(state.recentRiseBaselineMgdl(start + 37 * minute))
        }
    }

    @Test fun mmolAndFiveMinuteSensorsCanEstablishSameBaseline() {
        for (mmol in listOf(false, true)) {
            val state = ForecastLowReturnToBaselineState()
            val values = listOf(118f, 118f, 118f, 138f, 158f, 138f, 118f)
            values.forEachIndexed { index, value ->
                state.observe(start + index * 5 * minute, value / if (mmol) 18.0182f else 1f,
                    "sensor", 1, 0, mmol)
            }
            assertEquals(118f, state.recentRiseBaselineMgdl(start + 30 * minute)!!, 0.001f)
        }
    }
}
