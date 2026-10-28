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
    /**
     * A due reading waits this long before it is announced. The ongoing notification is
     * posted before the alert checks of the same reading finish (custom alerts run after
     * it), and an announcement already forwarded cannot be taken back.
     */
    static final long SETTLE_MS = 2_000L;

    /**
     * How long a render may hold an announcement before posting it. Only a render that
     * threw holds it this long; it is then given up.
     */
    static final long PENDING_TIMEOUT_MS = 5_000L;
    /** A render that finds another one posting an announcement looks again after this. */
    static final long PENDING_RECHECK_MS = 250L;

    static final int NOT_DUE = 0;
    /** Due, but still settling: render again after {@link #settleRemainingMillis()}. */
    static final int LATER = 1;
    static final int ANNOUNCE = 2;

    private static final String STATE_PREFS = "periodic_glucose_notification_state";
    private static final String KEY_READING = "reading_ms";
    private static final String KEY_WALL = "sent_wall_ms";
    private static final String KEY_ELAPSED = "sent_elapsed_ms";

    /** Readings measured before this process started are cached, not new. */
    private static long baselineReadingMillis;
    /** Set by switching the feature on; cleared by the post it causes. */
    private static boolean sendCurrentOnce;
    /** The due reading that is settling, and since when. */
    private static long settlingReadingMillis;
    private static long settlingSinceElapsed;
    /**
     * The announcement handed to a render and not yet posted: recorded by
     * {@link #commitAnnouncement} once it is out, or given back by
     * {@link #releaseAnnouncement} when the render is discarded.
     */
    private static long pendingReadingMillis;
    private static long pendingSinceElapsed;
    private static long pendingSettlingSince;
    private static boolean pendingForced;

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
     * standard title/text: {@link #NOT_DUE}, {@link #LATER} or {@link #ANNOUNCE}. The
     * caller reports what became of an announcement: {@link #commitAnnouncement} once it
     * is posted, so later posts of the same reading (follow-up repaints, chart changes)
     * stay without text, or {@link #releaseAnnouncement} if it never was.
     */
    static synchronized int claim(long readingMillis, float value) {
        if (isWearable) {
            return NOT_DUE;
        }
        final Context context = Applic.app;
        final SharedPreferences prefs = context.getSharedPreferences(
                NotificationForwardingSettings.PREFS, Context.MODE_PRIVATE);
        if (!NotificationForwardingSettings.isPeriodicEnabled(prefs)) {
            return NOT_DUE;
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
            return NOT_DUE;
        }
        if (pendingReadingMillis != 0L) {
            if (nowElapsed - pendingSinceElapsed < PENDING_TIMEOUT_MS) {
                // Another render is posting an announcement; whether it gets out decides
                // what this reading still needs.
                return LATER;
            }
            // The render that held it threw.
            releaseAnnouncement(pendingReadingMillis);
        }
        if (settlingReadingMillis != readingMillis) {
            settlingReadingMillis = readingMillis;
            settlingSinceElapsed = nowElapsed;
        }
        // Switching the feature on is not tied to a new reading's alerts.
        if (!forced && nowElapsed - settlingSinceElapsed < SETTLE_MS) {
            return LATER;
        }
        final long settlingSince = settlingSinceElapsed;
        settlingReadingMillis = 0L;
        if (!forced && alarmAlreadyCarried(readingMillis, settlingSince, nowElapsed)) {
            // An alarm notification just carried this reading to the watch. Count it as
            // this interval's message instead of sending the same value twice.
            if (doLog) {
                Log.i(LOG_ID, "reading delivered by an alarm notification");
            }
            record(state, readingMillis, nowWall, nowElapsed);
            return NOT_DUE;
        }
        pendingReadingMillis = readingMillis;
        pendingSinceElapsed = nowElapsed;
        pendingSettlingSince = settlingSince;
        pendingForced = forced;
        if (doLog) {
            Log.i(LOG_ID, "announce reading=" + readingMillis);
        }
        return ANNOUNCE;
    }

    /** The announcement for {@code readingMillis} was posted (or is already shown). */
    static synchronized void commitAnnouncement(long readingMillis) {
        if (readingMillis == 0L || readingMillis != pendingReadingMillis) {
            return;
        }
        pendingReadingMillis = 0L;
        if (pendingForced) {
            sendCurrentOnce = false;
        }
        record(Applic.app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE), readingMillis,
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    /**
     * The render carrying the announcement for {@code readingMillis} was discarded or
     * failed to post: the next render announces the reading instead, without settling
     * again.
     */
    static synchronized void releaseAnnouncement(long readingMillis) {
        if (readingMillis == 0L || readingMillis != pendingReadingMillis) {
            return;
        }
        pendingReadingMillis = 0L;
        settlingReadingMillis = readingMillis;
        settlingSinceElapsed = pendingSettlingSince;
        if (doLog) {
            Log.i(LOG_ID, "announcement not posted reading=" + readingMillis);
        }
    }

    private static void record(SharedPreferences state, long readingMillis, long nowWall, long nowElapsed) {
        state.edit().putLong(KEY_READING, readingMillis).putLong(KEY_WALL, nowWall)
                .putLong(KEY_ELAPSED, nowElapsed).apply();
    }

    /** How long the settling reading still waits; 0 when none is settling. */
    static synchronized long settleRemainingMillis() {
        if (pendingReadingMillis != 0L) {
            return PENDING_RECHECK_MS;
        }
        if (settlingReadingMillis == 0L) {
            return 0L;
        }
        return Math.max(0L, SETTLE_MS - (SystemClock.elapsedRealtime() - settlingSinceElapsed));
    }

    /**
     * Native alarms are posted before the ongoing notification and carry the reading's
     * time. Custom alerts are posted after it and carry their own time, so any alarm
     * notification posted while the reading settled was for this reading.
     */
    private static boolean alarmAlreadyCarried(long readingMillis, long settlingSince, long nowElapsed) {
        final long posted = alertPostedElapsedMillis;
        return posted > 0L && nowElapsed - posted <= ALERT_RECENT_MS
                && (posted >= settlingSince
                        || PeriodicGlucoseNotificationPolicy.sameMeasurement(alertReadingMillis, readingMillis));
    }
}
