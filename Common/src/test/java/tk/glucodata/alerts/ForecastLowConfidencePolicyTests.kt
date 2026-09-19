package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.alerts.ForecastLowConfidencePolicy.Decision

class ForecastLowConfidencePolicyTests {
    @Test fun forecastEvidenceIsOptInInBothUnits() {
        for (mmol in listOf(false, true)) {
            assertFalse(AlertDefaults.defaultConfig(AlertType.PRE_LOW, mmol).preLowEvidenceEnabled)
        }
    }

    private val start = 1_000_000L

    private fun tick(
        policy: ForecastLowConfidencePolicy,
        seconds: Long = 0,
        glucose: Float = 118f,
        rate: Float = -2.5f,
        iob: Float = 0f,
        sensitivity: Float = 50f,
        threshold: Float = 70f,
        horizon: Int = 20,
        mmol: Boolean = false,
        sensor: String? = "sensor-a",
        generation: Int = 1,
        active: Boolean = false,
        ageMs: Long = 0
    ): Decision {
        val scale = if (mmol) 18.0182f else 1f
        val value = glucose / scale
        return policy.evaluate(
            StandardGlucoseAlertCondition(value,
                AlertGlucoseMath.projectedDisplayValue(value, rate, horizon, mmol), threshold / scale),
            rate, start + seconds * 1000, start + seconds * 1000 + ageMs,
            sensor, generation, horizon, mmol, active, iob, sensitivity
        )
    }

    @Test fun transientDipAndReversalNeverBecomeEligible() {
        val p = ForecastLowConfidencePolicy()
        assertEquals(Decision.CONFIRMING, tick(p))
        assertEquals(Decision.NO_CANDIDATE, tick(p, 60, glucose = 120f, rate = 1f))
        assertEquals(Decision.CONFIRMING, tick(p, 120))
    }

    @Test fun persistentFallWithZeroIobConfirmsOnSecondMinuteReading() {
        val p = ForecastLowConfidencePolicy()
        assertFalse(tick(p).eligible)
        assertEquals(Decision.CONFIRMED, tick(p, 60, glucose = 115f))
    }

    @Test fun fiveMinuteCadenceConfirmsOnNextReading() {
        val p = ForecastLowConfidencePolicy()
        assertFalse(tick(p).eligible)
        assertEquals(Decision.CONFIRMED, tick(p, 300, glucose = 106f))
    }

    @Test fun fastCadenceMustSpanOneMinute() {
        val p = ForecastLowConfidencePolicy()
        assertFalse(tick(p).eligible)
        for (seconds in 5L..55L step 5) assertFalse(tick(p, seconds, glucose = 117f).eligible)
        assertEquals(Decision.CONFIRMED, tick(p, 60, glucose = 116f))
    }

    @Test fun schedulerTicksCannotConfirmOrRefreshOldReading() {
        val p = ForecastLowConfidencePolicy()
        assertFalse(tick(p).eligible)
        repeat(20) { assertEquals(Decision.CONFIRMING, tick(p, ageMs = 120_000)) }
        assertEquals(Decision.NO_CANDIDATE, tick(p, ageMs = 360_001))
        assertEquals(Decision.CONFIRMING, tick(p, 400))
    }

    @Test fun measuredReboundResetsEvenWhenSmoothedRateStillFalls() {
        val p = ForecastLowConfidencePolicy()
        tick(p)
        assertEquals(Decision.CONFIRMING, tick(p, 60, glucose = 119f, rate = -3f))
        assertEquals(Decision.CONFIRMED, tick(p, 120, glucose = 116f))
    }

    @Test fun forecastDisappearingResetsConfirmation() {
        val p = ForecastLowConfidencePolicy()
        tick(p)
        assertEquals(Decision.NO_CANDIDATE, tick(p, 60, rate = -1f))
        assertEquals(Decision.CONFIRMING, tick(p, 120))
    }

    @Test fun missingConditionResetsConfirmation() {
        val p = ForecastLowConfidencePolicy()
        tick(p)
        assertEquals(Decision.NO_CANDIDATE,
            p.evaluate(null, -3f, start + 60_000, start + 60_000, "sensor-a", 1, 20, false, false))
        assertEquals(Decision.CONFIRMING, tick(p, 120))
    }

