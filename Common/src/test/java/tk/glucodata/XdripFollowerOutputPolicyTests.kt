package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XdripFollowerOutputPolicyTests {
    private val policy = ExchangeOutputPolicy()
    private val base = 3_000_000L
    private val minute = 60_000L

    @Test
    fun followerCanStartAfterLoopFeedSentInTheSameInterval() {
        val first = decide(base, loop = true, follower = false)
        assertTrue(first.sendLoopFeed)
        assertFalse(first.sendXdripFollower)

        val enabled = decide(base + minute, loop = true, follower = true)
        assertFalse(enabled.sendLoopFeed)
        assertTrue(enabled.sendXdripFollower)

        val repeated = decide(base + 2 * minute, loop = true, follower = true)
        assertFalse(repeated.sendLoopFeed)
        assertFalse(repeated.sendXdripFollower)

        val next = decide(base + 5 * minute, loop = true, follower = true)
        assertTrue(next.sendLoopFeed)
        assertTrue(next.sendXdripFollower)
    }

    @Test
    fun followerDoesNotUseTheLoopFeedInterval() {
        val first = decide(base, loop = false, follower = true)
        assertFalse(first.sendLoopFeed)
        assertTrue(first.sendXdripFollower)

        val enabled = decide(base + minute, loop = true, follower = true)
        assertTrue(enabled.sendLoopFeed)
        assertFalse(enabled.sendXdripFollower)
    }

    @Test
    fun followerWaitsForTheMinuteGate() {
        val waiting = decide(base, minuteReady = false)
        assertFalse(waiting.sendXdripFollower)

        val ready = decide(base + minute)
        assertTrue(ready.sendXdripFollower)
    }

    @Test
    fun followerRejectsOldReadingsAndHonorsIntervalChanges() {
        assertTrue(decide(base).sendXdripFollower)
        assertFalse(decide(base - minute).sendXdripFollower)
        assertFalse(decide(base + minute).sendXdripFollower)
        assertTrue(decide(base + minute, interval = 3).sendXdripFollower)
    }

    private fun decide(
        time: Long,
        loop: Boolean = false,
        follower: Boolean = true,
        minuteReady: Boolean = true,
        interval: Int = 5
    ) = policy.decide(
        sensorId = "test-sensor",
        payloadTimeMs = time,
        intervalMinutes = interval,
        shouldBroadcastMinuteUpdate = minuteReady,
        jugglucoEnabled = false,
        outboundApiEnabled = false,
        wearIntEnabled = false,
        gadgetbridgeEnabled = false,
        loopFeedEnabled = loop,
        xdripFollowerEnabled = follower
    )
}
