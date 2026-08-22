package tk.glucodata

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * One-off informational notifications for the compression-low hold: the receipt after a
 * hold resolves or escalates, and the notice when the mode disables itself. Plain
 * notifications, not alarms — whoever slept through the episode reads what happened
 * afterwards, whoever sat at a desk sees it right away; the log line is the same either
 * way, because pressure has no bedtime.
 */
internal object CompressionHoldNotifier {
    private const val CHANNEL_ID = "COMPRESSION_HOLD_STATUS"
    private const val NOTIFICATION_ID_OUTCOME = 0x434C
    private const val NOTIFICATION_ID_SELF_DISABLED = 0x434D

    fun notifyResolved(context: Context, heldMinutes: Int, summary: String) {
        show(
            context,
            context.getString(R.string.sensor_pressure_hold_resolved_text, heldMinutes) + "\n" + summary,
            NOTIFICATION_ID_OUTCOME
        )
    }

    fun notifyEscalated(context: Context, heldMinutes: Int, summary: String) {
        show(
            context,
            context.getString(R.string.sensor_pressure_hold_escalated_text, heldMinutes) + "\n" + summary,
            NOTIFICATION_ID_OUTCOME
        )
    }

    fun notifySelfDisabled(context: Context, escalations: Int) {
        show(
            context,
            context.getString(R.string.sensor_pressure_hold_self_disabled_text, escalations),
            NOTIFICATION_ID_SELF_DISABLED
        )
    }

    private fun show(context: Context, text: String, notificationId: Int) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        createChannel(context, manager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(context.getString(R.string.sensor_pressure_hold_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(notificationId, notification)
    }

    private fun createChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            manager.getNotificationChannel(CHANNEL_ID) != null
        ) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sensor_pressure_hold_title),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setShowBadge(false)
            },
        )
    }
}
