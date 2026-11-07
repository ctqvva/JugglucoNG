package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.SensorOwnershipPolicy.Intent
import tk.glucodata.SensorOwnershipPolicy.PeerReport

/**
 * Phone and watch under automatic switching, each running the same decisions
 * SensorOwnershipRuntime runs: [resolvePeerStandDownStart] when a report
 * arrives, [resolvePeerStandDownConfirmed] when it is read, and
 * [resolveLocalOwnershipProof] for what each one announces. The policy tests
 * pin each rule alone; these pin what the two devices do to each other, which
 * is where "both stood down" and "neither took it" came from.
 */
class SensorOwnershipPairedTests {
    private val start = 1_800_000_000_000L
    private val confirmMs = 15_000L
    private val silentAfter = 11L * 60_000L

    private inner class Device(val isPhone: Boolean, val connectsPerReading: Boolean = false) {
        var released = false
        var gattConnected = false
        var seenAt: Long? = null
        var lastReadingMs = 0L
        private var peer: PeerReport? = null
        private var falseSince: Long? = null

        fun read(now: Long) {
            seenAt = now
            lastReadingMs = now
        }

        fun owns(now: Long) = resolveLocalOwnershipProof(
            released = released,
            gattConnected = gattConnected,
            connectsPerReading = connectsPerReading,
            lastLocalReadingSeenAtMs = seenAt,
            nowMs = now,
            perReadingFreshMs = 11L * 60_000L,
            connectedStaleMs = 20L * 60_000L,
        )

        fun hear(from: Device, now: Long) {
            val owns = from.owns(now)
            falseSince = resolvePeerStandDownStart(peer?.owns, owns, falseSince, now)
            peer = PeerReport(owns = owns, lastReadingMs = from.lastReadingMs, receivedAtMs = now)
        }

        private fun served(now: Long): PeerReport? {
            val raw = peer ?: return null
            if (raw.owns) return raw
            if (resolvePeerStandDownConfirmed(falseSince, now, confirmMs)) {
                falseSince = null
                return raw
            }
            return raw.copy(owns = true)
        }

        /** One reconcile pass; returns whether this device reads the sensor. */
        fun reconcile(now: Long): Boolean {
            val shouldRead = SensorOwnershipPolicy.shouldReadLocally(
                isPhone = isPhone,
                intent = Intent.TAKE,
                localHasConnection = owns(now),
                localLastReadingMs = lastReadingMs,
                peer = served(now),
                nowMs = now,
                peerSilentAfterMs = silentAfter,
            )
            if (!shouldRead && !released) {
                released = true
                gattConnected = false
                seenAt = null
            } else if (shouldRead && released) {
                released = false
            }
            return shouldRead
        }
    }

    private fun exchange(phone: Device, watch: Device, now: Long) {
        watch.hear(phone, now)
        phone.hear(watch, now)
    }

    @Test
    fun twoIdleDevicesStartingTogetherDoNotBothStandDown() {
        val phone = Device(isPhone = true)
        val watch = Device(isPhone = false)
        exchange(phone, watch, start)
        // Each one's first "I don't have it" used to be held as "I have it" for
        // fifteen seconds, and both let go of a sensor neither was reading.
        assertTrue(phone.reconcile(start))
        assertTrue(watch.reconcile(start))
    }

    @Test
    fun theWatchWaitsOutAReadingPhonesBlipThenTakesOver() {
        val phone = Device(isPhone = true)
        val watch = Device(isPhone = false)
        phone.gattConnected = true
        phone.read(start)
        exchange(phone, watch, start)
        assertFalse(watch.reconcile(start))
        assertTrue(phone.reconcile(start))

        // The phone's link drops. A blip must not start a takeover...
        val drop = start + 60_000L
        phone.gattConnected = false
        exchange(phone, watch, drop)
        assertFalse(watch.reconcile(drop + 5_000L))
        // ...but a real loss does, once the grace has passed.
        assertTrue(watch.reconcile(drop + confirmMs))
    }

    @Test
    fun aG7PhoneBetweenReadingsKeepsTheWatchOff() {
        val phone = Device(isPhone = true, connectsPerReading = true)
        val watch = Device(isPhone = false)
        phone.read(start)
        // Three minutes later the G7 has hung up, as it does after every reading.
        val between = start + 3L * 60_000L
        exchange(phone, watch, between)
        assertFalse(watch.reconcile(between + confirmMs))
        assertTrue(phone.reconcile(between + confirmMs))
    }

    @Test
    fun aLinkThatDeliversNothingLetsTheWatchTryInTheEnd() {
        val phone = Device(isPhone = true)
        val watch = Device(isPhone = false)
        phone.gattConnected = true
        phone.read(start)
        exchange(phone, watch, start)
        assertFalse(watch.reconcile(start))

        // Still connected, nothing accepted for over twenty minutes.
        val dead = start + 21L * 60_000L
        exchange(phone, watch, dead)
        assertFalse(watch.reconcile(dead))
        assertTrue(watch.reconcile(dead + confirmMs))
        // The phone keeps reading too, until one of them proves it has it.
        assertTrue(phone.reconcile(dead + confirmMs))
    }

    @Test
    fun theDeviceThatGotTheSensorKeepsItAndTheOtherStandsDown() {
        val phone = Device(isPhone = true)
        val watch = Device(isPhone = false)
        exchange(phone, watch, start)
        assertTrue(phone.reconcile(start))
        assertTrue(watch.reconcile(start))

        // The watch's connection lands first and a reading arrives.
        val got = start + 40_000L
        watch.gattConnected = true
        watch.read(got)
        exchange(phone, watch, got)
        assertFalse(phone.reconcile(got))
        assertTrue(watch.reconcile(got))
    }
}
