package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenIntentReadinessTests {

    private fun alert(mode: AlertDeliveryMode, enabled: Boolean = true): AlertConfig {
        return AlertConfig(type = AlertType.LOW, enabled = enabled, deliveryMode = mode)
    }

    private fun custom(style: String, enabled: Boolean = true): CustomAlertConfig {
        return CustomAlertConfig(enabled = enabled, style = style)
    }

    @Test
    fun permissionIsOnlyEnforcedFromAndroid14() {
        assertFalse(FullScreenIntentReadiness.isEnforced(33))
        assertTrue(FullScreenIntentReadiness.isEnforced(34))
        assertTrue(FullScreenIntentReadiness.isEnforced(35))
    }

    @Test
    fun alarmSurfacesUseFullScreenDelivery() {
        assertTrue(FullScreenIntentReadiness.usesFullScreenDelivery(alert(AlertDeliveryMode.SYSTEM_ALARM)))
        assertTrue(FullScreenIntentReadiness.usesFullScreenDelivery(alert(AlertDeliveryMode.BOTH)))
        assertFalse(FullScreenIntentReadiness.usesFullScreenDelivery(alert(AlertDeliveryMode.NOTIFICATION_ONLY)))
    }

    @Test
    fun disabledAlertsDoNotCount() {
        assertFalse(FullScreenIntentReadiness.usesFullScreenDelivery(alert(AlertDeliveryMode.SYSTEM_ALARM, enabled = false)))
        assertFalse(FullScreenIntentReadiness.usesFullScreenDelivery(custom("alarm", enabled = false)))
    }

    @Test
    fun customAlertStylesFollowTheSameRule() {
        assertTrue(FullScreenIntentReadiness.usesFullScreenDelivery(custom("alarm")))
        assertTrue(FullScreenIntentReadiness.usesFullScreenDelivery(custom("both")))
        assertFalse(FullScreenIntentReadiness.usesFullScreenDelivery(custom("notification")))
    }

    @Test
    fun checkIsSkippedWhenNoAlertUsesFullScreen() {
        val notificationOnly = listOf(alert(AlertDeliveryMode.NOTIFICATION_ONLY))
        val noCustom = emptyList<CustomAlertConfig>()
        assertFalse(FullScreenIntentReadiness.anyAlertUsesFullScreen(notificationOnly, noCustom))
        assertFalse(FullScreenIntentReadiness.shouldCheck(34, false))
    }

    @Test
    fun checkRunsOnAndroid14WhenAnyAlertUsesFullScreen() {
        val alerts = listOf(alert(AlertDeliveryMode.NOTIFICATION_ONLY), alert(AlertDeliveryMode.SYSTEM_ALARM))
        assertTrue(FullScreenIntentReadiness.anyAlertUsesFullScreen(alerts, emptyList()))
        assertTrue(FullScreenIntentReadiness.anyAlertUsesFullScreen(emptyList(), listOf(custom("both"))))
        assertTrue(FullScreenIntentReadiness.shouldCheck(34, true))
    }

    @Test
    fun checkNeverRunsBelowAndroid14() {
        assertFalse(FullScreenIntentReadiness.shouldCheck(33, true))
    }
}
