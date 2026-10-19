package tk.glucodata;

import static tk.glucodata.Applic.isWearable;
import static tk.glucodata.Log.doLog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

/**
 * Separate, ordinary glucose notifications at a chosen interval, for watches and bands
 * whose companion app forwards phone notifications (Mi Fitness, Haylou Fun, ...). Phone
 * only. It never touches the ongoing foreground notification or any alarm.
 *
 * <p>Driven by readings: every data change asks for a check, the check resolves the
 * same current snapshot the ongoing notification shows (primary sensor, display mode,
 * calibration) and {@link PeriodicGlucoseNotificationPolicy} decides. There is no timer
 * that re-sends a value, so a 5-minute sensor never yields more than its own readings
 * and a disconnected sensor yields nothing. One stable ID: the shade holds at most one,
 * and it times out when its reading stops being fresh.
 */
public final class PeriodicGlucoseNotifier {
    private PeriodicGlucoseNotifier() {}

    private static final String LOG_ID = "PeriodicGlucose";
    static final String CHANNEL_ID = "glucosePeriodicUpdates";
    static final int NOTIFICATION_ID = 81434;
    private static final int CONTENT_REQUEST_CODE = 81434;
    /** Lets the reading's Room write land (delta) and coalesces callbacks of one reading. */
    private static final long CHECK_DELAY_MS = 2_000L;
    /** An alarm notification posted this recently for the same measurement is its delivery. */
    private static final long ALERT_RECENT_MS = 2 * 60_000L;

    private static final String STATE_PREFS = "periodic_glucose_notification_state";
    private static final String KEY_READING = "reading_ms";
    private static final String KEY_WALL = "sent_wall_ms";
    private static final String KEY_ELAPSED = "sent_elapsed_ms";

    private static final Handler handler = makeHandler();
    private static final Object lock = new Object();
    private static boolean checkPending;
    /** Reading shown when this process started: cached, not new. Handler thread only. */
    private static long baselineReadingMillis;
    private static long shownReadingMillis;
    /** Set by switching the feature on; cleared by the post it causes. Handler thread only. */
    private static boolean sendCurrentOnce;

    private static volatile long alertReadingMillis;
    private static volatile long alertPostedElapsedMillis;

