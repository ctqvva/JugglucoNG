package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.ui.AlarmActivityAccess

/**
 * Forwards the alarm extras to the flavour's alarm activity through
 * [AlarmActivityAccess] when a notification cannot carry the full-screen intent.
 * One class for both flavours: the only difference was [launchFlags], and the two
 * copies of this receiver had the same FQN, so a change made on the phone silently
 * did not reach the watch (see the duplicate-FQN guard in `NoDuplicateFqnTest`).
 */
class AlarmLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (AlarmActivityAccess.isAcknowledged(intent)) return
            val base = AlarmActivityAccess.newIntent(context.applicationContext)
            if (base == null) {
                Log.e(LOG_ID, "no alarm activity registered; dropping launch")
                return
            }
            val alarmIntent = base.apply {
                putExtras(intent)
                flags = launchFlags(Applic.isWearable)
            }
            context.applicationContext.startActivity(alarmIntent)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "launch alarm activity", t)
        }
    }

    companion object {
        private const val LOG_ID = "AlarmLaunchReceiver"
        const val ACTION_LAUNCH_ALARM_ACTIVITY = "tk.glucodata.action.LAUNCH_ALARM_ACTIVITY"

        /**
         * NEW_TASK because the start comes from a receiver, SINGLE_TOP so a
         * repeated alarm does not stack a second activity, NO_USER_ACTION so
         * opening it is not treated as the user having seen the alarm.
         *
         * The watch adds CLEAR_TOP: a new alarm replaces the one already on
         * screen instead of landing behind it.
         */
        internal fun launchFlags(isWear: Boolean): Int =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                (if (isWear) Intent.FLAG_ACTIVITY_CLEAR_TOP else 0)
    }
}
