package tk.glucodata.drivers.sibionics

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import tk.glucodata.MainActivity
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.UiRefreshBus

internal object SibionicsResetReminder {
    private const val CHANNEL_ID = "SIBIONICS_RESET"
    private const val NOTIFICATION_BASE_ID = 0x5349
    private const val EXTRA_SENSOR_ID = "sensor_id"
    private const val EXTRA_HARD_DEADLINE_MS = "hard_deadline_ms"

    const val ACTION_RESET_NOW = "tk.glucodata.sibionics.RESET_NOW"
    const val ACTION_POSTPONE = "tk.glucodata.sibionics.POSTPONE_RESET"

    fun show(context: Context, sensorId: String, dueAtMs: Long, hardDeadlineMs: Long, nowMs: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        createChannel(context, manager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val requestCode = notificationId(sensorId)
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resetIntent = actionPendingIntent(
            context = context,
            sensorId = sensorId,
            hardDeadlineMs = hardDeadlineMs,
            action = ACTION_RESET_NOW,
            requestCode = requestCode + 1,
        )
        val postponeIntent = actionPendingIntent(
            context = context,
            sensorId = sensorId,
            hardDeadlineMs = hardDeadlineMs,
            action = ACTION_POSTPONE,
            requestCode = requestCode + 2,
        )
        val nextDeadline = if (dueAtMs > nowMs) dueAtMs else hardDeadlineMs
        val hours = ceil(((nextDeadline - nowMs).coerceAtLeast(0L)) / 3_600_000.0).toInt()
        val text = context.getString(R.string.sibionics_reset_reminder_text, hours)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(context.getString(R.string.sibionics_reset_reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.reset_sensor), resetIntent)
            .addAction(0, context.getString(R.string.sibionics_reset_postpone), postponeIntent)
            .build()
        manager.notify(notificationId(sensorId), notification)
    }

    fun cancel(context: Context, sensorId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(notificationId(sensorId))
    }

    private fun actionPendingIntent(
        context: Context,
        sensorId: String,
        hardDeadlineMs: Long,
        action: String,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, SibionicsResetReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_SENSOR_ID, sensorId)
            putExtra(EXTRA_HARD_DEADLINE_MS, hardDeadlineMs)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sibionics_reset_reminder_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.sibionics_reset_reminder_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun notificationId(sensorId: String): Int =
        NOTIFICATION_BASE_ID xor sensorId.hashCode()

    fun sensorId(intent: Intent): String = intent.getStringExtra(EXTRA_SENSOR_ID).orEmpty()

    fun hardDeadlineMs(intent: Intent): Long = intent.getLongExtra(EXTRA_HARD_DEADLINE_MS, 0L)
}

class SibionicsResetReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sensorId = SibionicsResetReminder.sensorId(intent)
        if (sensorId.isBlank()) return
        when (intent.action) {
            SibionicsResetReminder.ACTION_POSTPONE -> {
                val now = System.currentTimeMillis()
                val hardDeadline = SibionicsResetReminder.hardDeadlineMs(intent)
                val requestedUntil = now + SibionicsResetPolicy.POSTPONE_MS
                val until = if (hardDeadline > now) {
                    minOf(requestedUntil, hardDeadline)
                } else {
                    now
                }
                SibionicsRegistry.postponeReset(context, sensorId, until)
                SibionicsRegistry.markResetReminderShown(context, sensorId, now)
            }

            SibionicsResetReminder.ACTION_RESET_NOW -> {
                SibionicsRegistry.requestReset(context, sensorId)
                val manager = SensorBluetooth.mygatts()
                    .filterIsInstance<SibionicsBleManager>()
                    .firstOrNull { it.matchesManagedSensorId(sensorId) }
                if (manager != null) {
                    manager.resetSensor()
                } else {
                    SensorBluetooth.updateDevices()
                }
            }
        }
        SibionicsResetReminder.cancel(context, sensorId)
        UiRefreshBus.requestStatusRefresh()
    }
}
