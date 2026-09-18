package tk.glucodata.alerts;

/** Stable named resources, shared by actual playback and the settings preview. */
public final class AlertSoundDefaults {
    private AlertSoundDefaults() {}

    public static String cueFor(int type) {
        switch (type) {
            case 0: return "low";
            case 1: case 10: return "high";
            case 2: case 14: return "notice";
            case 3: case 11: return "reminder";
            case 4: case 9: return "signal";
            case 5: return "urgent_low";
            case 6: return "urgent_high";
            case 7: case 12: return "falling";
            case 8: case 13: return "rising";
            default: return "notice";
        }
    }

    // Native storage has five base slots plus five extra slots. Newer IDs are
    // preference-only; reading them through JNI indexes beyond that storage.
    public static boolean hasNativeSlot(int type) {
        return type >= 0 && type < 10;
    }

    public static String uri(String packageName, int type) {
        return "android.resource://" + packageName + "/raw/alert_ember_" + cueFor(type);
    }

    /** Retain saved native/custom/system choices; only an unset sound uses Ember. */
    public static String resolve(String savedUri, String packageName, int type) {
        return savedUri == null || savedUri.isEmpty() ? uri(packageName, type) : savedUri;
    }
}
