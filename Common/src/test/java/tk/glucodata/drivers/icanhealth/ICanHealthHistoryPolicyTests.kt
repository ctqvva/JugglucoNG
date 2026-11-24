package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthHistoryPolicyTests {

    @Test
    fun liveGap_tracksThreeMissingI3ReadingsWithoutIncludingTheNewLiveEdge() {
        val gap = ICanHealthHistoryPolicy.liveGapBetween(300, 312, 3)!!

        assertEquals(303, gap.startSequence)
        assertEquals(312, gap.endSequenceExclusive)
        assertTrue(gap.contains(303))
        assertTrue(gap.contains(306))
        assertTrue(gap.contains(309))
        assertFalse(gap.contains(304))
        assertFalse(gap.contains(312))
    }

    @Test
    fun automaticStart_prefersInteriorGapAfterLiveTailHasAlreadyAdvanced() {
        val gap = ICanHealthHistoryPolicy.liveGapBetween(300, 312, 3)

        assertEquals(
            303,
            ICanHealthHistoryPolicy.automaticGlucoseHistoryStartSequence(
                readingIntervalMinutes = 3,
                currentSequence = 312,
                latestKnownSequence = 312,
                pendingLiveGap = gap,
            )
        )
    }

    @Test
    fun automaticStart_withoutGapStillSkipsAnAlreadyCoveredTail() {
        assertNull(
            ICanHealthHistoryPolicy.automaticGlucoseHistoryStartSequence(
                readingIntervalMinutes = 3,
                currentSequence = 312,
                latestKnownSequence = 312,
                pendingLiveGap = null,
            )
        )
    }

    @Test
    fun overlap_allowsOnlyCadenceAlignedRecordsInsidePendingGap() {
        val gap = ICanHealthHistoryPolicy.liveGapBetween(300, 312, 3)
        val skip: (Int) -> Boolean = { sequence ->
            ICanHealthHistoryPolicy.shouldSkipHistoryOverlap(
                sequenceNumber = sequence,
                sampleTimeMs = 900_000L,
                coveredSequence = 312,
                coveredTimestampMs = 1_000_000L,
                pendingLiveGap = gap,
                ignoreCoveredSequence = false,
            )
        }

        assertFalse(skip(303))
        assertFalse(skip(306))
        assertFalse(skip(309))
        assertTrue(skip(300))
        assertTrue(skip(304))
        assertTrue(skip(312))
    }

    @Test
    fun gap_isCompleteOnlyWhenEveryExpectedReadingWasStored() {
        val gap = ICanHealthHistoryPolicy.liveGapBetween(300, 312, 3)!!

        assertFalse(gap.isFullyCoveredBy(setOf(303, 309)))
        assertTrue(gap.isFullyCoveredBy(setOf(303, 306, 309)))
    }

    @Test
    fun pendingRepairWindow_mergesASecondGapSoNeitherHoleIsForgotten() {
        val first = ICanHealthHistoryPolicy.liveGapBetween(300, 312, 3)!!
        val second = ICanHealthHistoryPolicy.liveGapBetween(318, 327, 3)!!

        val merged = first.mergedWith(second)

        assertEquals(303, merged.startSequence)
        assertEquals(327, merged.endSequenceExclusive)
        assertTrue(merged.contains(306))
        assertTrue(merged.contains(321))
        assertTrue(merged.contains(324))
    }

    @Test
    fun tailRetry_sendsAFarBehindRequestToTheLiveEdge() {
        // The shape every refused read has in the logs: both command families fail while the
        // request sits days behind the sensor's own end index.
        assertEquals(
            3555,
            ICanHealthHistoryPolicy.tailRetryStartSequence(
                readingIntervalMinutes = 3,
                currentSequence = 3558,
                requestedSequence = 3,
            )
        )
    }

    @Test
    fun tailRetry_isSkippedWhenTheFailedRequestWasAlreadyAtTheTail() {
        assertNull(
            ICanHealthHistoryPolicy.tailRetryStartSequence(
                readingIntervalMinutes = 3,
                currentSequence = 300,
                requestedSequence = 297,
            )
        )
        assertNull(
            ICanHealthHistoryPolicy.tailRetryStartSequence(
                readingIntervalMinutes = 3,
                currentSequence = 300,
                requestedSequence = 300,
            )
        )
    }

    @Test
    fun tailRetry_needsTheFailedRequestToBeMoreThanOneIntervalBehindTheTail() {
        // liveEdge = 300 - 3 = 297; within one interval of it (296) is not worth retrying ...
        assertNull(
            ICanHealthHistoryPolicy.tailRetryStartSequence(
                readingIntervalMinutes = 3,
                currentSequence = 300,
                requestedSequence = 296,
            )
        )
        // ... but a request more than one interval behind it is.
        assertEquals(
            297,
            ICanHealthHistoryPolicy.tailRetryStartSequence(
                readingIntervalMinutes = 3,
                currentSequence = 300,
                requestedSequence = 290,
            )
        )
    }

    @Test
    fun tailRetry_isSkippedWithoutAKnownLiveEdgeOrInterval() {
        assertNull(ICanHealthHistoryPolicy.tailRetryStartSequence(3, -1, 3))
        assertNull(ICanHealthHistoryPolicy.tailRetryStartSequence(3, 0, 3))
        assertNull(ICanHealthHistoryPolicy.tailRetryStartSequence(3, 3, 3))
        assertNull(ICanHealthHistoryPolicy.tailRetryStartSequence(0, 300, 3))
    }

    @Test
    fun batchOfAlreadyStoredRecords_doesNotDisableHistory() {
        // #742: the sensor answered with readings the live lane had already stored. They decoded
        // fine, so the next live gap must still be repaired from history.
        assertFalse(ICanHealthHistoryPolicy.glucoseBatchShowsUnsupportedHistory(0, 0, 3))
        assertFalse(ICanHealthHistoryPolicy.glucoseBatchShowsUnsupportedHistory(0, 2, 0))
        assertFalse(ICanHealthHistoryPolicy.glucoseBatchShowsUnsupportedHistory(1, 0, 0))
    }

    @Test
    fun batchWithNothingUsable_stillDisablesHistory() {
        // Every record failed to decode to a valid reading (wrong key, garbage fields).
        assertTrue(ICanHealthHistoryPolicy.glucoseBatchShowsUnsupportedHistory(0, 0, 0))
    }

    private fun slots(slots: Iterable<Int>, intervalMs: Long) = slots.map { 1_000_000L + it * intervalMs }.toLongArray()

    @Test
    fun timezoneRepair_coversOneContiguousBatchExactly() {
        for (intervalMs in listOf(60_000L, 180_000L)) {
            val batch = slots(0..99, intervalMs)
            assertEquals(
                listOf(batch.first()..batch.last()),
                ICanHealthHistoryPolicy.timezoneRepairIntervals(batch, intervalMs),
            )
        }
    }

    @Test
    fun timezoneRepair_leavesOutReadingsTheBatchSkipped() {
        val batch = slots((0..39).filter { it !in listOf(10, 20, 21) }, 180_000L)
        assertEquals(
            listOf(0..9, 11..19, 22..39).map { (1_000_000L + it.first * 180_000L)..(1_000_000L + it.last * 180_000L) },
            ICanHealthHistoryPolicy.timezoneRepairIntervals(batch, 180_000L),
        )
    }

    @Test
    fun timezoneRepair_toleratesAnchorJitter() {
        val batch = slots(0..39, 180_000L).mapIndexed { i, t -> if (i % 2 == 0) t else t + 45_000L }.toLongArray()
        assertEquals(1, ICanHealthHistoryPolicy.timezoneRepairIntervals(batch, 180_000L).size)
    }

    @Test
    fun timezoneRepair_needsASessionSizedBatch() {
        assertTrue(ICanHealthHistoryPolicy.timezoneRepairIntervals(LongArray(0), 180_000L).isEmpty())
        assertTrue(ICanHealthHistoryPolicy.timezoneRepairIntervals(slots(0..18, 360_000L), 360_000L).isEmpty())
        assertTrue(ICanHealthHistoryPolicy.timezoneRepairIntervals(slots(0..58, 60_000L), 60_000L).isEmpty())
    }
}
