package tk.glucodata

import android.content.Context

/**
 * Registration seam for [NotificationPredictionBridge] (plan P1/Q1).
 *
 * NotificationChartDrawer used to find `tk.glucodata.NotificationPredictionOverlay`
 * by name. A full keep rule protected it; registration drops that rule and leaves
 * ordinary interface calls behind.
 *
 * The method keeps the old reflective failure contract: an exception in the
 * prediction math degrades to an empty series list instead of breaking the
 * notification chart, which is drawn on every reading.
 */
object NotificationPredictionAccess {
    private const val TAG = "NotificationPredictionAccess"

    @Volatile
    private var bridge: NotificationPredictionBridge? = null

    @JvmStatic
    fun register(bridge: NotificationPredictionBridge) {
        this.bridge = bridge
    }

    /** Registration-completeness check (plan §6 Q1). */
    @JvmStatic
    fun isRegistered(): Boolean = bridge != null

    /** Empty without an overlay (the watch) or when the prediction failed. */
    @JvmStatic
    fun buildPredictionSeries(
        context: Context,
        data: List<GlucosePoint>?,
        isMmol: Boolean,
        viewMode: Int,
        hasCalibration: Boolean,
        calibrationSensorId: String?,
        targetLow: Float,
        targetHigh: Float,
    ): List<NotificationPredictionSeries> =
        runCatching {
            bridge?.buildPredictionSeries(
                context,
                data,
                isMmol,
                viewMode,
                hasCalibration,
                calibrationSensorId,
                targetLow,
                targetHigh,
            )
        }.onFailure { Log.stack(TAG, "buildPredictionSeries failed", it) }
            .getOrNull() ?: emptyList()
}