    private static Handler makeHandler() {
        final HandlerThread thread = new HandlerThread("JugglucoPeriodicGlucose", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        return new Handler(thread.getLooper());
    }

    private static final Runnable check = () -> {
        synchronized (lock) {
            checkPending = false;
        }
        try {
            runCheck();
        } catch (Throwable th) {
            Log.stack(LOG_ID, "check", th);
        }
    };

    static void createChannel(Context context, NotificationManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isWearable) {
            return;
        }
        // Default importance without sound or vibration: quiet on the phone, but not a
        // "silent" notification, which some companion apps do not forward. Users can
        // change it in the system channel settings.
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.periodic_glucose_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.periodic_glucose_channel_desc));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    /** Process start: the reading already stored is not a new measurement. */
    static void onProcessStart() {
        if (isWearable) {
            return;
        }
        handler.post(() -> {
            try {
                if (!NotificationForwardingSettings.isPeriodicEnabled(Applic.app)) {
                    return;
                }
                final CurrentDisplaySource.Snapshot current = resolveCurrent();
                baselineReadingMillis = current != null ? current.getTimeMillis() : 0L;
            } catch (Throwable th) {
                Log.stack(LOG_ID, "onProcessStart", th);
            }
        });
    }

    /** Any data change. Cheap when the feature is off; otherwise one coalesced check. */
    public static void requestCheck() {
        if (isWearable || Applic.app == null) {
            return;
        }
        try {
            if (!NotificationForwardingSettings.isPeriodicEnabled(Applic.app)) {
                return;
            }
        } catch (Throwable th) {
            return;
        }
        synchronized (lock) {
            if (checkPending) {
                return;
            }
            checkPending = true;
            handler.postDelayed(check, CHECK_DELAY_MS);
        }
    }

    /**
     * The switch changed. On: the current reading goes out once straight away, if fresh,
     * so the user sees the feature work, even if it was sent before the switch was turned
     * off. Off: the outstanding notification and any queued check go.
     */
    public static void onSettingChanged(boolean enabled) {
        if (isWearable) {
            return;
        }
        synchronized (lock) {
            handler.removeCallbacks(check);
            checkPending = false;
        }
        handler.post(() -> {
            sendCurrentOnce = enabled;
            if (enabled) {
                runCheckSafely();
            } else {
                cancel();
            }
        });
    }

    /** Called by the alarm path after it posted an alarm notification for a reading. */
    static void noteAlertNotificationPosted(long readingMillis) {
        alertReadingMillis = readingMillis;
        alertPostedElapsedMillis = SystemClock.elapsedRealtime();
    }

    private static void runCheckSafely() {
        try {
            runCheck();
        } catch (Throwable th) {
            Log.stack(LOG_ID, "check", th);
        }
    }

    private static void runCheck() {
        final Context context = Applic.app;
        final SharedPreferences prefs = context.getSharedPreferences(
                NotificationForwardingSettings.PREFS, Context.MODE_PRIVATE);
        final boolean enabled = NotificationForwardingSettings.isPeriodicEnabled(prefs);
        if (!enabled) {
            cancel();
            return;
        }
        final long nowWall = System.currentTimeMillis();
        final long nowElapsed = SystemClock.elapsedRealtime();
        if (shownReadingMillis != 0L
                && !NotificationRefreshPolicy.isFresh(shownReadingMillis, nowWall, Notify.glucosetimeout)) {
            // Its timeout normally removed it already; never leave an old reading up.
            cancel();
        }
        final CurrentDisplaySource.Snapshot current = resolveCurrent();
        final long readingMillis = isUsable(current) ? current.getTimeMillis() : 0L;
        final SharedPreferences state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        final PeriodicGlucoseNotificationPolicy.Sent last = new PeriodicGlucoseNotificationPolicy.Sent(
                state.getLong(KEY_READING, 0L), state.getLong(KEY_WALL, 0L), state.getLong(KEY_ELAPSED, 0L));
        final boolean forced = sendCurrentOnce;
        final PeriodicGlucoseNotificationPolicy.Decision decision = PeriodicGlucoseNotificationPolicy.decide(
                true, readingMillis, baselineReadingMillis, last, nowWall, nowElapsed,
                NotificationForwardingSettings.periodicIntervalMinutes(prefs), Notify.glucosetimeout, forced);
        if (doLog) {
            Log.i(LOG_ID, "check reading=" + readingMillis + " decision=" + decision);
        }
        if (decision != PeriodicGlucoseNotificationPolicy.Decision.SEND) {
            return;
        }
        sendCurrentOnce = false;
        if (!forced && alarmAlreadyCarried(readingMillis, nowElapsed)) {
            // The alarm notification just carried this reading to the watch. Count it as
            // this interval's message instead of sending the same value twice.
            if (doLog) {
                Log.i(LOG_ID, "reading delivered by an alarm notification");
            }
        } else {
            publish(context, current, nowWall);
        }
        state.edit().putLong(KEY_READING, readingMillis).putLong(KEY_WALL, nowWall)
                .putLong(KEY_ELAPSED, nowElapsed).apply();
    }

    private static boolean alarmAlreadyCarried(long readingMillis, long nowElapsed) {
        final long posted = alertPostedElapsedMillis;
        return posted > 0L && nowElapsed - posted <= ALERT_RECENT_MS
                && PeriodicGlucoseNotificationPolicy.sameMeasurement(alertReadingMillis, readingMillis);
    }

    private static CurrentDisplaySource.Snapshot resolveCurrent() {
        final String serial = Notify.resolveNotificationSensorSerial();
        if (serial == null || serial.isEmpty()) {
            return null;
        }
        final CurrentDisplaySource.Snapshot snapshot = CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout, serial);
        // Only the selected primary sensor, in the unit the user reads now.
        if (snapshot == null || !serial.equals(snapshot.getSensorId()) || snapshot.isMmol() != (Applic.unit == 1)) {
            return null;
        }
        return snapshot;
    }

    private static boolean isUsable(CurrentDisplaySource.Snapshot snapshot) {
        // Same validity floor as the ongoing notification.
        return snapshot != null && snapshot.getTimeMillis() > 0L
                && Float.isFinite(snapshot.getPrimaryValue()) && snapshot.getPrimaryValue() >= 2.0f;
    }

    private static void publish(Context context, CurrentDisplaySource.Snapshot snapshot, long nowWall) {
        final Notify notify = Notify.onenot;
        final NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notify == null || manager == null) {
            return;
        }
        final boolean isMmol = snapshot.isMmol();
        final String serial = snapshot.getSensorId();
        final int viewMode = snapshot.getViewMode();
        float rate = snapshot.getRate();
        String delta = "";
        try {
            // The same canonical trend points and arrow-rate rule as the ongoing
            // notification and the dashboard hero.
            java.util.List<GlucosePoint> points = NotificationHistorySource.getDisplayHistory(
                    nowWall - 2 * DisplayTrendSource.TREND_WINDOW_MS, isMmol, serial);
            points = DisplayTrendSource.resolveTrendPoints(points, snapshot, serial);
            rate = DisplayTrendSource.resolveArrowRate(points, snapshot, viewMode, isMmol, rate);
            final int deltaMinutes = context.getSharedPreferences(NotificationForwardingSettings.PREFS,
                    Context.MODE_PRIVATE).getInt("delta_interval_minutes", GlucoseDelta.DEFAULT_INTERVAL_MINUTES);
            delta = Notify.notificationDeltaText(points, viewMode == 1 || viewMode == 3, isMmol, deltaMinutes);
        } catch (Throwable th) {
            Log.stack(LOG_ID, "trend", th);
        }
        final long readingMillis = snapshot.getTimeMillis();
        final String title = GlucoseNotificationText.title(snapshot.getFullFormatted(), null,
                context.getString(isMmol ? R.string.mmolL : R.string.mgdL), rate);
        final String time = android.text.format.DateFormat.getTimeFormat(context)
                .format(new java.util.Date(readingMillis));
        final String text = GlucoseNotificationText.periodicText(delta, time);

        final Notification.Builder builder = builder(context, title, text, readingMillis, nowWall,
                Notify.glucosetimeout);
        notify.applyGlucoseIcon(builder, snapshot.getPrimaryValue(), snapshot.getSensorGen());
        manager.notify(NOTIFICATION_ID, builder.build());
        shownReadingMillis = readingMillis;
    }

    /** Everything but the icon, which needs the live Notify instance. */
    static Notification.Builder builder(Context context, String title, String text, long readingMillis,
            long nowWall, long freshnessMillis) {
        final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        // Not the ongoing notification's intents: tapping must not acknowledge an alarm
        // and dismissing must not stop one.
        final Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        builder.setContentTitle(title)
                .setContentText(text)
                .setWhen(readingMillis)
                .setShowWhen(true)
                .setContentIntent(PendingIntent.getActivity(context, CONTENT_REQUEST_CODE, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                // Each interval's update is a new message for the watch, not a silent edit.
                .setOnlyAlertOnce(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Gone once the reading is no longer fresh; never presented as current later.
            builder.setTimeoutAfter(timeoutAfterMillis(readingMillis, nowWall, freshnessMillis));
        }
        return builder;
    }

    static long timeoutAfterMillis(long readingMillis, long nowWall, long freshnessMillis) {
        return Math.max(1_000L, freshnessMillis - Math.max(0L, nowWall - readingMillis));
    }

    private static void cancel() {
        shownReadingMillis = 0L;
        final Context context = Applic.app;
        if (context == null) {
            return;
        }
        final NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }
}
