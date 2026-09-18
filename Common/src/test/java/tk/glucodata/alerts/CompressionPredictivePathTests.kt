package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.logic.CompressionLowDetector
import tk.glucodata.logic.CompressionLowDetector.Sample
import tk.glucodata.logic.CompressionLowDetector.Tuning
import tk.glucodata.alerts.CompressionTrendHoldState.Decision.*

/** Exercises the production detector/evidence, decision and candidate-consumption seams. */
class CompressionPredictivePathTests {
    private val minute = 60_000L
    private val start = 1_700_000_000_000L
    private val fallingTypes = listOf(AlertType.PRE_LOW, AlertType.FALLING_FAST)
    private fun trace(baseline: Float = 110f, vararg tail: Float): List<Sample> =
        (List(16) { baseline } + tail.toList()).mapIndexed { i, v -> Sample(start + i * minute, v) }

    private class Path {
        val evidence = CompressionTrendEvidence()
        val holds = CompressionTrendHoldState()
    }

    private fun decide(
        path: Path,
        type: AlertType,
        samples: List<Sample>,
        now: Long = samples.last().timestampMillis,
        iob: Float? = 0f,
        suppress: Boolean = false,
        enabled: Boolean = true,
        covered: Boolean = true,
        low: Float? = 70f,
        floor: Boolean = false,
        peakPassed: Boolean = true,
        tuning: Tuning = Tuning.DEFAULT
    ): CompressionTrendHoldState.Decision {
        val latest = samples.last()
        return CompressionTrendPolicy.decide(
            path.holds, type, now, latest.timestampMillis, latest.mgdl,
            enabled, covered, low, floor, suppress, { iob },
            hasEvidence = {
                path.evidence.observe(samples, now, latest.timestampMillis, "sensor", 50f,
                    iob ?: Float.NaN, peakPassed, tuning)
                path.evidence.qualifies(type, "sensor", latest.timestampMillis, latest.mgdl,
                    tuning.recoveryWindowMinutes * minute)
            }
        )
    }

    @Test fun shallowCandidatesEnterThroughDetectorEvidenceAndStayPending() {
        for (type in fallingTypes) {
            val path = Path()
            val samples = trace(110f, 105f, 100f)
            assertNull(CompressionLowDetector.assessOngoing(samples, samples.last().timestampMillis,
                50f, 0f, true))
            val episodes = AlertEpisodeState<AlertType>()
            assertTrue(episodes.update(setOf(type)).shouldTryFire(type))
            val consumed = CompressionTrendPolicy.consume(decide(path, type, samples),
                onHold = { episodes.markPendingDelivery(type) },
                onDrop = { fail("Shallow evidence should first wait") })
            assertTrue(consumed)
            assertTrue(episodes.update(setOf(type)).shouldTryFire(type))
            assertTrue(path.holds.isHolding(type))
        }
    }

    @Test fun shallowEvidenceDoesNotAdmitRisingWarningsOrChangeLowDepthTuning() {
        val samples = trace(110f, 105f, 100f)
        val path = Path()
        assertEquals(HOLD, decide(path, AlertType.PRE_LOW, samples, tuning = Tuning(minDropDepthMgdl = 60f)))
        assertFalse(path.evidence.qualifies(AlertType.PRE_HIGH, "sensor", samples.last().timestampMillis,
            105f, 45 * minute))
        assertEquals(25f, Tuning.DEFAULT.minDropDepthMgdl, 0f)
    }

    @Test fun zeroIobIsNotBlanketSuppressionWithoutOptIn() {
        for (type in fallingTypes) assertEquals(ALLOW, decide(Path(), type, trace(), suppress = false))
    }

    @Test fun explicitOptInConsumesBothFamiliesEvenWithoutCompressionEvidence() {
        for (type in fallingTypes) for (iob in listOf(0f, 0.00001f, CompressionTrendPolicy.ZERO_IOB_EPSILON_UNITS)) {
            val episodes = AlertEpisodeState<AlertType>()
            assertTrue(episodes.update(setOf(type)).shouldTryFire(type))
            var held = false
            assertTrue(CompressionTrendPolicy.consume(decide(Path(), type, trace(), iob = iob, suppress = true),
                onHold = { held = true }, onDrop = { episodes.clearPending(type) }))
            assertFalse(held)
            assertFalse(episodes.update(setOf(type)).shouldTryFire(type))
            episodes.update(emptySet())
            assertTrue(episodes.update(setOf(type)).shouldTryFire(type))
        }
    }

    @Test fun nonzeroAndUnknownIobDoNotBlanketSuppress() {
        for (type in fallingTypes) for (iob in listOf(null, Float.NaN, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, -0.00001f, 0.00011f, 0.1f, 2f)) {
            assertEquals("$type / $iob", ALLOW, decide(Path(), type, trace(), iob = iob, suppress = true))
        }
    }

    @Test fun featureAndSelectionRemainRequiredAndThresholdSafetyWins() {
        for (type in fallingTypes) {
            assertEquals(ALLOW, decide(Path(), type, trace(), suppress = true, enabled = false))
            assertEquals(ALLOW, decide(Path(), type, trace(), suppress = true, covered = false))
            assertEquals(ALLOW, decide(Path(), type, trace(), suppress = true, floor = true))
            for (low in listOf(null, Float.NaN, 0f, 110f, 120f)) {
                assertEquals(ALLOW, decide(Path(), type, trace(), suppress = true, low = low))
            }
        }
        for (type in listOf(AlertType.LOW, AlertType.VERY_LOW, AlertType.PRE_HIGH, AlertType.RISING_FAST)) {
            assertEquals(ALLOW, decide(Path(), type, trace(), suppress = true))
        }
    }

