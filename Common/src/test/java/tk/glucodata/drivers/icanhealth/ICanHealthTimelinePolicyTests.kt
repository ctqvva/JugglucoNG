package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timestamp-anchoring and backfill-trigger policy shared by every iCan model.
 *
 * The numbers here come from a real i6 trace on a UTC+3 phone: session start 1785690401000,
 * sequence 6864 read at 1786091442277. Those two only agree if the session start is three hours
 * earlier than the parser produced.
 */
class ICanHealthTimelinePolicyTests {

    private val sequenceUnitMs = 60_000L
    private val toleranceMs = 30 * 60 * 1000L

    // Session start as the old parser produced it: local wall clock read as UTC on a UTC+3 phone.
    private val skewedSessionStartMs = 1785690401000L
    private val correctedSessionStartMs = skewedSessionStartMs - 3 * 60 * 60 * 1000L
    private val observedSequence = 6864
    private val observedAtMs = 1786091442277L

    @Test
    fun sessionTimeline_rejectsTheTimezoneSkewedStartFromTheTrace() {
        assertFalse(
            matches(sessionStartMs = skewedSessionStartMs)
        )
    }

    @Test
    fun sessionTimeline_acceptsTheCorrectedStartFromTheTrace() {
        assertTrue(
            matches(sessionStartMs = correctedSessionStartMs)
        )
    }

    @Test
    fun sessionTimeline_rejectsNegativeOffsetSkewTheOldBoundsWouldHaveAllowed() {
        // A UTC-5 phone drags the start five hours into the past. The driver's per-candidate
        // window allows six hours of past drift, so only this cross-check catches it.
        val westward = correctedSessionStartMs - 5 * 60 * 60 * 1000L

        assertFalse(matches(sessionStartMs = westward))
    }

    @Test
    fun sessionTimeline_toleratesOrdinaryClockDrift() {
        assertTrue(matches(sessionStartMs = correctedSessionStartMs + 20 * 60 * 1000L))
        assertTrue(matches(sessionStartMs = correctedSessionStartMs - 20 * 60 * 1000L))
    }

    @Test
    fun sessionTimeline_rejectsDriftBeyondTolerance() {
        assertFalse(matches(sessionStartMs = correctedSessionStartMs + 31 * 60 * 1000L))
        assertFalse(matches(sessionStartMs = correctedSessionStartMs - 31 * 60 * 1000L))
    }

    @Test
    fun sessionTimeline_isUnjudgeableBeforeAnySequenceIsKnown() {
        assertTrue(matches(sessionStartMs = skewedSessionStartMs, sequence = -1))
    }

    @Test
    fun sessionTimeline_needsASessionStartAndAnObservation() {
        assertFalse(matches(sessionStartMs = 0L))
        assertFalse(matches(sessionStartMs = correctedSessionStartMs, observedAt = 0L))
    }

    @Test
    fun missedReadings_isZeroForConsecutiveReadingsOnEveryCadence() {
        // i3 / i6 / H6 step by three, i7 steps by one.
        assertEquals(0, ICanHealthConstants.missedReadingsBetween(6795, 6798, 3))
        assertEquals(0, ICanHealthConstants.missedReadingsBetween(6795, 6796, 1))
    }

    @Test
    fun missedReadings_countsTheHoleOnAThreeMinuteSensor() {
        assertEquals(1, ICanHealthConstants.missedReadingsBetween(6795, 6801, 3))
        assertEquals(4, ICanHealthConstants.missedReadingsBetween(6795, 6810, 3))
    }

    @Test
    fun missedReadings_countsTheHoleOnAOneMinuteSensor() {
        assertEquals(1, ICanHealthConstants.missedReadingsBetween(100, 102, 1))
        assertEquals(9, ICanHealthConstants.missedReadingsBetween(100, 110, 1))
    }

    @Test
    fun missedReadings_ignoresStalledOrRewoundSequences() {
        assertEquals(0, ICanHealthConstants.missedReadingsBetween(6795, 6795, 3))
        assertEquals(0, ICanHealthConstants.missedReadingsBetween(6795, 6700, 3))
        assertEquals(0, ICanHealthConstants.missedReadingsBetween(-1, 6795, 3))
        assertEquals(0, ICanHealthConstants.missedReadingsBetween(6795, 6801, 0))
    }

    @Test
    fun missedReadings_doesNotCountAPartialStepAsAGap() {
        // A sensor that reports one unit early must not look like a missed reading.
        assertEquals(0, ICanHealthConstants.missedReadingsBetween(6795, 6800, 3))
    }

    private fun matches(
        sessionStartMs: Long,
        sequence: Int = observedSequence,
        observedAt: Long = observedAtMs,
    ): Boolean = ICanHealthConstants.sessionTimelineMatchesSequenceCounter(
        sessionStartEpochMs = sessionStartMs,
        sequenceNumber = sequence,
        sequenceObservedAtMs = observedAt,
        sequenceUnitMs = sequenceUnitMs,
        toleranceMs = toleranceMs,
    )

    @Test
    fun newestLiveReadingOneHourBehindClockIsStale() {
        val now = 1_791_470_457_000L
        assertTrue(
            ICanHealthConstants.isStaleLiveTimestamp(
                sequenceNumber = 17073, currentSequenceNumber = 17070,
                resolvedMs = now - 3_596_000L, nowMs = now, maxLagMs = 600_000L,
            )
        )
    }