    @Test fun calibrationResetCannotReuseTheSameSample() {
        val p = ForecastLowConfidencePolicy()
        tick(p)
        p.reset()
        assertFalse(tick(p, glucose = 116f).eligible)
        assertEquals(Decision.CONFIRMING, tick(p, 60, glucose = 115f))
        assertEquals(Decision.CONFIRMED, tick(p, 120, glucose = 112f))
    }

    @Test fun sensorAndGenerationChangesRestartConfirmation() {
        val p = ForecastLowConfidencePolicy()
        tick(p)
        assertEquals(Decision.CONFIRMING, tick(p, 60, sensor = "sensor-b"))
        assertEquals(Decision.CONFIRMING, tick(p, 120, sensor = "sensor-b", generation = 2))
        assertEquals(Decision.CONFIRMED, tick(p, 180, sensor = "sensor-b", generation = 2, glucose = 115f))
    }

    @Test fun thresholdHorizonAndUnitChangesRestartConfirmation() {
        val p = ForecastLowConfidencePolicy()
        tick(p)
        assertEquals(Decision.CONFIRMING, tick(p, 60, threshold = 72f))
        assertEquals(Decision.CONFIRMING, tick(p, 120, threshold = 72f, horizon = 25))
        assertEquals(Decision.CONFIRMING, tick(p, 180, threshold = 72f, horizon = 25, mmol = true))
    }

    @Test fun gapAndOutOfOrderSamplesCannotCompleteARun() {
        val p = ForecastLowConfidencePolicy()
        tick(p)
        assertEquals(Decision.CONFIRMING, tick(p, 361))
        assertEquals(Decision.NO_CANDIDATE, tick(p, 360))
        assertEquals(Decision.CONFIRMING, tick(p, 421))
        assertEquals(Decision.CONFIRMED, tick(p, 481, glucose = 115f))
    }

    @Test fun meaningfulNearTermInsulinAllowsFirstCandidate() {
        assertEquals(Decision.INSULIN_SUPPORTED, tick(ForecastLowConfidencePolicy(), glucose = 120f, rate = -3f, iob = 0.5f))
        assertEquals(Decision.CONFIRMING, tick(ForecastLowConfidencePolicy(), glucose = 120f, rate = -3f, iob = 0.49f))
        assertEquals(Decision.INSULIN_SUPPORTED, tick(ForecastLowConfidencePolicy(), glucose = 120f, rate = -3f, iob = 0.25f, sensitivity = 100f))
    }

