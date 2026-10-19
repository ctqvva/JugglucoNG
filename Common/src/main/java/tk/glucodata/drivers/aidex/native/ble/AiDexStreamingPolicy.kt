package tk.glucodata.drivers.aidex.native.ble

import android.bluetooth.BluetoothDevice

internal object AiDexStreamingPolicy {

    enum class NoStreamRecoveryAction {
        KEEP_WAITING,
        REQUEST_CONNECTED_BROADCAST,
        REQUEST_HISTORY_REFRESH,
        REFRESH_LIVE_CCCDS,
        RECONNECT,
    }

    fun shouldRefreshLiveCccdsAfterKeyExchange(
        bondStateAtConnection: Int,
        bondBecameBondedThisConnection: Boolean,
    ): Boolean {
        return bondStateAtConnection != BluetoothDevice.BOND_BONDED || bondBecameBondedThisConnection
    }

    fun shouldReadSessionCharacteristicsBeforeFirstLive(
        bondStateAtConnection: Int,
        bondBecameBondedThisConnection: Boolean,
        autoActivationAttemptedThisConnection: Boolean,
        needsPostResetActivation: Boolean,
        hasPersistedHistoryState: Boolean,
    ): Boolean {
        if (needsPostResetActivation || autoActivationAttemptedThisConnection) {
            return true
        }
        if (bondStateAtConnection != BluetoothDevice.BOND_BONDED || bondBecameBondedThisConnection) {
            return true
        }
        return !hasPersistedHistoryState
    }

    fun decideNoStreamRecovery(
        hasRecentBroadcastData: Boolean,
        historyDownloading: Boolean,
        allowConnectedBroadcastRequest: Boolean,
        connectedBroadcastRequestAttempted: Boolean,
        hasSessionFallbackData: Boolean,
        historyRefreshAttempted: Boolean,
        liveCccdRefreshAttempted: Boolean,
    ): NoStreamRecoveryAction {
        if (hasRecentBroadcastData || historyDownloading) {
            return NoStreamRecoveryAction.KEEP_WAITING
        }
        if (allowConnectedBroadcastRequest && !connectedBroadcastRequestAttempted) {
            return NoStreamRecoveryAction.REQUEST_CONNECTED_BROADCAST
        }
        if (hasSessionFallbackData && !historyRefreshAttempted) {
            return NoStreamRecoveryAction.REQUEST_HISTORY_REFRESH
        }
        if (!liveCccdRefreshAttempted) {
            return NoStreamRecoveryAction.REFRESH_LIVE_CCCDS
        }
        return NoStreamRecoveryAction.RECONNECT
    }

    fun resolveNoStreamWatchdogDelayMs(
        defaultDelayMs: Long,
        nowMs: Long,
        latestKnownReadingMs: Long,
        expectedLiveIntervalMs: Long,
        expectedLiveGraceMs: Long,
    ): Long {
        if (latestKnownReadingMs <= 0L) return defaultDelayMs
        val waitUntil = latestKnownReadingMs + expectedLiveIntervalMs + expectedLiveGraceMs
        val historyAwareDelay = (waitUntil - nowMs).takeIf { it > 0L } ?: 0L
        return maxOf(defaultDelayMs, historyAwareDelay)
    }
}
