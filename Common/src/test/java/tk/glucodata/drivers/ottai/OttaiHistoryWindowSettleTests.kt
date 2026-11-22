package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.ottai.OttaiBleManager.HistoryHole
import tk.glucodata.drivers.ottai.OttaiBleManager.HistoryWindowProgress
import tk.glucodata.drivers.ottai.OttaiBleManager.MissingRange

/**
 * A history window used to leave the hole ledger whole as soon as its highest dataNo arrived.
 * A frame lost in the middle, or records that could not be judged yet, went with it and were
 * never asked for again. Only settled runs may leave the ledger now.
 */
class OttaiHistoryWindowSettleTests {

    private fun window(size: Int, vararg settled: IntRange) =
        ByteArray(size).also { array -> settled.forEach { range -> range.forEach { array[it] = OttaiBleManager.RECORD_SETTLED } } }

    @Test
    fun aLostMiddleFrameKeepsItsRecordsUnsettled() {
        // [100,130): first frame 100..109 and last frame 120..129 arrived; 110..119 did not.
        val runs = OttaiBleManager.historyWindowRuns(100, window(30, 0..9, 20..29))
        assertEquals(listOf(MissingRange(100, 110), MissingRange(120, 130)), runs.settled)
        assertEquals(listOf(MissingRange(110, 120)), runs.missing)
        assertTrue(runs.deferred.isEmpty())
    }

    @Test
    fun aFullyDeliveredWindowIsOneSettledRun() {
        val runs = OttaiBleManager.historyWindowRuns(0, window(10, 0..9))
        assertEquals(listOf(MissingRange(0, 10)), runs.settled)
        assertTrue(runs.missing.isEmpty())
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

    @Test
    fun aRunOverlappingAHoleLedgersThePartTheHoleMisses() {
        // Unledgered empty-live [100,160) ended with [100,120) missing; an older hole has [100,110).
        val holes = mutableListOf(HistoryHole(100, 110, 1))
        OttaiBleManager.noteHoleLedgerFailures(holes, listOf(MissingRange(100, 120)))
        assertEquals(listOf(HistoryHole(100, 110, 2), HistoryHole(110, 120, 1)), holes)
    }

    @Test
    fun aRunSpanningSeveralHolesLedgersEveryGapBetweenThem() {
        val holes = mutableListOf(HistoryHole(110, 120, 0), HistoryHole(130, 140, 0))
        OttaiBleManager.noteHoleLedgerFailures(holes, listOf(MissingRange(100, 150)))
        assertEquals(
            listOf(
                HistoryHole(100, 110, 1), HistoryHole(110, 120, 1), HistoryHole(120, 130, 1),
                HistoryHole(130, 140, 1), HistoryHole(140, 150, 1),
            ),
            holes,
        )
    }

    @Test
    fun aRunInsideAHoleAddsNothing() {
        val holes = mutableListOf(HistoryHole(100, 200, 0))
        OttaiBleManager.noteHoleLedgerFailures(holes, listOf(MissingRange(120, 130)))
        assertEquals(listOf(HistoryHole(100, 200, 1)), holes)
    }

    @Test
    fun aWindowMarksRecordsAgainstItsOwnBounds() {
        // A frame for the old window [0,30) must not set bits in a new window [100,130).
        val window = HistoryWindowProgress(100, 130)
        window.mark(listOf(5, 6, 105, 140), OttaiBleManager.RECORD_SETTLED)
        val runs = OttaiBleManager.historyWindowRuns(window.start, window.states)
        assertEquals(listOf(MissingRange(105, 106)), runs.settled)
        assertTrue(window.covers(100, 130))
        assertFalse(window.covers(0, 30))
    }

    @Test
    fun recordsThatArrivedUnjudgedAreDeferredNotMissing() {
        // [100,110): 100..103 stored, 104..107 arrived without a method, 108..109 never came.
        val window = HistoryWindowProgress(100, 110)
        window.mark((100..107).toList(), OttaiBleManager.RECORD_DEFERRED)
        window.mark((100..103).toList(), OttaiBleManager.RECORD_SETTLED)
        val runs = OttaiBleManager.historyWindowRuns(window.start, window.states)
        assertEquals(listOf(MissingRange(100, 104)), runs.settled)
        assertEquals(listOf(MissingRange(104, 108)), runs.deferred)
        assertEquals(listOf(MissingRange(108, 110)), runs.missing)
    }

    @Test
    fun aSettledRecordIsNotDowngradedByALaterUnjudgedCopy() {
        val window = HistoryWindowProgress(0, 2)
        window.mark(listOf(0, 1), OttaiBleManager.RECORD_SETTLED)
        window.mark(listOf(0, 1), OttaiBleManager.RECORD_DEFERRED) // a watchdog re-issue's copy
        assertEquals(listOf(MissingRange(0, 2)), OttaiBleManager.historyWindowRuns(0, window.states).settled)
    }

    @Test
    fun deferringKeepsTheRangeWithoutItsSpentAttempts() {
        val holes = mutableListOf(HistoryHole(100, 200, 3))
        OttaiBleManager.deferHoleLedger(holes, 120, 140)
        assertEquals(listOf(HistoryHole(100, 120, 3), HistoryHole(120, 140, 0), HistoryHole(140, 200, 3)), holes)
        // The attempt cap retires the charged parts; the deferred one is untouched.
        val retired = OttaiBleManager.noteHoleLedgerFailures(holes, listOf(MissingRange(100, 120), MissingRange(140, 200)), maxAttempts = 4)
        assertEquals(listOf(HistoryHole(100, 120, 4), HistoryHole(140, 200, 4)), retired)
        assertEquals(listOf(HistoryHole(120, 140, 0)), holes)
    }

    @Test
    fun aBackfillDeferredChunkByChunkStaysOneHole() {
        val holes = mutableListOf(HistoryHole(0, 10, 2))
        for (start in 10 until 10 + 270 * 70 step 270) OttaiBleManager.deferHoleLedger(holes, start, start + 270)
        assertEquals(listOf(HistoryHole(0, 10, 2), HistoryHole(10, 10 + 270 * 70, 0)), holes)
    }

    @Test
    fun anUnledgeredDeferredRangeIsLedgered() {
        val holes = mutableListOf<HistoryHole>()
        OttaiBleManager.deferHoleLedger(holes, 40, 45)
        assertEquals(listOf(HistoryHole(40, 45, 0)), holes)
    }
}
