package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;

/**
 * Authorization storage used only by the experimental source-security build.
 * It deliberately does not share the native authorization slot used by the
 * established Libre 3 engine, so installing the standard APK remains a safe
 * rollback.
 */
final class ExperimentalLibre3AuthorizationStore {
    static final int RECORD_SIZE = 149;
    private static final String PREFS_NAME = "libre3_source_auth_experimental";
    private static final String VERIFIED_PREFIX = "verified.";
    private static final String CANDIDATE_PREFIX = "candidate.";

    private ExperimentalLibre3AuthorizationStore() {
    }

    private static SharedPreferences prefs() {
        return Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static byte[] loadVerified(String serial) {
        return decode(prefs().getString(VERIFIED_PREFIX + serial, null));
    }

    static byte[] loadCandidate(String serial) {
        return decode(prefs().getString(CANDIDATE_PREFIX + serial, null));
    }

    static boolean saveCandidate(String serial, byte[] authorization) {
        String encoded = encode(authorization);
        if (encoded == null) return false;
        return prefs().edit().putString(CANDIDATE_PREFIX + serial, encoded).commit();
    }

    static boolean saveVerified(String serial, byte[] authorization) {
        String encoded = encode(authorization);
        if (encoded == null) return false;
        return prefs().edit()
                .putString(VERIFIED_PREFIX + serial, encoded)
                .remove(CANDIDATE_PREFIX + serial)
                .commit();
    }

    static String encode(byte[] value) {
        if (value == null || value.length != RECORD_SIZE) return null;
        char[] result = new char[value.length * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            int unsigned = value[i] & 0xff;
            result[i * 2] = hex[unsigned >>> 4];
            result[i * 2 + 1] = hex[unsigned & 0x0f];
        }
        return new String(result);
    }

    static byte[] decode(String value) {
        if (value == null || value.length() != RECORD_SIZE * 2) return null;
        byte[] result = new byte[RECORD_SIZE];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                Arrays.fill(result, (byte) 0);
                return null;
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
