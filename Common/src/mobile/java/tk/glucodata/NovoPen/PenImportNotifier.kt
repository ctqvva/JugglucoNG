package tk.glucodata.NovoPen

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
import tk.glucodata.ui.PendingNavigation

/**
 * The result of a pen read taken with the app in the background. There is no sheet to
 * show, so a notification says what happened; tapping it opens the journal, or the
 * app itself when the read is waiting for a review.
 */
internal object PenImportNotifier {
    private const val CHANNEL_ID = "PEN_IMPORT"
    private const val NOTIFICATION_ID = 0x5045

    private const val PREFS_NAME = "tk.glucodata_preferences"

    fun imported(context: Context, serial: String, count: Int) {
        val text = if (count > 0) {
            context.resources.getQuantityString(R.plurals.insulin_pen_imported_count, count, count)
        } else {
            context.getString(R.string.insulin_pen_no_new_doses)
        }
        show(context, serial, text, openJournal = true)
    }

    fun nothingNew(context: Context, serial: String) {
        show(context, serial, context.getString(R.string.insulin_pen_no_new_doses), openJournal = true)
    }

    fun awaitingReview(context: Context, serial: String, count: Int) {
        val text = context.resources.getQuantityString(R.plurals.insulin_pen_review_count, count, count)
        show(context, serial, text, openJournal = false)
    }

    private fun show(context: Context, serial: String, text: String, openJournal: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        createChannel(context, manager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Notifications refused: say it the way the foreground scan does, at least.
            tk.glucodata.Applic.Toaster(text)
            return
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (openJournal) putExtra(PendingNavigation.EXTRA_ROUTE, journalRoute(context))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(context.getString(R.string.insulin_pen_name, serial))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** The same choice the journal settings make: the tab when it is shown, else the settings page. */
    private fun journalRoute(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tab = prefs.getBoolean("dashboard_journal_enabled", true) &&
            prefs.getBoolean("dashboard_journal_navigation_tab_enabled", false)
        return if (tab) "journal" else "settings/journal/history"
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
                context.getString(R.string.insulin_pen_import_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }
}
