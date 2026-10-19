package tk.glucodata

import android.content.Context

/**
 * The mobile-only notification-chart prediction overlay, as the shared code
 * needs it (plan P1/Q1).
 *
 * Registered from the mobile `Specific.registerBridges`; the watch registers
 * nothing and [NotificationPredictionAccess] then returns an empty series list,
 * which is what an absent overlay produced before.
 */
interface NotificationPredictionBridge {
    fun buildPredictionSeries(
        context: Context,
        data: List<GlucosePoint>?,
        isMmol: Boolean,
        viewMode: Int,
        hasCalibration: Boolean,
        calibrationSensorId: String?,
        targetLow: Float,
        targetHigh: Float,
    ): List<NotificationPredictionSeries>
}
