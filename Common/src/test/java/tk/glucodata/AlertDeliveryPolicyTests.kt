package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDeliveryPolicyTests {

    @Test
    fun normalizeAcceptsStoredAndLegacyModeNames() {
        assertEquals(AlertDeliveryPolicy.NOTIFICATION_ONLY, AlertDeliveryPolicy.normalize(null))
        assertEquals(AlertDeliveryPolicy.NOTIFICATION_ONLY, AlertDeliveryPolicy.normalize("notification"))
        assertEquals(AlertDeliveryPolicy.SYSTEM_ALARM, AlertDeliveryPolicy.normalize("alarm"))
        assertEquals(AlertDeliveryPolicy.SYSTEM_ALARM, AlertDeliveryPolicy.normalize("system_alarm"))
        assertEquals(AlertDeliveryPolicy.BOTH, AlertDeliveryPolicy.normalize("both"))
    }

    @Test
    fun alarmWindowIsAttemptedOnlyForAlarmSurfaces() {
        assertFalse(AlertDeliveryPolicy.shouldAttemptAlarmWindow(AlertDeliveryPolicy.NOTIFICATION_ONLY))
        assertTrue(AlertDeliveryPolicy.shouldAttemptAlarmWindow(AlertDeliveryPolicy.SYSTEM_ALARM))
        assertTrue(AlertDeliveryPolicy.shouldAttemptAlarmWindow(AlertDeliveryPolicy.BOTH))
    }

    @Test
    fun alarmOnlyPostsNotificationOnlyAsFallback() {
        assertFalse(
            AlertDeliveryPolicy.shouldPostAlertNotification(
                AlertDeliveryPolicy.SYSTEM_ALARM,
                true
            )
        )
        assertTrue(
            AlertDeliveryPolicy.shouldPostAlertNotification(
                AlertDeliveryPolicy.SYSTEM_ALARM,
                false
            )
        )
    }

    @Test
    fun bothAndNotificationModeKeepNotificationSurface() {
        assertTrue(
            AlertDeliveryPolicy.shouldPostAlertNotification(
                AlertDeliveryPolicy.BOTH,
                true
            )
        )
        assertTrue(
            AlertDeliveryPolicy.shouldPostAlertNotification(
                AlertDeliveryPolicy.NOTIFICATION_ONLY,
                false
            )
        )
    }

    @Test
    fun fullScreenIntentIsFallbackForAlarmSurfacesOnly() {
        assertFalse(AlertDeliveryPolicy.shouldAttachFullScreenIntent(AlertDeliveryPolicy.NOTIFICATION_ONLY))
        assertTrue(AlertDeliveryPolicy.shouldAttachFullScreenIntent(AlertDeliveryPolicy.SYSTEM_ALARM))
        assertTrue(AlertDeliveryPolicy.shouldAttachFullScreenIntent(AlertDeliveryPolicy.BOTH))
    }

    @Test
    fun alarmSurfacesUseAlarmAudioEvenWithoutDndOverride() {
        assertFalse(
            AlertDeliveryPolicy.shouldUseAlarmAudioStream(
                AlertDeliveryPolicy.NOTIFICATION_ONLY,
                false
            )
        )
        assertTrue(
            AlertDeliveryPolicy.shouldUseAlarmAudioStream(
                AlertDeliveryPolicy.NOTIFICATION_ONLY,
                true
            )
        )
        assertTrue(
            AlertDeliveryPolicy.shouldUseAlarmAudioStream(
                AlertDeliveryPolicy.SYSTEM_ALARM,
                false
            )
        )
        assertTrue(
            AlertDeliveryPolicy.shouldUseAlarmAudioStream(
                AlertDeliveryPolicy.BOTH,
                false
            )
        )
    }
}
