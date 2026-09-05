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
}
