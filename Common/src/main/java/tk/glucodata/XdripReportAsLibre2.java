package tk.glucodata;

/**
 * Opt-in: name every sensor except Libre 3 and Dexcom "Libre2" in the
 * xDrip-style broadcast. AAPS enables advanced filtering, which SMB always
 * and SMB after carbs need, only for sources it trusts by name; this lets a
 * user who has judged their sensor good enough claim that trust explicitly
 * instead of the app claiming it for them.
 */
public final class XdripReportAsLibre2 {
    public static final String PREF_KEY = "xdrip_report_as_libre2";
    private static final String PREFS_NAME = "tk.glucodata_preferences";

    private XdripReportAsLibre2() {
    }

    public static boolean enabled() {
        try {
            return Applic.app
                    .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .getBoolean(PREF_KEY, false);
        } catch (Throwable th) {
            return false;
        }
    }
}
