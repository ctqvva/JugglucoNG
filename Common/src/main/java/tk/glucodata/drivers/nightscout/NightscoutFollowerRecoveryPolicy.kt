package tk.glucodata.drivers.nightscout

/**
 * Decides when the in-process probe should repair the follower's alarm chain.
 *
 * AlarmManager owns the normal cadence. The probe does not wake the phone; it only notices,
 * while the process is already running, that no future alarm is known or that one is late.
 */
object NightscoutFollowerRecoveryPolicy {
    /** Leave a full minute for normal alarm delivery and receiver-to-handler handoff. */
    const val OVERDUE_GRACE_MS: Long = 60_000L

    /**
     * How long a refresh may go without finishing a step before it counts as stuck. Every
     * request is bounded by its own timeouts (15 s connect, 30 s per read), so two minutes
     * without one finishing means a call that is not coming back. Measured in uptime, so a
     * refresh frozen because the phone slept is not mistaken for one that hung.
     */
    const val STALL_MS: Long = 2L * 60_000L

    /**
     * Opening the app asks for fresh values, but switching screens or turning the phone
     * resumes the activity again within seconds. A refresh that started this recently is
     * already as fresh as a new one would be.
     */
    const val FOREGROUND_MIN_GAP_MS: Long = 15_000L

    fun shouldRecover(
        nextPollElapsedRealtime: Long,
        nowElapsedRealtime: Long,
        syncing: Boolean,
        force: Boolean = false,
        graceMillis: Long = OVERDUE_GRACE_MS,
    ): Boolean {
        if (syncing) return false
        if (force) return true
        if (nextPollElapsedRealtime <= 0L) return true
        if (nowElapsedRealtime < nextPollElapsedRealtime) return false
        return nowElapsedRealtime - nextPollElapsedRealtime >= graceMillis.coerceAtLeast(0L)
    }

    /** True when a queued or running refresh has made no progress for [stallMillis] of uptime. */
    fun isStalled(
        lastProgressUptime: Long,
        nowUptime: Long,
        stallMillis: Long = STALL_MS,
    ): Boolean {
        if (lastProgressUptime <= 0L) return false
        if (nowUptime < lastProgressUptime) return false
        return nowUptime - lastProgressUptime >= stallMillis.coerceAtLeast(0L)
    }

    fun shouldRefreshOnForeground(
        lastRefreshStartedElapsed: Long,
        nowElapsedRealtime: Long,
        minGapMillis: Long = FOREGROUND_MIN_GAP_MS,
    ): Boolean {
        if (lastRefreshStartedElapsed <= 0L) return true
        // Elapsed realtime does not go backwards within a boot; if it appears to, the stored
        // start belongs to something else and must not suppress the refresh.
        if (nowElapsedRealtime < lastRefreshStartedElapsed) return true
        return nowElapsedRealtime - lastRefreshStartedElapsed >= minGapMillis.coerceAtLeast(0L)
    }

    /**
     * The alarm booked when a refresh starts, in case it never gets to book the next one: the
     * phone can sleep in the middle of it once the receiver's wakelock runs out. Never sooner
     * than the stall threshold, so a slow refresh is not interrupted by its own backstop.
     */
    fun backstopDelayMillis(pollIntervalMillis: Long): Long =
        pollIntervalMillis.coerceAtLeast(STALL_MS)
}
