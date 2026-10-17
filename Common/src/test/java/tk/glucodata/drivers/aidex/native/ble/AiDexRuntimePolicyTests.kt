package tk.glucodata.drivers.aidex.native.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexRuntimePolicyTests {

    @Test
    fun pairKeyStartAction_aSensorWithoutASavedKeyPairsFreshBondedOrNot() {
        // Every sensor paired before the vault existed is bonded with no key. The driver did
        // F001 on every connect until now, so it simply does it once more after the update.
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR,
            AiDexRuntimePolicy.decidePairKeyStartAction(hasSavedPairKey = false)
        )
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR,
            AiDexRuntimePolicy.decidePairKeyStartAction(hasSavedPairKey = false, savedKeyExhausted = true)
        )
    }

    @Test
    fun pairKeyStartAction_aSavedKeyIsAlwaysUsedAndNeverRotatedByPair() {
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.USE_SAVED_KEY,
            AiDexRuntimePolicy.decidePairKeyStartAction(hasSavedPairKey = true)
        )
        // Pair on a keyed sensor that is still working must not run F001 against it.
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.USE_SAVED_KEY,
            AiDexRuntimePolicy.decidePairKeyStartAction(hasSavedPairKey = true, explicitPairRequested = true)
        )
        // An unbonded phone never replaces a key on its own, even after it has failed its
        // retries: the sensor refuses F001 while another device holds the bond slot.
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.USE_SAVED_KEY,
            AiDexRuntimePolicy.decidePairKeyStartAction(hasSavedPairKey = true, savedKeyExhausted = true)
        )
        // Being bonded alone is not a reason to touch a key that still works.
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.USE_SAVED_KEY,
            AiDexRuntimePolicy.decidePairKeyStartAction(hasSavedPairKey = true, bonded = true)
        )
    }

    @Test
    fun pairKeyStartAction_deadSavedKeyIsReplacedByPairOrByTheBondedPhone() {
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR,
            AiDexRuntimePolicy.decidePairKeyStartAction(
                hasSavedPairKey = true,
                savedKeyExhausted = true,
                explicitPairRequested = true,
            )
        )
        // The reporter's case: bond state 12, saved key rejected three times. This phone is
        // the device the sensor accepts F001 from, so it re-pairs without a button press.
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR,
            AiDexRuntimePolicy.decidePairKeyStartAction(
                hasSavedPairKey = true,
                savedKeyExhausted = true,
                bonded = true,
            )
        )
    }

    @Test
    fun keyExchangeFailures_retryBoundedlyThenHoldInBroadcastOnly() {
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.RETRY_CLEAN_GATT,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(2, 3)
        )
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.BROADCAST_ONLY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(3, 3)
        )
        // A fresh pair that keeps failing parks, bonded or not: there is no other key to try.
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.BROADCAST_ONLY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(3, 3, usedSavedKey = false, bonded = true)
        )
    }

    @Test
    fun keyExchangeFailures_exhaustedSavedKeyOnABondedLinkIsReplacedNotParked() {
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.RETRY_CLEAN_GATT,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(2, 3, usedSavedKey = true, bonded = true)
        )
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.REPLACE_SAVED_KEY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(3, 3, usedSavedKey = true, bonded = true)
        )
        // Counters persist across a process death, so a count past the limit must not
        // fall back into retrying.
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.REPLACE_SAVED_KEY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(5, 3, usedSavedKey = true, bonded = true)
        )
        // Unbonded: the user decides, the status line tells them to press Pair.
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.BROADCAST_ONLY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(3, 3, usedSavedKey = true, bonded = false)
        )
    }

    @Test
    fun persistedPairKeyClearsOnlyAfterSuccessfulDeleteBondAck() {
        // AiDex ACKs success with 0x01; every DELETE_BOND in the field logs answered 0x01.
        assertFalse(AiDexRuntimePolicy.shouldClearPersistedPairKey(false, 0x01))
        assertFalse(AiDexRuntimePolicy.shouldClearPersistedPairKey(true, 0x00))
        assertFalse(AiDexRuntimePolicy.shouldClearPersistedPairKey(true, 0xFF))
        assertTrue(AiDexRuntimePolicy.shouldClearPersistedPairKey(true, 0x01))
    }

    @Test
    fun clearStorageIsConfirmedOnlyByAnAcceptedAck() {
        // 1.6.0 / 1.7.1 / 1.8.0: `F3 01 crc crc`, then history newest=1 and a zeroed session.
        assertTrue(AiDexRuntimePolicy.isClearStorageConfirmed(responseLength = 4, responseStatus = 0x01))
        assertTrue(AiDexRuntimePolicy.isClearStorageConfirmed(responseLength = 2, responseStatus = 0x01))
        // 1.8.3: `0x00`, and the old history was still there afterwards.
        assertFalse(AiDexRuntimePolicy.isClearStorageConfirmed(responseLength = 4, responseStatus = 0x00))
        assertFalse(AiDexRuntimePolicy.isClearStorageConfirmed(responseLength = 1, responseStatus = 0xFF))
        // A payload is not an ACK, whatever its second byte happens to be.
        assertFalse(AiDexRuntimePolicy.isClearStorageConfirmed(responseLength = 18, responseStatus = 0x01))
    }

    @Test
    fun pairKeyStartAction_aConfirmedResetPairsFreshOverTheKeptKey() {
        // The reporter's case: reset acknowledged, key still stored, sensor wiped its own.
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR,
            AiDexRuntimePolicy.decidePairKeyStartAction(hasSavedPairKey = true, pairKeyResetPending = true)
        )
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR,
            AiDexRuntimePolicy.decidePairKeyStartAction(
                hasSavedPairKey = true,
                bonded = true,
                pairKeyResetPending = true,
            )
        )
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR,
            AiDexRuntimePolicy.decidePairKeyStartAction(hasSavedPairKey = false, pairKeyResetPending = true)
        )
    }

    @Test
    fun keyExchangeFailures_aFailingPostResetFreshPairFallsBackToTheKeptKey() {
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.RETRY_CLEAN_GATT,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(2, 3, freshPairAfterReset = true)
        )
        // A reset that did not rotate the credential must not strand the sensor.
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.RESTORE_SAVED_KEY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(3, 3, freshPairAfterReset = true)
        )
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.RESTORE_SAVED_KEY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(3, 3, bonded = true, freshPairAfterReset = true)
        )
        // Without a reset behind it, a failing fresh pair still parks.
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.BROADCAST_ONLY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(3, 3, bonded = true)
        )
    }

    @Test
    fun lifecycleResetIsWithheldFromFirmware183AndLater() {
        assertTrue(AiDexRuntimePolicy.supportsLifecycleReset("1.6.0"))
        assertTrue(AiDexRuntimePolicy.supportsLifecycleReset("1.7.1.3"))
        assertTrue(AiDexRuntimePolicy.supportsLifecycleReset("1.8"))
        assertTrue(AiDexRuntimePolicy.supportsLifecycleReset("1.8.1"))
        assertTrue(AiDexRuntimePolicy.supportsLifecycleReset("1.8.2"))
        assertFalse(AiDexRuntimePolicy.supportsLifecycleReset("1.8.3"))
        assertFalse(AiDexRuntimePolicy.supportsLifecycleReset(" 1.8.3 "))
        assertFalse(AiDexRuntimePolicy.supportsLifecycleReset("1.8.3.1"))
        assertFalse(AiDexRuntimePolicy.supportsLifecycleReset("1.9.3"))
        assertFalse(AiDexRuntimePolicy.supportsLifecycleReset("1.10.0"))
        assertFalse(AiDexRuntimePolicy.supportsLifecycleReset("2.0"))
        // Not read yet, or not a version: no grounds to hide the action.
        assertTrue(AiDexRuntimePolicy.supportsLifecycleReset(""))
        assertTrue(AiDexRuntimePolicy.supportsLifecycleReset(null))
        assertTrue(AiDexRuntimePolicy.supportsLifecycleReset("V1.8.3"))
    }

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
                hasSensorReportedWearDays = true,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldRequestRoutineStreamingMetadata(
                startupMetadataComplete = true,
                hasModelMetadata = true,
                hasAuthoritativeSessionStart = false,
                hasSensorReportedWearDays = true,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldRequestRoutineStreamingMetadata(
                startupMetadataComplete = false,
                hasModelMetadata = false,
                hasAuthoritativeSessionStart = false,
                hasSensorReportedWearDays = false,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldRequestRoutineStreamingMetadata(
                startupMetadataComplete = false,
                hasModelMetadata = true,
                hasAuthoritativeSessionStart = true,
                hasSensorReportedWearDays = false,
            )
        )
    }

    @Test
    fun shouldRequestStartupDeviceInfo_retriesAFewSpacedTimesPerConnection() {
        val spacing = AiDexRuntimePolicy.STARTUP_DEVICE_INFO_RETRY_SPACING_MS
        val max = AiDexRuntimePolicy.STARTUP_DEVICE_INFO_MAX_ATTEMPTS
        assertTrue(AiDexRuntimePolicy.shouldRequestStartupDeviceInfo(false, 0, 0L, 1_000L))
        // An unanswered request is retried, but not on the very next pass.
        assertFalse(AiDexRuntimePolicy.shouldRequestStartupDeviceInfo(false, 1, 1_000L, 1_000L + spacing - 1))
        assertTrue(AiDexRuntimePolicy.shouldRequestStartupDeviceInfo(false, 1, 1_000L, 1_000L + spacing))
        assertTrue(AiDexRuntimePolicy.shouldRequestStartupDeviceInfo(false, max - 1, 1_000L, 1_000L + spacing))
        // Bounded: a sensor that never answers is not polled for the rest of the connection.
        assertFalse(AiDexRuntimePolicy.shouldRequestStartupDeviceInfo(false, max, 1_000L, 10L * 60_000L))
        // Nothing to ask once the startup metadata is complete.
        assertFalse(AiDexRuntimePolicy.shouldRequestStartupDeviceInfo(true, 0, 0L, 1_000L))
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
    fun shouldRequestRoutineCalibrationRefresh_stopsOnceTheSensorReportsAnEmptyRange() {
        // A never-calibrated sensor can never populate the record cache, so without this the
        // routine refresh re-armed on every live reading for the whole sensor life.
        assertFalse(
            AiDexRuntimePolicy.shouldRequestRoutineCalibrationRefresh(
                hasCachedCalibrationRecords = false,
                calibrationDownloading = false,
                calibrationRangeKnownEmpty = true,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldRequestRoutineCalibrationRefresh(
                hasCachedCalibrationRecords = false,
                calibrationDownloading = false,
                calibrationRangeKnownEmpty = false,
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
    fun cccdStartDelay_holdsTheFirstWriteUntilTheMtuBearerHasBeenQuiet() {
        // Reconnect on a cached GATT db: services discovered ~50ms after our MTU callback,
        // the sensor's own exchange still to come. Wait out the rest of the window.
        assertEquals(
            700L,
            AiDexRuntimePolicy.cccdStartDelayMs(lastMtuCallbackAtMs = 10_000L, nowMs = 10_050L, settleMs = 750L)
        )
        // First connect: full discovery took longer than the window, write immediately.
        assertEquals(
            0L,
            AiDexRuntimePolicy.cccdStartDelayMs(lastMtuCallbackAtMs = 10_000L, nowMs = 11_200L, settleMs = 750L)
        )
        assertEquals(
            0L,
            AiDexRuntimePolicy.cccdStartDelayMs(lastMtuCallbackAtMs = 10_000L, nowMs = 10_750L, settleMs = 750L)
        )
    }

    @Test
    fun cccdStartDelay_noMtuCallbackMeansNoHold() {
        // Stack never delivered onMtuChanged and the fallback started discovery.
        assertEquals(
            0L,
            AiDexRuntimePolicy.cccdStartDelayMs(lastMtuCallbackAtMs = 0L, nowMs = 10_050L, settleMs = 750L)
        )
    }

    @Test
    fun cccdStartDelay_clockStepBackwardsWaitsAFullWindow() {
        assertEquals(
            750L,
            AiDexRuntimePolicy.cccdStartDelayMs(lastMtuCallbackAtMs = 10_000L, nowMs = 9_000L, settleMs = 750L)
        )
    }

    @Test
    fun mtuExchangeCrossedPendingCccd_onlyWhileAChainWriteIsOutstanding() {
        assertTrue(
            AiDexRuntimePolicy.mtuExchangeCrossedPendingCccd(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                cccdWriteInProgress = true,
                hasPendingCccd = true,
            )
        )
        // Chain queued but not started: the settle window simply restarts.
        assertFalse(
            AiDexRuntimePolicy.mtuExchangeCrossedPendingCccd(
                phase = AiDexBleManager.Phase.CCCD_CHAIN,
                cccdWriteInProgress = false,
                hasPendingCccd = false,
            )
        )
        // Our own first exchange, before discovery.
        assertFalse(
            AiDexRuntimePolicy.mtuExchangeCrossedPendingCccd(
                phase = AiDexBleManager.Phase.DISCOVERING_SERVICES,
                cccdWriteInProgress = false,
                hasPendingCccd = false,
            )
        )
        // Post-key-exchange CCCD re-registration is not the setup chain.
        assertFalse(
            AiDexRuntimePolicy.mtuExchangeCrossedPendingCccd(
                phase = AiDexBleManager.Phase.KEY_EXCHANGE,
                cccdWriteInProgress = true,
                hasPendingCccd = true,
            )
        )
    }

    @Test
    fun decideMissingCccdCallbackAction_reconnectsAtTheFirstWindowWhenAnMtuExchangeCrossedTheWrite() {
        assertEquals(
            AiDexRuntimePolicy.MissingCccdCallbackAction.RECOVER_WEDGED_GATT,
            AiDexRuntimePolicy.decideMissingCccdCallbackAction(
                cccdWriteInProgress = true,
                hasPendingCccd = true,
                timeoutRetries = 0,
                maxRetries = 1,
                canInferComplete = true,
                mtuExchangeCrossedWrite = true,
            )
        )
        // The callback did arrive after all: nothing pending, nothing to recover.
        assertEquals(
            AiDexRuntimePolicy.MissingCccdCallbackAction.IGNORE,
            AiDexRuntimePolicy.decideMissingCccdCallbackAction(
                cccdWriteInProgress = false,
                hasPendingCccd = false,
                timeoutRetries = 0,
                maxRetries = 1,
                canInferComplete = true,
                mtuExchangeCrossedWrite = true,
            )
        )
    }

    @Test
    fun shortBondRead_isRereadOnceThenCountsAsAKeyExchangeFailure() {
        // 19:52 journal: unbonded phone, saved key, F002 answers a lone 00 and the sensor
        // hangs up 7s later. Before this the loop never touched keyExchangeFailures.
        assertEquals(
            AiDexRuntimePolicy.ShortBondReadAction.ACCEPT,
            AiDexRuntimePolicy.decideShortBondReadAction(replyLength = 17, rereads = 0, maxRereads = 1)
        )
        assertEquals(
            AiDexRuntimePolicy.ShortBondReadAction.REREAD,
            AiDexRuntimePolicy.decideShortBondReadAction(replyLength = 1, rereads = 0, maxRereads = 1)
        )
        assertEquals(
            AiDexRuntimePolicy.ShortBondReadAction.FAIL_KEY_EXCHANGE,
            AiDexRuntimePolicy.decideShortBondReadAction(replyLength = 1, rereads = 1, maxRereads = 1)
        )
        assertEquals(
            AiDexRuntimePolicy.ShortBondReadAction.ACCEPT,
            AiDexRuntimePolicy.decideShortBondReadAction(replyLength = 17, rereads = 1, maxRereads = 1)
        )
    }

    @Test
    fun refusedBondRead_endsInPressPairForAnUnbondedPhoneAndReplaceForABondedOne() {
        // Three refusals on an unbonded link: hold in broadcast-only, tell the user to Pair;
        // the Pair button then runs FRESH_PAIR because the saved key is exhausted.
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.BROADCAST_ONLY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(
                consecutiveFailures = 3, maxFailures = 3, usedSavedKey = true, bonded = false,
            )
        )
        assertEquals(
            AiDexRuntimePolicy.PairKeyStartAction.FRESH_PAIR,
            AiDexRuntimePolicy.decidePairKeyStartAction(
                hasSavedPairKey = true, savedKeyExhausted = true, explicitPairRequested = true, bonded = false,
            )
        )
        assertEquals(
            AiDexRuntimePolicy.KeyExchangeFailureAction.REPLACE_SAVED_KEY,
            AiDexRuntimePolicy.decideKeyExchangeFailureAction(
                consecutiveFailures = 3, maxFailures = 3, usedSavedKey = true, bonded = true,
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
    fun decideInvalidSetupRecoveryAction_neverRemovesBondAutomatically() {
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
            AiDexRuntimePolicy.InvalidSetupRecoveryAction.RECONNECT,
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
