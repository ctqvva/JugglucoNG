package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        val start = 37
        val endExclusive = 50
        var retries = 0
        var best = -1
        var cycles = 0
        var gaveUp = false
        cycle@ while (cycles++ < 100) {
            // Frame lands: the same two plausible records, 40 and 44.
            val frameMax = 44
            retries = OttaiBleManager.historyRetriesAfterFrame(retries, frameMax, best)
            if (frameMax > best) best = frameMax
            assertTrue("window must stay incomplete for this replay", frameMax + 1 < endExclusive)
            // Watchdog fires on the stalled window.
            when (OttaiBleManager.historyWatchdogAction(start, endExclusive, retries)) {
                OttaiBleManager.HistoryWatchdogAction.RETRY -> {
                    retries++
                    // The re-issue is the identical window, so it keeps what the chunk reached.
                    if (!OttaiBleManager.keepsChunkBest(start, endExclusive, start, endExclusive)) best = -1
                }
                OttaiBleManager.HistoryWatchdogAction.GIVE_UP -> {
                    gaveUp = true
                    break@cycle
                }
                else -> fail("a window in flight is retried or given up")
            }
        }
        assertTrue("chunk must reach the retry bound and be ledgered", gaveUp)
        assertEquals(OttaiBleManager.HISTORY_MAX_RETRIES, retries)
    }

    @Test
    fun theHistoryWatchdogRetriesAWindowThreeTimesThenGivesItUp() {
        assertEquals(OttaiBleManager.HistoryWatchdogAction.NONE, OttaiBleManager.historyWatchdogAction(0, -1, 0))
        assertEquals(OttaiBleManager.HistoryWatchdogAction.NONE, OttaiBleManager.historyWatchdogAction(-1, 0, 0))
        // A malformed window is skipped, not retried and not ledgered.
        assertEquals(OttaiBleManager.HistoryWatchdogAction.SKIP, OttaiBleManager.historyWatchdogAction(5, 5, 0))
        assertEquals(OttaiBleManager.HistoryWatchdogAction.SKIP, OttaiBleManager.historyWatchdogAction(-1, 270, 0))
        for (retries in 0 until OttaiBleManager.HISTORY_MAX_RETRIES) {
            assertEquals(OttaiBleManager.HistoryWatchdogAction.RETRY, OttaiBleManager.historyWatchdogAction(0, 270, retries))
        }
        assertEquals(OttaiBleManager.HistoryWatchdogAction.GIVE_UP, OttaiBleManager.historyWatchdogAction(0, 270, 3))
        assertEquals(3, OttaiBleManager.HISTORY_MAX_RETRIES)
    }

    @Test
    fun onlyTheIdenticalWindowKeepsTheChunksProgressMarker() {
        assertTrue(OttaiBleManager.keepsChunkBest(37, 50, 37, 50))
        assertFalse(OttaiBleManager.keepsChunkBest(37, 50, 50, 63))
        assertFalse(OttaiBleManager.keepsChunkBest(37, 50, 37, 49))
        assertFalse(OttaiBleManager.keepsChunkBest(-1, -1, 37, 50))
    }

    /**
     * The Room diff maps each stored timestamp back to its record: the nearest minute from the
     * start. An off-by-one here marks a missing minute present, and the diff latches "complete"
     * with it still missing.
     */
    @Test
    fun storedTimestampsMapToTheNearestRecord() {
        val startMs = 1_700_000_340_000L
        val minute = 60_000L
        fun present(vararg timestamps: Long) =
            OttaiBleManager.presentFromTimestamps(timestamps, startMs, 10).withIndex().filter { it.value }.map { it.index }
        assertEquals(listOf(5), present(startMs + 5 * minute + 29_000L))
        assertEquals(listOf(6), present(startMs + 5 * minute + 30_000L))
        assertEquals(listOf(0), present(startMs - 30_000L))
        assertEquals(listOf(9), present(startMs + 9 * minute + 29_999L))
        // The live record and anything past it are not the diff's to mark.
        assertEquals(emptyList<Int>(), present(startMs + 10 * minute, startMs + 60 * minute))
        assertEquals(listOf(0, 3, 4), present(startMs, startMs + 3 * minute + 1_000L, startMs + 4 * minute - 1_000L))
        assertEquals(10, OttaiBleManager.presentFromTimestamps(LongArray(0), startMs, 10).size)
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
