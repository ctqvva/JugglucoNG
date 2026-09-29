package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.ottai.OttaiBleManager.HistoryHole

/**
 * The history hole ledger: requested-but-undelivered windows, persisted per sensor. It is the
 * only thing that recovers history lost across disconnects and restarts, so its three list
 * operations are held here: add (at request time), trim (only when data arrives) and fail (the
 * cross-session attempt cap).
 */
class OttaiHistoryHoleLedgerTests {

    private fun hole(start: Int, endExclusive: Int, attempts: Int = 0) = HistoryHole(start, endExclusive, attempts)

    private fun fullLedger(): MutableList<HistoryHole> =
        (0 until OttaiBleManager.MAX_HISTORY_HOLES).map { hole(it * 2, it * 2 + 1) }.toMutableList()

    @Test
    fun aWindowAnExistingHoleCoversIsNotAddedAgain() {
        val holes = mutableListOf(hole(0, 540))
        assertNull(OttaiBleManager.ledgerAdd(holes, 100, 200))
        assertNull(OttaiBleManager.ledgerAdd(holes, 0, 540))
        assertEquals(listOf(hole(0, 540)), holes)
    }

    @Test
    fun addedHolesStaySortedByStart() {
        val holes = mutableListOf(hole(300, 400))
        assertEquals(emptyList<HistoryHole>(), OttaiBleManager.ledgerAdd(holes, 0, 100))
        // Overlapping without being covered is still a window of its own.
        assertEquals(emptyList<HistoryHole>(), OttaiBleManager.ledgerAdd(holes, 350, 450))
        assertEquals(listOf(hole(0, 100), hole(300, 400), hole(350, 450)), holes)
    }

    @Test
    fun aFullLedgerDropsItsOldestWindow() {
        val holes = fullLedger()
        assertEquals(listOf(hole(0, 1)), OttaiBleManager.ledgerAdd(holes, 1_000, 1_001))
        assertEquals(OttaiBleManager.MAX_HISTORY_HOLES, holes.size)
        assertEquals(hole(2, 3), holes.first())
        assertEquals(hole(1_000, 1_001), holes.last())
    }

    @Test
    fun arrivedDataTrimsTheHolesItReaches() {
        // The left part of a hole arrives.
        val left = mutableListOf(hole(0, 540))
        assertTrue(OttaiBleManager.ledgerTrim(left, 0, 270))
        assertEquals(listOf(hole(270, 540)), left)

        val front = mutableListOf(hole(100, 300))
        assertTrue(OttaiBleManager.ledgerTrim(front, 0, 200))
        assertEquals(listOf(hole(200, 300)), front)

        val tail = mutableListOf(hole(100, 300))
        assertTrue(OttaiBleManager.ledgerTrim(tail, 200, 300))
        assertEquals(listOf(hole(100, 200)), tail)

        val contained = mutableListOf(hole(100, 200), hole(600, 700))
        assertTrue(OttaiBleManager.ledgerTrim(contained, 0, 540))
        assertEquals(listOf(hole(600, 700)), contained)
    }

    @Test
    fun anArrivalStrictlyInsideAHoleOrBesideItChangesNothing() {
        val holes = mutableListOf(hole(0, 540))
        assertFalse(OttaiBleManager.ledgerTrim(holes, 100, 200))
        assertFalse(OttaiBleManager.ledgerTrim(holes, 540, 600))
        assertEquals(listOf(hole(0, 540)), holes)
    }

    @Test
    fun aMultiChunkHoleAbsorbsEachChunksFailure() {
        val holes = mutableListOf(hole(0, 540))
        assertEquals(emptyList<HistoryHole>(), OttaiBleManager.ledgerFail(holes, 0, 270))
        assertEquals(emptyList<HistoryHole>(), OttaiBleManager.ledgerFail(holes, 270, 540))
        assertEquals(listOf(hole(0, 540, 2)), holes)
    }

    @Test
    fun aFailureOverlappingTwoHolesCountsAgainstBoth() {
        val holes = mutableListOf(hole(0, 100), hole(100, 200))
        OttaiBleManager.ledgerFail(holes, 50, 150)
        assertEquals(listOf(hole(0, 100, 1), hole(100, 200, 1)), holes)
        // Touching is not overlapping: that failure is a window of its own.
        OttaiBleManager.ledgerFail(holes, 200, 300)
        assertEquals(listOf(hole(0, 100, 1), hole(100, 200, 1), hole(200, 300, 1)), holes)
    }

    /** The attempt cap retires such windows, so this path is not held to the size cap. */
    @Test
    fun aFailureNothingCoversIsLedgeredEvenOnAFullLedger() {
        val holes = fullLedger()
        OttaiBleManager.ledgerFail(holes, 1_000, 1_270)
        assertEquals(OttaiBleManager.MAX_HISTORY_HOLES + 1, holes.size)
        assertEquals(hole(0, 1), holes.first())
        assertEquals(hole(1_000, 1_270, 1), holes.last())
    }

