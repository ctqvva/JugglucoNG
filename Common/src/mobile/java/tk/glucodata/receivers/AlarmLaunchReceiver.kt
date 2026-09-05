package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.Log
import tk.glucodata.ui.AlarmActivity

class AlarmLaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val alarmIntent = Intent(context.applicationContext, AlarmActivity::class.java).apply {
                putExtras(intent)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
            context.applicationContext.startActivity(alarmIntent)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "launch alarm activity", t)
        }
    }

    companion object {
        private const val LOG_ID = "AlarmLaunchReceiver"
        const val ACTION_LAUNCH_ALARM_ACTIVITY = "tk.glucodata.action.LAUNCH_ALARM_ACTIVITY"
    }
}
