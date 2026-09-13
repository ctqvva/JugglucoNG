package tk.glucodata.alerts

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.AlertDeliveryPolicy
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.MainActivity
import tk.glucodata.Notify
import tk.glucodata.R

/**
 * The quiet window: a temporary, visible, self-expiring reduction of alarm output
 * for the cinema, the lecture, the funeral. It is the alternative to the global
 * alarm toggle, which is unbounded, all-or-nothing and invisible once forgotten.
 *
 * Only sound and vibration are touched, and only in [tk.glucodata.Notify]'s output
 * path through [AlertDeliveryPolicy]; evaluation, thresholds, retries and episodes
 * run as if there were no window. The rules that keep it safe:
 *
 *  - it always ends: every start is capped at [MAX_DURATION_MS] from now, and the
 *    end time is checked against the wall clock on every read, so a process kill
 *    or a missed alarm cannot stretch it;
 *  - a silenced alarm that stays active, unacknowledged, for the breakthrough
 *    time sounds anyway — every kind, or the very high and very low only;
 *  - it is visible: an ongoing notification with the end time and an "end now"
 *    action, a chip on the dashboard, and a quick-settings tile.
 */
object QuietWindow {
    private const val LOG_ID = "QuietWindow"
    private const val PREFS_NAME = "tk.glucodata.alerts"
    private const val KEY_UNTIL = "quiet_window_until_ms"
    private const val KEY_MODE = "quiet_window_mode"
    private const val KEY_DEFAULT_MINUTES = "quiet_window_default_minutes"
    private const val KEY_BREAKTHROUGH_MINUTES = "quiet_window_breakthrough_minutes"
    private const val KEY_BREAKTHROUGH_SCOPE = "quiet_window_breakthrough_scope"

    const val ACTION_EXPIRED = "tk.glucodata.ACTION_QUIET_WINDOW_EXPIRED"
    const val ACTION_END = "tk.glucodata.ACTION_QUIET_WINDOW_END"

    /** "Until I end it" is this long, no longer: there is no unbounded state. */
    const val MAX_DURATION_MS = 24L * 60L * 60L * 1000L
    const val DEFAULT_MINUTES = 60
    const val DEFAULT_BREAKTHROUGH_MINUTES = 10
    const val MIN_BREAKTHROUGH_MINUTES = 5
    const val MAX_BREAKTHROUGH_MINUTES = 30
    val PRESET_MINUTES = listOf(30, 60, 120, 180)

    /**
     * A silenced delivery more than this long after the previous silenced delivery
     * of the same kind starts a new silenced episode: the old one was not "still
     * active", whatever cleared it.
     */
    const val SILENCED_EPISODE_STALE_MS = 30L * 60L * 1000L

    /**
     * Custom alerts deliver as kind 0/1 but are not LOW/HIGH's episode: their silenced
     * episodes are keyed from here up. No AlertStateTracker episode backs them, so they
     * break through on their own redeliveries only, never on the scheduled check.
     */
    const val CUSTOM_EPISODE_KIND_BASE = 1000

    private const val CHANNEL_ID = "QUIET_WINDOW"
    private const val NOTIFICATION_ID_ACTIVE = 0x5150
    private const val NOTIFICATION_ID_ENDED = 0x5151
    private const val REQUEST_EXPIRY = 0x5150
    private const val REQUEST_END = 0x5151
    private const val REQUEST_OPEN = 0x5152
    private const val TILE_SERVICE_CLASS = "tk.glucodata.ui.QuietWindowTileService"

    data class State(val untilMs: Long, val mode: String) {
        val active: Boolean get() = untilMs > 0L
    }

