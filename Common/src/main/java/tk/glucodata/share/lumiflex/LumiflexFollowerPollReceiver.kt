package tk.glucodata.share.lumiflex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executor
import tk.glucodata.Log

class LumiflexFollowerPollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        try {
            // Keep the broadcast alive until the request is persisted. WorkManager then
            // owns the process/wakelock lifetime, including multi-page catch-up reads.
            val operation = LumiflexCloudSyncWorker.enqueue(context)
            if (operation == null) pending.finish()
            else operation.result.addListener({ pending.finish() }, Executor { it.run() })
        } catch (error: Exception) {
            pending.finish()
            Log.stack("LumiflexFollower", "enqueue poll", error)
        }
    }
    companion object { const val ACTION = "tk.glucodata.lumiflex.FOLLOW_POLL" }
}
