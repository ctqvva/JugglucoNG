package tk.glucodata.alerts;

import java.util.Locale;

/** Alert stream setting and the original, independent overall-intensity gain. */
public final class AlertSoundVolume {
    public static final int MIN_PERCENT = 30;
    public static final int DEFAULT_PERCENT = 60;
    public static final int MAX_PERCENT = 100;

    private AlertSoundVolume() {}

    public static String preferenceKey(int kind) {
        return "alert_" + kind + "_soundVolumePercent";
    }

    public static int sanitize(int percent) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    }

    public static int streamIndex(int percent, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum,
                Math.round(maximum * sanitize(percent) / 100.0f)));
    }

    public static float profileGain(String profile) {
        if (profile == null) return 1.0f;
        switch (profile.toUpperCase(Locale.ROOT)) {
            case "SOFT": case "LOW": return 0.4f;
            case "STEADY": case "MEDIUM": return 0.7f;
            default: return 1.0f;
        }
    }

    public static long fadeMillis() {
        return 1_200L;
    }

    public static float fadeGain(String profile, float progress) {
        float target = profileGain(profile);
        float start = Math.min(target, 0.12f);
        return start + (target - start) * Math.max(0.0f, Math.min(1.0f, progress));
    }
}
