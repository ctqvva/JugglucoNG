package tk.glucodata.ui.alerts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tk.glucodata.R
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertNotificationDismissAction
import tk.glucodata.alerts.HapticProfile

@Composable
internal fun AlertDeliveryMode.localizedName(): String = stringResource(
    when (this) {
        AlertDeliveryMode.NOTIFICATION_ONLY -> R.string.alert_delivery_notification
        AlertDeliveryMode.SYSTEM_ALARM -> R.string.alert_delivery_alarm
        AlertDeliveryMode.BOTH -> R.string.alert_delivery_both
    }
)

@Composable
internal fun HapticProfile.localizedName(): String = stringResource(
    when (this) {
        HapticProfile.SOFT -> R.string.haptic_profile_soft
        HapticProfile.STEADY -> R.string.haptic_profile_steady
        HapticProfile.STRONG -> R.string.haptic_profile_strong
        HapticProfile.ESCALATING -> R.string.haptic_profile_escalating
    }
)

@Composable
internal fun AlertNotificationDismissAction.localizedName(): String = stringResource(
    when (this) {
        AlertNotificationDismissAction.DISMISS -> R.string.notification_dismiss_action_dismiss
        AlertNotificationDismissAction.SNOOZE -> R.string.notification_dismiss_action_snooze
    }
)
