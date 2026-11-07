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

    private fun decide(
        enabled: Boolean = true,
        main: String? = "46HU804EBJ4",
        managed: Boolean = true,
        last: String? = null,
        peerReads: Boolean = false,
    ) = shouldProvisionWatch(
        enabled = enabled,
        mainSensor = main,
        mainIsManaged = managed,
        lastProvisioned = last,
        peerReadsMain = peerReads,
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
    fun theSameSensorIsNotSentAgain() {
        // Re-applying it would overwrite the watch's newer driver state.
        assertFalse(decide(last = "46hu804ebj4"))
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
