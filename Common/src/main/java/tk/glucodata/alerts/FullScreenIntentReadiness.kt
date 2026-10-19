package tk.glucodata.alerts

import tk.glucodata.AlertDeliveryPolicy

/**
 * Pure rules for the "full-screen alarms" readiness check.
 *
 * Since Android 14 the USE_FULL_SCREEN_INTENT permission is no longer granted
 * just because the manifest declares it: for apps whose core function is not
 * calling or alarms it can be missing after install and is taken away again
 * with app updates, and only the user can re-grant it from system settings.
 * Without it a full-screen alarm silently degrades to a plain notification on
 * a locked screen. The readiness card only matters when at least one alert
 * actually uses full-screen delivery.
 */
object FullScreenIntentReadiness {
    /** Build.VERSION_CODES.UPSIDE_DOWN_CAKE, kept as a plain int so tests need no Android stubs. */
    const val FIRST_ENFORCED_SDK = 34

    fun isEnforced(sdkInt: Int): Boolean = sdkInt >= FIRST_ENFORCED_SDK

    fun usesFullScreenDelivery(config: AlertConfig): Boolean {
        return config.enabled &&
            AlertDeliveryPolicy.shouldAttachFullScreenIntent(AlertDeliveryPolicy.normalize(config.deliveryMode.name))
    }

    fun usesFullScreenDelivery(config: CustomAlertConfig): Boolean {
        return config.enabled &&
            AlertDeliveryPolicy.shouldAttachFullScreenIntent(AlertDeliveryPolicy.normalize(config.style))
    }

    fun anyAlertUsesFullScreen(
        alerts: Collection<AlertConfig>,
        customAlerts: Collection<CustomAlertConfig>
    ): Boolean {
        return alerts.any(::usesFullScreenDelivery) || customAlerts.any(::usesFullScreenDelivery)
    }

    /** The readiness item is shown only where the permission can actually be missing and matters. */
    fun shouldCheck(sdkInt: Int, anyAlertUsesFullScreen: Boolean): Boolean {
        return isEnforced(sdkInt) && anyAlertUsesFullScreen
    }
}
