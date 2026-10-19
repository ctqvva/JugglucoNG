package tk.glucodata;

import android.content.Context;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailabilityLight;

public final class GoogleServices {
    private static final String LOG_ID = "GoogleServices";
    private static final long CACHE_TTL_MS = 30_000L;
    private static final long WARN_LOG_INTERVAL_MS = 300_000L;

    private static volatile long lastCheckMs = 0L;
    private static volatile boolean lastAvailable = false;
    private static volatile int lastStatus = Integer.MIN_VALUE;
    private static volatile long lastWarnLogMs = 0L;

    private GoogleServices() {
    }

    public static boolean isPlayServicesAvailable(Context context) {
        if (context == null) {
            return false;
        }
        final long now = System.currentTimeMillis();
        if ((now - lastCheckMs) < CACHE_TTL_MS && lastStatus != Integer.MIN_VALUE) {
            return lastAvailable;
        }

        final Context appContext = context.getApplicationContext();
        try {
            final int previousStatus = lastStatus;
            final int status = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(appContext);
            final boolean available = status == ConnectionResult.SUCCESS;
            lastStatus = status;
            lastAvailable = available;
            lastCheckMs = now;

            if (!available) {
                final boolean statusChanged = status != previousStatus;
                final boolean due = (now - lastWarnLogMs) >= WARN_LOG_INTERVAL_MS;
                if (statusChanged || due) {
                    Log.w(LOG_ID, "Google Play Services unavailable, status=" + status);
                    lastWarnLogMs = now;
                }
            }
            return available;
        } catch (Throwable ignored) {
            // Huawei/Harmony and aggressively trimmed GMS environments can throw here.
            // Fail closed without stack spam; callers already handle "false" gracefully.
            if ((now - lastWarnLogMs) >= WARN_LOG_INTERVAL_MS) {
                Log.w(LOG_ID, "Google Play Services check failed; treating as unavailable");
                lastWarnLogMs = now;
            }
            lastStatus = Integer.MIN_VALUE;
            lastAvailable = false;
            lastCheckMs = now;
            return false;
        }
    }
}
