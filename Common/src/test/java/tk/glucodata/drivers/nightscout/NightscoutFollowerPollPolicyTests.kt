package tk.glucodata.drivers.nightscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interval is the follower's latency and its battery cost at once, so what a stored
 * number is allowed to mean is worth pinning down: nothing off the list reaches the alarm.
 */
class NightscoutFollowerPollPolicyTests {

    @Test
    fun everyOfferedChoiceSurvivesSanitizing() {
        NightscoutFollowerPollPolicy.CHOICES_MINUTES.forEach { minutes ->
            assertEquals(minutes, NightscoutFollowerPollPolicy.sanitizeMinutes(minutes))
        }
    }

    @Test
    fun theDefaultIsOneOfTheChoices() {
        assertTrue(
            NightscoutFollowerPollPolicy.DEFAULT_MINUTES in NightscoutFollowerPollPolicy.CHOICES_MINUTES
        )
    }

    @Test
    fun nonsenseIsPulledOntoTheList() {
        // Zero or negative would be a poll with no wait at all, which is the one outcome a
        // stored number must never produce.
        assertEquals(1, NightscoutFollowerPollPolicy.sanitizeMinutes(0))
        assertEquals(1, NightscoutFollowerPollPolicy.sanitizeMinutes(-30))
        assertEquals(30, NightscoutFollowerPollPolicy.sanitizeMinutes(Int.MAX_VALUE))
    }

    @Test
    fun aValueOffTheListLandsOnTheNearestChoice() {
        assertEquals(2, NightscoutFollowerPollPolicy.sanitizeMinutes(3))
        assertEquals(5, NightscoutFollowerPollPolicy.sanitizeMinutes(4))
        assertEquals(15, NightscoutFollowerPollPolicy.sanitizeMinutes(13))
        assertEquals(30, NightscoutFollowerPollPolicy.sanitizeMinutes(24))
    }

    @Test
    fun noWholeNumberSitsBetweenTwoChoices() {
        // What lets nearest be unambiguous: every midpoint falls on a half minute.
        NightscoutFollowerPollPolicy.CHOICES_MINUTES.zipWithNext { low, high ->
            assertTrue("$low..$high has a whole midpoint", (low + high) % 2 != 0)
        }
    }

    @Test
    fun theIntervalIsTheSanitizedNumberInMillis() {
        assertEquals(5L * 60_000L, NightscoutFollowerPollPolicy.intervalMillis(5))
        assertEquals(60_000L, NightscoutFollowerPollPolicy.intervalMillis(0))
        assertEquals(30L * 60_000L, NightscoutFollowerPollPolicy.intervalMillis(45))
    }

    // ---------- changing the interval ----------

    private val minute = 60_000L

    @Test
    fun shorterIntervalMovesThePollForward() {
        // Thirty minutes to one, twenty seconds after the last refresh: the next poll is
        // forty seconds away, not the rest of the thirty.
        val delay = NightscoutFollowerPollPolicy.delayAfterIntervalChange(
            lastCompletedElapsed = 1_000_000L,
            nextPollElapsed = 1_000_000L + 30 * minute,
            nowElapsed = 1_020_000L,
            newIntervalMillis = minute,
            failing = false,
        )
        assertEquals(40_000L, delay)
    }

    @Test
    fun aNewIntervalAlreadyOverduePollsNow() {
        val delay = NightscoutFollowerPollPolicy.delayAfterIntervalChange(
            lastCompletedElapsed = 1_000_000L,
            nextPollElapsed = 1_000_000L + 30 * minute,
            nowElapsed = 1_000_000L + 4 * minute,
            newIntervalMillis = 2 * minute,
            failing = false,
        )
        assertEquals(0L, delay)
    }

    @Test
    fun longerIntervalPushesThePollOut() {
        val delay = NightscoutFollowerPollPolicy.delayAfterIntervalChange(
            lastCompletedElapsed = 1_000_000L,
            nextPollElapsed = 1_000_000L + minute,
            nowElapsed = 1_030_000L,
            newIntervalMillis = 10 * minute,
            failing = false,
        )
        assertEquals(10 * minute - 30_000L, delay)
    }

    @Test
    fun aSoonerRetryAfterAFailureKeepsItsPlace() {
        val delay = NightscoutFollowerPollPolicy.delayAfterIntervalChange(
            lastCompletedElapsed = 1_000_000L,
            nextPollElapsed = 1_030_000L,
            nowElapsed = 1_010_000L,
            newIntervalMillis = 15 * minute,
            failing = true,
        )
        assertEquals(20_000L, delay)
    }

    @Test
    fun noCompletedRefreshYetPollsNow() {
        val delay = NightscoutFollowerPollPolicy.delayAfterIntervalChange(
            lastCompletedElapsed = 0L,
            nextPollElapsed = 0L,
            nowElapsed = 1_000_000L,
            newIntervalMillis = 5 * minute,
            failing = false,
        )
        assertEquals(0L, delay)
    }

    @Test
    fun onlyIntervalsUnderTheDozeFloorAreWarnedAbout() {
        assertTrue(NightscoutFollowerPollPolicy.isThrottledByDoze(1))
        assertTrue(NightscoutFollowerPollPolicy.isThrottledByDoze(5))
        assertFalse(NightscoutFollowerPollPolicy.isThrottledByDoze(10))
        assertFalse(NightscoutFollowerPollPolicy.isThrottledByDoze(30))
    }
}
