package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.PeriodicGlucoseNotificationPolicy.Decision
import tk.glucodata.PeriodicGlucoseNotificationPolicy.Sent

class PeriodicGlucoseNotificationPolicyTests {
    private val freshness = 330_000L
    private val minute = 60_000L
    private val noon = 1_760_000_000_000L
    /** Boot at noon minus one day: elapsed = wall - bootWall while the clock is right. */
    private val bootWall = noon - 24 * 60 * minute

    /** Simulated phone: wall clock, elapsed realtime, and what was last sent. */
    private inner class Phone(var wall: Long = noon, var bootAt: Long = bootWall) {
        var last = Sent.NONE
        var baseline = 0L
        val elapsed get() = wall - bootAt
        val sent = mutableListOf<Long>()

        fun reading(at: Long, interval: Int, enabled: Boolean = true, sendCurrentOnce: Boolean = false): Decision {
            val decision = PeriodicGlucoseNotificationPolicy.decide(
                enabled, at, baseline, last, wall, elapsed, interval, freshness, sendCurrentOnce)
            if (decision == Decision.SEND) {
                last = Sent(at, wall, elapsed)
                sent += at
            }
            return decision
        }
    }

    @Test fun disabledNeverSends() {
        assertEquals(Decision.DISABLED, Phone().reading(noon, 5, enabled = false))
    }

    @Test fun firstEligibleNewReadingIsSent() {
        assertEquals(Decision.SEND, Phone().reading(noon - 20_000L, 5))
    }

    @Test fun oneMinuteSensorAtFiveMinutesSendsEveryFifthReading() {
        val phone = Phone()
        val decisions = (0..10).map { i ->
            // Arrival jitter of a few seconds either way.
            phone.wall = noon + i * minute + (if (i % 2 == 0) -2_000L else 3_000L)
            phone.reading(phone.wall - 1_000L, 5)
        }
        assertEquals(
            listOf(Decision.SEND, Decision.TOO_SOON, Decision.TOO_SOON, Decision.TOO_SOON, Decision.TOO_SOON,
                Decision.SEND, Decision.TOO_SOON, Decision.TOO_SOON, Decision.TOO_SOON, Decision.TOO_SOON,
                Decision.SEND),
            decisions
        )
    }

    @Test fun everyReadingOptionSendsEachNewOneOnly() {
        val phone = Phone()
        for (i in 0..4) {
            phone.wall = noon + i * minute
            assertEquals(Decision.SEND, phone.reading(phone.wall, 1))
            assertEquals("same reading, second callback", Decision.ALREADY_SENT, phone.reading(phone.wall, 1))
        }
    }

    @Test fun fiveMinuteSensorIsNeverPaddedToAShorterInterval() {
        val phone = Phone()
        // Three-minute interval, readings only every five minutes: each reading once,
        // nothing in between however often the check runs.
        for (i in 0..3) {
            val readingAt = noon + i * 5 * minute
            for (offset in listOf(0L, minute, 2 * minute, 3 * minute, 4 * minute)) {
                phone.wall = readingAt + offset
                phone.reading(readingAt, 3)
            }
        }
        assertEquals(listOf(noon, noon + 5 * minute, noon + 10 * minute, noon + 15 * minute), phone.sent)
    }

    @Test fun fiveMinuteSensorAtTenMinutesSendsEveryOtherReading() {
        val phone = Phone()
        val decisions = (0..4).map { i ->
            phone.wall = noon + i * 5 * minute + 4_000L
            phone.reading(noon + i * 5 * minute, 10)
        }
        assertEquals(listOf(Decision.SEND, Decision.TOO_SOON, Decision.SEND, Decision.TOO_SOON, Decision.SEND), decisions)
    }

    @Test fun staleReadingsAreNotSentButAFiveMinuteOldOneStillIs() {
        val phone = Phone()
        assertEquals(Decision.STALE, phone.reading(noon - 331_000L, 5))
        assertEquals(Decision.SEND, phone.reading(noon - 5 * minute, 5))
        assertEquals(Decision.NO_READING, phone.reading(0L, 5))
    }

    @Test fun disconnectProducesNothingAndReconnectSendsOneReadingNotABurst() {
        val phone = Phone()
        phone.reading(noon, 5)
        // Sensor gone for 30 minutes: checks keep seeing the old reading.
        for (i in 1..30) {
            phone.wall = noon + i * minute
            val decision = phone.reading(noon, 5)
            assert(decision == Decision.ALREADY_SENT || decision == Decision.STALE)
        }
        // Reconnect delivers the backlog oldest-first, then the current reading.
        phone.wall = noon + 31 * minute
        val backlog = (20..31).map { phone.reading(noon + it * minute, 5) }
        assertEquals(1, backlog.count { it == Decision.SEND })
        assertEquals(listOf(noon, noon + 26 * minute), phone.sent)
    }

    @Test fun delayedOlderReadingIsNeverReplayed() {
        val phone = Phone()
        phone.reading(noon, 5)
        phone.wall = noon + 6 * minute
        phone.reading(noon + 6 * minute, 5)
        // A late callback for a reading measured before the last one sent.
        assertEquals(Decision.ALREADY_SENT, phone.reading(noon + 5 * minute, 5))
    }

