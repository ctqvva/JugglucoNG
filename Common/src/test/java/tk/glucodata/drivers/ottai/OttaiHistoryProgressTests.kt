package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiHistoryProgressTests {

    @Test
    fun detectsCorruptedAheadProgressFromRejectedFrames() {
        assertTrue(OttaiBleManager.isPersistedDataNoAheadOfLive(29_284, 19_832))
        assertTrue(OttaiBleManager.isPersistedDataNoAheadOfLive(60_571, 19_832))
    }

    @Test
    fun keepsNormalProgressPointers() {
        assertFalse(OttaiBleManager.isPersistedDataNoAheadOfLive(19_831, 19_832))
        assertFalse(OttaiBleManager.isPersistedDataNoAheadOfLive(19_900, 19_832))
        assertFalse(OttaiBleManager.isPersistedDataNoAheadOfLive(-1, 19_832))
    }

    @Test
    fun corruptedAheadProgressForcesRoomGapScan() {
        assertEquals(-1, OttaiBleManager.previousDataNoForHistory(29_284, 19_832))
        assertEquals(19_831, OttaiBleManager.previousDataNoForHistory(19_831, 19_832))
        assertEquals(-1, OttaiBleManager.previousDataNoForHistory(-1, 19_832))
    }

    @Test
    fun aFrameThatReachesFurtherRefundsTheRetryBudget() {
        assertEquals(0, OttaiBleManager.historyRetriesAfterFrame(retries = 2, frameMaxDataNo = 44, chunkBestDataNo = 40))
        // First frame for a window: nothing delivered yet, so anything is progress.
        assertEquals(0, OttaiBleManager.historyRetriesAfterFrame(retries = 1, frameMaxDataNo = 0, chunkBestDataNo = -1))
    }

    @Test
    fun aFrameThatRepeatsWhatWeHaveKeepsTheRetryBudgetSpent() {
        // The Syai stall: the chunk's tail never decodes, so every retry re-delivers the same
        // records. Refunding on that is what made the watchdog re-request forever.
        assertEquals(2, OttaiBleManager.historyRetriesAfterFrame(retries = 2, frameMaxDataNo = 44, chunkBestDataNo = 44))
        assertEquals(2, OttaiBleManager.historyRetriesAfterFrame(retries = 2, frameMaxDataNo = 40, chunkBestDataNo = 44))
    }

    @Test
    fun anUndeliverableChunkExhaustsItsRetriesInsteadOfLoopingForever() {
        // Replay of chunk [37,50) from the 2026-08-11 trace: every frame tops out at 44, so the
        // window can never complete. Walk the watchdog/frame cycle and require it to terminate.
        val endExclusive = 50
        var retries = 0
        var best = -1
        var cycles = 0
        var gaveUp = false
        while (cycles++ < 100) {
            // Frame lands: the same two plausible records, 40 and 44.
            val frameMax = 44
            retries = OttaiBleManager.historyRetriesAfterFrame(retries, frameMax, best)
            if (frameMax > best) best = frameMax
            assertTrue("window must stay incomplete for this replay", frameMax + 1 < endExclusive)
            // Watchdog fires on the stalled window.
            if (retries < OttaiBleManager.HISTORY_MAX_RETRIES) {
                retries++
            } else {
                gaveUp = true
                break
            }
        }
        assertTrue("chunk must reach the retry bound and be ledgered", gaveUp)
        assertEquals(OttaiBleManager.HISTORY_MAX_RETRIES, retries)
    }

    @Test
    fun backfillPercentTracksTheRunningChain() {
        // A full re-add fetches ~19000 records in 270-record chunks over several minutes:
        // one chunk in is 270/18856, i.e. 1%.
        assertEquals(1, OttaiBleManager.historyBackfillPercent(0, 270, 18_856))
        assertEquals(35, OttaiBleManager.historyBackfillPercent(0, 6_750, 18_856))
        // A chain that does not start at zero measures from its own start, not from zero.
        assertEquals(50, OttaiBleManager.historyBackfillPercent(10_000, 14_000, 18_000))
    }

    @Test
    fun backfillPercentReportsNothingWhenNoChainIsRunning() {
        assertEquals(-1, OttaiBleManager.historyBackfillPercent(-1, 0, 0))
        assertEquals(-1, OttaiBleManager.historyBackfillPercent(0, 0, 0))
        assertEquals(-1, OttaiBleManager.historyBackfillPercent(500, 500, 500))
    }

    @Test
    fun backfillPercentNeverReadsAsFinishedWhileRequestsAreStillInFlight() {
        // The chain is only cleared once it completes, so 100% would read as "done" for however
        // long the tail takes; and a pointer past the end must not produce a nonsense value.
        assertEquals(99, OttaiBleManager.historyBackfillPercent(0, 18_856, 18_856))
        assertEquals(99, OttaiBleManager.historyBackfillPercent(0, 99_999, 18_856))
        assertEquals(0, OttaiBleManager.historyBackfillPercent(0, -50, 18_856))
    }
}