    @Test fun unavailableIobAndEvidenceExceptionsFailOpen() {
        assertEquals(ALLOW, CompressionTrendPolicy.decide(CompressionTrendHoldState(), AlertType.PRE_LOW,
            start, start, 100f, true, true, 70f, false, true,
            iobUnits = { error("IOB unavailable") }, hasEvidence = { error("history unavailable") }))
    }

    @Test fun predictiveWaitExpiresAndCannotRestartOnTheSameEpisode() {
        for (type in fallingTypes) {
            val path = Path()
            val samples = trace(110f, 105f, 100f)
            val at = samples.last().timestampMillis
            assertEquals(HOLD, decide(path, type, samples))
            assertEquals(ALLOW, decide(path, type, samples, now = at + 6 * minute))
            assertEquals(ALLOW, decide(path, type, samples, now = at + 7 * minute))
        }
    }

    @Test fun predictiveRecoveryConsumesPendingCandidateThroughProductionRoute() {
        val path = Path()
        val type = AlertType.PRE_LOW
        val episodes = AlertEpisodeState<AlertType>()
        episodes.update(setOf(type))
        val samples = trace(110f, 105f, 100f)
        assertTrue(CompressionTrendPolicy.consume(decide(path, type, samples),
            { episodes.markPendingDelivery(type) }, { fail("Not recovered yet") }))
        val recovered = samples + Sample(samples.last().timestampMillis + 6 * minute, 104f)
        assertTrue(CompressionTrendPolicy.consume(decide(path, type, recovered),
            { fail("Wait must end") }, { episodes.clearPending(type) }))
        assertFalse(episodes.update(setOf(type)).shouldTryFire(type))
    }

    @Test fun shallowSuspicionRetainsBaselineInsulinAndPeakChecks() {
        for (type in fallingTypes) {
            assertEquals(ALLOW, decide(Path(), type, trace(110f, 105f, 100f), iob = 1f))
            assertEquals(ALLOW, decide(Path(), type, trace(110f, 105f, 100f), iob = null))
            assertEquals(ALLOW, decide(Path(), type, trace(110f, 105f, 100f), peakPassed = false))
            assertEquals(ALLOW, decide(Path(), type, trace(110f, 105f, 100f).takeLast(3)))
            val stale = trace(110f, 105f, 100f)
            assertEquals(ALLOW, decide(Path(), type, stale, now = stale.last().timestampMillis + 7 * minute))
        }
    }

    @Test fun actualLowUsesFullDetectorAfterPredictiveSuppressionAndEscalatesWithoutUpturn() {
        val path = Path()
        assertEquals(DROP, decide(path, AlertType.PRE_LOW, trace(), suppress = true))
        val shallowLow = trace(80f, 75f, 68f)
        assertEquals(ALLOW, decide(path, AlertType.FALLING_FAST, shallowLow, suppress = true))
        assertNull(CompressionLowDetector.assessOngoing(shallowLow, shallowLow.last().timestampMillis,
            50f, 0f, true))
        val low = trace(110f, 100f, 85f, 68f)
        val at = low.last().timestampMillis
        val suspect = CompressionLowDetector.assessOngoing(low, at, 50f, 0f, true)
        assertNotNull(suspect)
        val hold = CompressionHoldState()
        assertEquals(CompressionHoldState.Action.StartHold,
            hold.onLowActive(at, 68f, 55f, 10 * minute, suspect != null))
        assertEquals(CompressionHoldState.Action.Escalate("no-upturn"),
            hold.onLowActive(at + 6 * minute, 68f, 55f, 10 * minute, false))
    }

    @Test fun genuineLowUpturnContinuesAndResolvesButFloorAndTimeoutEscalate() {
        val low = trace(110f, 100f, 85f, 64f)
        val at = low.last().timestampMillis
        val suspect = CompressionLowDetector.assessOngoing(low, at, 50f, 0f, true) != null
        fun holding() = CompressionHoldState().also {
            assertEquals(CompressionHoldState.Action.StartHold,
                it.onLowActive(at, 64f, 55f, 10 * minute, suspect))
        }
        val recovering = holding()
        assertEquals(CompressionHoldState.Action.ContinueHold,
            recovering.onLowActive(at + 6 * minute, 68f, 55f, 10 * minute, false))
        assertTrue(recovering.onLowCleared(at + 7 * minute) is CompressionHoldState.Action.Resolved)
        assertEquals(CompressionHoldState.Action.Escalate("hard-floor"),
            holding().onLowActive(at + minute, 55f, 55f, 10 * minute, false))
        assertEquals(CompressionHoldState.Action.Escalate("hold-expired"),
            holding().onLowActive(at + 10 * minute, 68f, 55f, 10 * minute, false))
    }

    @Test fun deltaConsumptionDoesNotRearmUntilRunBreaks() {
        val delta = DeltaAlarmState(falling = true)
        fun candidate(at: Long, value: Float) = delta.shouldTrigger(true, true, false, value,
            at, 5f, 1, 120f, 1)
        assertFalse(candidate(start, 110f))
        assertTrue(candidate(start + minute, 100f))
        assertTrue(CompressionTrendPolicy.consume(decide(Path(), AlertType.FALLING_FAST, trace(), suppress = true),
            { delta.rearmAfterFailedDelivery() }, {}))
        assertFalse(candidate(start + minute, 100f))
        assertFalse(candidate(start + 2 * minute, 106f))
        assertTrue(candidate(start + 3 * minute, 96f))
    }
}
