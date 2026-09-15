package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A live sample that lands past the record the app holds has told us, unasked, that the
 * records in between are missing. That is a bounded hole and the ledger is built for it — but
 * the only route to the ledger used to run through the connection-lifetime backfill one-shot.
 *
 * On the 2026-08-30 trace the one-shot latched at 02:10, the link then stayed up for eleven and
 * a half hours, and the hole that opened at 12:10 was consequently never re-requested: the
 * sensor was connected and streaming the whole time, and the chart still showed an outage.
 */
class OttaiLiveGapLedgerTests {

    @Test
    fun consecutiveRecordsAreNotAGap() {
        assertNull(OttaiBleManager.liveGapRange(previousDataNo = 9744, liveDataNo = 9745))
    }

    @Test
    fun theTraceGapIsLedgeredExactly() {
        // 12:10:38 stored 9744; the next stored record was 9760 at 12:26:37.
        assertEquals(
            OttaiBleManager.MissingRange(9745, 9760),
            OttaiBleManager.liveGapRange(previousDataNo = 9744, liveDataNo = 9760),
        )
    }

    @Test
    fun aJumpPastTheCeilingIsNotLedgered() {
        // The ceiling is the app's answer to "the sensor cannot be there yet". A dataNo beyond
        // it is a corrupt frame, and enqueueing its span would ask for records that don't exist.
        assertNull(
            OttaiBleManager.liveGapRange(previousDataNo = 9744, liveDataNo = 20_000, ceiling = 9_800)
        )
    }

    @Test
    fun aSpanTooWideForTheLivePathIsLeftToTheReconciliation() {
        // A whole-store hole is the initial backfill's problem. Spending the ledger's bounded
        // attempts on it would starve the real dropouts it exists to repair.
        assertNull(OttaiBleManager.liveGapRange(previousDataNo = 100, liveDataNo = 9_000))
    }

    @Test
    fun aFirstEverSampleHasNothingBehindIt() {
        assertNull(OttaiBleManager.liveGapRange(previousDataNo = -1, liveDataNo = 9760))
    }
}