    @Test
    fun theFifthFailureRetiresTheWindow() {
        val holes = mutableListOf(hole(0, 270), hole(600, 700))
        repeat(OttaiBleManager.HISTORY_HOLE_MAX_ATTEMPTS - 1) {
            assertEquals(emptyList<HistoryHole>(), OttaiBleManager.ledgerFail(holes, 0, 270))
        }
        assertEquals(hole(0, 270, OttaiBleManager.HISTORY_HOLE_MAX_ATTEMPTS - 1), holes.first())
        assertEquals(
            listOf(hole(0, 270, OttaiBleManager.HISTORY_HOLE_MAX_ATTEMPTS)),
            OttaiBleManager.ledgerFail(holes, 0, 270),
        )
        assertEquals(listOf(hole(600, 700)), holes)
        assertEquals(5, OttaiBleManager.HISTORY_HOLE_MAX_ATTEMPTS)
    }

    @Test
    fun aChunkIsCoveredOnlyWhenEveryDataNoArrived() {
        assertTrue(OttaiBleManager.historyWindowFullyCovered(100, 104, setOf(100, 101, 102, 103)))
        assertFalse(OttaiBleManager.historyWindowFullyCovered(100, 104, setOf(100, 101, 103)))
        assertFalse(OttaiBleManager.historyWindowFullyCovered(100, 104, setOf(103)))
        assertFalse(OttaiBleManager.historyWindowFullyCovered(100, 100, setOf(100)))
    }

    @Test
    fun absorbHistoryChunkPayload_aStaleWindowDoesNotTouchTheNewSet() {
        val seen = mutableSetOf(200, 201)
        val result = OttaiBleManager.absorbHistoryChunkPayload(
            activeStart = 200,
            activeEndExclusive = 204,
            payloadStart = 100,
            payloadEndExclusive = 104,
            seen = seen,
            dataNos = listOf(100, 101, 102, 103),
        )
        assertEquals(OttaiBleManager.HistoryChunkAbsorb.STALE, result)
        assertEquals(setOf(200, 201), seen)
    }

    @Test
    fun absorbHistoryChunkPayload_completesOnlyTheWindowItWasCountedAgainst() {
        val seen = mutableSetOf(100, 101, 103)
        val result = OttaiBleManager.absorbHistoryChunkPayload(
            activeStart = 100,
            activeEndExclusive = 104,
            payloadStart = 100,
            payloadEndExclusive = 104,
            seen = seen,
            dataNos = listOf(102),
        )
        assertEquals(OttaiBleManager.HistoryChunkAbsorb.COMPLETED, result)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun absorbHistoryChunkPayload_keepsAPartialWindow() {
        val seen = mutableSetOf(100)
        val result = OttaiBleManager.absorbHistoryChunkPayload(
            activeStart = 100,
            activeEndExclusive = 104,
            payloadStart = 100,
            payloadEndExclusive = 104,
            seen = seen,
            dataNos = listOf(101, 500),
        )
        assertEquals(OttaiBleManager.HistoryChunkAbsorb.INCOMPLETE, result)
        assertEquals(setOf(100, 101), seen)
    }

    @Test
    fun aCoveredChunkResetsTheRetryBudgetEvenWhenTheFrameIsNotANewMaximum() {
        assertEquals(
            0,
            OttaiBleManager.historyRetryCountAfterAbsorb(
                OttaiBleManager.HistoryChunkAbsorb.COMPLETED,
                retries = 3,
                frameMaxDataNo = 150,
                chunkBestDataNo = 200,
            ),
        )
        assertEquals(
            2,
            OttaiBleManager.historyRetryCountAfterAbsorb(
                OttaiBleManager.HistoryChunkAbsorb.INCOMPLETE,
                retries = 2,
                frameMaxDataNo = 150,
                chunkBestDataNo = 200,
            ),
        )
        assertEquals(
            0,
            OttaiBleManager.historyRetryCountAfterAbsorb(
                OttaiBleManager.HistoryChunkAbsorb.INCOMPLETE,
                retries = 2,
                frameMaxDataNo = 103,
                chunkBestDataNo = -1,
            ),
        )
        assertEquals(
            3,
            OttaiBleManager.historyRetryCountAfterAbsorb(
                OttaiBleManager.HistoryChunkAbsorb.STALE,
                retries = 3,
                frameMaxDataNo = 103,
                chunkBestDataNo = -1,
            ),
        )
        assertTrue(OttaiBleManager.coveredChunkResetsRetryCount(100, 104, 100, 104))
        assertTrue(OttaiBleManager.coveredChunkResetsRetryCount(-1, -1, 100, 104))
        assertFalse(OttaiBleManager.coveredChunkResetsRetryCount(500, 580, 100, 104))
    }
}