    @Test fun missingOrInvalidInsulinStillUsesGlucoseConfirmation() {
        for (iob in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f)) {
            val p = ForecastLowConfidencePolicy()
            assertEquals(Decision.CONFIRMING, tick(p, iob = iob))
            assertEquals(Decision.CONFIRMED, tick(p, 60, glucose = 115f, iob = iob))
        }
    }

    @Test fun absentConfiguredSensitivityDoesNotInventInsulinEvidence() {
        for (isf in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            val p = ForecastLowConfidencePolicy()
            assertEquals(Decision.CONFIRMING, tick(p, iob = 5f, sensitivity = isf))
            assertEquals(Decision.CONFIRMED, tick(p, 60, glucose = 115f, iob = 5f, sensitivity = isf))
        }
    }

    @Test fun nearThresholdAndImminentCrossingBypassConfirmation() {
        assertEquals(Decision.NEAR_THRESHOLD, tick(ForecastLowConfidencePolicy(), glucose = 85f, rate = -0.8f))
        assertEquals(Decision.CONFIRMING, tick(ForecastLowConfidencePolicy(), glucose = 85.1f, rate = -0.8f))
        assertEquals(Decision.IMMINENT, tick(ForecastLowConfidencePolicy(), glucose = 100f, rate = -3f))
        assertEquals(Decision.CONFIRMING, tick(ForecastLowConfidencePolicy(), glucose = 100.1f, rate = -3f))
    }

    @Test fun urgentRiskCanAppearDuringConfirmation() {
        val p = ForecastLowConfidencePolicy()
        tick(p)
        assertEquals(Decision.IMMINENT, tick(p, 30, glucose = 115f, rate = -5f))
    }

    @Test fun mmolUsesTheSameEvidenceBoundaries() {
        assertEquals(Decision.CONFIRMING, tick(ForecastLowConfidencePolicy(), mmol = true))
        assertEquals(Decision.NEAR_THRESHOLD, tick(ForecastLowConfidencePolicy(), glucose = 84f, mmol = true))
        assertEquals(Decision.IMMINENT, tick(ForecastLowConfidencePolicy(), glucose = 99f, rate = -3f, mmol = true))
        assertEquals(Decision.INSULIN_SUPPORTED, tick(ForecastLowConfidencePolicy(), iob = 0.5f, mmol = true))
        val p = ForecastLowConfidencePolicy()
        tick(p, mmol = true)
        assertEquals(Decision.CONFIRMED, tick(p, 60, glucose = 115f, mmol = true))
    }

    @Test fun activeEpisodeSurvivalBelongsToExistingEvaluator() {
        assertEquals(Decision.EXISTING_EPISODE,
            tick(ForecastLowConfidencePolicy(), glucose = 86f, rate = -0.6f, active = true))
    }

    @Test fun staleOrFutureReadingCannotCreateEvenUrgentEpisode() {
        assertEquals(Decision.NO_CANDIDATE,
            tick(ForecastLowConfidencePolicy(), glucose = 80f, ageMs = 360_001))
        assertEquals(Decision.NO_CANDIDATE,
            tick(ForecastLowConfidencePolicy(), glucose = 80f, ageMs = -1))
    }

    @Test fun invalidGlucoseRateOrThresholdNeverBuildsConfirmation() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(Decision.NO_CANDIDATE, tick(ForecastLowConfidencePolicy(), glucose = bad))
            assertEquals(Decision.NO_CANDIDATE, tick(ForecastLowConfidencePolicy(), rate = bad))
            assertEquals(Decision.NO_CANDIDATE, tick(ForecastLowConfidencePolicy(), threshold = bad))
        }
        assertEquals(Decision.NO_CANDIDATE, tick(ForecastLowConfidencePolicy(), threshold = 0f))
    }

    /** Exercise the real forecast and episode policies around the new entry gate. */
    private class Harness {
        val policy = ForecastLowConfidencePolicy()
        val episodes = AlertEpisodeState<AlertType>()
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(AlertType.PRE_LOW, enabled = true, threshold = 70f, forecastMinutes = 20),
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 70f),
            AlertType.VERY_LOW to AlertConfig(AlertType.VERY_LOW, enabled = true, threshold = 55f)
        )
        fun tick(seconds: Long, glucose: Float, rate: Float): AlertEpisodeTransition<AlertType> {
            val conditions = StandardGlucoseAlertEvaluator.resolveActive(glucose, rate, configs,
                configs.keys, false, { true }, episodes::isActive)
            val decision = policy.evaluate(conditions[AlertType.PRE_LOW], rate,
                1_000_000L + seconds * 1000, 1_000_000L + seconds * 1000,
                "sensor-a", 1, 20, false, episodes.isActive(AlertType.PRE_LOW))
            val eligible = if (decision.eligible) conditions else conditions - AlertType.PRE_LOW
            return episodes.update(eligible.keys)
        }
    }

    @Test fun confirmationPrecedesEpisodeEntryAndDoesNotConsumeFirstDelivery() {
        val h = Harness()
        assertTrue(h.tick(0, 118f, -2.5f).entered.isEmpty())
        assertFalse(h.episodes.isActive(AlertType.PRE_LOW))
        assertTrue(h.tick(60, 115f, -2.5f).shouldTryFire(AlertType.PRE_LOW))
    }

    @Test fun eligibleNearLowEpisodeSurvivesProjectionJitterAndKeepsPendingDelivery() {
        val h = Harness()
        assertTrue(h.tick(0, 84f, -1f).shouldTryFire(AlertType.PRE_LOW))
        h.episodes.markPendingDelivery(AlertType.PRE_LOW)
        val jitter = h.tick(60, 83f, -0.6f) // Projected 71, existing episode remains active.
        assertTrue(jitter.cleared.isEmpty())
        assertTrue(jitter.shouldTryFire(AlertType.PRE_LOW))
        h.episodes.clearPending(AlertType.PRE_LOW)
        assertFalse(h.tick(120, 82f, -0.6f).shouldTryFire(AlertType.PRE_LOW))
    }

    @Test fun hardLowAndVeryLowRemainImmediateDuringUnconfirmedForecast() {
        val h = Harness()
        h.tick(0, 118f, -2.5f)
        assertTrue(h.tick(60, 65f, -3f).shouldTryFire(AlertType.LOW))
        assertTrue(h.tick(120, 50f, -3f).shouldTryFire(AlertType.VERY_LOW))
    }
}
