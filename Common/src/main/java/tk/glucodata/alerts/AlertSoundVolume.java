package tk.glucodata.alerts;

import java.util.Locale;

/** Per-player gain; never changes the phone's alarm/notification stream volume. */
public final class AlertSoundVolume {
    public static final int MIN_PERCENT = 30;
    public static final int DEFAULT_PERCENT = 80;
    public static final int MAX_PERCENT = 100;
    public static final int MAX_BOOST_MILLIBELS = 600;

    private AlertSoundVolume() {}

    public static String preferenceKey(int kind) {
        return "alert_" + kind + "_soundVolumePercent";
    }

    public static int sanitize(int percent) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    }

    public static float gain(int percent) {
        return sanitize(percent) / 100.0f;
    }

    /** Processing is opt-in through the slider's region above its normal default. */
    public static int boostMillibels(int percent) {
        return Math.max(0, sanitize(percent) - DEFAULT_PERCENT) * MAX_BOOST_MILLIBELS
                / (MAX_PERCENT - DEFAULT_PERCENT);
    }

    /** Scale the original overall-intensity sound levels, with room above the default. */
    public static float gain(int percent, String profile) {
        return gain(percent) * profileGain(profile);
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

    public static float fadeGain(int percent, String profile, float progress) {
        float target = gain(percent, profile);
        float start = Math.min(target, 0.12f);
        return start + (target - start) * Math.max(0.0f, Math.min(1.0f, progress));
    }
}