    private val prefs by lazy { Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val _state by lazy { MutableStateFlow(readState(System.currentTimeMillis())) }
    val state: StateFlow<State> get() = _state.asStateFlow()

    private val silencedEpisodes = SilencedEpisodeTracker(SILENCED_EPISODE_STALE_MS)
    private val breakthroughSchedules = HashMap<Int, ScheduledFuture<*>>()
    private val scheduler by lazy { java.util.concurrent.Executors.newSingleThreadScheduledExecutor() }

    // ---- pure helpers (unit-tested) -------------------------------------------

    /** The stored end time if it is still ahead of the clock, else 0 (over). */
    @JvmStatic
    fun effectiveUntil(storedUntilMs: Long, nowMs: Long): Long =
        if (storedUntilMs > nowMs) storedUntilMs else 0L

    /** A requested end time, never later than [MAX_DURATION_MS] from now and never in the past. */
    @JvmStatic
    fun cappedUntil(requestedUntilMs: Long, nowMs: Long): Long =
        requestedUntilMs.coerceIn(nowMs, nowMs + MAX_DURATION_MS)

    @JvmStatic
    fun parseMode(raw: String?): String = AlertDeliveryPolicy.normalizeQuietMode(raw)

    @JvmStatic
    fun sanitizeBreakthroughMinutes(minutes: Int): Int =
        if (minutes in MIN_BREAKTHROUGH_MINUTES..MAX_BREAKTHROUGH_MINUTES) minutes else DEFAULT_BREAKTHROUGH_MINUTES

    @JvmStatic
    fun sanitizeDefaultMinutes(minutes: Int): Int =
        if (minutes in 1..(MAX_DURATION_MS / 60_000L).toInt()) minutes else DEFAULT_MINUTES

    /**
     * The end time a picked wall-clock time of day means: today if still ahead,
     * otherwise tomorrow; capped like every other start.
     */
    @JvmStatic
    fun untilForTimeOfDay(hour: Int, minute: Int, nowMs: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= nowMs) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return cappedUntil(cal.timeInMillis, nowMs)
    }

    // ---- state ----------------------------------------------------------------

    /**
     * The end of the running window, or 0. An end time the clock has passed is
     * ended right here (notification, tile, confirmation), so no reader ever acts
     * on a window that is over.
     */
    @JvmStatic
    fun untilMs(nowMs: Long): Long {
        val stored = prefs.getLong(KEY_UNTIL, 0L)
        if (stored == 0L) return 0L
        val until = effectiveUntil(stored, nowMs)
        if (until == 0L) {
            endInternal(Applic.app, expired = true)
        }
        return until
    }

    @JvmStatic
    fun isActive(nowMs: Long): Boolean = untilMs(nowMs) > 0L

    @JvmStatic
    fun mode(): String = parseMode(prefs.getString(KEY_MODE, null))

    @JvmStatic
    fun breakthroughMinutes(): Int =
        sanitizeBreakthroughMinutes(prefs.getInt(KEY_BREAKTHROUGH_MINUTES, DEFAULT_BREAKTHROUGH_MINUTES))

    @JvmStatic
    fun breakthroughMillis(): Long = TimeUnit.MINUTES.toMillis(breakthroughMinutes().toLong())

    fun defaultMinutes(): Int = sanitizeDefaultMinutes(prefs.getInt(KEY_DEFAULT_MINUTES, DEFAULT_MINUTES))

    /** Which silenced alarms may break through: all, or very high only. */
    @JvmStatic
    fun breakthroughScope(): String =
        AlertDeliveryPolicy.normalizeBreakthroughScope(prefs.getString(KEY_BREAKTHROUGH_SCOPE, null))

    fun setBreakthroughScope(scope: String) {
        prefs.edit().putString(KEY_BREAKTHROUGH_SCOPE, AlertDeliveryPolicy.normalizeBreakthroughScope(scope)).apply()
    }

