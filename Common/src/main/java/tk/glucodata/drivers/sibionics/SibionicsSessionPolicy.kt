package tk.glucodata.drivers.sibionics

internal object SibionicsSessionPolicy {
    fun isConfirmedIndexRestart(
        index: Int,
        previousNextIndex: Int,
        isCurrentReading: Boolean,
        isRehydrating: Boolean,
    ): Boolean =
        !isRehydrating && isCurrentReading && index <= 1 && previousNextIndex > 1

    fun shouldRebaseNativeWindow(hadStartTime: Boolean, index: Int): Boolean =
        !hadStartTime && index >= 0

    fun shouldShowHistoryProgress(
        receivedCount: Int,
        totalCount: Int,
        hasReceivedLiveReading: Boolean,
    ): Boolean =
        !hasReceivedLiveReading && receivedCount > 0 && totalCount > receivedCount

    fun shouldRecoverSetupTimeout(
        isPending: Boolean,
        isStopped: Boolean,
        isPaused: Boolean,
    ): Boolean =
        isPending && !isStopped && !isPaused

    fun connectCallbackTimeoutDelayMs(
        requestedDelayMs: Long,
        callbackTimeoutMs: Long,
    ): Long =
        requestedDelayMs.coerceAtLeast(0L) + callbackTimeoutMs.coerceAtLeast(0L)

    /**
     * Whether a queued full-algorithm rebuild should wait for the history transfer
     * to finish. A rebuild replays the whole DSP and re-mirrors every reading, so
     * running one mid-backlog costs the full history and buys nothing — nothing
     * displays the result until the transfer ends.
     *
     * Note this deliberately does *not* key off "has a live reading been seen" or
     * "is the algorithm rehydrating" alone: a committed rebuild sets the live index
     * and clears the rehydration flag, so every rebuild after the first would run
     * anyway. In the 2026-08-24 capture that produced three commits of 128, 1504 and
     * 4867 samples inside one transfer, with no rehydration at all.
     *
     * [deferredForMs] is capped so a sensor that streams backlog without ever
     * delivering a current sample cannot postpone the rebuild forever.
     */
    fun shouldDeferRebuildForHistoryTransfer(
        historyTransferActive: Boolean,
        isRehydrating: Boolean,
        deferredForMs: Long,
        maxDeferralMs: Long,
    ): Boolean =
        (historyTransferActive || isRehydrating) && deferredForMs <= maxDeferralMs

    fun shouldUseAdvertisementRecovery(
        failedDuringConnect: Boolean,
        isStopped: Boolean,
        isPaused: Boolean,
        hasKnownAddress: Boolean,
        recoveryAlreadyActive: Boolean,
    ): Boolean =
        failedDuringConnect &&
            !isStopped &&
            !isPaused &&
            hasKnownAddress &&
            !recoveryAlreadyActive
}
