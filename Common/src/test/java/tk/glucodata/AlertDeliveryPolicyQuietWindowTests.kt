package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.AlertType

/**
 * The quiet window may only take sound (and, in notification-only mode,
 * vibration) away, and one rule is not the UI's to bend: a silenced alarm that
 * stays active past the breakthrough time sounds as if there were no window —
 * every kind, or the very high and very low only.
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
    fun everyKindIsSilencedVeryLowIncluded() {
        AlertType.values().forEach {
            assertTrue(it.name, AlertDeliveryPolicy.shouldSilenceSound(true, it.id, false))
        }
        assertTrue(AlertDeliveryPolicy.shouldSuppressVibration(
            true, AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, veryLow, false
        ))
        // Until it breaks through: then it sounds as if there were no window.
        assertFalse(AlertDeliveryPolicy.shouldSilenceSound(true, veryLow, true))
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

    @Test
    fun breakthroughScopeAllLetsEverySilencedAlarmThrough() {
        listOf(low, high, AlertType.VERY_HIGH.id, AlertType.PRE_LOW.id, AlertType.FALLING_FAST.id).forEach {
            assertTrue("kind $it", AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(it, AlertDeliveryPolicy.BREAKTHROUGH_ALL))
        }
        assertTrue(AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(low, null))
        assertTrue(AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(low, "whatever"))
    }

    @Test
    fun breakthroughScopeVeryOnlyKeepsTheRestQuietForTheWholeWindow() {
        val scope = AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY
        assertTrue(AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(AlertType.VERY_HIGH.id, scope))
        assertTrue(AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(veryLow, scope))
        listOf(low, high, AlertType.PRE_LOW.id, AlertType.PRE_HIGH.id, AlertType.FALLING_FAST.id,
            AlertType.RISING_FAST.id, AlertType.MISSED_READING.id, AlertType.LOSS.id).forEach {
            assertFalse("kind $it", AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(it, scope))
        }
        // Case and unknown values: unknown falls back to all.
        assertFalse(AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(low, "VERY_ONLY"))
        assertEquals(AlertDeliveryPolicy.BREAKTHROUGH_ALL, AlertDeliveryPolicy.normalizeBreakthroughScope("nope"))
    }
}