    fun setBreakthroughMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_BREAKTHROUGH_MINUTES, sanitizeBreakthroughMinutes(minutes)).apply()
    }

    fun setDefaultMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_DEFAULT_MINUTES, sanitizeDefaultMinutes(minutes)).apply()
    }

    /** Changes the mode; a running window keeps its end time and shows the new mode. */
    fun setMode(context: Context, mode: String) {
        val normalized = parseMode(mode)
        prefs.edit().putString(KEY_MODE, normalized).apply()
        val now = System.currentTimeMillis()
        val until = untilMs(now)
        _state.value = State(until, normalized)
        if (until > 0L) {
            showActiveNotification(context, until, normalized)
            requestTileRefresh(context)
        }
    }

    fun startFor(context: Context, durationMs: Long, mode: String = mode()) {
        val now = System.currentTimeMillis()
        startUntil(context, now + durationMs, mode, now)
    }

    /** What one tap on the quick-settings tile starts: the default duration, the stored mode. */
    fun startDefault(context: Context) {
        startFor(context, TimeUnit.MINUTES.toMillis(defaultMinutes().toLong()))
    }

    fun startUntil(context: Context, untilMs: Long, mode: String = mode(), nowMs: Long = System.currentTimeMillis()) {
        val until = cappedUntil(untilMs, nowMs)
        val normalized = parseMode(mode)
        prefs.edit().putLong(KEY_UNTIL, until).putString(KEY_MODE, normalized).apply()
        _state.value = State(until, normalized)
        // A new window starts the silenced-episode clock afresh.
        clearAllSilencedEpisodes()
        scheduleExpiry(context, until)
        showActiveNotification(context, until, normalized)
        requestTileRefresh(context)
        Log.i(LOG_ID, "Quiet window until $until mode=$normalized")
    }

    fun end(context: Context) {
        endInternal(context, expired = false)
    }

    /** App start: an expired window ends, a running one re-arms its alarm and notification. */
    @JvmStatic
    fun syncOnStart(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val until = untilMs(now)
            if (until > 0L) {
                scheduleExpiry(context, until)
                showActiveNotification(context, until, mode())
                requestTileRefresh(context)
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "syncOnStart", t)
        }
    }

    // ---- silenced episodes (the breakthrough rule) ----------------------------

    /**
     * Called for every delivery the window silences. Returns when the silenced
     * episode of this kind began, and on its first delivery arms a check at the
     * breakthrough time: if the alarm is still active then, it is delivered
     * audibly. Retries reach the same rule on their own through Notify.
     */
    @JvmStatic
    fun noteSilencedDelivery(kind: Int, nowMs: Long): Long {
        val since = silencedEpisodes.note(kind, nowMs)
        if (since == nowMs && kind < CUSTOM_EPISODE_KIND_BASE &&
            AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(kind, breakthroughScope())
        ) {
            scheduleBreakthroughCheck(kind, breakthroughMillis())
        }
        return since
    }

    @JvmStatic
    fun customEpisodeKind(kind: Int): Int = CUSTOM_EPISODE_KIND_BASE + kind

    @JvmStatic
    fun clearSilencedEpisode(kind: Int) {
        silencedEpisodes.clear(kind)
        synchronized(breakthroughSchedules) {
            breakthroughSchedules.remove(kind)?.cancel(false)
        }
    }

    private fun clearAllSilencedEpisodes() {
        silencedEpisodes.clearAll()
        synchronized(breakthroughSchedules) {
            breakthroughSchedules.values.forEach { it.cancel(false) }
            breakthroughSchedules.clear()
        }
    }

    private fun scheduleBreakthroughCheck(kind: Int, delayMs: Long) {
        try {
            synchronized(breakthroughSchedules) {
                breakthroughSchedules.remove(kind)?.cancel(false)
                breakthroughSchedules[kind] = scheduler.schedule(
                    { breakThroughIfStillActive(kind) },
                    delayMs,
                    TimeUnit.MILLISECONDS
                )
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "scheduleBreakthroughCheck", t)
        }
    }

    private fun breakThroughIfStillActive(kind: Int) {
        synchronized(breakthroughSchedules) { breakthroughSchedules.remove(kind) }
        val now = System.currentTimeMillis()
        if (untilMs(now) == 0L) return
        if (!silencedEpisodes.has(kind)) return
        // The scope may have been narrowed since the check was armed.
        if (!AlertDeliveryPolicy.quietWindowBreakthroughAppliesTo(kind, breakthroughScope())) return
        val type = AlertType.fromId(kind) ?: return
        if (!AlertStateTracker.isEpisodeActive(type)) {
            // Cleared without resetState reaching us: the staleness rule retires the
            // record on its own.
            return
        }
        if (SnoozeManager.isSnoozed(type) || AlertStateTracker.isDismissed(type)) {
            // Acknowledged: without a window this alarm would stay quiet now, and the
            // window must never make anything louder than that.
            silencedEpisodes.clear(kind)
            return
        }
        // The redelivery must land in this episode, however long the check took to
        // come round, so the staleness rule does not start a fresh one under it.
        silencedEpisodes.refresh(kind, now)
        Log.i(LOG_ID, "Silenced ${type.name} still active after ${breakthroughMinutes()} min: breaking through")
        Notify.breakThroughQuietWindow(kind)
    }

    // ---- android plumbing ------------------------------------------------------

    private fun readState(nowMs: Long): State =
        State(effectiveUntil(prefs.getLong(KEY_UNTIL, 0L), nowMs), mode())

    private fun endInternal(context: Context, expired: Boolean) {
        prefs.edit().remove(KEY_UNTIL).apply()
        _state.value = State(0L, mode())
        clearAllSilencedEpisodes()
        try {
            cancelExpiry(context)
            cancelActiveNotification(context)
            if (expired) {
                showEndedNotification(context)
            }
            requestTileRefresh(context)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "endInternal", t)
        }
        Log.i(LOG_ID, if (expired) "Quiet window expired" else "Quiet window ended")
    }

    private fun expiryPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_EXPIRY,
            Intent(ACTION_EXPIRED).setClass(context, QuietWindowReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun scheduleExpiry(context: Context, untilMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = expiryPendingIntent(context)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, untilMs, pendingIntent)
        } catch (e: SecurityException) {
            // Exact alarms denied: an inexact one still ends the window, the clock
            // check on every read ends it on time for anyone who asks.
            Log.stack(LOG_ID, "exact alarm denied, falling back", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, untilMs, pendingIntent)
        }
    }

    private fun cancelExpiry(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(expiryPendingIntent(context))
    }

    private fun formatTime(context: Context, untilMs: Long): String =
        DateFormat.getTimeFormat(context).format(Date(untilMs))

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationManager(context: Context): NotificationManager? {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        createChannel(context, manager)
        return manager
    }

    private fun showActiveNotification(context: Context, untilMs: Long, mode: String) {
        if (Applic.isWearable) return
        val manager = notificationManager(context) ?: return
        if (!canPost(context)) return
        val endIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_END,
            Intent(ACTION_END).setClass(context, QuietWindowReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val modeText = context.getString(
            if (mode == AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY) R.string.quiet_window_mode_notification_only_desc
            else R.string.quiet_window_mode_vibrate_only_desc
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(context.getString(R.string.quiet_window_active_until, formatTime(context, untilMs)))
            .setContentText(modeText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(modeText))
            .setContentIntent(openIntent)
            .addAction(0, context.getString(R.string.quiet_window_end_now), endIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        manager.notify(NOTIFICATION_ID_ACTIVE, notification)
    }

    private fun cancelActiveNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID_ACTIVE)
    }

    private fun showEndedNotification(context: Context) {
        if (Applic.isWearable) return
        val manager = notificationManager(context) ?: return
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(context.getString(R.string.quiet_window_ended_title))
            .setContentText(context.getString(R.string.quiet_window_ended_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TimeUnit.MINUTES.toMillis(10))
            .build()
        manager.notify(NOTIFICATION_ID_ENDED, notification)
    }

    private fun createChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.quiet_window_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }

    /** The tile lives in the mobile source set; it is addressed by name and may be absent. */
    private fun requestTileRefresh(context: Context) {
        if (Applic.isWearable) return
        try {
            TileService.requestListeningState(context, ComponentName(context, TILE_SERVICE_CLASS))
        } catch (t: Throwable) {
            if (Log.doLog) Log.i(LOG_ID, "tile refresh skipped: $t")
        }
    }
}

/**
 * Which alert kinds are in a silenced episode, and since when. Pure, so the
 * staleness rule can be tested: a delivery [staleMs] or more after the previous
 * silenced delivery of the same kind starts a new episode.
 */
internal class SilencedEpisodeTracker(private val staleMs: Long) {
    private class Episode(val sinceMs: Long, var lastMs: Long)

    private val episodes = HashMap<Int, Episode>()

    /** Records a silenced delivery and returns the episode's start. */
    @Synchronized
    fun note(kind: Int, nowMs: Long): Long {
        val current = episodes[kind]
        if (current != null && nowMs - current.lastMs < staleMs) {
            current.lastMs = nowMs
            return current.sinceMs
        }
        episodes[kind] = Episode(nowMs, nowMs)
        return nowMs
    }

    @Synchronized
    fun has(kind: Int): Boolean = episodes.containsKey(kind)

    /** Marks the episode as just seen, so the next delivery continues it whatever the gap. */
    @Synchronized
    fun refresh(kind: Int, nowMs: Long) {
        episodes[kind]?.lastMs = nowMs
    }

    @Synchronized
    fun clear(kind: Int) {
        episodes.remove(kind)
    }

    @Synchronized
    fun clearAll() {
        episodes.clear()
    }
}
