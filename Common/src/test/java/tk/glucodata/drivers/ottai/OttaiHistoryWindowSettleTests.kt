package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.ottai.OttaiBleManager.HistoryHole
import tk.glucodata.drivers.ottai.OttaiBleManager.MissingRange

/**
 * A history window used to leave the hole ledger whole as soon as its highest dataNo arrived.
 * A frame lost in the middle, or records that could not be judged yet, went with it and were
 * never asked for again. Only settled runs may leave the ledger now.
 */
class OttaiHistoryWindowSettleTests {

    private fun window(size: Int, vararg settled: IntRange) =
        BooleanArray(size).also { array -> settled.forEach { range -> range.forEach { array[it] = true } } }

    @Test
    fun aLostMiddleFrameKeepsItsRecordsUnsettled() {
        // [100,130): first frame 100..109 and last frame 120..129 arrived; 110..119 did not.
        val runs = OttaiBleManager.historyWindowRuns(100, window(30, 0..9, 20..29))
        assertEquals(listOf(MissingRange(100, 110), MissingRange(120, 130)), runs.settled)
        assertEquals(listOf(MissingRange(110, 120)), runs.unsettled)
    }

    @Test
    fun aFullyDeliveredWindowIsOneSettledRun() {
        val runs = OttaiBleManager.historyWindowRuns(0, window(10, 0..9))
        assertEquals(listOf(MissingRange(0, 10)), runs.settled)
        assertTrue(runs.unsettled.isEmpty())
    }

    @Test
    fun settlingAroundAGapSplitsTheHole() {
        val holes = mutableListOf(HistoryHole(100, 130, 0))
        assertTrue(OttaiBleManager.trimHoleLedger(holes, 100, 110))
        assertTrue(OttaiBleManager.trimHoleLedger(holes, 120, 130))
        assertEquals(listOf(HistoryHole(110, 120, 0)), holes)
    }

    @Test
    fun aRunStrictlyInsideAHoleSplitsItKeepingAttempts() {
        val holes = mutableListOf(HistoryHole(100, 130, 2))
        assertTrue(OttaiBleManager.trimHoleLedger(holes, 110, 120))
        assertEquals(listOf(HistoryHole(100, 110, 2), HistoryHole(120, 130, 2)), holes)
    }

    @Test
    fun aFullLedgerKeepsTheHoleWholeRatherThanDropOne() {
        val holes = mutableListOf(HistoryHole(0, 10, 0), HistoryHole(100, 130, 0))
        assertFalse(OttaiBleManager.trimHoleLedger(holes, 110, 120, maxHoles = 2))
        assertEquals(listOf(HistoryHole(0, 10, 0), HistoryHole(100, 130, 0)), holes)
    }

    @Test
    fun severalUnsettledRunsChargeAHoleOnce() {
        val holes = mutableListOf(HistoryHole(100, 200, 1))
        val retired = OttaiBleManager.noteHoleLedgerFailures(
            holes,
            listOf(MissingRange(110, 120), MissingRange(150, 160), MissingRange(190, 200)),
        )
        assertTrue(retired.isEmpty())
        assertEquals(listOf(HistoryHole(100, 200, 2)), holes)
    }

    @Test
    fun anUnledgeredRunIsLedgeredWithOneAttempt() {
        val holes = mutableListOf<HistoryHole>()
        OttaiBleManager.noteHoleLedgerFailures(holes, listOf(MissingRange(40, 45)))
        assertEquals(listOf(HistoryHole(40, 45, 1)), holes)
    }

    @Test
    fun theAttemptCapStillRetiresAHole() {
        val holes = mutableListOf(HistoryHole(10, 20, 4), HistoryHole(50, 60, 0))
        val retired = OttaiBleManager.noteHoleLedgerFailures(holes, listOf(MissingRange(12, 14)), maxAttempts = 5)
        assertEquals(listOf(HistoryHole(10, 20, 5)), retired)
        assertEquals(listOf(HistoryHole(50, 60, 0)), holes)
    }
}
