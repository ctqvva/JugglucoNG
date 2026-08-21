package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.AlertType

/**
 * The quiet window may only take sound (and, in notification-only mode,
 * vibration) away, and two rules are not the UI's to bend: VERY_LOW ignores the
 * window, and a silenced alarm that stays active past the breakthrough time
 * sounds as if there were no window.
 */
class AlertDeliveryPolicyQuietWindowTests {

    private val low = AlertType.LOW.id
    private val high = AlertType.HIGH.id
    private val veryLow = AlertType.VERY_LOW.id
    private val minute = 60_000L

    @Test
    fun windowInactiveChangesNothing() {
        for (kind in AlertType.entries.map { it.id }) {
            assertFalse(AlertDeliveryPolicy.shouldSilenceSound(false, kind, false))
            assertFalse(
                AlertDeliveryPolicy.shouldSuppressVibration(
                    false, AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, kind, false
                )
            )
        }
    }

    @Test
    fun vibrateOnlySilencesSoundAndKeepsVibration() {
        assertTrue(AlertDeliveryPolicy.shouldSilenceSound(true, low, false))
        assertTrue(AlertDeliveryPolicy.shouldSilenceSound(true, high, false))
        assertFalse(
            AlertDeliveryPolicy.shouldSuppressVibration(true, AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, low, false)
        )
    }

    @Test
    fun notificationOnlySilencesSoundAndVibration() {
        assertTrue(AlertDeliveryPolicy.shouldSilenceSound(true, low, false))
        assertTrue(
            AlertDeliveryPolicy.shouldSuppressVibration(
                true, AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, low, false
            )
        )
    }

    @Test
    fun veryLowIgnoresTheWindowEntirely() {
        assertFalse(AlertDeliveryPolicy.quietWindowAppliesTo(veryLow))
        assertFalse(AlertDeliveryPolicy.shouldSilenceSound(true, veryLow, false))
        assertFalse(
            AlertDeliveryPolicy.shouldSuppressVibration(
                true, AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, veryLow, false
            )
        )
        // Every other kind is a candidate.
        AlertType.entries.filter { it != AlertType.VERY_LOW }.forEach {
            assertTrue(it.name, AlertDeliveryPolicy.quietWindowAppliesTo(it.id))
        }
    }

    @Test
    fun breakthroughRestoresSoundAndVibration() {
        assertFalse(AlertDeliveryPolicy.shouldSilenceSound(true, low, true))
        assertFalse(
            AlertDeliveryPolicy.shouldSuppressVibration(
                true, AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, low, true
            )
        )
    }

    @Test
    fun breakthroughHappensOnceTheSilencedEpisodeIsOldEnough() {
        val since = 1_700_000_000_000L
        val tenMinutes = 10 * minute
        assertFalse(AlertDeliveryPolicy.quietWindowBreaksThrough(since, since, tenMinutes))
        assertFalse(AlertDeliveryPolicy.quietWindowBreaksThrough(since, since + 9 * minute, tenMinutes))
        assertTrue(AlertDeliveryPolicy.quietWindowBreaksThrough(since, since + tenMinutes, tenMinutes))
        assertTrue(AlertDeliveryPolicy.quietWindowBreaksThrough(since, since + 12 * minute, tenMinutes))
        // No silenced episode, or no breakthrough configured: never.
        assertFalse(AlertDeliveryPolicy.quietWindowBreaksThrough(0L, since + tenMinutes, tenMinutes))
        assertFalse(AlertDeliveryPolicy.quietWindowBreaksThrough(since, since + tenMinutes, 0L))
    }

    @Test
    fun unknownModeIsVibrateOnly() {
        assertEquals(AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, AlertDeliveryPolicy.normalizeQuietMode(null))
        assertEquals(AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, AlertDeliveryPolicy.normalizeQuietMode("silent"))
        assertEquals(AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, AlertDeliveryPolicy.normalizeQuietMode("vibrate_only"))
        assertEquals(
            AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY,
            AlertDeliveryPolicy.normalizeQuietMode("NOTIFICATION_ONLY")
        )
        assertFalse(AlertDeliveryPolicy.shouldSuppressVibration(true, "silent", low, false))
    }

    @Test
    fun existingDeliveryDecisionsAreUntouched() {
        // The window is not a delivery mode: the surfaces and the audio stream
        // choice answer exactly as before.
        assertTrue(AlertDeliveryPolicy.shouldAttachFullScreenIntent(AlertDeliveryPolicy.SYSTEM_ALARM))
        assertTrue(AlertDeliveryPolicy.shouldPostAlertNotification(AlertDeliveryPolicy.BOTH, true))
        assertTrue(AlertDeliveryPolicy.shouldUseAlarmAudioStream(AlertDeliveryPolicy.NOTIFICATION_ONLY, true))
        assertFalse(AlertDeliveryPolicy.shouldUseAlarmAudioStream(AlertDeliveryPolicy.NOTIFICATION_ONLY, false))
    }
}
