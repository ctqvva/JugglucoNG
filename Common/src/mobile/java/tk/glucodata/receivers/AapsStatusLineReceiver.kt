package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tk.glucodata.Log
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.journal.AapsJournalImport
import tk.glucodata.data.journal.AapsStatusLineImport

/**
 * The status line AAPS broadcasts for xDrip, read for its temp basal. AAPS sends it
 * implicitly (no target package), which Android 8+ does not deliver to manifest
 * receivers, so this one is registered at runtime and lives as long as the process.
 */
class AapsStatusLineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AapsJournalImport.isEnabled(context)) return
        val statusLine = intent.getStringExtra(EXTRA_STATUSLINE) ?: return
        val receivedAt = System.currentTimeMillis()
        val pendingResult = goAsync()
        importScope.launch {
            try {
                val written = AapsStatusLineImport.handle(context.applicationContext, statusLine, receivedAt)
                if (written > 0) UiRefreshBus.requestDataRefresh()
            } catch (t: Throwable) {
                Log.stack(TAG, "AAPS status line import failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AapsStatusLineReceiver"
        private const val ACTION_STATUSLINE = "com.eveningoutpost.dexdrip.ExternalStatusline"
        private const val EXTRA_STATUSLINE = "com.eveningoutpost.dexdrip.Extras.Statusline"
        private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val registered = AtomicBoolean(false)

        @JvmStatic
        fun register(context: Context) {
            if (!registered.compareAndSet(false, true)) return
            try {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    AapsStatusLineReceiver(),
                    IntentFilter(ACTION_STATUSLINE),
                    ContextCompat.RECEIVER_EXPORTED,
                )
            } catch (t: Throwable) {
                registered.set(false)
                Log.stack(TAG, "register", t)
            }
        }
    }
}
