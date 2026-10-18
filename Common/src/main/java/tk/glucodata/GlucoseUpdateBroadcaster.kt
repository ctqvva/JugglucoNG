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

    // Outside the throttle both ways: a broadcast in the second before expiry (startup, a
    // settings change) must not swallow the one redraw that retires the number, and that
    // redraw must not hold back a fresh reading arriving right after it.
    private val freshnessDeadline = AlarmManager.OnAlarmListener {
        synchronized(lock) { pendingDeadlineMillis = 0L }
        deliver(null, throttled = false)
    }
    private var pendingDeadlineMillis = 0L

    /**
     * Redraws the lock-screen overlay and widgets once [readingTimeMillis] passes
     * [Notify.glucosetimeout], so they swap the number for "No new value since …" whether
     * or not the glucose notification is enabled (its own deadline only runs when it is).
     * Non-wakeup: fires on time while the device is awake, otherwise on the next wake.
     *
     * Called for every live reading and by each surface that draws a fresh number, so a
     * reading restored after a process restart (which drops the in-process alarm) still
     * expires. One alarm serves them all at the earliest deadline: readings differ per
     * sensor (switching the primary can draw an older one), and the redraw it triggers
     * re-arms whatever each surface still shows, so a too-early deadline costs one redraw.
     */
    @JvmStatic
    fun armFreshnessDeadline(readingTimeMillis: Long) {
        if (Applic.isWearable || readingTimeMillis <= 0L) return
        val alarmManager = Applic.app?.getSystemService(AlarmManager::class.java) ?: return
        // DisplayDataState counts the exact threshold as fresh; go one ms past it.
        val deadline = readingTimeMillis + Notify.glucosetimeout + 1L
        synchronized(lock) {
            if (pendingDeadlineMillis in 1..deadline) return
            try {
                alarmManager.cancel(freshnessDeadline)
                alarmManager.setExact(
                    AlarmManager.RTC,
                    deadline,
                    "tk.glucodata:glucose-freshness",
                    freshnessDeadline,
                    null
                )
                pendingDeadlineMillis = deadline
            } catch (th: Throwable) {
                Log.stack(LOG_ID, "armFreshnessDeadline", th)
            }
        }
    }

    @JvmStatic
    fun hasActiveTickObservers(): Boolean {
        return tickState.subscriptionCount.value > 0
    }

    @JvmStatic
    @JvmOverloads
    fun send(context: Context? = null) {
        deliver(context, throttled = true)
    }

    private fun deliver(context: Context?, throttled: Boolean) {
        val appContext = (context?.applicationContext ?: Applic.app) ?: return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (throttled) {
                if (lastBroadcastElapsedRealtime > 0L &&
                    now - lastBroadcastElapsedRealtime < MIN_BROADCAST_INTERVAL_MS
                ) {
                    return
                }
                lastBroadcastElapsedRealtime = now
            }
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
