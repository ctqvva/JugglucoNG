package tk.glucodata;

import tk.glucodata.drivers.ManagedSensorIdentityRegistry;
import tk.glucodata.drivers.ManagedSensorUiFamily;

public final class SensorSourceResolver {
    public static final int SENSOR_KIND_UNKNOWN = -1;
    public static final int SENSOR_KIND_LIBRE2 = 2;
    public static final int SENSOR_KIND_LIBRE3 = 3;
    public static final int SENSOR_KIND_SIBIONICS = 0x10;
    public static final int SENSOR_KIND_ACCUCHEK = 0x20;
    public static final int SENSOR_KIND_AIDEX = 0x30;
    public static final int SENSOR_KIND_DEXCOM = 0x40;
    // careSensAirKind in cpp/SensorGlucoseData.hpp. Upstream Juggluco uses 0x30,
    // which is SENSOR_KIND_AIDEX here.
    public static final int SENSOR_KIND_CARESENS_AIR = 0x60;

    // Sensors a Kotlin driver owns and native has no flag for. Native never
    // returns these: its ladder calls such a shell Libre 2 by elimination, so
    // they come from the family the driver stamps into the shell instead.
    private static final int MANAGED_KIND_BASE = 0x1000;
    public static final int SENSOR_KIND_MQ = MANAGED_KIND_BASE | 1;
    public static final int SENSOR_KIND_ICAN = MANAGED_KIND_BASE | 3;
    public static final int SENSOR_KIND_ANYTIME = MANAGED_KIND_BASE | 4;
    public static final int SENSOR_KIND_OTTAI = MANAGED_KIND_BASE | 5;
    public static final int SENSOR_KIND_NIGHTSCOUT = MANAGED_KIND_BASE | 7;
    public static final int SENSOR_KIND_MEDTRUM = MANAGED_KIND_BASE | 8;
    // A driver with no family of its own (the API source): readings another app
    // pushed in, whose sensor this app cannot vouch for.
    public static final int SENSOR_KIND_EXTERNAL = MANAGED_KIND_BASE;

    private SensorSourceResolver() {}

    public static String resolveSourceInfo(String sensorId, int fallbackSensorGen) {
        return sourceForKind(resolveSensorKind(sensorId, fallbackSensorGen));
    }

    public static String resolveXdripSourceInfo(String sensorId, int fallbackSensorGen) {
        return xdripSourceInfoForKind(
                resolveSensorKind(sensorId, fallbackSensorGen),
                XdripReportAsLibre2.enabled());
    }

    /**
     * The xDrip SourceInfo string for a sensor kind. AAPS keys advanced filtering
     * (and with it SMB always / after carbs) on this string, and grants it only to
     * "Libre2", "Libre3" and the Dexcom names. So each sensor names itself, using
     * upstream Juggluco's strings where upstream has one, and claims Libre 2 only
     * when the user has explicitly asked for it.
     */
    static String xdripSourceInfoForKind(int sensorKind, boolean reportAsLibre2) {
        final String own = switch (sensorKind) {
            case SENSOR_KIND_LIBRE3 -> "Libre3";
            case SENSOR_KIND_DEXCOM -> "G7";
            case SENSOR_KIND_SIBIONICS -> "GS1Sb";
            case SENSOR_KIND_ACCUCHEK -> "AccuChek";
            case SENSOR_KIND_AIDEX -> "AidexX";
            case SENSOR_KIND_OTTAI -> "Ottai";
            case SENSOR_KIND_ICAN -> "iCan";
            case SENSOR_KIND_ANYTIME -> "Anytime";
            case SENSOR_KIND_MQ -> "MQ";
            case SENSOR_KIND_NIGHTSCOUT -> "Nightscout";
            case SENSOR_KIND_MEDTRUM -> "Medtrum";
            case SENSOR_KIND_CARESENS_AIR -> "CareSenseAir";
            case SENSOR_KIND_EXTERNAL -> "Unknown";
            // Libre 2, and what is left once every flagged or stamped sensor has
            // been named: the Libre family by elimination, as upstream does it.
            // An unresolved reading comes from a Libre callback, whose sensorGen is
            // the patch generation rather than one of the kinds above.
            default -> "Libre2";
        };
        if (reportAsLibre2 && !"Libre3".equals(own) && !"G7".equals(own)) {
            return "Libre2";
        }
        return own;
    }

    public static int resolveSensorKind(String sensorId, int fallbackSensorGen) {
        final int driverKind = resolvePersistedDriverKind(sensorId);
        if (driverKind != SENSOR_KIND_UNKNOWN) {
            return driverKind;
        }
        final int snapshotKind = resolveSnapshotSensorKind(sensorId);
        if (snapshotKind != SENSOR_KIND_UNKNOWN) {
            return snapshotKind;
        }
        return fallbackKindFromSensorGen(fallbackSensorGen);
    }

    public static boolean isLibreKind(int sensorKind) {
        return sensorKind == SENSOR_KIND_LIBRE2 || sensorKind == SENSOR_KIND_LIBRE3;
    }

