package tk.glucodata.drivers.nightscout

import org.junit.Assert.assertEquals
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

    // ---------- stalled refresh ----------

    @Test
    fun refreshMakingProgressIsNotStalled() {
        val stall = NightscoutFollowerRecoveryPolicy.STALL_MS
        assertFalse(NightscoutFollowerRecoveryPolicy.isStalled(1_000L, 1_000L + stall - 1L))
    }

    @Test
    fun refreshWithoutProgressForTheThresholdIsStalled() {
        // The case that used to block polling for good: the probe saw a refresh in flight
        // and left it alone no matter how long it had been there.
        val stall = NightscoutFollowerRecoveryPolicy.STALL_MS
        assertTrue(NightscoutFollowerRecoveryPolicy.isStalled(1_000L, 1_000L + stall))
    }

    @Test
    fun stallThresholdOutlastsOneBoundedRequest() {
        // 15 s connect + 30 s read is the most one well-behaved request takes; a refresh in
        // the middle of one must never be given up on.
        assertTrue(NightscoutFollowerRecoveryPolicy.STALL_MS > 45_000L)
    }

    @Test
    fun unknownProgressIsNotStalled() {
        assertFalse(NightscoutFollowerRecoveryPolicy.isStalled(0L, Long.MAX_VALUE))
    }

    @Test
    fun uptimeMovingBackIsNotStalled() {
        assertFalse(NightscoutFollowerRecoveryPolicy.isStalled(500_000L, 1_000L))
    }

    // ---------- foreground refresh ----------

    @Test
    fun foregroundRefreshesWhenNothingHasRunYet() {
        // Shortly after boot elapsed realtime is smaller than the gap; that must not read as
        // "a refresh just started".
        assertTrue(NightscoutFollowerRecoveryPolicy.shouldRefreshOnForeground(0L, 5_000L))
    }

    @Test
    fun foregroundRefreshesOnceTheGapHasPassed() {
        val gap = NightscoutFollowerRecoveryPolicy.FOREGROUND_MIN_GAP_MS
        assertTrue(NightscoutFollowerRecoveryPolicy.shouldRefreshOnForeground(100_000L, 100_000L + gap))
    }

    @Test
    fun repeatedResumesDoNotRefetch() {
        val gap = NightscoutFollowerRecoveryPolicy.FOREGROUND_MIN_GAP_MS
        assertFalse(NightscoutFollowerRecoveryPolicy.shouldRefreshOnForeground(100_000L, 100_000L + gap - 1L))
    }

    @Test
    fun foregroundIgnoresAStartTimeInTheFuture() {
        assertTrue(NightscoutFollowerRecoveryPolicy.shouldRefreshOnForeground(500_000L, 10_000L))
    }

    // ---------- backstop alarm ----------

    @Test
    fun backstopFollowsALongInterval() {
        assertEquals(30L * 60_000L, NightscoutFollowerRecoveryPolicy.backstopDelayMillis(30L * 60_000L))
    }

    @Test
    fun backstopNeverInterruptsARefreshBeforeItCouldStall() {
        assertEquals(
            NightscoutFollowerRecoveryPolicy.STALL_MS,
            NightscoutFollowerRecoveryPolicy.backstopDelayMillis(60_000L),
        )
    }
}
