package tk.glucodata;

import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Periodic glucose updates for watches and bands whose companion app forwards phone
 * notifications (Mi Fitness, Haylou Fun, ...). Phone only.
 *
 * <p>No notification of its own: the ongoing notification carries standard title/text
 * only on the post that announces a reading, and none on every other post. Listener
 * apps forward a post with text and skip one without, so the watch gets one message per
 * interval while the phone shows nothing new (its custom views ignore title/text).
 *
 * <p>Driven by readings, never by a timer: {@link PeriodicGlucoseNotificationPolicy}
 * decides per rendered reading, so a 5-minute sensor never yields more than its own
 * readings and a disconnected sensor yields nothing.
 */
public final class PeriodicGlucoseUpdates {
    private PeriodicGlucoseUpdates() {}

    private static final String LOG_ID = "PeriodicGlucose";
    /** An alarm notification posted this recently for the same measurement is its delivery. */
    private static final long ALERT_RECENT_MS = 2 * 60_000L;

    private static final String STATE_PREFS = "periodic_glucose_notification_state";
    private static final String KEY_READING = "reading_ms";
    private static final String KEY_WALL = "sent_wall_ms";
    private static final String KEY_ELAPSED = "sent_elapsed_ms";

    /** Readings measured before this process started are cached, not new. */
    private static long baselineReadingMillis;
    /** Set by switching the feature on; cleared by the post it causes. */
    private static boolean sendCurrentOnce;

    private static volatile long alertReadingMillis;
    private static volatile long alertPostedElapsedMillis;

    static synchronized void onProcessStart() {
        baselineReadingMillis = System.currentTimeMillis();
    }

    /**
     * The switch changed. On: the next post carries the current reading once, if fresh,
     * so the user sees the feature work, even if it went out before the switch was
     * turned off. Call before refreshing the notification.
     */
    public static synchronized void onSettingChanged(boolean enabled) {
        sendCurrentOnce = enabled;
    }

    /** Called by the alarm path after it posted an alarm notification for a reading. */
    static void noteAlertNotificationPosted(long readingMillis) {
        alertReadingMillis = readingMillis;
        alertPostedElapsedMillis = SystemClock.elapsedRealtime();
    }

    /**
     * Whether this post of the ongoing notification announces the reading, i.e. carries
     * standard title/text. Records the announcement, so later posts of the same reading
     * (follow-up repaints, chart changes) stay without text.
     */
    static synchronized boolean claim(long readingMillis, float value) {
        if (isWearable) {
            return false;
        }
        final Context context = Applic.app;
        final SharedPreferences prefs = context.getSharedPreferences(
                NotificationForwardingSettings.PREFS, Context.MODE_PRIVATE);
        if (!NotificationForwardingSettings.isPeriodicEnabled(prefs)) {
            return false;
        }
        // Same validity floor as the ongoing notification.
        if (!Float.isFinite(value) || value < 2.0f) {
            readingMillis = 0L;
        }
        final long nowWall = System.currentTimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final SharedPreferences state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        final PeriodicGlucoseNotificationPolicy.Sent last = new PeriodicGlucoseNotificationPolicy.Sent(
                state.getLong(KEY_READING, 0L), state.getLong(KEY_WALL, 0L), state.getLong(KEY_ELAPSED, 0L));
        final boolean forced = sendCurrentOnce;
        final PeriodicGlucoseNotificationPolicy.Decision decision = PeriodicGlucoseNotificationPolicy.decide(
                true, readingMillis, baselineReadingMillis, last, nowWall, nowElapsed,
                NotificationForwardingSettings.periodicIntervalMinutes(prefs), Notify.glucosetimeout, forced);
        if (decision != PeriodicGlucoseNotificationPolicy.Decision.SEND) {
            return false;
        }
        sendCurrentOnce = false;
        state.edit().putLong(KEY_READING, readingMillis).putLong(KEY_WALL, nowWall)
                .putLong(KEY_ELAPSED, nowElapsed).apply();
        if (!forced && alarmAlreadyCarried(readingMillis, nowElapsed)) {
            // The alarm notification just carried this reading to the watch. Count it as
            // this interval's message instead of sending the same value twice.
            if (doLog) {
                Log.i(LOG_ID, "reading delivered by an alarm notification");
            }
            return false;
        }
        if (doLog) {
            Log.i(LOG_ID, "announce reading=" + readingMillis);
        }
        return true;
    }

    private static boolean alarmAlreadyCarried(long readingMillis, long nowElapsed) {
        final long posted = alertPostedElapsedMillis;
        return posted > 0L && nowElapsed - posted <= ALERT_RECENT_MS
                && PeriodicGlucoseNotificationPolicy.sameMeasurement(alertReadingMillis, readingMillis);
    }
}
