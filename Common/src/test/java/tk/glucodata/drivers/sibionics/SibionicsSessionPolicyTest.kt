package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

}
