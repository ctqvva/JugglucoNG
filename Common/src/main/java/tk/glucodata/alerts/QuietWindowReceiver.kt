package tk.glucodata.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.Log

/**
 * The quiet window's broadcasts: the AlarmManager expiry, the breakthrough check
 * for a silenced alarm, and the "end now" action of its notification. Declared
 * in the manifest, never exported.
 */
class QuietWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                QuietWindow.ACTION_EXPIRED -> {
                    // untilMs() ends an expired window itself; an alarm that came a
                    // little early just re-arms for the remaining time.
                    val until = QuietWindow.untilMs(System.currentTimeMillis())
                    if (until > 0L) {
                        QuietWindow.syncOnStart(context)
                    }
                }
                QuietWindow.ACTION_END -> QuietWindow.end(context)
                QuietWindow.ACTION_BREAKTHROUGH -> {
                    val kind = intent.getIntExtra(QuietWindow.EXTRA_KIND, -1)
                    if (kind >= 0) {
                        QuietWindow.breakThroughIfStillActive(kind)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.stack("QuietWindowReceiver", "onReceive", t)
        }
    }
}
