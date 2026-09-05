package tk.glucodata.drivers.nightscout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutFollowerRecoveryPolicyTests {

    @Test
    fun missingDeadlineIsRecovered() {
        assertTrue(
            NightscoutFollowerRecoveryPolicy.shouldRecover(
                nextPollElapsedRealtime = 0L,
                nowElapsedRealtime = 10_000L,
                syncing = false,
            )
        )
    }

    @Test
    fun futureDeadlineIsLeftToAlarmManager() {
        assertFalse(
            NightscoutFollowerRecoveryPolicy.shouldRecover(
                nextPollElapsedRealtime = 120_000L,
                nowElapsedRealtime = 60_000L,
                syncing = false,
            )
        )
    }

    @Test
    fun gracePeriodPreventsDuplicateDelivery() {
        val due = 120_000L
        val grace = NightscoutFollowerRecoveryPolicy.OVERDUE_GRACE_MS
        assertFalse(
            NightscoutFollowerRecoveryPolicy.shouldRecover(due, due + grace - 1L, syncing = false)
        )
        assertTrue(
            NightscoutFollowerRecoveryPolicy.shouldRecover(due, due + grace, syncing = false)
        )
    }

    @Test
    fun activeSyncIsNeverDuplicated() {
        assertFalse(
            NightscoutFollowerRecoveryPolicy.shouldRecover(
                nextPollElapsedRealtime = 0L,
                nowElapsedRealtime = Long.MAX_VALUE,
                syncing = true,
            )
        )
    }

    @Test
    fun networkRecoveryCanReplaceAnIdleBackoff() {
        assertTrue(
            NightscoutFollowerRecoveryPolicy.shouldRecover(
                nextPollElapsedRealtime = 120_000L,
                nowElapsedRealtime = 60_000L,
                syncing = false,
                force = true,
            )
        )
    }

    @Test
    fun networkRecoveryDoesNotDuplicateAnActiveSync() {
        assertFalse(
            NightscoutFollowerRecoveryPolicy.shouldRecover(
                nextPollElapsedRealtime = 0L,
                nowElapsedRealtime = 60_000L,
                syncing = true,
                force = true,
            )
        )
    }

    @Test
    fun elapsedRealtimeMovingBackDoesNotLookOverdue() {
        assertFalse(
            NightscoutFollowerRecoveryPolicy.shouldRecover(
                nextPollElapsedRealtime = 120_000L,
                nowElapsedRealtime = 10_000L,
                syncing = false,
            )
        )
    }
}
