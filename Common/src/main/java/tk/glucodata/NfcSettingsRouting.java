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
     * NFC reader mode only serves Libre scans (opt-in pen import and the manual Ottai wake have
     * their own entry points), so an active Libre 2/3 sensor is the signal that NFC matters.
     * Same relevance check the CGM-readiness NFC row uses; every native call is guarded so an
     * unloaded native library just means "not needed".
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
