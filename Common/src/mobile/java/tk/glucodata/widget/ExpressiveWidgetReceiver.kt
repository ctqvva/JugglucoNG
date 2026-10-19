package tk.glucodata.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tk.glucodata.GlucoseUpdateBroadcaster
import tk.glucodata.Log

class ExpressiveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExpressiveAppWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GlucoseUpdateBroadcaster.ACTION_GLUCOSE_UPDATE) {
            if (intent.getBooleanExtra(GlucoseUpdateBroadcaster.EXTRA_TICK_DELIVERED, false) &&
                GlucoseUpdateBroadcaster.hasActiveTickObservers()
            ) {
                return
            }
            val pendingResult = goAsync()
            scope.launch {
                try {
                    performUpdate(context.applicationContext)
                } catch (th: Throwable) {
                    Log.stack(LOG_ID, "onReceive update", th)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }
    
    companion object {
        private const val LOG_ID = "ExpressiveWidget"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        @JvmStatic
        fun updateAll(context: Context) {
            scope.launch {
                try {
                    performUpdate(context.applicationContext)
                } catch (th: Throwable) {
                    Log.stack(LOG_ID, "updateAll", th)
                }
            }
        }

        private suspend fun performUpdate(context: Context) {
            val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
            val widget = ExpressiveAppWidget()
            val glanceIds = manager.getGlanceIds(ExpressiveAppWidget::class.java)
            glanceIds.forEach { glanceId ->
                try {
                    widget.update(context, glanceId)
                } catch (th: Throwable) {
                    Log.stack(LOG_ID, "update $glanceId", th)
                }
            }
        }
    }
}
