package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiLiveFreshnessTests {

    @Test
    fun acceptsV17MinuteFlooredLiveSampleFromTrace() {
        assertTrue(
            OttaiBleManager.isFreshLiveSample(
                receivedAtMs = 1_782_823_566_000L,
                sampleMs = 1_782_823_440_000L,
            ),
        )
    }

    @Test
    fun rejectsClearlyStaleLiveSample() {
        assertFalse(
            OttaiBleManager.isFreshLiveSample(
                receivedAtMs = 1_782_823_566_000L,
                sampleMs = 1_782_823_320_000L,
            ),
        )
    }

    @Test
    fun rejectsMissingTimestamps() {
        assertFalse(OttaiBleManager.isFreshLiveSample(0L, 1_782_823_440_000L))
        assertFalse(OttaiBleManager.isFreshLiveSample(1_782_823_566_000L, 0L))
    }

    @Test
    fun historyWithProvisionalAnchorDoesNotConsumeEarlierLivePublication() {
        val provisionalHistoryMs = 1_782_823_500_000L
        val reliableLiveMs = 1_782_823_440_000L
        val receivedAtMs = 1_782_823_566_000L
        val state = OttaiCurrentReadingState()

        assertFalse(state.accept(live = false, receivedAtMs = receivedAtMs, sampleMs = provisionalHistoryMs).publishCurrent)
        val firstLive = state.accept(live = true, receivedAtMs = receivedAtMs, sampleMs = reliableLiveMs)
        assertEquals(provisionalHistoryMs, firstLive.previousDisplayedHighWaterMs)
        assertFalse(firstLive.displayAdvanced)
        assertTrue(firstLive.publishCurrent)
        assertFalse(
            state.accept(live = true, receivedAtMs = receivedAtMs, sampleMs = reliableLiveMs).publishCurrent,
        )
    }

    @Test
    fun historyAndOlderLiveSamplesCannotPublishCurrent() {
        val newestMs = 1_782_823_440_000L
        val state = OttaiCurrentReadingState(initialPublishedHighWaterMs = newestMs)

        assertFalse(state.accept(live = false, receivedAtMs = newestMs, sampleMs = newestMs).publishCurrent)
        assertFalse(
            state.accept(live = true, receivedAtMs = newestMs, sampleMs = newestMs - 60_000L).publishCurrent,
        )
    }

    @Test
    fun restoredPublicationHighWaterSuppressesEqualLiveReplay() {
        val publishedMs = 1_782_823_440_000L
        val persistedClaims = mutableListOf<Long>()
        val firstManager = OttaiCurrentReadingState()

        assertTrue(
            firstManager.accept(
                live = true,
                receivedAtMs = publishedMs,
                sampleMs = publishedMs,
                persist = { persistedClaims += it },
            ).publishCurrent,
        )
        assertEquals(listOf(publishedMs), persistedClaims)

        val recreatedManager = OttaiCurrentReadingState()
        recreatedManager.restorePublishedHighWater(persistedClaims.single())
        assertFalse(
            recreatedManager.accept(live = false, receivedAtMs = publishedMs, sampleMs = publishedMs).publishCurrent,
        )
        assertFalse(
            recreatedManager.accept(live = true, receivedAtMs = publishedMs, sampleMs = publishedMs).publishCurrent,
        )
    }

    /**
     * The 2026-08-01 room-backfill round trip: live read at 1785605688, history requested at
     * 1785605689, its payload back a second later — the re-read at 1785605690 returned the same
     * front=14332 the live read had just brought in.
     */
    @Test
    fun skipsPostHistoryLiveReadRightAfterALiveFrame() {
        assertFalse(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_689_000L,
                lastLiveFrameAtMs = 1_785_605_688_000L,
            ),
        )
    }

    @Test
    fun readsLiveAfterAHistoryBurstThatEndedBehindWallTime() {
        assertTrue(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_689_000L,
                lastLiveFrameAtMs = 1_785_605_299_000L,
            ),
        )
    }

    @Test
    fun readsLiveWhenNoLiveFrameHasBeenSeenYet() {
        assertTrue(OttaiBleManager.shouldReadLiveAfterHistory(1_785_605_689_000L, 0L))
    }

    /** One whole record has passed, so the sensor has something new to give. */
    @Test
    fun readsLiveExactlyOneRecordIntervalAfterTheLastFrame() {
        assertTrue(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_688_000L + 60_000L,
                lastLiveFrameAtMs = 1_785_605_688_000L,
            ),
        )
        assertFalse(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_688_000L + 59_999L,
                lastLiveFrameAtMs = 1_785_605_688_000L,
            ),
        )
    }

    @Test
    fun readsLiveWhenTheClockSteppedBackwards() {
        assertTrue(
            OttaiBleManager.shouldReadLiveAfterHistory(
                receivedAtMs = 1_785_605_688_000L,
                lastLiveFrameAtMs = 1_785_605_988_000L,
            ),
        )
    }
}