    /**
     * The managed adapter that owns this sensor's persisted record knows what it
     * is, stamped shell or not; a follower's mirror shell, for one, may predate
     * its stamp. The stamp in the native record is what a Clone recipient, with
     * no record of its own, goes by. Reads prefs only: this runs for every
     * broadcast reading, so it must not take SensorBluetooth's locks.
     */
    private static int resolvePersistedDriverKind(String sensorId) {
        if (sensorId == null || sensorId.isEmpty()) {
            return SENSOR_KIND_UNKNOWN;
        }
        try {
            final ManagedSensorUiFamily family = ManagedSensorIdentityRegistry.resolvePersistedFamily(sensorId);
            if (family != null) {
                final int kind = kindForManagedFamily(family);
                return kind != SENSOR_KIND_UNKNOWN ? kind : SENSOR_KIND_EXTERNAL;
            }
        } catch (Throwable th) {
            Log.stack("SensorSourceResolver", "resolvePersistedDriverKind", th);
        }
        return SENSOR_KIND_UNKNOWN;
    }

    private static int resolveSnapshotSensorKind(String sensorId) {
        if (sensorId == null || sensorId.isEmpty()) {
            return SENSOR_KIND_UNKNOWN;
        }
        try {
            final long[] snapshot = Natives.getSensorUiSnapshot(sensorId);
            if (snapshot != null && snapshot.length >= 1) {
                final int managedFamily = snapshot.length >= 6 ? (int) snapshot[5] : 0;
                return kindForSnapshot((int) snapshot[0], managedFamily);
            }
        } catch (Throwable th) {
            Log.stack("SensorSourceResolver", "resolveSnapshotSensorKind", th);
        }
        return SENSOR_KIND_UNKNOWN;
    }

    /**
     * A stamped managed family outranks the native kind, which for a Kotlin
     * driver's shell is Libre 2 by elimination. No family (0) leaves the native
     * kind alone: shells written before the stamp existed carry no evidence.
     */
    static int kindForSnapshot(int nativeKind, int managedFamily) {
        final int kind = kindForManagedFamily(ManagedSensorUiFamily.Companion.fromNativeCode(managedFamily));
        return kind != SENSOR_KIND_UNKNOWN ? kind : nativeKind;
    }

    static int kindForManagedFamily(ManagedSensorUiFamily family) {
        return switch (family) {
            case AIDEX -> SENSOR_KIND_AIDEX;
            case SIBIONICS -> SENSOR_KIND_SIBIONICS;
            case MQ -> SENSOR_KIND_MQ;
            case ICAN -> SENSOR_KIND_ICAN;
            case ANYTIME -> SENSOR_KIND_ANYTIME;
            case OTTAI -> SENSOR_KIND_OTTAI;
            case NIGHTSCOUT -> SENSOR_KIND_NIGHTSCOUT;
            case MEDTRUM -> SENSOR_KIND_MEDTRUM;
            case GENERIC -> SENSOR_KIND_UNKNOWN;
        };
    }

    private static int fallbackKindFromSensorGen(int sensorGen) {
        return switch (sensorGen) {
            case SENSOR_KIND_LIBRE2 -> SENSOR_KIND_LIBRE2;
            case SENSOR_KIND_LIBRE3 -> SENSOR_KIND_LIBRE3;
            case SENSOR_KIND_SIBIONICS -> SENSOR_KIND_SIBIONICS;
            case SENSOR_KIND_ACCUCHEK -> SENSOR_KIND_ACCUCHEK;
            case SENSOR_KIND_CARESENS_AIR -> SENSOR_KIND_CARESENS_AIR;
            case SENSOR_KIND_AIDEX -> SENSOR_KIND_AIDEX;
            case SENSOR_KIND_DEXCOM -> SENSOR_KIND_DEXCOM;
            default -> SENSOR_KIND_UNKNOWN;
        };
    }

    private static String sourceForKind(int sensorKind) {
        return switch (sensorKind) {
            case SENSOR_KIND_LIBRE3 -> "Libre3";
            case SENSOR_KIND_SIBIONICS -> "GS1Sb";
            case SENSOR_KIND_ACCUCHEK -> "AccuChek";
            case SENSOR_KIND_CARESENS_AIR -> "CareSensAir";
            case SENSOR_KIND_AIDEX -> "AiDex";
            case SENSOR_KIND_DEXCOM -> "G7";
            case SENSOR_KIND_LIBRE2 -> "Libre2";
            case SENSOR_KIND_OTTAI -> "Ottai";
            case SENSOR_KIND_ICAN -> "iCan";
            case SENSOR_KIND_ANYTIME -> "Anytime";
            case SENSOR_KIND_MQ -> "MQ";
            case SENSOR_KIND_NIGHTSCOUT -> "Nightscout";
            case SENSOR_KIND_MEDTRUM -> "Medtrum";
            default -> "Unknown";
        };
    }
}
