package tk.glucodata

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

object WearOngoingActivity : OngoingNotification {
    private const val LOG_ID = "WearOngoingActivity"

    override fun attach(context: Context, notification: Notification, notificationId: Int): Notification {
        return try {
            val builder = NotificationCompat.Builder(context, notification)
            OngoingActivity.Builder(context, notificationId, builder)
                .setStaticIcon(R.drawable.novalue)
                .setStatus(status(context))
                .setTouchIntent(mainPendingIntent(context))
                .setOngoingActivityId(notificationId)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setTitle(context.getString(R.string.app_name))
                .build()
                .apply(context)
            builder.build().also { it.flags = it.flags or Notification.FLAG_ONGOING_EVENT }
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "attach", th)
            notification
        }
    }

    override fun updateStatus(context: Context?, notificationId: Int) {
        context ?: return
        try {
            OngoingActivity.recoverOngoingActivity(context, notificationId)
                ?.update(context, status(context))
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "updateStatus", th)
        }
    }

    private fun status(context: Context): Status {
        val value = runCatching { CurrentDisplaySource.resolveCurrent()?.primaryStr }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.novalue)
        return Status.forPart(Status.TextPart(value))
    }

    private fun mainPendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, flags)
    }
}
