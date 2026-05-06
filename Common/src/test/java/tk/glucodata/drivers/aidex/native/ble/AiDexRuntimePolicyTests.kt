package tk.glucodata.drivers.aidex.native.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexRuntimePolicyTests {

    @Test
    fun initialAssistDelay_waitsForInitialHistoryWindow() {
        val delayMs = AiDexRuntimePolicy.initialAssistDelayMs(
            nowMs = 16_000L,
            phaseStreaming = true,
            pendingInitialHistoryRequest = true,
            historyDownloading = false,
            streamingStartedAtMs = 1_000L,
            initialHistoryRequestDelayMs = 65_000L,
        )

        assertEquals(50_000L, delayMs)
    }

    @Test
    fun initialAssistDelay_disabledOnceHistoryWindowIsOver() {
        val delayMs = AiDexRuntimePolicy.initialAssistDelayMs(
            nowMs = 80_000L,
            phaseStreaming = true,
            pendingInitialHistoryRequest = true,
            historyDownloading = false,
            streamingStartedAtMs = 0L,
            initialHistoryRequestDelayMs = 65_000L,
        )

        assertNull(delayMs)
    }

    @Test
    fun shouldContinueAssistScanning_falseWhileInitialHistoryStillPending() {
        val shouldContinue = AiDexRuntimePolicy.shouldContinueAssistScanning(
            stop = false,
            broadcastOnlyMode = false,
            phaseStreaming = true,
            hasRecentLiveData = false,
            pendingInitialHistoryRequest = true,
            historyDownloading = false,
            anchorMs = 0L,
            nowMs = 30_000L,
            lastGlucoseTimeMs = 0L,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
        )

        assertFalse(shouldContinue)
    }

    @Test
    fun shouldContinueAssistScanning_falseAfterValidReadingSeen() {
        val anchorMs = 1_000L
        val shouldContinue = AiDexRuntimePolicy.shouldContinueAssistScanning(
            stop = false,
            broadcastOnlyMode = false,
            phaseStreaming = true,
            hasRecentLiveData = false,
            pendingInitialHistoryRequest = false,
            historyDownloading = false,
            anchorMs = anchorMs,
            nowMs = anchorMs + 10L * 60_000L,
            lastGlucoseTimeMs = anchorMs + 5L * 60_000L,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
        )

        assertFalse(shouldContinue)
    }

    @Test
    fun shouldContinueAssistScanning_trueDuringExtendedWaitWithoutReading() {
        val anchorMs = 1_000L
        val shouldContinue = AiDexRuntimePolicy.shouldContinueAssistScanning(
            stop = false,
            broadcastOnlyMode = false,
            phaseStreaming = true,
            hasRecentLiveData = false,
            pendingInitialHistoryRequest = false,
            historyDownloading = false,
            anchorMs = anchorMs,
            nowMs = anchorMs + 20L * 60_000L,
            lastGlucoseTimeMs = 0L,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
        )

        assertTrue(shouldContinue)
    }

    @Test
    fun firstValidReadingWaitStatus_reportsWarmupExtendedAndNoValidData() {
        val anchorMs = 1_000L
        val warmupMs = 7L * 60_000L
        val maxWaitMs = 60L * 60_000L

        assertEquals(
            "age=2m warmup=5m",
            AiDexRuntimePolicy.firstValidReadingWaitStatus(
                anchorMs = anchorMs,
                nowMs = anchorMs + 2L * 60_000L,
                warmupDurationMs = warmupMs,
                firstValidReadingWaitMaxMs = maxWaitMs,
            )
        )
        assertEquals(
            "age=10m extended=50m",
            AiDexRuntimePolicy.firstValidReadingWaitStatus(
                anchorMs = anchorMs,
                nowMs = anchorMs + 10L * 60_000L,
                warmupDurationMs = warmupMs,
                firstValidReadingWaitMaxMs = maxWaitMs,
            )
        )
        assertEquals(
            "age=61m no-valid-data",
            AiDexRuntimePolicy.firstValidReadingWaitStatus(
                anchorMs = anchorMs,
                nowMs = anchorMs + 61L * 60_000L,
                warmupDurationMs = warmupMs,
                firstValidReadingWaitMaxMs = maxWaitMs,
            )
        )
    }

    @Test
    fun connectedWarmupStatus_hidesWarmupOnceValidReadingExists() {
        val anchorMs = 1_000L
        val warmupMs = 7L * 60_000L
        val status = AiDexRuntimePolicy.connectedWarmupStatus(
            connectionPart = "Connected",
            anchorMs = anchorMs,
            nowMs = anchorMs + 2L * 60_000L,
            lastGlucoseTimeMs = anchorMs + 90_000L,
            warmupDurationMs = warmupMs,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
            firstValidReadingWaitActive = true,
        )

        assertNull(status)
    }

    @Test
    fun connectedWarmupStatus_reportsWarmupUntilFirstValidReading() {
        val anchorMs = 1_000L
        val warmupMs = 7L * 60_000L
        val status = AiDexRuntimePolicy.connectedWarmupStatus(
            connectionPart = "Connected",
            anchorMs = anchorMs,
            nowMs = anchorMs + 2L * 60_000L,
            lastGlucoseTimeMs = 0L,
            warmupDurationMs = warmupMs,
            firstValidReadingWaitMaxMs = 60L * 60_000L,
            firstValidReadingWaitActive = true,
        )

        assertEquals("Connected — Warmup 5m", status)
    }

    @Test
    fun shouldStartHistoryImmediately_onlyWhenPendingAndNotDownloading() {
        assertTrue(
            AiDexRuntimePolicy.shouldStartHistoryImmediately(
                pendingInitialHistoryRequest = true,
                historyDownloading = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldStartHistoryImmediately(
                pendingInitialHistoryRequest = false,
                historyDownloading = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldStartHistoryImmediately(
                pendingInitialHistoryRequest = true,
                historyDownloading = true,
            )
        )
    }

    @Test
    fun shouldRequestRoutineStreamingMetadata_onlyWhenMetadataIsStillMissing() {
        assertFalse(
            AiDexRuntimePolicy.shouldRequestRoutineStreamingMetadata(
                startupMetadataComplete = true,
                hasModelMetadata = true,
                hasAuthoritativeSessionStart = true,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldRequestRoutineStreamingMetadata(
                startupMetadataComplete = true,
                hasModelMetadata = true,
                hasAuthoritativeSessionStart = false,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldRequestRoutineStreamingMetadata(
                startupMetadataComplete = false,
                hasModelMetadata = false,
                hasAuthoritativeSessionStart = false,
            )
        )
    }

    @Test
    fun shouldRequestRoutineCalibrationRefresh_onlyWithoutCachedRecords() {
        assertTrue(
            AiDexRuntimePolicy.shouldRequestRoutineCalibrationRefresh(
                hasCachedCalibrationRecords = false,
                calibrationDownloading = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldRequestRoutineCalibrationRefresh(
                hasCachedCalibrationRecords = true,
                calibrationDownloading = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldRequestRoutineCalibrationRefresh(
                hasCachedCalibrationRecords = false,
                calibrationDownloading = true,
            )
        )
    }

    @Test
    fun shouldRunOptionalStreamingSync_onlyAfterDirectLiveIsStable() {
        assertTrue(
            AiDexRuntimePolicy.shouldRunOptionalStreamingSync(
                phase = AiDexBleManager.Phase.STREAMING,
                hasGatt = true,
                historyDownloading = false,
                pendingInitialHistoryRequest = false,
                noDirectLiveBroadcastFallbackMode = false,
                hasDirectLiveThisConnection = true,
                hasRecentLiveData = true,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldRunOptionalStreamingSync(
                phase = AiDexBleManager.Phase.STREAMING,
                hasGatt = true,
                historyDownloading = false,
                pendingInitialHistoryRequest = false,
                noDirectLiveBroadcastFallbackMode = true,
                hasDirectLiveThisConnection = true,
                hasRecentLiveData = true,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldRunOptionalStreamingSync(
                phase = AiDexBleManager.Phase.STREAMING,
                hasGatt = true,
                historyDownloading = false,
                pendingInitialHistoryRequest = false,
                noDirectLiveBroadcastFallbackMode = false,
                hasDirectLiveThisConnection = false,
                hasRecentLiveData = true,
            )
        )
    }

    @Test
    fun shouldAcceptBroadcastFallback_trueWhileWaitingForFirstDirectLive() {
        assertTrue(
            AiDexRuntimePolicy.shouldAcceptBroadcastFallback(
                broadcastOnlyMode = false,
                waitingForFirstDirectLive = true,
                hadRecentLiveDataBeforeBroadcast = true,
            )
        )
    }

    @Test
    fun shouldAcceptBroadcastFallback_falseWhenDirectLiveIsAlreadyHealthy() {
        assertFalse(
            AiDexRuntimePolicy.shouldAcceptBroadcastFallback(
                broadcastOnlyMode = false,
                waitingForFirstDirectLive = false,
                hadRecentLiveDataBeforeBroadcast = true,
            )
        )
    }

    @Test
    fun shouldContinueBroadcastScanning_trueForNoDirectLiveFallbackMode() {
        assertTrue(
            AiDexRuntimePolicy.shouldContinueBroadcastScanning(
                broadcastOnlyMode = false,
                noDirectLiveBroadcastFallbackMode = true,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldContinueBroadcastScanning(
                broadcastOnlyMode = true,
                noDirectLiveBroadcastFallbackMode = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldContinueBroadcastScanning(
                broadcastOnlyMode = false,
                noDirectLiveBroadcastFallbackMode = false,
            )
        )
    }

    @Test
    fun shouldRecoverFromSetupStall_forBondedCccdChainAfterTimeout() {
        assertTrue(
            AiDexRuntimePolicy.shouldRecoverFromSetupStall(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                phaseAgeMs = 25_000L,
                bondState = BluetoothDevice.BOND_BONDED,
                keyExchangePendingBond = false,
                setupTimeoutMs = 25_000L,
                bondingTimeoutMs = 35_000L,
            )
        )
    }

    @Test
    fun shouldRecoverFromSetupStall_waitsLongerWhileBonding() {
        assertFalse(
            AiDexRuntimePolicy.shouldRecoverFromSetupStall(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                phaseAgeMs = 25_000L,
                bondState = BluetoothDevice.BOND_BONDING,
                keyExchangePendingBond = true,
                setupTimeoutMs = 25_000L,
                bondingTimeoutMs = 35_000L,
            )
        )
    }

    @Test
    fun shouldRecoverFromConnectAttemptStall_whenConnectCallbackNeverArrives() {
        assertTrue(
            AiDexRuntimePolicy.shouldRecoverFromConnectAttemptStall(
                phase = AiDexBleManager.Phase.GATT_CONNECTING,
                phaseAgeMs = 20_000L,
                connectTimeoutMs = 20_000L,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldRecoverFromConnectAttemptStall(
                phase = AiDexBleManager.Phase.DISCOVERING_SERVICES,
                phaseAgeMs = 20_000L,
                connectTimeoutMs = 20_000L,
            )
        )
    }

    @Test
    fun shouldRecoverFromPreAuthEncryptedTraffic_whenBondedTrafficPersistsBeforeAuth() {
        assertTrue(
            AiDexRuntimePolicy.shouldRecoverFromPreAuthEncryptedTraffic(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                bondState = BluetoothDevice.BOND_BONDED,
                keyExchangePendingBond = false,
                encryptedFrameCount = 4,
                firstEncryptedFrameAtMs = 10_000L,
                nowMs = 15_500L,
                minFrames = 3,
                timeoutMs = 5_000L,
            )
        )
    }

    @Test
    fun shouldRecoverFromPreAuthEncryptedTraffic_ignoresBondingTraffic() {
        assertFalse(
            AiDexRuntimePolicy.shouldRecoverFromPreAuthEncryptedTraffic(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                bondState = BluetoothDevice.BOND_BONDING,
                keyExchangePendingBond = true,
                encryptedFrameCount = 6,
                firstEncryptedFrameAtMs = 10_000L,
                nowMs = 20_000L,
                minFrames = 3,
                timeoutMs = 5_000L,
            )
        )
    }

    @Test
    fun shouldAdvanceBondedReconnectToKeyExchange_whenCccdQueueIsDrained() {
        assertTrue(
            AiDexRuntimePolicy.shouldAdvanceBondedReconnectToKeyExchange(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                bondState = BluetoothDevice.BOND_BONDED,
                keyExchangePendingBond = false,
                cccdQueueEmpty = true,
                cccdWriteInProgress = false,
                cccdChainComplete = false,
                challengeWritten = false,
                bondDataRead = false,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldAdvanceBondedReconnectToKeyExchange(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                bondState = BluetoothDevice.BOND_BONDED,
                keyExchangePendingBond = false,
                cccdQueueEmpty = true,
                cccdWriteInProgress = true,
                cccdChainComplete = false,
                challengeWritten = false,
                bondDataRead = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldAdvanceBondedReconnectToKeyExchange(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                bondState = BluetoothDevice.BOND_BONDED,
                keyExchangePendingBond = false,
                cccdQueueEmpty = false,
                cccdWriteInProgress = false,
                cccdChainComplete = false,
                challengeWritten = false,
                bondDataRead = false,
            )
        )
    }

    @Test
    fun shouldUseSavedPairKeyReconnect_requiresBondedDeviceAndSavedKey() {
        assertTrue(
            AiDexRuntimePolicy.shouldUseSavedPairKeyReconnect(
                bondState = BluetoothDevice.BOND_BONDED,
                hasSavedPairKey = true,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldUseSavedPairKeyReconnect(
                bondState = BluetoothDevice.BOND_BONDED,
                hasSavedPairKey = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldUseSavedPairKeyReconnect(
                bondState = BluetoothDevice.BOND_NONE,
                hasSavedPairKey = true,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldUseSavedPairKeyReconnect(
                bondState = BluetoothDevice.BOND_BONDING,
                hasSavedPairKey = true,
            )
        )
    }

    @Test
    fun decideMissingCccdCallbackAction_waitsThenAssumesComplete() {
        assertEquals(
            AiDexRuntimePolicy.MissingCccdCallbackAction.WAIT,
            AiDexRuntimePolicy.decideMissingCccdCallbackAction(
                cccdWriteInProgress = true,
                hasPendingCccd = true,
                timeoutRetries = 0,
                maxRetries = 1,
                canInferComplete = true,
            )
        )
        assertEquals(
            AiDexRuntimePolicy.MissingCccdCallbackAction.ASSUME_COMPLETE,
            AiDexRuntimePolicy.decideMissingCccdCallbackAction(
                cccdWriteInProgress = true,
                hasPendingCccd = true,
                timeoutRetries = 1,
                maxRetries = 1,
                canInferComplete = true,
            )
        )
        assertEquals(
            AiDexRuntimePolicy.MissingCccdCallbackAction.WAIT,
            AiDexRuntimePolicy.decideMissingCccdCallbackAction(
                cccdWriteInProgress = true,
                hasPendingCccd = true,
                timeoutRetries = 1,
                maxRetries = 1,
                canInferComplete = false,
            )
        )
        assertEquals(
            AiDexRuntimePolicy.MissingCccdCallbackAction.IGNORE,
            AiDexRuntimePolicy.decideMissingCccdCallbackAction(
                cccdWriteInProgress = false,
                hasPendingCccd = true,
                timeoutRetries = 1,
                maxRetries = 1,
                canInferComplete = true,
            )
        )
    }

    @Test
    fun shouldRecoverFromBlockedReconnect_whenStaleGattExistsWithoutConnectionCallback() {
        assertTrue(
            AiDexRuntimePolicy.shouldRecoverFromBlockedReconnect(
                phase = AiDexBleManager.Phase.IDLE,
                hasGatt = true,
                connectAttemptInFlight = false,
                hasRecentLiveData = false,
                lastLiveReadingObservedTimeMs = 0L,
            )
        )
    }

    @Test
    fun shouldRecoverFromBlockedReconnect_whenIdleAttemptRemainsInFlightWithoutGatt() {
        assertTrue(
            AiDexRuntimePolicy.shouldRecoverFromBlockedReconnect(
                phase = AiDexBleManager.Phase.IDLE,
                hasGatt = false,
                connectAttemptInFlight = true,
                hasRecentLiveData = false,
                lastLiveReadingObservedTimeMs = 0L,
            )
        )
    }

    @Test
    fun shouldRecoverFromBlockedReconnect_whenStreamingStateIsStaleWithoutRecentLive() {
        assertTrue(
            AiDexRuntimePolicy.shouldRecoverFromBlockedReconnect(
                phase = AiDexBleManager.Phase.STREAMING,
                hasGatt = false,
                connectAttemptInFlight = false,
                hasRecentLiveData = false,
                lastLiveReadingObservedTimeMs = 500L,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldRecoverFromBlockedReconnect(
                phase = AiDexBleManager.Phase.STREAMING,
                hasGatt = true,
                connectAttemptInFlight = false,
                hasRecentLiveData = false,
                lastLiveReadingObservedTimeMs = 500L,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.shouldRecoverFromBlockedReconnect(
                phase = AiDexBleManager.Phase.STREAMING,
                hasGatt = false,
                connectAttemptInFlight = false,
                hasRecentLiveData = true,
                lastLiveReadingObservedTimeMs = 500L,
            )
        )
    }

    @Test
    fun decideInvalidSetupRecoveryAction_escalatesToBondResetOnlyForUnvalidatedBond() {
        assertEquals(
            AiDexRuntimePolicy.InvalidSetupRecoveryAction.RECONNECT,
            AiDexRuntimePolicy.decideInvalidSetupRecoveryAction(
                consecutiveRecoveries = 1,
                bondState = BluetoothDevice.BOND_BONDED,
                bondResetThreshold = 2,
                bondValidatedByStreaming = false,
            )
        )
        assertEquals(
            AiDexRuntimePolicy.InvalidSetupRecoveryAction.REMOVE_BOND_AND_RECONNECT,
            AiDexRuntimePolicy.decideInvalidSetupRecoveryAction(
                consecutiveRecoveries = 2,
                bondState = BluetoothDevice.BOND_BONDED,
                bondResetThreshold = 2,
                bondValidatedByStreaming = false,
            )
        )
        assertEquals(
            AiDexRuntimePolicy.InvalidSetupRecoveryAction.RECONNECT,
            AiDexRuntimePolicy.decideInvalidSetupRecoveryAction(
                consecutiveRecoveries = 2,
                bondState = BluetoothDevice.BOND_BONDED,
                bondResetThreshold = 2,
                bondValidatedByStreaming = true,
            )
        )
    }
}