    @Test fun processRestartDoesNotReplayTheCachedReading() {
        val phone = Phone()
        phone.reading(noon, 5)
        phone.wall = noon + 3 * minute
        // New process: persisted state survives, and the reading on screen at start is
        // the baseline even though it was never sent (inside the interval).
        val cached = noon + 2 * minute
        phone.baseline = cached
        phone.wall = noon + 6 * minute
        assertEquals(Decision.ALREADY_SENT, phone.reading(cached, 5))
        assertEquals(Decision.SEND, phone.reading(noon + 6 * minute, 5))
    }

    @Test fun rebootFallsBackToTheWallClock() {
        val phone = Phone()
        phone.reading(noon, 5)
        // Rebooted at 12:02: elapsed restarts near zero.
        phone.bootAt = noon + 2 * minute
        phone.wall = noon + 3 * minute
        assertEquals(Decision.TOO_SOON, phone.reading(noon + 3 * minute, 5))
        phone.wall = noon + 5 * minute
        assertEquals(Decision.SEND, phone.reading(noon + 5 * minute, 5))
    }

    @Test fun clockMovedBackDoesNotHoldReadingsBack() {
        val phone = Phone()
        phone.reading(noon, 5)
        // The clock is set back an hour; elapsed keeps counting.
        phone.wall = noon - 60 * minute + 6 * minute
        phone.bootAt = bootWall - 60 * minute
        assertEquals(Decision.SEND, phone.reading(phone.wall, 5))
        phone.wall += minute
        assertEquals(Decision.TOO_SOON, phone.reading(phone.wall, 5))
    }

    @Test fun clockMovedBackALittleNeverResendsTheCachedReading() {
        for (interval in intArrayOf(1, 5)) {
            val phone = Phone()
            phone.reading(noon, interval)
            // Back three minutes: the cached reading is now in the future, still fresh,
            // and the last-sent marker no longer counts.
            phone.wall = noon - 3 * minute + 10_000L
            phone.bootAt = bootWall - 3 * minute
            repeat(3) {
                assertEquals("interval $interval", Decision.ALREADY_SENT, phone.reading(noon, interval))
                phone.wall += 10_000L
            }
            // The next reading, stamped by the corrected clock, still goes out.
            assertEquals("interval $interval", Decision.SEND, phone.reading(phone.wall, interval))
            assertEquals(listOf(noon, phone.wall), phone.sent)
        }
    }

    @Test fun clockMovedForwardSendsAtMostOnceEarly() {
        val phone = Phone()
        phone.reading(noon, 5)
        phone.wall = noon + 61 * minute
        phone.bootAt = bootWall + 60 * minute
        assertEquals(Decision.SEND, phone.reading(phone.wall, 5))
        phone.wall += minute
        assertEquals(Decision.TOO_SOON, phone.reading(phone.wall, 5))
    }

    @Test fun elapsedTimeKeepsTheIntervalWhenTheClockIsUnchanged() {
        val phone = Phone()
        phone.reading(noon, 10)
        phone.wall = noon + 9 * minute
        assertEquals(Decision.TOO_SOON, phone.reading(phone.wall, 10))
    }

    @Test fun switchingOnAgainShowsTheCurrentFreshReadingOnce() {
        val phone = Phone()
        phone.reading(noon, 5)
        // Off, then on again a minute later: the same reading was already sent and the
        // interval has not passed, but the user asked to see it.
        phone.wall = noon + minute
        assertEquals(Decision.ALREADY_SENT, phone.reading(noon, 5))
        assertEquals(Decision.SEND, phone.reading(noon, 5, sendCurrentOnce = true))
        phone.wall += minute
        assertEquals(Decision.TOO_SOON, phone.reading(phone.wall, 5))
        assertEquals("never a stale value", Decision.STALE,
            phone.reading(phone.wall - 400_000L, 5, sendCurrentOnce = true))
        // Restart protection is a separate input and stays in force without the switch.
        phone.baseline = phone.wall
        assertEquals(Decision.ALREADY_SENT, phone.reading(phone.wall, 1))
    }

    @Test fun alarmMatchesOnlyItsOwnMeasurement() {
        assertTrue(PeriodicGlucoseNotificationPolicy.sameMeasurement(noon + 450L, noon))
        assertTrue("native whole-second time", PeriodicGlucoseNotificationPolicy.sameMeasurement(noon / 1000L, noon + 300L))
        assertFalse("the next one-minute reading", PeriodicGlucoseNotificationPolicy.sameMeasurement(noon, noon + minute))
        assertFalse(PeriodicGlucoseNotificationPolicy.sameMeasurement(noon, noon + 1_000L))
        assertFalse(PeriodicGlucoseNotificationPolicy.sameMeasurement(0L, 0L))
    }

    @Test fun intervalChangeAppliesToTheNextReading() {
        val phone = Phone()
        phone.reading(noon, 10)
        phone.wall = noon + 3 * minute
        assertEquals(Decision.TOO_SOON, phone.reading(phone.wall, 10))
        assertEquals(Decision.SEND, phone.reading(phone.wall, 3))
    }
}
