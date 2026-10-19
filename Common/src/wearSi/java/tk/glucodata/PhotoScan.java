package tk.glucodata;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;

public class PhotoScan {
public static void scan(Activity act, int type) { }
public static void scan(Activity act, int type, String title) { }
static void connectSensor(final String scantag) {}
public static void connectSensor(final String scantag, MainActivity act, int request, long sensorptr) {}
public static boolean applyManualPairingCode(Context context, String rawCode) {
    if (rawCode == null || rawCode.trim().length() < 3) {
        return false;
    }
    if (tryConnect(rawCode.trim())) {
        return true;
    }
    String fakeSibionics = buildSibionicsSensorPayload(rawCode);
    return fakeSibionics != null && tryConnect(fakeSibionics);
}
static boolean handleUnifiedScanResult(int resultCode, Intent data, MainActivity act, int type) { return false; }
public static Intent createUnifiedScanIntent(Context context, int type, long sensorptr) { return null; }
public static Intent createUnifiedScanIntent(Context context, int type, long sensorptr, String title) { return null; }
//static boolean zXingResult(int resultCode, Object data) {return false;}

private static boolean tryConnect(String payload) {
    int[] indexptr = { -1 };
    String name = Natives.addSIscangetName(payload, indexptr);
    if (name == null || name.isEmpty()) {
        return false;
    }
    if (Natives.getusebluetooth()) {
        Applic.updateDevices();
        SuperGattCallback.glucosealarms.setLossAlarm();
    } else {
        Natives.updateUsedSensors();
    }
    Applic.wakemirrors();
    return true;
}

private static String buildSibionicsSensorPayload(String input) {
    String trimmed = input == null ? "" : input.trim();
    if (trimmed.length() < 3 || count(trimmed, '/') > 2) {
        return null;
    }
    String code = onlyAlnumUpper(trimmed.contains("/") ? trimmed.substring(trimmed.lastIndexOf('/') + 1) : trimmed);
    if (code.length() < 3) {
        code = onlyAlnumUpper(trimmed);
    }
    if (code.length() < 3) {
        return null;
    }
    String magicCode = "0697283164";
    if (code.contains(magicCode) && code.length() >= 55) {
        return code;
    }
    if (code.length() <= 11) {
        String tail = code.substring(Math.max(0, code.length() - 4));
        String shortName = tail + code + "00000000000";
        String syntheticName16 = "00000" + shortName.substring(0, 11);
        int prefixPaddingLen = 70 - magicCode.length() - syntheticName16.length() - 1;
        return magicCode + repeat('0', Math.max(0, prefixPaddingLen)) + syntheticName16 + "X";
    }
    return magicCode + repeat('0', 53 - magicCode.length()) + code;
}

private static int count(String value, char target) {
    int count = 0;
    for (int i = 0; i < value.length(); i++) {
        if (value.charAt(i) == target) {
            count++;
        }
    }
    return count;
}

private static String onlyAlnumUpper(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
        char ch = value.charAt(i);
        if (Character.isLetterOrDigit(ch)) {
            out.append(Character.toUpperCase(ch));
        }
    }
    return out.toString();
}

private static String repeat(char ch, int count) {
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
        out.append(ch);
    }
    return out.toString();
}
};
