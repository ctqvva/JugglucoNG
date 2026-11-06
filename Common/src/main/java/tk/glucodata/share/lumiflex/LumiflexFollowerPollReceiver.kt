package tk.glucodata.share.lumiflex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class LumiflexFollowerPollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val callback = LumiflexFollowerRegistry.reconcile(context) ?: return
        val lock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Juggluco::LumiflexFollower")?.apply {
                setReferenceCounted(false); acquire(3 * 60_000L)
            }
        callback.refresh(lock)
    }
    companion object { const val ACTION = "tk.glucodata.lumiflex.FOLLOW_POLL" }
}
