package tk.glucodata

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #902: with Do Not Disturb on and "Override silent mode" off, a Low still
 * buzzed the phone (and, through it, a watch) because only the sound looked at
 * Do Not Disturb. Sound and vibration are now one decision. These pin the rule
 * itself, including the half that must not move: an alert whose override is on
 * still gets through.
 */
class AlertDeliveryPolicyDndTests {

    private val all = NotificationManager.INTERRUPTION_FILTER_ALL
    private val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY
    private val none = NotificationManager.INTERRUPTION_FILTER_NONE
    private val alarms = NotificationManager.INTERRUPTION_FILTER_ALARMS
    private val unknown = NotificationManager.INTERRUPTION_FILTER_UNKNOWN

    @Test
    fun everyDoNotDisturbModeCounts() {
        assertTrue(AlertDeliveryPolicy.isDoNotDisturbActive(priority))
        assertTrue(AlertDeliveryPolicy.isDoNotDisturbActive(none))
        assertTrue(AlertDeliveryPolicy.isDoNotDisturbActive(alarms))
    }

    @Test
    fun noFilterAndAnUnreadableFilterAreNotDoNotDisturb() {
        assertFalse(AlertDeliveryPolicy.isDoNotDisturbActive(all))
        // The system would not say: that is no reason to silence a glucose alert.
        assertFalse(AlertDeliveryPolicy.isDoNotDisturbActive(unknown))
    }

    @Test
    fun onlyDoNotDisturbWithoutTheOverrideBlocksEffects() {
        assertTrue(AlertDeliveryPolicy.dndBlocksEffects(true, false))
        assertFalse(AlertDeliveryPolicy.dndBlocksEffects(true, true))
        assertFalse(AlertDeliveryPolicy.dndBlocksEffects(false, false))
        assertFalse(AlertDeliveryPolicy.dndBlocksEffects(false, true))
    }

    @Test
    fun theIssueScenarioVibrationStopsWithDoNotDisturbAndNoOverride() {
        val blocks = AlertDeliveryPolicy.dndBlocksEffects(
            AlertDeliveryPolicy.isDoNotDisturbActive(priority), false
        )
        assertFalse(
            "a Low with the override off must not vibrate under Do Not Disturb",
            AlertDeliveryPolicy.shouldVibrate(true, false, blocks)
        )
    }

    @Test
    fun anAlertWithTheOverrideStillVibratesThroughDoNotDisturb() {
        // The default Low and Very low carry the override; this is the half of
        // the rule that must never be lost.
        val blocks = AlertDeliveryPolicy.dndBlocksEffects(
            AlertDeliveryPolicy.isDoNotDisturbActive(alarms), true
        )
        assertTrue(
            AlertDeliveryPolicy.shouldVibrate(true, false, blocks)
        )
    }

    @Test
    fun vibrationIsNeverInventedForAnAlertThatDoesNotVibrate() {
        for (quiet in listOf(false, true)) for (dnd in listOf(false, true)) {
            assertFalse(AlertDeliveryPolicy.shouldVibrate(false, quiet, dnd))
        }
    }

    @Test
    fun theQuietWindowAndDoNotDisturbEachCutVibrationOnTheirOwn() {
        assertTrue(AlertDeliveryPolicy.shouldVibrate(true, false, false))
        assertFalse(AlertDeliveryPolicy.shouldVibrate(true, true, false))
        assertFalse(AlertDeliveryPolicy.shouldVibrate(true, false, true))
        assertFalse(AlertDeliveryPolicy.shouldVibrate(true, true, true))
    }

    @Test
    fun theQuietWindowsVibrateOnlyModeDoesNotLetDoNotDisturbVibrate() {
        // Quiet window "vibrate only" keeps vibration for itself; Do Not Disturb
        // without the override still takes it.
        val quietSuppresses = AlertDeliveryPolicy.shouldSuppressVibration(
            true, AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, false
        )
        assertFalse(quietSuppresses)
        assertFalse(AlertDeliveryPolicy.shouldVibrate(true, quietSuppresses, true))
        assertTrue(AlertDeliveryPolicy.shouldVibrate(true, quietSuppresses, false))
    }

    @Test
    fun aQuietWindowBreakthroughDoesNotBreakThroughDoNotDisturb() {
        // The breakthrough ends the quiet window's cut; it is not an override.
        val breakThrough = true
        val quietSuppresses = AlertDeliveryPolicy.shouldSuppressVibration(
            true, AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, breakThrough
        )
        assertFalse(quietSuppresses)
        assertFalse(AlertDeliveryPolicy.shouldVibrate(true, quietSuppresses, true))
    }

    @Test
    fun aSoundDelayIsDroppedWhenNothingWillPlay() {
        // The delay stretches the stop (and the next retry) by N seconds; with the sound
        // gone there is nothing to wait for.
        assertTrue(AlertDeliveryPolicy.shouldDelaySound(true, true, false, false))
        assertFalse(AlertDeliveryPolicy.shouldDelaySound(true, true, false, true))
        assertFalse(AlertDeliveryPolicy.shouldDelaySound(true, true, true, false))
        assertFalse(AlertDeliveryPolicy.shouldDelaySound(true, false, false, false))
        assertFalse(AlertDeliveryPolicy.shouldDelaySound(false, true, false, false))
    }

    @Test
    fun thePhoneFollowsItsOwnSettingWhateverTheWatchHasHeard() {
        assertFalse(AlertDeliveryPolicy.effectiveOverrideDnd(false, false, false))
        assertTrue(AlertDeliveryPolicy.effectiveOverrideDnd(false, false, true))
        assertFalse(AlertDeliveryPolicy.effectiveOverrideDnd(false, true, false))
        assertTrue(AlertDeliveryPolicy.effectiveOverrideDnd(false, true, true))
    }

    @Test
    fun aWatchThatHasNotHeardFromThePhoneKeepsBreakingThrough() {
        // Going quiet on a setting it was never told would silence a hypo.
        assertTrue(AlertDeliveryPolicy.effectiveOverrideDnd(true, false, false))
        assertTrue(AlertDeliveryPolicy.effectiveOverrideDnd(true, false, true))
    }

    @Test
    fun aWatchFollowsThePhoneOnceItHasReported() {
        assertFalse(AlertDeliveryPolicy.effectiveOverrideDnd(true, true, false))
        assertTrue(AlertDeliveryPolicy.effectiveOverrideDnd(true, true, true))
    }

    @Test
    fun theWholeMatrixAgreesWithTheStatedRule() {
        // device x override x DND x vibrate: the alert vibrates exactly when it
        // is asked to and Do Not Disturb is not holding it back.
        var cases = 0
        for (wearable in listOf(false, true)) for (reported in listOf(false, true))
            for (configured in listOf(false, true)) for (dnd in listOf(false, true)) {
                val override = AlertDeliveryPolicy.effectiveOverrideDnd(wearable, reported, configured)
                val blocks = AlertDeliveryPolicy.dndBlocksEffects(dnd, override)
                val vibrates = AlertDeliveryPolicy.shouldVibrate(true, false, blocks)
                val expectedOverride = if (wearable && !reported) true else configured
                assertEquals("wearable=$wearable reported=$reported configured=$configured dnd=$dnd",
                    !dnd || expectedOverride, vibrates)
                cases++
            }
        assertEquals(16, cases)
    }
}
