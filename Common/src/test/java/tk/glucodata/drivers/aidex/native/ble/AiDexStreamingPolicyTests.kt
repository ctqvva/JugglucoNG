package tk.glucodata.drivers.aidex.native.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexStreamingPolicyTests {

    @Test
    fun decideNoStreamRecovery_keepsWaitingWhenBroadcastIsRecent() {
        assertEquals(
            AiDexStreamingPolicy.NoStreamRecoveryAction.KEEP_WAITING,
            AiDexStreamingPolicy.decideNoStreamRecovery(
                hasRecentBroadcastData = true,
                historyDownloading = false,
                allowConnectedBroadcastRequest = true,
                connectedBroadcastRequestAttempted = false,
                hasSessionFallbackData = false,
                historyRefreshAttempted = false,
                liveCccdRefreshAttempted = false,
            )
        )
    }

    @Test
    fun decideNoStreamRecovery_requestsHistoryRefreshWhenSessionFallbackAlreadyWorked() {
        assertEquals(
            AiDexStreamingPolicy.NoStreamRecoveryAction.REQUEST_HISTORY_REFRESH,
            AiDexStreamingPolicy.decideNoStreamRecovery(
                hasRecentBroadcastData = false,
                historyDownloading = false,
                allowConnectedBroadcastRequest = true,
                connectedBroadcastRequestAttempted = true,
                hasSessionFallbackData = true,
                historyRefreshAttempted = false,
                liveCccdRefreshAttempted = false,
            )
        )
    }

    @Test
    fun decideNoStreamRecovery_requestsConnectedBroadcastBeforeOtherRecovery() {
        assertEquals(
            AiDexStreamingPolicy.NoStreamRecoveryAction.REQUEST_CONNECTED_BROADCAST,
            AiDexStreamingPolicy.decideNoStreamRecovery(
                hasRecentBroadcastData = false,
                historyDownloading = false,
                allowConnectedBroadcastRequest = true,
                connectedBroadcastRequestAttempted = false,
                hasSessionFallbackData = false,
                historyRefreshAttempted = false,
                liveCccdRefreshAttempted = false,
            )
        )
    }

    @Test
    fun decideNoStreamRecovery_refreshesLiveCccdsAfterConnectedBroadcastWhenNoFallbackPathWasSeen() {
        assertEquals(
            AiDexStreamingPolicy.NoStreamRecoveryAction.REFRESH_LIVE_CCCDS,
            AiDexStreamingPolicy.decideNoStreamRecovery(
                hasRecentBroadcastData = false,
                historyDownloading = false,
                allowConnectedBroadcastRequest = true,
                connectedBroadcastRequestAttempted = true,
                hasSessionFallbackData = false,
                historyRefreshAttempted = false,
                liveCccdRefreshAttempted = false,
            )
        )
    }

    @Test
    fun decideNoStreamRecovery_reconnectsAfterBoundedRecoveryStepsAreExhausted() {
        assertEquals(
            AiDexStreamingPolicy.NoStreamRecoveryAction.RECONNECT,
            AiDexStreamingPolicy.decideNoStreamRecovery(
                hasRecentBroadcastData = false,
                historyDownloading = false,
                allowConnectedBroadcastRequest = true,
                connectedBroadcastRequestAttempted = true,
                hasSessionFallbackData = true,
                historyRefreshAttempted = true,
                liveCccdRefreshAttempted = true,
            )
        )
    }

    @Test
    fun shouldRefreshLiveCccdsAfterKeyExchange_falseForAlreadyBondedReconnect() {
        assertFalse(
            AiDexStreamingPolicy.shouldRefreshLiveCccdsAfterKeyExchange(
                bondStateAtConnection = BluetoothDevice.BOND_BONDED,
                bondBecameBondedThisConnection = false,
            )
        )
    }

    @Test
    fun shouldRefreshLiveCccdsAfterKeyExchange_trueWhenBondStateChangedThisConnection() {
        assertTrue(
            AiDexStreamingPolicy.shouldRefreshLiveCccdsAfterKeyExchange(
                bondStateAtConnection = BluetoothDevice.BOND_BONDED,
                bondBecameBondedThisConnection = true,
            )
        )
    }

    @Test
    fun shouldRefreshLiveCccdsAfterKeyExchange_trueWhenNotBondedAtConnectionStart() {
        assertTrue(
            AiDexStreamingPolicy.shouldRefreshLiveCccdsAfterKeyExchange(
                bondStateAtConnection = BluetoothDevice.BOND_NONE,
                bondBecameBondedThisConnection = false,
            )
        )
    }

    @Test
    fun shouldReadSessionCharacteristicsBeforeFirstLive_falseForKnownBondedReconnect() {
        assertFalse(
            AiDexStreamingPolicy.shouldReadSessionCharacteristicsBeforeFirstLive(
                bondStateAtConnection = BluetoothDevice.BOND_BONDED,
                bondBecameBondedThisConnection = false,
                autoActivationAttemptedThisConnection = false,
                needsPostResetActivation = false,
                hasPersistedHistoryState = true,
            )
        )
    }

    @Test
    fun shouldReadSessionCharacteristicsBeforeFirstLive_trueForBondTransition() {
        assertTrue(
            AiDexStreamingPolicy.shouldReadSessionCharacteristicsBeforeFirstLive(
                bondStateAtConnection = BluetoothDevice.BOND_BONDED,
                bondBecameBondedThisConnection = true,
                autoActivationAttemptedThisConnection = false,
                needsPostResetActivation = false,
                hasPersistedHistoryState = true,
            )
        )
    }

    @Test
    fun shouldReadSessionCharacteristicsBeforeFirstLive_trueWhenHistoryStateIsMissing() {
        assertTrue(
            AiDexStreamingPolicy.shouldReadSessionCharacteristicsBeforeFirstLive(
                bondStateAtConnection = BluetoothDevice.BOND_BONDED,
                bondBecameBondedThisConnection = false,
                autoActivationAttemptedThisConnection = false,
                needsPostResetActivation = false,
                hasPersistedHistoryState = false,
            )
        )
    }

    @Test
    fun shouldReadSessionCharacteristicsBeforeFirstLive_trueForActivationFlows() {
        assertTrue(
            AiDexStreamingPolicy.shouldReadSessionCharacteristicsBeforeFirstLive(
                bondStateAtConnection = BluetoothDevice.BOND_BONDED,
                bondBecameBondedThisConnection = false,
                autoActivationAttemptedThisConnection = true,
                needsPostResetActivation = false,
                hasPersistedHistoryState = true,
            )
        )
        assertTrue(
            AiDexStreamingPolicy.shouldReadSessionCharacteristicsBeforeFirstLive(
                bondStateAtConnection = BluetoothDevice.BOND_BONDED,
                bondBecameBondedThisConnection = false,
                autoActivationAttemptedThisConnection = false,
                needsPostResetActivation = true,
                hasPersistedHistoryState = true,
            )
        )
    }

    @Test
    fun resolveNoStreamWatchdogDelayMs_extendsWhenRecentHistoryExists() {
        val delayMs = AiDexStreamingPolicy.resolveNoStreamWatchdogDelayMs(
            defaultDelayMs = 25_000L,
            nowMs = 200_000L,
            latestKnownReadingMs = 170_000L,
            expectedLiveIntervalMs = 60_000L,
            expectedLiveGraceMs = 20_000L,
        )

        assertEquals(50_000L, delayMs)
    }

    @Test
    fun resolveNoStreamWatchdogDelayMs_usesDefaultWithoutRecentHistory() {
        val delayMs = AiDexStreamingPolicy.resolveNoStreamWatchdogDelayMs(
            defaultDelayMs = 25_000L,
            nowMs = 200_000L,
            latestKnownReadingMs = 0L,
            expectedLiveIntervalMs = 60_000L,
            expectedLiveGraceMs = 20_000L,
        )

        assertEquals(25_000L, delayMs)
    }
}
