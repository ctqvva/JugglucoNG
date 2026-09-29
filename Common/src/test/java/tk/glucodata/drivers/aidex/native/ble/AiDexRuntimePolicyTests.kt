package tk.glucodata.drivers.aidex.native.ble

import android.bluetooth.BluetoothDevice
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.native.ble.AiDexRuntimePolicy.BroadcastFallbackExit
import tk.glucodata.drivers.aidex.native.ble.AiDexRuntimePolicy.ClearStorageResponseOutcome
import tk.glucodata.drivers.aidex.native.ble.AiDexRuntimePolicy.DisconnectOwner
import tk.glucodata.drivers.aidex.native.ble.AiDexRuntimePolicy.PostResetReconnectStep
import tk.glucodata.drivers.aidex.native.ble.AiDexRuntimePolicy.QuietWindowExpiry
import tk.glucodata.drivers.aidex.native.ble.AiDexRuntimePolicy.ResetAdmission
import tk.glucodata.drivers.aidex.native.ble.AiDexRuntimePolicy.UnconfirmedWriteAction
import tk.glucodata.drivers.aidex.native.ble.AiDexRuntimePolicy.UnpairAdmission
import tk.glucodata.drivers.aidex.native.protocol.AiDexOpcodes

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
        // retries: the sensor refuses F001 while another device holds the bond slot. (A pending
        // reset is the exception; see pairKeyStartAction_aConfirmedResetPairsFreshOverTheKeptKey.)
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
        // (After an accepted erase there is one: RESTORE_SAVED_KEY.)
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
    fun lifecycleReset_isAllowedOnlyWhenTheVersionDecides() {
        val allowed = AiDexRuntimePolicy.LifecycleReset.ALLOWED
        val refused = AiDexRuntimePolicy.LifecycleReset.REFUSED
        val unknown = AiDexRuntimePolicy.LifecycleReset.UNKNOWN
        assertEquals(allowed, AiDexRuntimePolicy.lifecycleReset("1.6.0"))
        assertEquals(allowed, AiDexRuntimePolicy.lifecycleReset("1.7"))
        assertEquals(allowed, AiDexRuntimePolicy.lifecycleReset("1.8.2"))
        assertEquals(refused, AiDexRuntimePolicy.lifecycleReset("1.8.3"))
        assertEquals(refused, AiDexRuntimePolicy.lifecycleReset("1.9"))
        assertEquals(refused, AiDexRuntimePolicy.lifecycleReset("2"))
        // What 0x10/0x21 report for both 1.8.0 and 1.8.3: the send waits for the DIS revision.
        assertEquals(unknown, AiDexRuntimePolicy.lifecycleReset("1.8"))
        assertEquals(unknown, AiDexRuntimePolicy.lifecycleReset("1"))
        assertEquals(unknown, AiDexRuntimePolicy.lifecycleReset(""))
        assertEquals(unknown, AiDexRuntimePolicy.lifecycleReset(null))
        assertEquals(unknown, AiDexRuntimePolicy.lifecycleReset("V1.8.3"))
        // The button follows only a known refusal.
        assertTrue(AiDexRuntimePolicy.supportsLifecycleReset("1.8"))
        assertFalse(AiDexRuntimePolicy.supportsLifecycleReset("1.8.3"))
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
    fun shouldRequestRoutineCalibrationRefresh_stopsAfterAFruitlessFetchAttempt() {
        // A non-empty range whose records never parse leaves the cache empty too, and the fetch
        // clears calibrationDownloading on a fixed delay after the last request. Without the
        // attempt flag the refresh re-armed on every live reading and re-ran the whole fetch
        // roughly once a minute for the whole wear time.
        //
        // The flag means "the query was sent": requestRoutineCalibrationRefreshIfNeeded sets it when
        // it enqueues 0x26, so on the same connection a reply that is lost or rejected cannot re-arm
        // the refresh either. This predicate only answers for the state it is given.
        assertFalse(
            AiDexRuntimePolicy.shouldRequestRoutineCalibrationRefresh(
                hasCachedCalibrationRecords = false,
                calibrationDownloading = false,
                calibrationRangeKnownEmpty = false,
                calibrationFetchAttempted = true,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.shouldRequestRoutineCalibrationRefresh(
                hasCachedCalibrationRecords = false,
                calibrationDownloading = false,
                calibrationRangeKnownEmpty = false,
                calibrationFetchAttempted = false,
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

    private fun zeroStart(
        debt: Boolean = false,
        attempted: Boolean = false,
        storedKey: Boolean = false,
        history: Boolean = false,
        reading: Boolean = false,
        bondValidated: Boolean = false,
    ) = AiDexRuntimePolicy.decideZeroSessionStartActivation(
        needsPostResetActivation = debt,
        autoActivationAttemptedThisConnection = attempted,
        hasStoredPairCredential = storedKey,
        hasDownloadedHistory = history,
        hasAcceptedReading = reading,
        bondValidatedByStreaming = bondValidated,
    )

    @Test
    fun decideZeroSessionStartActivation_startsASensorWithNoTraceOfAnEarlierSession() {
        // The field case: a new sensor reads zeros until 0x20 and never produces glucose without
        // it, so the setup stalls forever unless the driver starts it here.
        assertEquals(AiDexRuntimePolicy.ZeroStartActivation.FRESH_SENSOR, zeroStart())
    }

    @Test
    fun decideZeroSessionStartActivation_neverRestartsASensorThisAppHasSeenRunning() {
        // 0x20 restamps the session and quarantines the stored ring: each piece of evidence alone
        // has to be enough to keep a zero read away from the command.
        val known = AiDexRuntimePolicy.ZeroStartActivation.KNOWN_RUNNING
        assertEquals(known, zeroStart(storedKey = true))
        assertEquals(known, zeroStart(history = true))
        assertEquals(known, zeroStart(reading = true))
        assertEquals(known, zeroStart(bondValidated = true))
    }

    @Test
    fun decideZeroSessionStartActivation_aConfirmedEraseOutranksTheEvidence() {
        // The sensor itself answered this app's 0xF3 with 0x01: the old session is gone even
        // though the key, the history and the bond from it are all still here.
        assertEquals(
            AiDexRuntimePolicy.ZeroStartActivation.POST_RESET,
            zeroStart(debt = true, storedKey = true, history = true, reading = true, bondValidated = true),
        )
        assertEquals(AiDexRuntimePolicy.ZeroStartActivation.POST_RESET, zeroStart(debt = true))
    }

    @Test
    fun decideZeroSessionStartActivation_neverSendsASecondOneOnTheSameConnection() {
        // A lost ACK must not become a blind retry of an actuator command.
        val attempted = AiDexRuntimePolicy.ZeroStartActivation.ALREADY_ATTEMPTED
        assertEquals(attempted, zeroStart(attempted = true))
        assertEquals(attempted, zeroStart(attempted = true, debt = true))
        assertEquals(attempted, zeroStart(attempted = true, storedKey = true))
    }

    @Test
    fun isRetryableF002Query_neverReplaysAStateChangingWrite() {
        // A missing or failed write callback does not prove the sensor never got the frame,
        // so anything that moves sensor state has to fail into a safe state instead of being
        // re-sent. opcode 0 stands for an untagged write and must default to unretryable.
        for (opcode in listOf(
            AiDexOpcodes.SET_NEW_SENSOR,
            AiDexOpcodes.SET_CALIBRATION,
            AiDexOpcodes.SET_DEFAULT_PARAM,
            AiDexOpcodes.SET_AUTO_UPDATE_STATUS,
            AiDexOpcodes.SET_DYNAMIC_ADV_MODE,
            AiDexOpcodes.RESET,
            AiDexOpcodes.SHELF_MODE,
            AiDexOpcodes.DELETE_BOND,
            AiDexOpcodes.CLEAR_STORAGE,
            0,
        )) {
            assertFalse(
                "opcode 0x${opcode.toString(16)} must never be re-sent",
                AiDexRuntimePolicy.isRetryableF002Query(opcode)
            )
        }
        for (opcode in listOf(
            AiDexOpcodes.GET_STARTUP_DEVICE_INFO,
            AiDexOpcodes.GET_BROADCAST_DATA,
            AiDexOpcodes.GET_LOCAL_START_TIME,
            AiDexOpcodes.GET_HISTORY_RANGE,
            AiDexOpcodes.GET_HISTORIES_RAW,
            AiDexOpcodes.GET_HISTORIES,
            AiDexOpcodes.GET_CALIBRATION_RANGE,
            AiDexOpcodes.GET_CALIBRATION,
            AiDexOpcodes.GET_DEFAULT_PARAM,
        )) {
            assertTrue(
                "read-only query 0x${opcode.toString(16)} should stay retryable",
                AiDexRuntimePolicy.isRetryableF002Query(opcode)
            )
        }
    }

    @Test
    fun maySendSetNewSensor_refusesEveryUnreadyPath() {
        assertFalse(
            AiDexRuntimePolicy.maySendSetNewSensor(
                gattReady = false,
                servicesReady = true,
                sessionKeyPresent = true,
                resetInFlight = false,
                unpairInFlight = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.maySendSetNewSensor(
                gattReady = true,
                servicesReady = false,
                sessionKeyPresent = true,
                resetInFlight = false,
                unpairInFlight = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.maySendSetNewSensor(
                gattReady = true,
                servicesReady = true,
                sessionKeyPresent = false,
                resetInFlight = false,
                unpairInFlight = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.maySendSetNewSensor(
                gattReady = true,
                servicesReady = true,
                sessionKeyPresent = true,
                resetInFlight = true,
                unpairInFlight = false,
            )
        )
        assertFalse(
            AiDexRuntimePolicy.maySendSetNewSensor(
                gattReady = true,
                servicesReady = true,
                sessionKeyPresent = true,
                resetInFlight = false,
                unpairInFlight = true,
            )
        )
        assertTrue(
            AiDexRuntimePolicy.maySendSetNewSensor(
                gattReady = true,
                servicesReady = true,
                sessionKeyPresent = true,
                resetInFlight = false,
                unpairInFlight = false,
            )
        )
    }

    @Test
    fun setNewSensorLocalFate_coversNeverOnAirNackAndLostAck() {
        assertEquals(
            AiDexRuntimePolicy.SetNewSensorLocalFate.ROLLBACK_NEVER_ON_AIR,
            AiDexRuntimePolicy.setNewSensorLocalFate(dispatched = false, nackSeen = false),
        )
        assertEquals(
            AiDexRuntimePolicy.SetNewSensorLocalFate.ROLLBACK_SENSOR_NACK,
            AiDexRuntimePolicy.setNewSensorLocalFate(dispatched = true, nackSeen = true),
        )
        assertEquals(
            AiDexRuntimePolicy.SetNewSensorLocalFate.KEEP_DISPATCHED_UNCONFIRMED,
            AiDexRuntimePolicy.setNewSensorLocalFate(dispatched = true, nackSeen = false),
        )
        assertEquals(
            AiDexRuntimePolicy.SetNewSensorLocalFate.ROLLBACK_SENSOR_NACK,
            AiDexRuntimePolicy.setNewSensorLocalFate(dispatched = true, nackSeen = true),
        )
    }

    @Test
    fun isSetNewSensorNack_requiresStatusByte() {
        assertFalse(AiDexRuntimePolicy.isSetNewSensorNack(byteArrayOf()))
        assertFalse(AiDexRuntimePolicy.isSetNewSensorNack(byteArrayOf(AiDexOpcodes.SET_NEW_SENSOR.toByte())))
        assertFalse(
            AiDexRuntimePolicy.isSetNewSensorNack(
                byteArrayOf(AiDexOpcodes.SET_NEW_SENSOR.toByte(), 0x00),
            ),
        )
        // 0x01 is no refusal on its own: a field sensor answered it and started.
        assertFalse(
            AiDexRuntimePolicy.isSetNewSensorNack(
                byteArrayOf(AiDexOpcodes.SET_NEW_SENSOR.toByte(), 0x01),
            ),
        )
        for (status in listOf(0x02, 0x7F, 0xFF)) {
            assertTrue(
                "status $status stays a refusal",
                AiDexRuntimePolicy.isSetNewSensorNack(
                    byteArrayOf(AiDexOpcodes.SET_NEW_SENSOR.toByte(), status.toByte()),
                ),
            )
        }
        assertFalse(
            AiDexRuntimePolicy.isSetNewSensorNack(
                byteArrayOf(AiDexOpcodes.GET_HISTORY_RANGE.toByte(), 0x02),
            ),
        )
    }

    @Test
    fun setNewSensorAck_onlyTheFieldObserved0x01IsLeftToTheSessionStart() {
        assertEquals(AiDexRuntimePolicy.SetNewSensorAck.ACCEPTED, AiDexRuntimePolicy.setNewSensorAck(0x00))
        assertEquals(AiDexRuntimePolicy.SetNewSensorAck.UNCONFIRMED, AiDexRuntimePolicy.setNewSensorAck(0x01))
        for (status in 0x02..0xFF) {
            assertEquals(AiDexRuntimePolicy.SetNewSensorAck.REFUSED, AiDexRuntimePolicy.setNewSensorAck(status))
        }
    }

    @Test
    fun decideUnconfirmedActivation_theSensorsOwnStartDecides() {
        val dispatch = 1_790_191_111_000L
        val slack = 120_000L
        fun decide(start: Long, now: Long = 10_500L, dispatchAt: Long = dispatch) =
            AiDexRuntimePolicy.decideUnconfirmedActivation(
                startMs = start,
                dispatchAtMs = dispatchAt,
                ackAtMs = 10_000L,
                nowMs = now,
                settleMs = 3_000L,
                slackMs = slack,
            )
        // The field case: the new start is the 0x20's own stamp.
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.STARTED, decide(start = dispatch))
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.STARTED, decide(start = dispatch - slack))
        // The driver reads for the verdict at the settle time or later: a confirming start read then
        // is still STARTED, never overtaken by the settle rule.
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.STARTED, decide(start = dispatch, now = 13_000L))
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.STARTED, decide(start = dispatch - slack, now = 60_000L))
        // A non-zero start older than the 0x20 is the session the sensor kept: never "took effect".
        // Like zeros, it gets the settle time first (the sensor may not have updated 0x2AAA yet).
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.READ_AGAIN, decide(start = dispatch - slack - 1L, now = 12_999L))
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.REFUSED, decide(start = dispatch - slack - 1L, now = 13_000L))
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.REFUSED, decide(start = dispatch - 7L * 86_400_000L, now = 13_000L))
        // Zeros too soon may be a sensor that has not updated 0x2AAA yet; later zeros refuse.
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.READ_AGAIN, decide(start = 0L, now = 12_999L))
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.REFUSED, decide(start = 0L, now = 13_000L))
        // Without a dispatch time nothing can rule a real start out.
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.STARTED, decide(start = 1_000L, dispatchAt = 0L))
        assertEquals(AiDexRuntimePolicy.UnconfirmedActivation.STARTED, decide(start = 1_000L, dispatchAt = 0L, now = 13_000L))
    }

    @Test
    fun defaultParam0x30_bothActuatorBarriersAreDown() {
        // 0x30 rewrites the sensor's whole parameter table and has never been byte-compared with
        // the official app. Both barriers are pinned here rather than in a private companion
        // constant, so flipping either one turns this red instead of passing a green run. They
        // lift together, on a capture — and this test is updated in the same commit, deliberately.
        assertFalse(
            "unattended 0x30 apply must stay off until the write is verified on hardware",
            AiDexRuntimePolicy.mayAutoApplyDefaultParam()
        )
        assertFalse(
            "with no verified 0x30 ACK status an apply must refuse to start at all",
            AiDexRuntimePolicy.hasVerifiedDefaultParamAckStatus()
        )
    }

    @Test
    fun isDefaultParamAckAccepted_refusesEveryUnverifiedStatus() {
        // A short ACK carries no status at all, and no byte value is a verified success code:
        // 0xF3 and 0xF2 answer 0x01, and 0x20 has been seen answering both 0x00 and 0x01, so picking
        // any is a blind guess between incompatible conventions. Pushing the remaining chunks over a refusal would leave half
        // the old table and half the new one on the sensor, so every case must read as "stop".
        assertFalse("a missing status is not an accept", AiDexRuntimePolicy.isDefaultParamAckAccepted(null))
        assertFalse("0x00 is not verified for 0x30", AiDexRuntimePolicy.isDefaultParamAckAccepted(0x00))
        assertFalse("0x01 is not verified for 0x30", AiDexRuntimePolicy.isDefaultParamAckAccepted(0x01))
        for (status in 0..0xFF) {
            assertFalse(
                "status 0x${status.toString(16)} must not count as accepted",
                AiDexRuntimePolicy.isDefaultParamAckAccepted(status)
            )
        }
    }

    // -- R1: 0xF3 ACK ---------------------------------------------------------------------------

    @Test
    fun commandAckStatus_readsByteOneUnsignedAndAMissingOneAs0xFF() {
        val f3 = AiDexOpcodes.CLEAR_STORAGE.toByte()
        assertEquals(0x00, AiDexRuntimePolicy.commandAckStatus(byteArrayOf(f3, 0x00)))
        assertEquals(0x81, AiDexRuntimePolicy.commandAckStatus(byteArrayOf(f3, 0x81.toByte(), 0x12)))
        assertEquals(0xFF, AiDexRuntimePolicy.commandAckStatus(byteArrayOf(f3)))
        assertEquals(0xFF, AiDexRuntimePolicy.commandAckStatus(byteArrayOf()))
    }

    @Test
    fun isClearStorageAccepted_onlyABare0xF3AckCarryingStatusOne() {
        val f3 = AiDexOpcodes.CLEAR_STORAGE.toByte()
        assertTrue(AiDexRuntimePolicy.isClearStorageAccepted(byteArrayOf(f3, 0x01)))
        assertTrue(
            "trailing CRC bytes are not read",
            AiDexRuntimePolicy.isClearStorageAccepted(byteArrayOf(f3, 0x01, 0x5A, 0xA5.toByte())),
        )
        assertFalse("no status byte is not an accept", AiDexRuntimePolicy.isClearStorageAccepted(byteArrayOf(f3)))
        assertFalse(
            "0x00 is the 1.8.3 refusal",
            AiDexRuntimePolicy.isClearStorageAccepted(byteArrayOf(f3, 0x00)),
        )
        assertFalse(
            "a reply longer than an ACK is not one",
            AiDexRuntimePolicy.isClearStorageAccepted(byteArrayOf(f3, 0x01, 0x5A, 0xA5.toByte(), 0x11)),
        )
        assertFalse(
            "a 0x20 answer is not a 0xF3 accept",
            AiDexRuntimePolicy.isClearStorageAccepted(byteArrayOf(AiDexOpcodes.SET_NEW_SENSOR.toByte(), 0x01)),
        )
        assertFalse(AiDexRuntimePolicy.isClearStorageAccepted(byteArrayOf()))
    }

    @Test
    fun clearStorageAckStatus_readsAnythingLongerThanAnAckAsARefusal() {
        val f3 = AiDexOpcodes.CLEAR_STORAGE.toByte()
        assertEquals(0x01, AiDexRuntimePolicy.clearStorageAckStatus(byteArrayOf(f3, 0x01, 0x5A, 0xA5.toByte())))
        assertEquals(0x00, AiDexRuntimePolicy.clearStorageAckStatus(byteArrayOf(f3, 0x00)))
        assertEquals(0xFF, AiDexRuntimePolicy.clearStorageAckStatus(byteArrayOf(f3, 0x01, 0x5A, 0xA5.toByte(), 0x11)))
        assertEquals(0xFF, AiDexRuntimePolicy.clearStorageAckStatus(byteArrayOf(f3)))
    }

    @Test
    fun isClearStorageAccepted_agreesWithTheHandlerOutcome() {
        // The binder peek claims the erase before the handler sees the frame. The two must never
        // disagree on which frames confirm it: a peek-only accept would confirm an erase the
        // handler refuses, and a handler-only accept is the one the DISCONNECTED wipe can delete.
        val f3 = AiDexOpcodes.CLEAR_STORAGE.toByte()
        val frames = listOf(
            byteArrayOf(),
            byteArrayOf(f3),
            byteArrayOf(f3, 0x00),
            byteArrayOf(f3, 0x01),
            byteArrayOf(f3, 0xFF.toByte()),
            byteArrayOf(f3, 0x01, 0x11),
            byteArrayOf(f3, 0x01, 0x11, 0x22),
            byteArrayOf(f3, 0x01, 0x11, 0x22, 0x33),
            byteArrayOf(f3, 0x01) + ByteArray(16) { it.toByte() },
            byteArrayOf(AiDexOpcodes.SET_NEW_SENSOR.toByte(), 0x01),
            byteArrayOf(AiDexOpcodes.DELETE_BOND.toByte(), 0x01),
            byteArrayOf(0x00, 0x01),
        )
        for (frame in frames) {
            val handlerConfirms = frame.isNotEmpty() &&
                (frame[0].toInt() and 0xFF) == AiDexOpcodes.CLEAR_STORAGE &&
                AiDexRuntimePolicy.clearStorageResponseOutcome(
                    resetPending = true,
                    ackStamped = false,
                    status = AiDexRuntimePolicy.clearStorageAckStatus(frame),
                ) == ClearStorageResponseOutcome.CONFIRM
            assertEquals(
                frame.joinToString(" ") { "%02X".format(it) },
                handlerConfirms,
                AiDexRuntimePolicy.isClearStorageAccepted(frame),
            )
        }
    }

    @Test
    fun clearStorageOutcome_exhaustiveOverLatchStampAndStatus() {
        for (pending in listOf(false, true)) for (stamped in listOf(false, true)) for (status in 0..0xFF) {
            val outcome = AiDexRuntimePolicy.clearStorageResponseOutcome(pending, stamped, status)
            val label = "pending=$pending stamped=$stamped status=0x${"%02X".format(status)}"
            assertEquals(label, pending && status == 0x01, outcome == ClearStorageResponseOutcome.CONFIRM)
            assertEquals(
                label,
                pending && !stamped && status != 0x01,
                outcome == ClearStorageResponseOutcome.ABANDON,
            )
            assertEquals(
                label,
                !pending && !stamped && status == 0x01,
                outcome == ClearStorageResponseOutcome.LATE_ACCEPT,
            )
        }
    }

    @Test
    fun clearStorageOutcome_duplicateAcceptIsAnIdempotentConfirm() {
        // The binder peek stamped first; the handler's own CONFIRM must still run its commit path.
        assertEquals(
            ClearStorageResponseOutcome.CONFIRM,
            AiDexRuntimePolicy.clearStorageResponseOutcome(resetPending = true, ackStamped = true, status = 0x01),
        )
    }

    @Test
    fun clearStorageOutcome_refusalAfterAConfirmedAcceptIsIgnored_failsOnHead() {
        // HEAD called abandonPendingReset for every other status, and that cleared the debt and
        // the barrier of the erase the sensor had just confirmed.
        assertEquals(
            ClearStorageResponseOutcome.IGNORE,
            AiDexRuntimePolicy.clearStorageResponseOutcome(resetPending = true, ackStamped = true, status = 0x00),
        )
        assertEquals(
            ClearStorageResponseOutcome.IGNORE,
            AiDexRuntimePolicy.clearStorageResponseOutcome(resetPending = false, ackStamped = true, status = 0x00),
        )
    }

    @Test
    fun clearStorageOutcome_refusalWithNothingPendingIsIgnored_failsOnHead() {
        // HEAD abandoned here too — with no attempt left to abandon, only an earlier erase's debt.
        assertEquals(
            ClearStorageResponseOutcome.IGNORE,
            AiDexRuntimePolicy.clearStorageResponseOutcome(resetPending = false, ackStamped = false, status = 0x00),
        )
        assertEquals(
            ClearStorageResponseOutcome.IGNORE,
            AiDexRuntimePolicy.clearStorageResponseOutcome(resetPending = false, ackStamped = false, status = 0xFF),
        )
    }

    @Test
    fun clearStorageOutcome_acceptAfterAbandonIsOnlyReported() {
        assertEquals(
            ClearStorageResponseOutcome.LATE_ACCEPT,
            AiDexRuntimePolicy.clearStorageResponseOutcome(resetPending = false, ackStamped = false, status = 0x01),
        )
        assertEquals(
            "0x00, the 1.8.3 answer, abandons",
            ClearStorageResponseOutcome.ABANDON,
            AiDexRuntimePolicy.clearStorageResponseOutcome(resetPending = true, ackStamped = false, status = 0x00),
        )
    }

    // -- R2: session start vs the reset (pin) ----------------------------------------------------

    @Test
    fun sessionStartPredatesReset_pinsTheTwoMinuteSlack() {
        val resetAt = 1_782_900_000_000L
        val slack = AiDexRuntimePolicy.POST_RESET_START_SLACK_MS
        assertEquals(120_000L, slack)

        assertFalse(
            "no debt, no force",
            AiDexRuntimePolicy.sessionStartPredatesReset(false, resetAt, resetAt - slack - 1),
        )
        // With no barrier the comparison would be startMs < -120_000; only the guard stops it.
        assertFalse(AiDexRuntimePolicy.sessionStartPredatesReset(true, 0L, -slack - 1))
        assertFalse(AiDexRuntimePolicy.sessionStartPredatesReset(true, 0L, resetAt - slack - 1))

        assertTrue(AiDexRuntimePolicy.sessionStartPredatesReset(true, resetAt, resetAt - slack - 1))
        assertFalse(
            "exactly barrier - 2 min is not older than it",
            AiDexRuntimePolicy.sessionStartPredatesReset(true, resetAt, resetAt - slack),
        )
        assertFalse(AiDexRuntimePolicy.sessionStartPredatesReset(true, resetAt, resetAt - 90_000L))
        assertFalse(AiDexRuntimePolicy.sessionStartPredatesReset(true, resetAt, resetAt))
        assertFalse(AiDexRuntimePolicy.sessionStartPredatesReset(true, resetAt, resetAt + 1))
    }

    // -- R3: bond after a confirmed erase, quiet window ----------------------------------------------

    @Test
    fun postResetReconnectStep_confirmedEraseOnALiveBondHolds_failsOnHead() {
        // HEAD connected 5 s later whatever the bond state.
        assertEquals(
            PostResetReconnectStep.HOLD_FOR_BOND_REMOVAL,
            AiDexRuntimePolicy.postResetReconnectStep(true, BluetoothDevice.BOND_BONDED),
        )
        assertEquals(
            PostResetReconnectStep.HOLD_FOR_BOND_REMOVAL,
            AiDexRuntimePolicy.postResetReconnectStep(true, BluetoothDevice.BOND_BONDING),
        )
    }

    @Test
    fun postResetReconnectStep_everythingElseConnects() {
        assertEquals(PostResetReconnectStep.CONNECT, AiDexRuntimePolicy.postResetReconnectStep(true, BluetoothDevice.BOND_NONE))
        assertEquals(PostResetReconnectStep.CONNECT, AiDexRuntimePolicy.postResetReconnectStep(true, null))
        for (bond in listOf(BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED, null)) {
            assertEquals(
                "unconfirmed erase, bond=$bond",
                PostResetReconnectStep.CONNECT,
                AiDexRuntimePolicy.postResetReconnectStep(false, bond),
            )
        }
    }

    @Test
    fun quietWindowExpiry_pinsHeadOrder() {
        val written = 1_782_900_000_000L
        // Forgotten and "nothing pending" win over everything.
        assertEquals(QuietWindowExpiry.IGNORE, AiDexRuntimePolicy.decideQuietWindowExpiry(true, true, written, false, true))
        assertEquals(QuietWindowExpiry.IGNORE, AiDexRuntimePolicy.decideQuietWindowExpiry(true, true, 0L, false, false))
        assertEquals(QuietWindowExpiry.IGNORE, AiDexRuntimePolicy.decideQuietWindowExpiry(false, false, written, false, true))
        assertEquals(QuietWindowExpiry.IGNORE, AiDexRuntimePolicy.decideQuietWindowExpiry(false, false, 0L, false, false))
        // A 0xF3 that never left the phone is abandoned, with or without a GATT.
        assertEquals(
            QuietWindowExpiry.ABANDON_NEVER_DISPATCHED,
            AiDexRuntimePolicy.decideQuietWindowExpiry(false, true, 0L, false, true),
        )
        assertEquals(
            QuietWindowExpiry.ABANDON_NEVER_DISPATCHED,
            AiDexRuntimePolicy.decideQuietWindowExpiry(false, true, 0L, false, false),
        )
        assertEquals(
            QuietWindowExpiry.COMPLETE_NO_GATT,
            AiDexRuntimePolicy.decideQuietWindowExpiry(false, true, written, false, false),
        )
        assertEquals(
            QuietWindowExpiry.LOCAL_DISCONNECT,
            AiDexRuntimePolicy.decideQuietWindowExpiry(false, true, written, false, true),
        )
    }

    @Test
    fun quietWindowExpiry_aClaimedAckIsProofOfDispatch_failsOnHead() {
        // Before this change the write stamp alone decided: a 0x01 claimed on the binder before
        // drainGattQueue stamped the write sent the timer into ABANDON, abandonPendingReset refused
        // the confirmed attempt, and the latch and the window stayed up with no owner.
        assertEquals(
            QuietWindowExpiry.LOCAL_DISCONNECT,
            AiDexRuntimePolicy.decideQuietWindowExpiry(false, true, 0L, true, true),
        )
        assertEquals(
            QuietWindowExpiry.COMPLETE_NO_GATT,
            AiDexRuntimePolicy.decideQuietWindowExpiry(false, true, 0L, true, false),
        )
        // Forgotten or nothing pending still wins.
        assertEquals(QuietWindowExpiry.IGNORE, AiDexRuntimePolicy.decideQuietWindowExpiry(true, true, 0L, true, true))
        assertEquals(QuietWindowExpiry.IGNORE, AiDexRuntimePolicy.decideQuietWindowExpiry(false, false, 0L, true, true))
    }

    @Test
    fun postResetBondHold_leavesOnlyAfterTheSettleAndOnBondNone() {
        val notBefore = 1_000_000L
        // Before the settle delay nothing releases the hold, not even BOND_NONE.
        assertFalse(AiDexRuntimePolicy.mayLeavePostResetBondHold(notBefore - 1, notBefore, BluetoothDevice.BOND_NONE))
        assertTrue(AiDexRuntimePolicy.mayLeavePostResetBondHold(notBefore, notBefore, BluetoothDevice.BOND_NONE))
        assertTrue(AiDexRuntimePolicy.mayLeavePostResetBondHold(notBefore + 600_000L, notBefore, BluetoothDevice.BOND_NONE))
        // No timed return onto the old bond, however long the hold lasts.
        for (bond in listOf(BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_BONDING, null)) {
            assertFalse("bond=$bond", AiDexRuntimePolicy.mayLeavePostResetBondHold(Long.MAX_VALUE, notBefore, bond))
        }
    }

    // -- R3/R4: who owns a DISCONNECTED ------------------------------------------------------------

    @Test
    fun disconnectOwner_exhaustiveFirstMatchInTheFixedOrder() {
        val bools = listOf(false, true)
        for (unpair in bools) for (reset in bools) for (invalid in bools) for (stale in bools) {
            for (status in listOf(0, 5, 8, 19, 22, 133)) {
                val expected = listOf(
                    unpair to DisconnectOwner.UNCONFIRMED_UNPAIR,
                    reset to DisconnectOwner.POST_RESET,
                    invalid to DisconnectOwner.INVALID_SETUP_RECOVERY,
                    stale to DisconnectOwner.STALE_RECOVERY,
                    (status == 5) to DisconnectOwner.AUTH_FAILURE,
                ).firstOrNull { it.first }?.second ?: DisconnectOwner.DEFAULT
                assertEquals(
                    "unpair=$unpair reset=$reset invalid=$invalid stale=$stale status=$status",
                    expected,
                    AiDexRuntimePolicy.decideDisconnectOwner(unpair, reset, invalid, stale, status),
                )
            }
        }
    }

    @Test
    fun disconnectOwner_latchesOutrankRecoveryAndAuthExits_failsOnHead() {
        // HEAD ran the invalid-setup, stale-recovery and status-5 exits first and returned from
        // each, so every row here missed the unpair or post-reset branch.
        assertEquals(
            DisconnectOwner.UNCONFIRMED_UNPAIR,
            AiDexRuntimePolicy.decideDisconnectOwner(true, false, true, false, 0),
        )
        assertEquals(
            DisconnectOwner.UNCONFIRMED_UNPAIR,
            AiDexRuntimePolicy.decideDisconnectOwner(true, false, false, true, 19),
        )
        assertEquals(
            DisconnectOwner.UNCONFIRMED_UNPAIR,
            AiDexRuntimePolicy.decideDisconnectOwner(true, false, false, false, 5),
        )
        assertEquals(DisconnectOwner.POST_RESET, AiDexRuntimePolicy.decideDisconnectOwner(false, true, true, false, 0))
        assertEquals(DisconnectOwner.POST_RESET, AiDexRuntimePolicy.decideDisconnectOwner(false, true, false, true, 19))
        assertEquals(DisconnectOwner.POST_RESET, AiDexRuntimePolicy.decideDisconnectOwner(false, true, false, false, 5))
    }

    // -- R1/R4: admission ---------------------------------------------------------------------------

    @Test
    fun resetAdmission_allEightCombinationsInHeadOrder() {
        fun admit(reset: Boolean, unpair: Boolean, link: Boolean) =
            AiDexRuntimePolicy.decideResetAdmission(resetInFlight = reset, unpairInFlight = unpair, linkUsable = link)
        assertEquals(ResetAdmission.ALLOW, admit(reset = false, unpair = false, link = true))
        assertEquals(ResetAdmission.REFUSE_NOT_CONNECTED, admit(reset = false, unpair = false, link = false))
        assertEquals(ResetAdmission.REFUSE_UNPAIR_IN_FLIGHT, admit(reset = false, unpair = true, link = true))
        assertEquals(ResetAdmission.REFUSE_UNPAIR_IN_FLIGHT, admit(reset = false, unpair = true, link = false))
        assertEquals(ResetAdmission.REFUSE_RESET_IN_FLIGHT, admit(reset = true, unpair = false, link = true))
        assertEquals(ResetAdmission.REFUSE_RESET_IN_FLIGHT, admit(reset = true, unpair = false, link = false))
        assertEquals(ResetAdmission.REFUSE_RESET_IN_FLIGHT, admit(reset = true, unpair = true, link = true))
        assertEquals(ResetAdmission.REFUSE_RESET_IN_FLIGHT, admit(reset = true, unpair = true, link = false))
    }

    @Test
    fun unpairAdmission_all32CombinationsInOrder() {
        val bools = listOf(false, true)
        var allowed = 0
        for (unpair in bools) for (reset in bools) for (link in bools) for (quiet in bools) for (key in bools) {
            val expected = listOf(
                unpair to UnpairAdmission.ALREADY_IN_PROGRESS,
                reset to UnpairAdmission.REFUSE_RESET_IN_FLIGHT,
                !(key && link && !quiet) to UnpairAdmission.REFUSE_NOT_READY,
            ).firstOrNull { it.first }?.second ?: UnpairAdmission.ALLOW
            val actual = AiDexRuntimePolicy.decideUnpairAdmission(
                unpairInFlight = unpair,
                resetInFlight = reset,
                linkUsable = link,
                quietWindowActive = quiet,
                sessionKeyPresent = key,
            )
            assertEquals("unpair=$unpair reset=$reset link=$link quiet=$quiet key=$key", expected, actual)
            if (actual == UnpairAdmission.ALLOW) allowed++
        }
        assertEquals("only an idle, keyed, usable link outside the quiet window sends 0xF2", 1, allowed)
    }

    @Test
    fun unpairAdmission_secondPressAndResetLatch_failsOnHead() {
        // HEAD enqueued a second 0xF2 on a second press over a usable link.
        assertEquals(
            UnpairAdmission.ALREADY_IN_PROGRESS,
            AiDexRuntimePolicy.decideUnpairAdmission(
                unpairInFlight = true,
                resetInFlight = false,
                linkUsable = true,
                quietWindowActive = false,
                sessionKeyPresent = true,
            ),
        )
        // HEAD refused only on the quiet window, so a reset latch with the window down let 0xF2 out.
        assertEquals(
            UnpairAdmission.REFUSE_RESET_IN_FLIGHT,
            AiDexRuntimePolicy.decideUnpairAdmission(
                unpairInFlight = false,
                resetInFlight = true,
                linkUsable = true,
                quietWindowActive = false,
                sessionKeyPresent = true,
            ),
        )
    }

    // -- failGattWrite (pin) --------------------------------------------------------------------

    private fun unconfirmedWrite(
        opcode: Int,
        retryCount: Int = 0,
        callbackReceived: Boolean = true,
        ackStamped: Boolean = false,
        unpairPending: Boolean = false,
    ) = AiDexRuntimePolicy.decideUnconfirmedWrite(
        opcode = opcode,
        retryCount = retryCount,
        maxQueryRetries = 2, // GATT_OP_WATCHDOG_RETRIES on HEAD
        callbackReceived = callbackReceived,
        clearStorageAckStamped = ackStamped,
        unpairPending = unpairPending,
    )

    private val requeueOnly = UnconfirmedWriteAction(
        requeueQuery = true,
        abandonReset = false,
        abandonUnpair = false,
        calibrationUnconfirmed = false,
    )

    private val noAction = requeueOnly.copy(requeueQuery = false)

    @Test
    fun unconfirmedWrite_queriesRequeueWhileUnderTheRetryBudget() {
        val raw = AiDexOpcodes.GET_HISTORIES_RAW
        assertEquals(requeueOnly, unconfirmedWrite(raw, retryCount = 0))
        assertEquals(requeueOnly, unconfirmedWrite(raw, retryCount = 1))
        assertEquals(noAction, unconfirmedWrite(raw, retryCount = 2))
    }

    @Test
    fun unconfirmedWrite_clearStorageAbandonsOnlyOnAnErrorCallbackBeforeAnyAck() {
        val f3 = AiDexOpcodes.CLEAR_STORAGE
        assertEquals(noAction.copy(abandonReset = true), unconfirmedWrite(f3))
        assertEquals("no callback: the watchdog path", noAction, unconfirmedWrite(f3, callbackReceived = false))
        assertEquals("a stamped ACK outranks the transport status", noAction, unconfirmedWrite(f3, ackStamped = true))
    }

    @Test
    fun unconfirmedWrite_deleteBondAbandonsOnlyWithTheLatchSet() {
        assertEquals(
            noAction.copy(abandonUnpair = true),
            unconfirmedWrite(AiDexOpcodes.DELETE_BOND, unpairPending = true),
        )
        assertEquals(noAction, unconfirmedWrite(AiDexOpcodes.DELETE_BOND, unpairPending = false))
    }

    @Test
    fun unconfirmedWrite_calibrationIsReportedAndOtherActuatorsAreDroppedSilently() {
        assertEquals(noAction.copy(calibrationUnconfirmed = true), unconfirmedWrite(AiDexOpcodes.SET_CALIBRATION))
        for (opcode in listOf(AiDexOpcodes.SET_NEW_SENSOR, AiDexOpcodes.SET_DEFAULT_PARAM, 0)) {
            assertEquals(
                "opcode 0x${"%02X".format(opcode)}",
                noAction,
                unconfirmedWrite(opcode, unpairPending = true),
            )
        }
    }

    @Test
    fun unconfirmedWrite_everyOpcodeRequeuesIffItIsARetryableQuery() {
        for (opcode in 0..0xFF) {
            val expected = if (AiDexRuntimePolicy.isRetryableF002Query(opcode)) {
                requeueOnly
            } else {
                UnconfirmedWriteAction(
                    requeueQuery = false,
                    abandonReset = opcode == AiDexOpcodes.CLEAR_STORAGE,
                    abandonUnpair = opcode == AiDexOpcodes.DELETE_BOND,
                    calibrationUnconfirmed = opcode == AiDexOpcodes.SET_CALIBRATION,
                )
            }
            assertEquals(
                "opcode 0x${"%02X".format(opcode)}",
                expected,
                unconfirmedWrite(opcode, unpairPending = true),
            )
        }
    }

    // -- maybeLeaveBroadcastOnlyFallback (pin) ------------------------------------------------------

    @Test
    fun broadcastFallbackExit_pinsHeadGatesAndReadsTheScreenLast() {
        var screenReads = 0
        fun exit(
            enteredAt: Long = 1_000L,
            broadcastOnly: Boolean = true,
            stop: Boolean = false,
            paused: Boolean = false,
            unpaired: Boolean = false,
            now: Long = 601_000L,
            screen: Boolean? = true,
        ) = AiDexRuntimePolicy.decideBroadcastFallbackExit(
            enteredAtElapsed = enteredAt,
            broadcastOnly = broadcastOnly,
            stop = stop,
            paused = paused,
            unpaired = unpaired,
            nowElapsed = now,
            holdMs = 600_000L, // BROADCAST_FALLBACK_RETRY_MS = 10 min
            screenInteractive = {
                screenReads++
                screen
            },
        )

        assertEquals("0 is 'no timed return'", BroadcastFallbackExit.STAY, exit(enteredAt = 0L))
        assertEquals(BroadcastFallbackExit.STAY, exit(broadcastOnly = false))
        assertEquals(BroadcastFallbackExit.STAY, exit(stop = true))
        assertEquals(BroadcastFallbackExit.STAY, exit(paused = true))
        assertEquals(BroadcastFallbackExit.STAY, exit(unpaired = true))
        // 600_999 - 1_000 = 599_999 < 600_000.
        assertEquals(BroadcastFallbackExit.STAY, exit(now = 600_999L))
        assertEquals("the screen is read only after every cheap gate passed", 0, screenReads)

        // 601_000 - 1_000 = 600_000, the hold has expired.
        assertEquals(BroadcastFallbackExit.LEAVE, exit(screen = true))
        assertEquals("unknown screen state leaves, as on HEAD", BroadcastFallbackExit.LEAVE, exit(screen = null))
        assertEquals(BroadcastFallbackExit.STAY, exit(screen = false))
        assertEquals(3, screenReads)
    }

    // -- R9: session-start time zone ----------------------------------------------------------------

    @Test
    fun sessionStartOffset_decodesQuarterHours() {
        assertEquals(18_000, aiDexSessionStartOffsetSeconds(20, 0)) // 20 * 900
        assertEquals(-18_000, aiDexSessionStartOffsetSeconds(-20, 0))
        assertEquals(7_200, aiDexSessionStartOffsetSeconds(4, 4)) // 3_600 + 4 * 900
        assertEquals(10_800, aiDexSessionStartOffsetSeconds(4, 8)) // 3_600 + 8 * 900
        assertEquals("255 means DST unknown and adds nothing", 3_600, aiDexSessionStartOffsetSeconds(4, 255))
        assertEquals("0x80 means time zone unknown", 0, aiDexSessionStartOffsetSeconds(-128, 0))
        assertEquals("unsigned 0x80 is the same sentinel", 0, aiDexSessionStartOffsetSeconds(0x80, 0))
        assertEquals("1 is not a DST value the encoder writes", 0, aiDexSessionStartOffsetSeconds(0, 1))
    }

    @Test
    fun sessionStartOffset_halfHourDstCounts_failsOnHead() {
        // UTC+10:30 standard (42 quarters) with a 30-minute DST (2 quarters), as the 0x20 encoder
        // writes it for Lord Howe: 42 * 900 + 2 * 900 = 37_800 + 1_800. HEAD added DST only for 4
        // and decoded this as 37_800, half an hour off.
        assertEquals(39_600, aiDexSessionStartOffsetSeconds(42, 2))
    }

    @Test
    fun sessionStartOffset_roundTripsTheActivationEncoder() {
        fun noonUtc(month: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, month, 15, 12, 0, 0)
        }.timeInMillis

        // zone, month, expected UTC offset in seconds (checks the JVM's tzdata before the codec)
        val cases = listOf(
            Triple("Europe/Berlin", Calendar.JANUARY, 3_600),
            Triple("Europe/Berlin", Calendar.JULY, 7_200),
            Triple("America/New_York", Calendar.JULY, -14_400),
            Triple("Asia/Kolkata", Calendar.JULY, 19_800),
        )
        for ((id, month, offsetSeconds) in cases) {
            val tz = TimeZone.getTimeZone(id)
            assertEquals("tzdata has $id", id, tz.id)
            val instant = noonUtc(month)
            assertEquals("$id month=$month", offsetSeconds * 1000, tz.getOffset(instant))

            val cal = Calendar.getInstance(tz).apply { timeInMillis = instant }
            val encoded = aiDexActivationTimeZone(cal, tz)
            assertEquals(
                "$id month=$month tz=${encoded.tzQuarters} dst=${encoded.dstQuarters}",
                tz.getOffset(instant),
                aiDexSessionStartOffsetSeconds(encoded.tzQuarters, encoded.dstQuarters) * 1000,
            )
        }
    }
}
