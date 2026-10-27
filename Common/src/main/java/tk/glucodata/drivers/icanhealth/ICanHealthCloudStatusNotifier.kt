// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Driver
// ICanHealthCloudStatusNotifier.kt — plain informational notification for the offline
// fallback ladder of the vendor calibration table (see ICanCloudOfflineLadder): when
// the vendor cloud is unreachable at provisioning time, tell the user which fallback
// was applied instead of calibrating silently. Deliberately plain, like
// SensorHandoverNotifier — a state change to notice, not an alarm.
//
// Texts live in res/values/strings_ican.xml (a separate file so this change does not
// touch the shared strings.xml owned by another workstream).

package tk.glucodata.drivers.icanhealth

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

internal object ICanHealthCloudStatusNotifier {
    private const val CHANNEL_ID = "ICAN_CLOUD_STATUS"
    private const val NOTIFICATION_ID_UNAVAILABLE = 0x4943
    private const val NOTIFY_THROTTLE_MS = 30L * 60L * 1000L

    @Volatile
    private var lastNotifiedMs = 0L

    fun notifyCalibrationPending(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedMs < NOTIFY_THROTTLE_MS) return
        lastNotifiedMs = now
        show(
            context,
            context.getString(R.string.ican_cloud_unavailable_title),
            context.getString(R.string.ican_calibration_pending),
            NOTIFICATION_ID_UNAVAILABLE,
        )
    }

    fun clearServerUnavailable(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(NOTIFICATION_ID_UNAVAILABLE)
    }

    private fun show(context: Context, title: String, text: String, notificationId: Int) {
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
            .setContentTitle(title)
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ican_cloud_unavailable_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }
}
