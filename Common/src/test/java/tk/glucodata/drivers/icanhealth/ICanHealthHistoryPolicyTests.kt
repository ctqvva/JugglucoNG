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
}
