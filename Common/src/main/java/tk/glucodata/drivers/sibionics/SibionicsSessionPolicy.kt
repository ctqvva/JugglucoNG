package tk.glucodata.drivers.sibionics

internal object SibionicsSessionPolicy {
    /**
     * One incoming sample, as far as session identity is concerned.
     * [sensorLiveIndex] is the sensor's newest index when the packet says so
     * (Chinese: index + NumOfUnreceived), null when it does not (V120).
     */
    data class SessionSample(
        val index: Int,
        val eventMs: Long,
        val live: Boolean,
        val sensorLiveIndex: Int? = null,
    )

    /**
     * A session that starts less than this after the known one is not a new
     * session. Sensor clock drift over a full wear is a minute or two.
     */
    const val MIN_SESSION_SHIFT_MS = 60L * 60L * 1000L

    /** Slack between the last sample we saw and a later session's start. */
    const val LAST_SEEN_SLACK_MS = 30L * 60L * 1000L

    private const val MAX_FUTURE_START_MS = 5L * 60L * 1000L
    private const val MIN_REASONABLE_START_MS = 946_684_800_000L // 2000-01-01, an unset clock

    /** The session start a sample implies: indices are one-minute sensor records. */
    fun impliedStartMs(index: Int, eventMs: Long): Long =
        eventMs - index.toLong() * SibionicsConstants.READING_INTERVAL_MS

    fun isConfirmedIndexRestart(
        index: Int,
        previousNextIndex: Int,
        isCurrentReading: Boolean,
        isRehydrating: Boolean,
    ): Boolean =
        !isRehydrating && isCurrentReading && index <= 1 && previousNextIndex > 1

    /**
     * The start of a new sensor session that [samples] prove, or null when they
     * are consistent with the session the driver already tracks.
     *
     * A reset - ours or another app's - restarts the transmitter's index. Asked
     * for the old cursor, a restarted sensor answers with its current record
     * (idx=1024 against cursor 23437 in the 2026-09-24 20:04 capture), so a restart
     * is recognised by where a sample places its session's start:
     * - a live idx<=1 (the only rule 1.2.1 had);
     * - a sample behind the cursor whose session began no earlier than the last
     *   sample already held. A sample of the tracked session cannot do that: it
     *   was recorded before that last sample, so its index would have to be under
     *   half an hour.
     *
     * The last sample's time is the later of [lastSeenMs] and what the cursor
     * implies, so an index the sensor skipped cannot make an old sample look new.
     * [knownStartMs] <= 0 (never seen a sample) leaves only the live-index rule.
     *
     * Chinese-protocol times are the phone's receipt time minus the sensor's
     * backlog, so a phone clock change moves every implied start with it. Those
     * packets also carry the sensor's newest index, and the second rule then
     * also needs the sensor to hold fewer indices than we have already received:
     * a restart the clock cannot fake.
     */
    fun restartedSessionStartMs(
        samples: List<SessionSample>,
        knownStartMs: Long,
        knownCursor: Int,
        lastSeenMs: Long,
        isRehydrating: Boolean,
        nowMs: Long,
    ): Long? {
        for (sample in samples.sortedBy { it.index }) {
            if (sample.index < 0) continue
            if (isConfirmedIndexRestart(sample.index, knownCursor, sample.live, isRehydrating)) {
                return if (sample.eventMs > 0L) impliedStartMs(sample.index, sample.eventMs) else nowMs
            }
            if (sample.eventMs <= 0L || knownStartMs <= 0L || knownCursor <= 1) continue
            if (sample.index >= knownCursor) continue
            // A sensor still holding every index we already have is on the
            // tracked session, whatever the clock says.
            if (sample.sensorLiveIndex != null && sample.sensorLiveIndex >= knownCursor - 1) continue
            val implied = impliedStartMs(sample.index, sample.eventMs)
            if (implied > nowMs + MAX_FUTURE_START_MS || implied < MIN_REASONABLE_START_MS) continue
            if (implied - knownStartMs < MIN_SESSION_SHIFT_MS) continue
            val lastHeldMs = maxOf(
                lastSeenMs,
                knownStartMs + (knownCursor - 1).toLong() * SibionicsConstants.READING_INTERVAL_MS,
            )
            if (implied >= lastHeldMs - LAST_SEEN_SLACK_MS) return implied
        }
        return null
    }

    /**
     * Whether a page that proved a restart must be set aside so the new session can
     * be downloaded from its first minute, the way a newly added sensor is. True
     * when the page starts mid-session: a stale cursor is answered with the
     * sensor's current record, and processing that on a fresh algorithm would
     * leave every earlier minute of the session out of history.
     */
    fun shouldDownloadRestartedSessionFromStart(samples: List<SessionSample>): Boolean =
        samples.isNotEmpty() && samples.none { it.index <= 1 }

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
     * delivering a current sample cannot postpone the rebuild forever. Within
     * the cap, a transfer that has stopped delivering pages for [stallMs] is
     * not in progress any more. A fixed two-minute cap alone fired in the middle
     * of an ordinary full-wear fetch (23 000 samples, ~150 s), and the rebuild
     * it started was invalidated by the very next page.
     */
    fun shouldDeferRebuildForHistoryTransfer(
        historyTransferActive: Boolean,
        isRehydrating: Boolean,
        deferredForMs: Long,
        maxDeferralMs: Long,
        sinceLastPageMs: Long = 0L,
        stallMs: Long = Long.MAX_VALUE,
    ): Boolean =
        (historyTransferActive || isRehydrating) &&
            deferredForMs <= maxDeferralMs &&
            sinceLastPageMs <= stallMs

    /**
     * The sensor answered with a page we did not ask for — a data-request write
     * that never left the phone leaves it streaming from its own cursor. The
     * exact algorithm state behind `lastIndex` is untouched by that, so the page
     * is dropped and the same index is asked for again. Only a sensor that keeps
     * refusing the index for [maxAttempts] consecutive connections justifies
     * throwing the state away and replaying from the beginning.
     */
    fun shouldAbandonExactStateForUnrequestedPages(
        consecutiveUnrequestedConnections: Int,
        maxAttempts: Int,
    ): Boolean = consecutiveUnrequestedConnections >= maxAttempts

    /**
     * Index to put in the next data-request. While the source journal is being
     * backfilled behind a live algorithm, connections alternate between the
     * journal gap and the live cursor so readings keep flowing during the
     * transfer; a gap at or past the live cursor is not a gap.
     */
    fun dataRequestIndex(
        lastIndex: Int,
        journalGapIndex: Int,
        backfillTurn: Boolean,
    ): Int =
        if (backfillTurn && journalGapIndex in 1 until lastIndex) journalGapIndex else lastIndex.coerceAtLeast(0)

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