    @Test
    fun newestLiveReadingWithinLagWindowIsKept() {
        val now = 1_791_470_457_000L
        assertFalse(
            ICanHealthConstants.isStaleLiveTimestamp(
                sequenceNumber = 17073, currentSequenceNumber = 17070,
                resolvedMs = now - 180_000L, nowMs = now, maxLagMs = 600_000L,
            )
        )
    }

    @Test
    fun delayedOlderSequenceIsNeverTreatedAsStale() {
        val now = 1_791_470_457_000L
        assertFalse(
            ICanHealthConstants.isStaleLiveTimestamp(
                sequenceNumber = 17060, currentSequenceNumber = 17070,
                resolvedMs = now - 3_600_000L, nowMs = now, maxLagMs = 600_000L,
            )
        )
    }

    @Test
    fun edgeAnHourBehindTheCounterReadIsDivergent() {
        val observed = 1_791_470_432_000L
        assertTrue(
            ICanHealthConstants.isAnchorDivergentFromObservedSequence(observed - 3_631_000L, observed, 600_000L)
        )
    }

    @Test
    fun edgeNearTheCounterReadIsNotDivergent() {
        val observed = 1_791_470_432_000L
        assertFalse(
            ICanHealthConstants.isAnchorDivergentFromObservedSequence(observed - 60_000L, observed, 600_000L)
        )
    }

    @Test
    fun missingCounterObservationIsNeverDivergent() {
        assertFalse(ICanHealthConstants.isAnchorDivergentFromObservedSequence(1L, 0L, 600_000L))
    }

    /**
     * Replay of the exact values in juggluco-trace-20261008-175444.log: the counter (seq 17070)
     * read at 17:40:32 and a skewed covered edge reconstructing 16:38:01 for that same sequence.
     * The plausibility window alone let that edge through, so every live reading was stored an
     * hour late (seq 17073 arriving at 17:40:57 was stored at 16:41:01).
     */
    @Test
    fun replay_traceSkewedEdgeIsRejectedByTheCounterRead() {
        val anchor = ICanHealthConstants.persistedCoveredAnchorMs(
            persistedCoveredSequence = traceCoveredSequence,
            persistedCoveredTimestampMs = traceSkewedEdgeMs,
            currentSequenceNumber = traceCounterSequence,
            sequenceUnitMs = sequenceUnitMs,
        )
        assertEquals(traceSkewedEdgeMs, anchor)
        // The trace's failure mode: the edge sits inside the plausibility window.
        assertTrue(anchor in tracePlausibleRange)

        assertFalse(
            ICanHealthConstants.isPersistedCoveredAnchorUsable(
                anchorMs = anchor,
                sequenceObservedAtMs = traceCounterObservedAtMs,
                divergenceToleranceMs = maxLiveLagMs,
                plausibleRange = tracePlausibleRange,
            )
        )
    }

    @Test
    fun replay_traceEdgeThatAgreesWithTheCounterReadIsUsed() {
        val anchor = ICanHealthConstants.persistedCoveredAnchorMs(
            persistedCoveredSequence = traceCoveredSequence - 5,
            persistedCoveredTimestampMs = traceCounterObservedAtMs - 5 * sequenceUnitMs - 31_000L,
            currentSequenceNumber = traceCounterSequence,
            sequenceUnitMs = sequenceUnitMs,
        )
        assertEquals(1_791_470_401_000L, anchor) // 17:40:01

        assertTrue(
            ICanHealthConstants.isPersistedCoveredAnchorUsable(
                anchorMs = anchor,
                sequenceObservedAtMs = traceCounterObservedAtMs,
                divergenceToleranceMs = maxLiveLagMs,
                plausibleRange = tracePlausibleRange,
            )
        )
    }

    @Test
    fun persistedAnchorOutsideThePlausibleRangeIsNotUsedEvenWithoutACounterRead() {
        assertFalse(
            ICanHealthConstants.isPersistedCoveredAnchorUsable(
                anchorMs = tracePlausibleRange.first - 1L,
                sequenceObservedAtMs = 0L,
                divergenceToleranceMs = maxLiveLagMs,
                plausibleRange = tracePlausibleRange,
            )
        )
    }

    /** Second line of defence: had the skewed anchor been used, the live guard restamps it. */
    @Test
    fun replay_traceLiveReadingFromTheSkewedEdgeIsStale() {
        val resolvedLiveMs = traceSkewedEdgeMs + (traceLiveSequence - traceCounterSequence) * sequenceUnitMs
        assertEquals(1_791_466_861_000L, resolvedLiveMs) // 16:41:01, what the trace stored
        assertTrue(
            ICanHealthConstants.isStaleLiveTimestamp(
                sequenceNumber = traceLiveSequence,
                currentSequenceNumber = traceCounterSequence,
                resolvedMs = resolvedLiveMs,
                nowMs = traceLiveArrivalMs,
                maxLagMs = maxLiveLagMs,
            )
        )
    }

    private val maxLiveLagMs = 600_000L
    private val traceCoveredSequence = 17_070
    private val traceCounterSequence = 17_070
    private val traceLiveSequence = 17_073
    private val traceCounterObservedAtMs = 1_791_470_432_000L // 17:40:32
    private val traceLiveArrivalMs = 1_791_470_457_000L       // 17:40:57
    private val traceSkewedEdgeMs = 1_791_466_681_000L        // 16:38:01
    // Days of session behind, a few minutes of future drift ahead: the edge an hour back fits.
    private val tracePlausibleRange = (traceLiveArrivalMs - 14 * 24 * 60 * 60 * 1000L)..(traceLiveArrivalMs + 5 * 60 * 1000L)
}
