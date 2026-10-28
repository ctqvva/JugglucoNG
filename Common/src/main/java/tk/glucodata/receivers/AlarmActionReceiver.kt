package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.CustomAlertAccess
import tk.glucodata.Log
import tk.glucodata.Notify
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType

/**
 * Handles notification actions for alerts (snooze, dismiss, ignore) on both
 * flavours. Custom alerts go through [CustomAlertAccess], which forwards to the
 * engine the phone registers at startup and no-ops where none is registered.
 */
class AlarmActionReceiver : BroadcastReceiver() {

    companion object {
        private const val LOG_ID = "AlarmActionReceiver"
        const val ACTION_SNOOZE = "tk.glucodata.ACTION_SNOOZE"
        const val ACTION_DISMISS = "tk.glucodata.ACTION_DISMISS"
        const val ACTION_IGNORE = "tk.glucodata.ACTION_IGNORE"
        const val EXTRA_ALERT_TYPE_ID = "alert_type_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val customAlertId = intent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID)
        val fallbackAlertTypeId = intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1)

        when (intent.action) {
            ACTION_DISMISS -> tk.glucodata.alerts.AlertActions.handle(
                fallbackAlertTypeId.takeIf { it >= 0 } ?: Notify.resolveAlertKind(-1), customAlertId
            )

            ACTION_SNOOZE -> {
                val kind = fallbackAlertTypeId.takeIf { it >= 0 } ?: Notify.resolveAlertKind(-1)
                val minutes = if (intent.hasExtra(EXTRA_SNOOZE_MINUTES)) {
                    intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 15)
                } else if (customAlertId != null) 15
                else AlertType.fromId(kind)?.let { AlertRepository.loadConfig(it).defaultSnoozeMinutes } ?: 15
                tk.glucodata.alerts.AlertActions.handle(kind, customAlertId, minutes)
            }

            ACTION_IGNORE -> {
                Log.i(LOG_ID, "Ignore action received for alert type: $fallbackAlertTypeId")
                Notify.cancelQueuedAlarmActivityLaunch(
                    Notify.resolveAlertKind(fallbackAlertTypeId),
                    customAlertId,
                    "notification-ignore"
                )
                Notify.stopalarm()
                if (customAlertId != null) {
                    CustomAlertAccess.ignoreAlert(customAlertId)
                }
                Notify.cancelAlertNotification()
            }
        }
    }
}
