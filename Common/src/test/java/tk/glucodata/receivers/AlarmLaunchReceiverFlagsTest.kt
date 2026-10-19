package tk.glucodata.receivers

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch flags are the only thing that ever differed between the phone and
 * watch copies of [AlarmLaunchReceiver]. Pinning both here is what makes the
 * merged receiver safe: a future edit that drops the watch's CLEAR_TOP (or the
 * phone's single-top) turns this red instead of changing alarm behaviour
 * silently. See T5.1 in docs/architecture/.
 */
class AlarmLaunchReceiverFlagsTest {

    @Test
    fun thePhoneLaunchStartsFreshWithoutClearingTheStack() {
        val flags = AlarmLaunchReceiver.launchFlags(isWear = false)

        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    @Test
    fun theWearLaunchReplacesTheAlarmAlreadyOnScreen() {
        val flags = AlarmLaunchReceiver.launchFlags(isWear = true)

        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun theActionNameIsStable() {
        // Notify builds an Intent with this action and the manifest matches it.
        assertEquals(
            "tk.glucodata.action.LAUNCH_ALARM_ACTIVITY",
            AlarmLaunchReceiver.ACTION_LAUNCH_ALARM_ACTIVITY,
        )
    }
}
