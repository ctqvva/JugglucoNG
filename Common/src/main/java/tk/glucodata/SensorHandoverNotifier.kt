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
 * One-off informational notifications for the automatic sensor handover:
 * the receipt after a switch (also the warming-up variant) and the warning
 * when several successor candidates exist. Deliberately plain notifications,
 * not alarms - the user should see the state change (even in the morning
 * after sleeping through it), not be woken by it.
 */
internal object SensorHandoverNotifier {
    private const val CHANNEL_ID = "SENSOR_HANDOVER"
    private const val NOTIFICATION_ID_SWITCHED = 0x5348
    private const val NOTIFICATION_ID_WARNING = 0x5349

    fun notifySwitched(context: Context, oldSerial: String, newSerial: String) {
        show(
            context,
            context.getString(R.string.sensor_handover_switched_text, oldSerial, newSerial),
            NOTIFICATION_ID_SWITCHED
        )
    }

    fun notifySwitchedWarming(context: Context, newSerial: String, warmupMinutes: Int) {
        show(
            context,
            context.getString(R.string.sensor_handover_warming_text, newSerial, warmupMinutes),
            NOTIFICATION_ID_SWITCHED
        )
    }

    fun notifyMultipleCandidates(context: Context) {
        show(context, context.getString(R.string.sensor_handover_multiple_text), NOTIFICATION_ID_WARNING)
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
            .setContentTitle(context.getString(R.string.sensor_handover_title))
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
                context.getString(R.string.sensor_handover_title),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setShowBadge(false)
            },
        )
    }
}
