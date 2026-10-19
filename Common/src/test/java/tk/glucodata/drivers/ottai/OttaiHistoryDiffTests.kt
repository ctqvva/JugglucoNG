package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect backfill diffs what the sensor holds against what is already stored and asks
 * only for the difference. Before this, one permanently-missing early record re-requested the
 * sensor's entire history on every reconnect — on device that was 4171 records and ~24s of
 * uninterrupted GATT traffic, repeatedly, during the very link trouble that caused the reconnect.
 */
class OttaiHistoryDiffTests {

    private fun present(size: Int, vararg missing: IntRange): BooleanArray {
        val flags = BooleanArray(size) { true }
        missing.forEach { range -> range.forEach { flags[it] = false } }
        return flags
    }

    private fun ranges(vararg pairs: Pair<Int, Int>) =
        pairs.map { OttaiBleManager.MissingRange(it.first, it.second) }

    @Test
    fun deferredDiffIsRetriedEvenAfterLiveProgressEstablishesAPreviousDataNo() {
        assertTrue(OttaiBleManager.shouldDiffStoredHistory(previousDataNo = 500, diffRetryPending = true))
    }

    @Test
    fun usablePreviousDataNoKeepsTheCheapIncrementalPath() {
        assertFalse(OttaiBleManager.shouldDiffStoredHistory(previousDataNo = 500, diffRetryPending = false))
    }

    @Test
    fun unusablePreviousDataNoRequiresAStoredHistoryDiff() {
        assertTrue(OttaiBleManager.shouldDiffStoredHistory(previousDataNo = -1, diffRetryPending = false))
    }

    @Test
    fun noGapsWhenEverythingIsStored() {
        assertEquals(emptyList<OttaiBleManager.MissingRange>(), OttaiBleManager.missingRanges(present(500)))
    }

    @Test
    fun findsEachGapSeparately() {
        assertEquals(
            ranges(10 to 13, 400 to 402),
            OttaiBleManager.missingRanges(present(500, 10..12, 400..401))
        )
    }

    @Test
    fun anEarlyGapDoesNotDragInEverythingAfterIt() {
        // The regression: dataNo 1 and 2 were rejected by the continuity filter at activation and
        // are never stored, so they are missing forever. Asking for "first gap .. newest" turned
        // that into a full-history download on every single reconnect.
        val gaps = OttaiBleManager.missingRanges(present(4171, 1..2))
        assertEquals(ranges(1 to 3), gaps)
        assertEquals(2, gaps.sumOf { it.endExclusive - it.start })
    }

    @Test
    fun onlyTheTailIsFetchedAfterAnOfflineStretch() {
        assertEquals(ranges(4100 to 4171), OttaiBleManager.missingRanges(present(4171, 4100..4170)))
    }

    @Test
    fun mergesGapsSeparatedByOnlyAFewStoredRecords() {
        // [10,13) and [16,19) are 3 records apart: one request for [10,19) beats two round trips.
        assertEquals(ranges(10 to 19), OttaiBleManager.missingRanges(present(500, 10..12, 16..18)))
    }

    @Test
    fun keepsDistantGapsApart() {
        assertEquals(
            ranges(10 to 13, 300 to 303),
            OttaiBleManager.missingRanges(present(500, 10..12, 300..302))
        )
    }

    @Test
    fun capsTheWindowCountByMergingTheClosestPairsFirst() {
        // Ten scattered gaps, capped at three windows.
        val missing = (0 until 10).map { (it * 40)..(it * 40) }.toTypedArray()
        val gaps = OttaiBleManager.missingRanges(present(500, *missing), maxRanges = 3)
        assertEquals(3, gaps.size)
        // Capping trades redundancy for window count; it must never drop a missing record.
        val covered = gaps.flatMap { it.start until it.endExclusive }.toSet()
        (0 until 10).forEach { assertTrue("record ${it * 40} uncovered", (it * 40) in covered) }
    }

    @Test
    fun mergingOnlyEverWidensWindows() {
        // Whatever the coalescing does, every missing index stays inside some returned window
        // and the windows stay ordered and disjoint.
        val flags = present(1000, 3..5, 7..7, 200..260, 262..262, 900..999)
        val gaps = OttaiBleManager.missingRanges(flags)
        flags.indices.filter { !flags[it] }.forEach { index ->
            assertTrue("index $index uncovered", gaps.any { index >= it.start && index < it.endExclusive })
        }
        gaps.zipWithNext().forEach { (left, right) ->
            assertTrue("windows overlap or are unordered", left.endExclusive < right.start)
        }
    }

    @Test
    fun handlesTheDegenerateInputs() {
        assertEquals(emptyList<OttaiBleManager.MissingRange>(), OttaiBleManager.missingRanges(BooleanArray(0)))
        assertEquals(ranges(0 to 3), OttaiBleManager.missingRanges(BooleanArray(3)))
    }

    @Test
    fun anImpossibleCapStillReportsTheMissingRecord() {
        // Empty is the caller's proof that nothing is missing, and it latches "history complete"
        // for the connection. A cap that cannot be satisfied must never borrow that answer.
        assertEquals(ranges(5 to 6), OttaiBleManager.missingRanges(present(100, 5..5), maxRanges = 0))
        assertEquals(ranges(5 to 6), OttaiBleManager.missingRanges(present(100, 5..5), maxRanges = -3))
    }

    @Test
    fun emptyOnlyEverMeansNothingIsMissing() {
        // The one input that may produce an empty result.
        assertTrue(OttaiBleManager.missingRanges(present(200)).isEmpty())
        // Any genuinely-missing record must survive every combination of the tuning knobs.
        for (cap in intArrayOf(-1, 0, 1, 2, 8, 1000)) {
            for (coalesce in intArrayOf(-1, 0, 1, 5, 10_000)) {
                val gaps = OttaiBleManager.missingRanges(present(200, 7..7, 150..151), coalesce, cap)
                val covered = gaps.flatMap { it.start until it.endExclusive }.toSet()
                assertTrue("cap=$cap coalesce=$coalesce lost record 7", 7 in covered)
                assertTrue("cap=$cap coalesce=$coalesce lost record 150", 150 in covered)
                assertTrue("cap=$cap coalesce=$coalesce lost record 151", 151 in covered)
            }
        }
    }
}
