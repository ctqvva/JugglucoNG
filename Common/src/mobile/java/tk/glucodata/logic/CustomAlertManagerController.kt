package tk.glucodata.logic

import android.content.Context
import tk.glucodata.CustomAlertController
import tk.glucodata.LiveReadingLanes

/**
 * Hands the shared code this variant's custom-alert engine. Registered once in Specific.start();
 * see [tk.glucodata.CustomAlertAccess] for why this is not looked up reflectively.
 */
object CustomAlertManagerController : CustomAlertController {
    override fun checkAndTrigger(
        context: Context,
        reading: LiveReadingLanes,
        rate: Float,
        timestampMillis: Long,
        sensorId: String?,
        sensorGen: Int
    ) {
        CustomAlertManager.checkAndTrigger(context, reading, rate, timestampMillis, sensorId, sensorGen)
    }

    override fun dismissAlert(alertId: String) {
        CustomAlertManager.dismissAlert(alertId)
    }

    override fun snoozeAlert(alertId: String, snoozeMinutes: Int) {
        CustomAlertManager.snoozeAlert(alertId, snoozeMinutes)
    }

    override fun ignoreAlert(alertId: String) {
        CustomAlertManager.ignoreAlert(alertId)
    }
}
