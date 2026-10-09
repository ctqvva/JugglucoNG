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
}
