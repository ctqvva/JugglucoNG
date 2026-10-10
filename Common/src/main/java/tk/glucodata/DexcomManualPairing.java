package tk.glucodata;

import java.security.SecureRandom;
import java.util.Random;

/** Builds a scan-compatible payload for a manually entered Dexcom G7 pairing code. */
public final class DexcomManualPairing {
    private static final String MANUAL_PAYLOAD_PREFIX = "JUGGLUCO-MANUAL-G7:";
    private static final String MANUAL_PAYLOAD_PADDING = "00000000000000000";
    private static final String DEXCOM_PAIRING_MARKER = "240";
    private static final String SENSOR_ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int PAIRING_CODE_LENGTH = 4;
    private static final int SENSOR_ID_RANDOM_LENGTH = 11;
    private static final SecureRandom sensorIdRandom = new SecureRandom();

    private DexcomManualPairing() {
    }

    public static String normalizePairingCode(String rawCode) {
        if (rawCode == null) {
            return "";
        }
        return rawCode.trim();
    }

    public static boolean isValidPairingCode(String rawCode) {
        final String code = normalizePairingCode(rawCode);
        if (code.length() != PAIRING_CODE_LENGTH) {
            return false;
        }
        for (int index = 0; index < code.length(); index++) {
            final char character = code.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a payload accepted by the existing native QR parser, or {@code null} for invalid
     * input. Native pairing may reuse an unfinished, data-empty and unbound manual record with the
     * same PIN after interrupted setup; a genuinely new sensor receives a random,
     * clock-independent identity here so clock rollback cannot make it reopen an older record.
     */
    public static String createScanPayload(String rawCode) {
        return createScanPayload(rawCode, 10);
    }

    public static String createScanPayload(String rawCode, int wearDays) {
        return createScanPayload(rawCode, createSensorId(sensorIdRandom), wearDays);
    }

    static String createScanPayload(String rawCode, String sensorId) {
        return createScanPayload(rawCode, sensorId, 10);
    }

    static String createScanPayload(String rawCode, String sensorId, int wearDays) {
        final String code = normalizePairingCode(rawCode);
        if (!isValidPairingCode(code) || !isValidSensorId(sensorId)
                || (wearDays != 10 && wearDays != 15)) {
            return null;
        }

        return MANUAL_PAYLOAD_PREFIX
                + sensorId
                + (wearDays == 15 ? "15000000000000000" : MANUAL_PAYLOAD_PADDING)
                + DEXCOM_PAIRING_MARKER
                + code;
    }

    static String createSensorId(Random random) {
        final StringBuilder sensorId = new StringBuilder(1 + SENSOR_ID_RANDOM_LENGTH);
        sensorId.append('M');
        for (int index = 0; index < SENSOR_ID_RANDOM_LENGTH; index++) {
            sensorId.append(SENSOR_ID_ALPHABET.charAt(random.nextInt(SENSOR_ID_ALPHABET.length())));
        }
        return sensorId.toString();
    }

    private static boolean isValidSensorId(String sensorId) {
        if (sensorId == null || sensorId.length() != 1 + SENSOR_ID_RANDOM_LENGTH
                || sensorId.charAt(0) != 'M') {
            return false;
        }
        for (int index = 1; index < sensorId.length(); index++) {
            if (SENSOR_ID_ALPHABET.indexOf(sensorId.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }
}
