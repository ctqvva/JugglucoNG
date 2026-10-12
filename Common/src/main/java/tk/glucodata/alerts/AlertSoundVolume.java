package tk.glucodata.alerts;

/** Per-player gain; never changes the phone's alarm/notification stream volume. */
public final class AlertSoundVolume {
    public static final int MIN_PERCENT = 30;
    public static final int DEFAULT_PERCENT = 100;

    private AlertSoundVolume() {}

    public static String preferenceKey(int kind) {
        return "alert_" + kind + "_soundVolumePercent";
    }

    public static int sanitize(int percent) {
        return Math.max(MIN_PERCENT, Math.min(DEFAULT_PERCENT, percent));
    }

    public static float gain(int percent) {
        return sanitize(percent) / 100.0f;
    }

    public static long fadeMillis(int kind) {
        return kind == 0 || kind == 5 ? 120L : 240L;
    }

    public static float fadeGain(int percent, int kind, float progress) {
        float target = gain(percent);
        float start = Math.min(target, kind == 0 || kind == 5 ? 0.7f : 0.45f);
        return start + (target - start) * Math.max(0.0f, Math.min(1.0f, progress));
    }
}
