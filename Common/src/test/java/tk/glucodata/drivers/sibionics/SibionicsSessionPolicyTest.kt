package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsSessionPolicyTest {
    @Test
    fun repeatedHistoricalIndexOneDoesNotResetSensorState() {
        assertFalse(
            SibionicsSessionPolicy.isConfirmedIndexRestart(
                index = 1,
                previousNextIndex = 421,
                isCurrentReading = false,
                isRehydrating = false,
            ),
        )
    }

    @Test
    fun currentIndexOneConfirmsPhysicalSensorRestart() {
        assertTrue(
            SibionicsSessionPolicy.isConfirmedIndexRestart(
                index = 1,
                previousNextIndex = 421,
                isCurrentReading = true,
                isRehydrating = false,
            ),
        )
    }

    @Test
    fun newResetCycleRebasesNativeWindowOnlyAtItsBeginning() {
        assertTrue(
            SibionicsSessionPolicy.shouldRebaseNativeWindow(
                hadStartTime = false,
                index = 1,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldRebaseNativeWindow(
                hadStartTime = true,
                index = 1,
            ),
        )
        assertTrue(
            SibionicsSessionPolicy.shouldRebaseNativeWindow(
                hadStartTime = false,
                index = 42,
            ),
        )
    }

    @Test
    fun liveStreamDoesNotRemainLabelledAsPartialHistory() {
        assertFalse(
            SibionicsSessionPolicy.shouldShowHistoryProgress(
                receivedCount = 11,
                totalCount = 420,
                hasReceivedLiveReading = true,
            ),
        )
        assertTrue(
            SibionicsSessionPolicy.shouldShowHistoryProgress(
                receivedCount = 11,
                totalCount = 420,
                hasReceivedLiveReading = false,
            ),
        )
    }

    @Test
    fun setupTimeoutOnlyRecoversAnActivePendingStage() {
        assertTrue(
            SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = true,
                isStopped = false,
                isPaused = false,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = false,
                isStopped = false,
                isPaused = false,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = true,
                isStopped = true,
                isPaused = false,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = true,
                isStopped = false,
                isPaused = true,
            ),
        )
    }

    @Test
    fun connectCallbackDeadlineStartsAfterRequestedDelay() {
        assertEquals(
            25_000L,
            SibionicsSessionPolicy.connectCallbackTimeoutDelayMs(
                requestedDelayMs = 5_000L,
                callbackTimeoutMs = 20_000L,
            ),
        )
        assertEquals(
            20_000L,
            SibionicsSessionPolicy.connectCallbackTimeoutDelayMs(
                requestedDelayMs = -1L,
                callbackTimeoutMs = 20_000L,
            ),
        )
    }

    @Test
    fun failedDirectConnectUsesOneAdvertisementRecovery() {
        assertTrue(
            SibionicsSessionPolicy.shouldUseAdvertisementRecovery(
                failedDuringConnect = true,
                isStopped = false,
                isPaused = false,
                hasKnownAddress = true,
                recoveryAlreadyActive = false,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldUseAdvertisementRecovery(
                failedDuringConnect = true,
                isStopped = false,
                isPaused = false,
                hasKnownAddress = true,
                recoveryAlreadyActive = true,
            ),
        )
    }

    @Test
    fun advertisementRecoveryDoesNotHijackNormalDisconnectsOrStoppedSensors() {
        assertFalse(
            SibionicsSessionPolicy.shouldUseAdvertisementRecovery(
                failedDuringConnect = false,
                isStopped = false,
                isPaused = false,
                hasKnownAddress = true,
                recoveryAlreadyActive = false,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldUseAdvertisementRecovery(
                failedDuringConnect = true,
                isStopped = true,
                isPaused = false,
                hasKnownAddress = true,
                recoveryAlreadyActive = false,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldUseAdvertisementRecovery(
                failedDuringConnect = true,
                isStopped = false,
                isPaused = false,
                hasKnownAddress = false,
                recoveryAlreadyActive = false,
            ),
        )
    }
    // --- rebuild deferral during a history transfer (2026-08-24 GC/BLE stall) ---

    @Test
    fun backlogTransferDefersTheRebuild() {
        assertTrue(
            SibionicsSessionPolicy.shouldDeferRebuildForHistoryTransfer(
                historyTransferActive = true,
                isRehydrating = false,
                deferredForMs = 3_000L,
                maxDeferralMs = 120_000L,
            ),
        )
    }

    /**
     * The regression this exists for. A committed rebuild sets the live index and
     * clears the rehydration flag, so a guard keyed on those alone stops deferring
     * after the first commit — which is how one transfer produced rebuilds of 128,
     * then 1504, then 4867 samples. Only the transfer flag survives that.
     */
    @Test
    fun stillDefersAfterAnEarlierRebuildAlreadyCommitted() {
        assertTrue(
            SibionicsSessionPolicy.shouldDeferRebuildForHistoryTransfer(
                historyTransferActive = true,
                isRehydrating = false,
                deferredForMs = 30_000L,
                maxDeferralMs = 120_000L,
            ),
        )
    }

    @Test
    fun rehydrationAloneAlsoDefers() {
        assertTrue(
            SibionicsSessionPolicy.shouldDeferRebuildForHistoryTransfer(
                historyTransferActive = false,
                isRehydrating = true,
                deferredForMs = 0L,
                maxDeferralMs = 120_000L,
            ),
        )
    }

    @Test
    fun settledSessionRebuildsImmediately() {
        assertFalse(
            SibionicsSessionPolicy.shouldDeferRebuildForHistoryTransfer(
                historyTransferActive = false,
                isRehydrating = false,
                deferredForMs = 0L,
                maxDeferralMs = 120_000L,
            ),
        )
    }

    @Test
    fun aTransferThatKeepsDeliveringPagesKeepsTheRebuildDeferred() {
        // 2026-09-24 trace: a full-wear fetch still streaming at 122 s was
        // rebuilt mid-transfer and the result discarded by the next page.
        assertTrue(
            SibionicsSessionPolicy.shouldDeferRebuildForHistoryTransfer(
                historyTransferActive = true,
                isRehydrating = false,
                deferredForMs = 122_000L,
                maxDeferralMs = 15L * 60L * 1000L,
                sinceLastPageMs = 3_000L,
                stallMs = 30_000L,
            ),
        )
    }

    @Test
    fun aStalledTransferNoLongerDefersTheRebuild() {
        assertFalse(
            SibionicsSessionPolicy.shouldDeferRebuildForHistoryTransfer(
                historyTransferActive = true,
                isRehydrating = false,
                deferredForMs = 40_000L,
                maxDeferralMs = 15L * 60L * 1000L,
                sinceLastPageMs = 31_000L,
                stallMs = 30_000L,
            ),
        )
    }

    @Test
    fun deferralCapEventuallyLetsTheRebuildThrough() {
        assertFalse(
            SibionicsSessionPolicy.shouldDeferRebuildForHistoryTransfer(
                historyTransferActive = true,
                isRehydrating = true,
                deferredForMs = 120_001L,
                maxDeferralMs = 120_000L,
            ),
        )
    }

    // 2026-09-19 trace: a data-request write refused as busy left the sensor
    // streaming from its own cursor (idx 14983 against an exact cursor of 5558);
    // treating that as lost state restarted a 23 000-sample replay every time.
    @Test
    fun oneUnrequestedPageKeepsTheExactState() {
        assertFalse(
            SibionicsSessionPolicy.shouldAbandonExactStateForUnrequestedPages(
                consecutiveUnrequestedConnections = 1,
                maxAttempts = 3,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldAbandonExactStateForUnrequestedPages(
                consecutiveUnrequestedConnections = 2,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun aSensorThatKeepsRefusingTheIndexIsReplayedFromTheStart() {
        assertTrue(
            SibionicsSessionPolicy.shouldAbandonExactStateForUnrequestedPages(
                consecutiveUnrequestedConnections = 3,
                maxAttempts = 3,
            ),
        )
    }

    @Test
    fun withoutAJournalGapTheRequestFollowsTheExactCursor() {
        assertEquals(
            5558,
            SibionicsSessionPolicy.dataRequestIndex(lastIndex = 5558, journalGapIndex = -1, backfillTurn = true),
        )
        assertEquals(
            5558,
            SibionicsSessionPolicy.dataRequestIndex(lastIndex = 5558, journalGapIndex = -1, backfillTurn = false),
        )
    }

    @Test
    fun journalBackfillAlternatesBetweenTheGapAndTheLiveCursor() {
        assertEquals(
            1,
            SibionicsSessionPolicy.dataRequestIndex(lastIndex = 23_010, journalGapIndex = 1, backfillTurn = true),
        )
        assertEquals(
            23_010,
            SibionicsSessionPolicy.dataRequestIndex(lastIndex = 23_010, journalGapIndex = 1, backfillTurn = false),
        )
    }

    @Test
    fun aGapAtOrPastTheLiveCursorIsNotBackfilled() {
        assertEquals(
            100,
            SibionicsSessionPolicy.dataRequestIndex(lastIndex = 100, journalGapIndex = 100, backfillTurn = true),
        )
        assertEquals(
            100,
            SibionicsSessionPolicy.dataRequestIndex(lastIndex = 100, journalGapIndex = 400, backfillTurn = true),
        )
    }

    @Test
    fun aFreshSensorRequestsFromZeroWhateverTheTurn() {
        assertEquals(
            0,
            SibionicsSessionPolicy.dataRequestIndex(lastIndex = 0, journalGapIndex = -1, backfillTurn = true),
        )
    }

    // --- Session boundaries: resets done by us or by another app ---------------

    private val minute = SibionicsConstants.READING_INTERVAL_MS
    private val oldStart = 1_757_000_000_000L // an 18-day-old session
    private val oldCursor = 26_000
    private val now = oldStart + oldCursor * minute

    private fun sample(index: Int, eventMs: Long, live: Boolean = false) =
        SibionicsSessionPolicy.SessionSample(index, eventMs, live)

    private fun restartedAt(
        samples: List<SibionicsSessionPolicy.SessionSample>,
        knownStartMs: Long = oldStart,
        knownCursor: Int = oldCursor,
        lastSeenMs: Long = 0L,
        isRehydrating: Boolean = false,
        nowMs: Long = now,
    ): Long? = SibionicsSessionPolicy.restartedSessionStartMs(
        samples = samples,
        knownStartMs = knownStartMs,
        knownCursor = knownCursor,
        lastSeenMs = lastSeenMs,
        isRehydrating = isRehydrating,
        nowMs = nowMs,
    )

    @Test
    fun the2004CaptureAStaleCursorAnsweredByTheCurrentRecordIsARestart() {
        // Device capture 2026-09-24 20:04: re-added sensor, reset by another install
        // at 02:59; JNG asked for idx=23437 and got the live idx=1024 back.
        val knownStart = 1_788_785_280_000L // Mon 07.09.2026 17:48 +05
        val lastSeen = 1_790_200_380_000L   // the last reading JNG held
        val newStart = 1_790_200_740_000L   // what the device logged
        assertEquals(
            newStart,
            restartedAt(
                listOf(sample(1024, newStart + 1024 * minute, live = true)),
                knownStartMs = knownStart,
                knownCursor = 23_437,
                lastSeenMs = lastSeen,
                nowMs = newStart + 1025 * minute,
            ),
        )
    }

    @Test
    fun aRestartProvedMidSessionIsDownloadedFromTheStart() {
        // 20:04: the page that proved the restart was the live idx=1024 alone.
        assertTrue(
            SibionicsSessionPolicy.shouldDownloadRestartedSessionFromStart(
                listOf(sample(1024, now, live = true)),
            ),
        )
        // Our own reset's probe, or a live idx=1: the page already starts the session.
        assertFalse(SibionicsSessionPolicy.shouldDownloadRestartedSessionFromStart(listOf(sample(1, now, live = true))))
        assertFalse(
            SibionicsSessionPolicy.shouldDownloadRestartedSessionFromStart((0..999).map { sample(it, now + it * minute) }),
        )
        assertFalse(SibionicsSessionPolicy.shouldDownloadRestartedSessionFromStart(emptyList()))
    }

    @Test
    fun ourResetIsConfirmedByTheProbesIndexOne() {
        // reset.log: after our reset the next link's idx=1 is the new session.
        val newStart = now + 3 * minute
        assertEquals(newStart, restartedAt(listOf(sample(1, newStart + minute)), nowMs = newStart + 2 * minute))
        assertEquals(
            newStart,
            restartedAt(listOf(sample(1, newStart + minute, live = true)), nowMs = newStart + minute),
        )
    }

    @Test
    fun oldSessionPagesAnsweringTheProbeAreNotARestart() {
        // A sensor that ignored the reset serves its old idx=1 and on.
        val oldPage = (1..1000).map { sample(it, oldStart + it * minute) }
        assertNull(restartedAt(oldPage, nowMs = now + 2 * minute))
    }

    @Test
    fun restartIsDetectedDuringAReplayOfTheOldSession() {
        // A re-added sensor replaying from idx=1 while the transmitter was reset
        // elsewhere: the cursor to compare against is the replay target.
        val newStart = now + 10 * minute
        val page = (1..5).map { sample(it, newStart + it * minute) }
        assertEquals(
            newStart,
            restartedAt(page, isRehydrating = true, lastSeenMs = now - minute, nowMs = newStart + 6 * minute),
        )
    }

    @Test
    fun ordinaryHistoryAndLiveSamplesOfTheTrackedSessionAreNotARestart() {
        val history = (100..1100).map { sample(it, oldStart + it * minute) }
        assertNull(restartedAt(history))
        val live = listOf(sample(oldCursor, oldStart + oldCursor * minute, live = true))
        assertNull(restartedAt(live))
        // A few minutes of sensor clock drift over the whole wear is not a new session.
        val drifted = (1..50).map { sample(it, oldStart + 4 * minute + it * minute) }
        assertNull(restartedAt(drifted))
    }

    @Test
    fun anIndexSkipEarlyInASessionIsNotMistakenForARestart() {
        // The sensor skipped 90 minutes of indices near the start. Cursor 60 then
        // implies we last saw data at +59 min, but we actually saw it at +149;
        // a re-served sample from +135 must stay in the tracked session.
        val skip = 90 * minute
        val lastSeen = oldStart + skip + 59 * minute
        assertNull(restartedAt(listOf(sample(45, oldStart + skip + 45 * minute)), knownCursor = 60, lastSeenMs = lastSeen))
        // A genuine restart after that still is one.
        val newStart = oldStart + skip + 70 * minute
        assertEquals(
            newStart,
            restartedAt(listOf(sample(3, newStart + 3 * minute)), knownCursor = 60, lastSeenMs = lastSeen),
        )
    }

    @Test
    fun withoutAKnownStartOnlyALiveIndexOneRestarts() {
        val newStart = now - 20 * minute
        val history = (1..10).map { sample(it, newStart + it * minute) }
        assertNull(restartedAt(history, knownStartMs = 0L))
        assertEquals(now - minute, restartedAt(listOf(sample(1, now, live = true)), knownStartMs = 0L))
    }

    @Test
    fun aPhoneClockJumpDoesNotTurnARepeatedChineseSampleIntoARestart() {
        // Chinese-protocol times are the phone's receipt time minus the sensor's
        // backlog, so moving the phone clock an hour forward moves every implied
        // start with it. Forty minutes into a session the sensor re-serves idx=20.
        val start = now - 40 * minute
        val jump = 60 * minute
        val repeated = SibionicsSessionPolicy.SessionSample(
            index = 20,
            eventMs = start + 20 * minute + jump,
            live = false,
            sensorLiveIndex = 40,
        )
        assertNull(
            restartedAt(
                listOf(repeated),
                knownStartMs = start,
                knownCursor = 40,
                lastSeenMs = start + 39 * minute,
                nowMs = now + jump,
            ),
        )
    }

    @Test
    fun aChineseSensorWhoseSessionIsShorterThanTheCursorHasRestarted() {
        // Reset elsewhere 25 minutes after our last sample; the sensor now holds
        // 30 minutes, fewer than the 26 000 we already received.
        val newStart = now + 25 * minute
        val page = (5..29).map {
            SibionicsSessionPolicy.SessionSample(it, newStart + it * minute, live = false, sensorLiveIndex = 30)
        }
        assertEquals(
            newStart,
            restartedAt(page, lastSeenMs = now - minute, nowMs = newStart + 30 * minute),
        )
    }

    @Test
    fun implausibleSessionStartsAreNotEvidence() {
        // A start in the future, or before 2000 (a clock that never got its sync).
        assertNull(restartedAt(listOf(sample(1, now + 3 * 60 * minute))))
        assertNull(restartedAt(listOf(sample(5, 946_000_000_000L))))
        // A live idx=1 still proves a restart on its own, even without a time.
        assertEquals(now, restartedAt(listOf(sample(1, 0L, live = true))))
    }
}
