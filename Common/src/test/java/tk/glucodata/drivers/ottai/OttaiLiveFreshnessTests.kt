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

    // A sensor started at :45 past the minute, so record n is measured at start + n minutes + 45 s.
    private val trueStartMs = 1_785_605_640_000L + 45_000L

    private fun measuredAtMs(dataNo: Int) = trueStartMs + dataNo * 60_000L

    @Test
    fun arrivalBoundPreservesTheSecondsWithinTheMinute() {
        val receivedAtMs = measuredAtMs(100) + 3_000L
        val start = OttaiBleManager.arrivalBoundStartMs(100, receivedAtMs)

        assertEquals(48_000L, start % 60_000L)
        assertEquals(receivedAtMs, start + 100 * 60_000L)
    }

    private val trueStartMinuteMs = 1_785_605_640_000L
    private val promptStartMs = trueStartMs + 1_000L
    private val lateStartMs = trueStartMs + 20_000L

    /**
     * One sensor's live path as the manager runs it: dated from the stream start, published
     * through the current-reading state, and only then — accepted frames only — offered to the
     * arrival correction, which may move the start. [seedArrivalStartMs] is what a live seed
     * hands the correction, 0 for a history seed.
     */
    private class LiveStream(var startMs: Long, seedArrivalStartMs: Long) {
        val correction = OttaiArrivalStartCorrection().also { it.onAnchorSeeded(startMs, seedArrivalStartMs) }
        val state = OttaiCurrentReadingState()
        var unpublished = 0
        var stale = 0

        fun frame(dataNo: Int, receivedAtMs: Long, accepted: Boolean = true): Long {
            val sampleMs = OttaiBleManager.anchoredLiveSampleMs(startMs, dataNo, receivedAtMs)
            assertTrue("dataNo=$dataNo dated after arrival", sampleMs <= receivedAtMs)
            if (!accepted) return sampleMs
            if (!OttaiBleManager.isFreshLiveSample(receivedAtMs, sampleMs)) stale++
            if (!state.accept(live = true, receivedAtMs = receivedAtMs, sampleMs = sampleMs).publishCurrent) unpublished++
            correction.onAcceptedLive(startMs, dataNo, receivedAtMs).takeIf { it > 0L }?.let { startMs = it }
            return sampleMs
        }

        fun prompt(range: IntRange, measuredAt: (Int) -> Long) {
            for (dataNo in range) frame(dataNo, measuredAt(dataNo) + 1_000L)
        }
    }

    /** Seeded the way the manager's "live" source does it, from [dataNo]'s arrival. */
    private fun liveSeeded(dataNo: Int, receivedAtMs: Long) =
        LiveStream(OttaiBleManager.arrivalBoundStartMs(dataNo, receivedAtMs), receivedAtMs - dataNo * 60_000L)

    /** A prompt frame seeded the stream at the true start minute. */
    private fun onTimeStream() = liveSeeded(5, measuredAtMs(5) + 1_000L)

    /** A frame 20 s late, past the minute, seeded the stream a record after the true start. */
    private fun overshotStream() = liveSeeded(10, measuredAtMs(10) + 20_000L)

    /**
     * A seed frame arrived 20 seconds late. Two prompt accepted frames correct that delay,
     * preserving the seconds within the minute rather than rounding the sample down.
     */
    @Test
    fun twoPromptFramesCorrectAnAnchorFromALateOne() {
        val stream = overshotStream()
        assertEquals(lateStartMs, stream.startMs)

        assertEquals(measuredAtMs(11) + 1_000L, stream.frame(11, measuredAtMs(11) + 1_000L))
        assertEquals("one frame is not enough", lateStartMs, stream.startMs)
        stream.prompt(12..30, ::measuredAtMs)

        assertEquals(promptStartMs, stream.startMs)
        assertEquals(0, stream.unpublished)
        assertEquals(0, stream.stale)
    }

    /**
     * The review replay: two counters four ahead, both refused by the continuity gate. A refused
     * frame is dated at arrival and goes no further, so the anchor and every later frame are
     * untouched.
     */
    @Test
    fun rejectedFramesLeaveTheAnchorAlone() {
        val stream = onTimeStream()
        stream.prompt(5..10, ::measuredAtMs)
        stream.frame(15, measuredAtMs(11) + 1_000L, accepted = false)
        stream.frame(16, measuredAtMs(12) + 1_000L, accepted = false)
        stream.prompt(13..30, ::measuredAtMs)

        assertEquals(promptStartMs, stream.startMs)
        assertEquals(0, stream.unpublished)
        assertEquals(0, stream.stale)
    }

    /**
     * The continuity gate compares glucose, not counters, so the same pair with plausible values
     * is ACCEPTED. Four records is more than flooring can overshoot by, so the anchor stays.
     */
    @Test
    fun anAcceptedCorruptPairCannotMoveTheAnchorFurtherThanARecord() {
        val stream = onTimeStream()
        stream.prompt(5..10, ::measuredAtMs)
        stream.frame(15, measuredAtMs(11) + 1_000L)
        stream.frame(16, measuredAtMs(12) + 1_000L)
        stream.prompt(17..40, ::measuredAtMs)

        assertEquals(promptStartMs, stream.startMs)
        assertEquals(0, stream.stale)
    }

    /**
     * A pair one ahead fits both bounds and does lower the anchor a record — the worst a corrupt
     * counter can do. Genuine frames then lag their arrival by under two minutes and stay fresh,
     * but are dated a record early, so the one after each corrupt pair lands on the minute that
     * pair already published and misses publication once. A second pair finds the budget spent.
     */
    @Test
    fun theWorstCorruptCorrectionKeepsReadingsFreshAndIsSpentOnce() {
        val stream = onTimeStream()
        stream.prompt(5..10, ::measuredAtMs)
        stream.frame(12, measuredAtMs(11) + 1_000L)
        stream.frame(13, measuredAtMs(12) + 1_000L)
        assertEquals(promptStartMs - 60_000L, stream.startMs)

        stream.prompt(13..20, ::measuredAtMs)
        assertEquals(1, stream.unpublished)
        stream.frame(23, measuredAtMs(21) + 1_000L)
        stream.frame(24, measuredAtMs(22) + 1_000L)
        stream.prompt(23..40, ::measuredAtMs)

        assertEquals(promptStartMs - 60_000L, stream.startMs)
        assertEquals(2, stream.unpublished)
        assertEquals(0, stream.stale)
    }

    /**
     * The second review replay: a monitor time 116 s behind arrival is usable, and flooring puts
     * the anchor 166 s behind. A corrupt pair three ahead implies a start exactly one record
     * lower — inside the budget — but that would date every genuine frame 226 s before arrival,
     * stale and unpublished for the rest of the session. The anchor stays; the corrupt pair's own
     * minutes hold back the two genuine frames they claimed, then publication carries on.
     */
    @Test
    fun aCorrectionCannotPushAnAlreadyLaggingAnchorPastFreshness() {
        val monitorMs = measuredAtMs(5) + 1_000L - 116_000L
        assertTrue(OttaiBleManager.isUsableLiveMonitorTime(monitorMs, measuredAtMs(5) + 1_000L))
        val seedMs = (monitorMs / 60_000L) * 60_000L - 5 * 60_000L
        val stream = LiveStream(seedMs, measuredAtMs(5) + 1_000L - 5 * 60_000L)
        stream.prompt(5..10, ::measuredAtMs)
        val lagMs = measuredAtMs(10) + 1_000L - OttaiBleManager.anchoredLiveSampleMs(seedMs, 10, measuredAtMs(10) + 1_000L)
        assertEquals(166_000L, lagMs)
        assertEquals(seedMs - 14_000L, OttaiBleManager.arrivalBoundStartMs(14, measuredAtMs(11) + 1_000L))

        stream.frame(14, measuredAtMs(11) + 1_000L)
        stream.frame(15, measuredAtMs(12) + 1_000L)
        stream.prompt(13..40, ::measuredAtMs)

        assertEquals(seedMs, stream.startMs)
        assertEquals(0, stream.stale)
        assertEquals(2, stream.unpublished)
    }

    /**
     * The third review replay: the same lagging anchor seeded from history before any live frame,
     * so the corrupt pair would be the only arrivals the bound has seen and would vouch for its
     * own correction. A history seed has no lag of its own to measure, so it is never corrected.
     */
    @Test
    fun aHistorySeededAnchorIsNotCorrectedByTheFirstLiveFrames() {
        val seedMs = trueStartMinuteMs - 120_000L
        val stream = LiveStream(seedMs, 0L)
        stream.frame(14, measuredAtMs(11) + 1_000L)
        stream.frame(15, measuredAtMs(12) + 1_000L)
        stream.prompt(13..40, ::measuredAtMs)

        assertEquals(seedMs, stream.startMs)
        assertEquals(0, stream.stale)
        assertEquals(2, stream.unpublished)
    }

    /**
     * The cost of that: a history-seeded anchor a record past the start keeps it for the session.
     * Every prompt record is then dated at its arrival minute instead — never in the future, and
     * still published.
     */
    @Test
    fun aHistorySeededOvershootIsDatedAtArrivalInstead() {
        val stream = LiveStream(trueStartMinuteMs + 60_000L, 0L)
        stream.prompt(11..30, ::measuredAtMs)

        assertEquals(trueStartMinuteMs + 60_000L, stream.startMs)
        assertEquals(0, stream.unpublished)
        assertEquals(0, stream.stale)
    }

    /** A candidate from before a reconnect gap cannot be corroborated by a frame after it. */
    @Test
    fun aCandidateExpiresAcrossAGap() {
        val stream = overshotStream()
        stream.frame(11, measuredAtMs(11) + 1_000L)
        stream.frame(21, measuredAtMs(21) + 1_000L)
        assertEquals(lateStartMs, stream.startMs)

        stream.frame(22, measuredAtMs(22) + 1_000L)
        assertEquals(promptStartMs, stream.startMs)
    }

    /** The two frames must be consecutive: an accepted frame dated on time drops the candidate. */
    @Test
    fun anOnTimeFrameDropsTheCandidate() {
        val stream = overshotStream()
        stream.frame(11, measuredAtMs(11) + 1_000L)
        stream.frame(12, measuredAtMs(12) + 20_000L)
        stream.frame(13, measuredAtMs(13) + 1_000L)
        assertEquals(lateStartMs, stream.startMs)
    }

    /** A replay of the same record is not a second, independent observation. */
    @Test
    fun theSameCounterTwiceDoesNotAgree() {
        val stream = overshotStream()
        stream.frame(11, measuredAtMs(11) + 1_000L)
        stream.frame(11, measuredAtMs(11) + 2_000L)
        assertEquals(lateStartMs, stream.startMs)
    }

    /** A counter one ahead paired with a genuine frame: the genuine, later start wins. */
    @Test
    fun aCounterOneAheadCannotPullTheStartPastTheGenuineFrame() {
        val stream = overshotStream()
        stream.frame(12, measuredAtMs(11) + 1_000L)
        stream.frame(13, measuredAtMs(13) + 1_000L)
        assertEquals(promptStartMs, stream.startMs)
    }

    /** Reseeding the anchor drops a candidate taken against the old one. */
    @Test
    fun reseedingDropsTheCandidate() {
        val correction = OttaiArrivalStartCorrection()
        correction.onAnchorSeeded(trueStartMinuteMs + 60_000L, measuredAtMs(10) + 20_000L - 600_000L)
        assertEquals(0L, correction.onAcceptedLive(trueStartMinuteMs + 60_000L, 11, measuredAtMs(11) + 1_000L))
        correction.onAnchorSeeded(trueStartMinuteMs + 60_000L, measuredAtMs(10) + 20_000L - 600_000L)
        assertEquals(0L, correction.onAcceptedLive(trueStartMinuteMs + 60_000L, 12, measuredAtMs(12) + 1_000L))
    }

    @Test
    fun monitorTimeMayLagArrivalButNotLeadIt() {
        val receivedAtMs = 1_785_605_688_000L
        assertTrue(OttaiBleManager.isUsableLiveMonitorTime(receivedAtMs - 100_000L, receivedAtMs))
        // Even inside the same minute, a future monitor time must be rejected.
        assertFalse(OttaiBleManager.isUsableLiveMonitorTime(receivedAtMs + 5_000L, receivedAtMs))
        // The case the old two-sided check let through: floored into the next minute.
        assertFalse(OttaiBleManager.isUsableLiveMonitorTime(receivedAtMs + 15_000L, receivedAtMs))
        assertFalse(OttaiBleManager.isUsableLiveMonitorTime(receivedAtMs + 100_000L, receivedAtMs))
        assertFalse(OttaiBleManager.isUsableLiveMonitorTime(receivedAtMs - 121_000L, receivedAtMs))
        assertFalse(OttaiBleManager.isUsableLiveMonitorTime(0L, receivedAtMs))
    }
}
