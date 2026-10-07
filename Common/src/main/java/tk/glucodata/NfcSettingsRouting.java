package tk.glucodata;

/**
 * NFC relevance and system-settings routing.
 *
 * <p>Kept separate from MainActivity so the pure parts are unit-testable on the JVM: MainActivity
 * cannot be loaded without the Android runtime. The one-shot nag state lives in {@link
 * NfcPromptState}; only pure decisions and guarded native probing live here.
 */
public final class NfcSettingsRouting {
    private NfcSettingsRouting() {
    }

    /**
     * A passive check (e.g. onResume) never launches another app. Only an explicit NFC action
     * (e.g. starting a Libre scan) on a primary may open the system NFC settings; followers
     * never need that page.
     */
    public static boolean shouldOpen(boolean userRequested, int backupHostNr) {
        return userRequested && backupHostNr == 0;
    }

    /**
     * Whether foreground NFC reads are expected at all. Pushed from the mobile source set
     * (InsulinPenManager: pens are opt-in and live there, while this class must stay usable
     * from src/main, which the wear build also compiles). Defaults to false, matching pens
     * being off until asked for.
     */
    private static volatile boolean penReadsExpected = false;

    public static void setPenReadsExpected(boolean expected) {
        penReadsExpected = expected;
    }

    public static boolean isPenReadsExpected() {
        return penReadsExpected;
    }

    /**
     * Whether an Ottai wake/setup flow currently expects a tap. Pushed from OttaiNfc itself
     * (arm/disarm around the wizard's NFC-dump mode and activation retries). Needed because
     * Ottai tags may be NfcA or MifareUltralight, which the manifest TECH_DISCOVERED filter
     * (NfcV-only) does not dispatch — only an armed reader reliably delivers those taps.
     * Do not widen the manifest filter instead: that would make the app eligible for every
     * unrelated NFC-A tag the system dispatches.
     */
    private static volatile boolean ottaiTapExpected = false;

    public static void setOttaiTapExpected(boolean expected) {
        ottaiTapExpected = expected;
    }

    public static boolean isOttaiTapExpected() {
        return ottaiTapExpected;
    }

    /**
     * Whether the NFC stack may be touched at all. Reader mode aside, even querying the
     * adapter has surfaced NFC-access UI on some devices/ROMs (cause of the Mi 9T sheet is
     * not established — stock Android documents no runtime-consent flow for reader mode),
     * so no-touch without a consumer: Libre scans, explicit NFC flows, enabled insulin
     * pens, or an armed Ottai tap expectation. Everyone else still gets NfcV taps through
     * manifest TECH_DISCOVERED dispatch if one ever happens.
     */
    public static boolean needsNfcStack(boolean userRequested, boolean nfcNeeded,
            boolean penReadsExpected, boolean ottaiTapExpected) {
        return userRequested || nfcNeeded || penReadsExpected || ottaiTapExpected;
    }

    /**
     * An active Libre 2/3 sensor means NFC scans may happen at any time, so the reader stays
     * armed. (Opt-in pen reads and the manual Ottai wake are tracked separately via their own
     * expectation flags.) Same relevance check the CGM-readiness NFC row uses; every native
     * call is guarded so an unloaded native library just means "not needed".
     */
    public static boolean isNfcNeeded() {
        try {
            final String[] active = Natives.activeSensors();
            if (active != null) {
                for (final String sensorId : active) {
                    if (sensorId != null && isLibreSensorId(sensorId)) {
                        return true;
                    }
                }
            }
        } catch (Throwable th) {
            Log.stack("NfcSettingsRouting", "isNfcNeeded", th);
        }
        return false;
    }

    public static boolean isLibreSensorId(String sensorId) {
        try {
            final int kind = SensorSourceResolver.resolveSensorKind(sensorId,
                    SensorSourceResolver.SENSOR_KIND_UNKNOWN);
            if (kind == SensorSourceResolver.SENSOR_KIND_LIBRE2
                    || kind == SensorSourceResolver.SENSOR_KIND_LIBRE3) {
                return true;
            }
            final long sensorPtr = Natives.str2sensorptr(sensorId);
            if (sensorPtr != 0L) {
                final int nativeKind = Natives.getSensorptrLibreVersion(sensorPtr);
                return nativeKind == SensorSourceResolver.SENSOR_KIND_LIBRE2
                        || nativeKind == SensorSourceResolver.SENSOR_KIND_LIBRE3;
            }
        } catch (Throwable th) {
            Log.stack("NfcSettingsRouting", "isLibreSensorId", th);
        }
        return false;
    }
}
