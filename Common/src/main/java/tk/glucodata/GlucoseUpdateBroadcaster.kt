package tk.glucodata

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object GlucoseUpdateBroadcaster {
    const val ACTION_GLUCOSE_UPDATE = "tk.glucodata.action.GLUCOSE_UPDATE"
    const val EXTRA_TICK_DELIVERED = "tk.glucodata.extra.TICK_DELIVERED"

    private const val LOG_ID = "GlucoseUpdateBroadcast"
    private const val MIN_BROADCAST_INTERVAL_MS = 1_000L
    private val lock = Any()
    private var lastBroadcastElapsedRealtime = 0L

    private val tickState = MutableStateFlow(0L)

    // Glance widget sessions observe this because update() recomposes the
    // captured content but does not re-run provideGlance.
    val tick: StateFlow<Long> get() = tickState

    private val freshnessDeadline = AlarmManager.OnAlarmListener { send() }

    /**
     * Redraws the lock-screen overlay and widgets once [readingTimeMillis] passes
     * [Notify.glucosetimeout], so they swap the number for "No new value since …" whether
     * or not the glucose notification is enabled (its own deadline only runs when it is).
     * Non-wakeup: fires on time while the device is awake, otherwise on the next wake.
     */
    @JvmStatic
    fun armFreshnessDeadline(readingTimeMillis: Long) {
        if (Applic.isWearable || readingTimeMillis <= 0L) return
        val alarmManager = Applic.app?.getSystemService(AlarmManager::class.java) ?: return
        try {
            alarmManager.cancel(freshnessDeadline)
            // DisplayDataState counts the exact threshold as fresh; go one ms past it.
            alarmManager.setExact(
                AlarmManager.RTC,
                readingTimeMillis + Notify.glucosetimeout + 1L,
                "tk.glucodata:glucose-freshness",
                freshnessDeadline,
                null
            )
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "armFreshnessDeadline", th)
        }
    }

    @JvmStatic
    fun hasActiveTickObservers(): Boolean {
        return tickState.subscriptionCount.value > 0
    }

    @JvmStatic
    @JvmOverloads
    fun send(context: Context? = null) {
        val appContext = (context?.applicationContext ?: Applic.app) ?: return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (lastBroadcastElapsedRealtime > 0L &&
                now - lastBroadcastElapsedRealtime < MIN_BROADCAST_INTERVAL_MS
            ) {
                return
            }
            lastBroadcastElapsedRealtime = now
            tickState.value = tickState.value + 1L
        }

        try {
            appContext.sendBroadcast(
                Intent(ACTION_GLUCOSE_UPDATE)
                    .setPackage(appContext.packageName)
                    .putExtra(EXTRA_TICK_DELIVERED, true)
            )
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "send", th)
        }
    }
}
