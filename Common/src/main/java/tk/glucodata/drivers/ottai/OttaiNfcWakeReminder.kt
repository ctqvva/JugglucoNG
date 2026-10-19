package tk.glucodata.drivers.ottai

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
import tk.glucodata.MainActivity
import tk.glucodata.R

internal object OttaiNfcWakeReminder {
    private const val CHANNEL_ID = "OTTAI_NFC_WAKE"
    private const val NOTIFICATION_BASE_ID = 0x4f54

    fun show(context: Context, sensorId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        createChannel(context, manager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notificationId = notificationId(sensorId)
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.ottai_nfc_dump_armed)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(context.getString(R.string.ottai_setup_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(notificationId, notification)
    }

    fun cancel(context: Context, sensorId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(notificationId(sensorId))
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
                context.getString(R.string.ottai_setup_title),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.ottai_nfc_dump_armed)
                setShowBadge(false)
            },
        )
    }

    private fun notificationId(sensorId: String): Int =
        NOTIFICATION_BASE_ID xor OttaiConstants.canonicalSensorId(sensorId).hashCode()
}
