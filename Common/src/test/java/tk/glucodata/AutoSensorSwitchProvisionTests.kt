package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the phone sends the watch the worn sensor's setup for automatic
 * switching. It used to go once, when the switch was turned on, so a lost send
 * or the next sensor left the watch armed with nothing it could connect to.
 */
class AutoSensorSwitchProvisionTests {
    private val same: (String?, String?) -> Boolean = { a, b -> a.equals(b, ignoreCase = true) }

    private val now = 1_800_000_000_000L
    private val refresh = 60L * 60L * 1000L

    private fun decide(
        enabled: Boolean = true,
        main: String? = "46HU804EBJ4",
        managed: Boolean = true,
        last: String? = null,
        lastAt: Long = now,
        peerReads: Boolean = false,
        phoneReads: Boolean = false,
    ) = shouldProvisionWatch(
        enabled = enabled,
        mainSensor = main,
        mainIsManaged = managed,
        lastProvisioned = last,
        lastProvisionedAtMs = lastAt,
        peerReadsMain = peerReads,
        phoneReadsMain = phoneReads,
        nowMs = now,
        refreshMs = refresh,
        sameSensor = same,
    )

    @Test
    fun aWatchThatNeverGotTheSetupIsSentIt() {
        assertTrue(decide(last = null))
    }

    @Test
    fun aNewSensorIsSentEvenThoughTheOldOneWas() {
        assertTrue(decide(last = "46HU804EBJ0"))
    }

    @Test
    fun theSameSensorIsNotSentAgainSoon() {
        assertFalse(decide(last = "46hu804ebj4", phoneReads = true, lastAt = now - refresh + 1L))
    }

    @Test
    fun aSendThatMayHaveBeenLostIsRepeatedWhileThePhoneReadsIt() {
        // A successful send only means the Data Layer took it.
        assertTrue(decide(last = "46hu804ebj4", phoneReads = true, lastAt = now - refresh))
    }

    @Test
    fun theSameSensorIsNotRefreshedUnlessThePhoneReadsIt() {
        // The watch may have read it since; its state could be newer.
        assertFalse(decide(last = "46hu804ebj4", phoneReads = false, lastAt = now - 10L * refresh))
        assertFalse(decide(last = "46hu804ebj4", phoneReads = true, peerReads = true, lastAt = 0L))
    }

    @Test
    fun nothingIsSentWhileTheWatchIsReadingIt() {
        assertFalse(decide(last = null, peerReads = true))
    }

    @Test
    fun nativeSensorsAndASwitchedOffSettingSendNothing() {
        assertFalse(decide(managed = false))
        assertFalse(decide(enabled = false))
        assertFalse(decide(main = null))
    }
}
