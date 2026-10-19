package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;

public final class Libre3NfcSettings {
    public static final int MODE_AUTOMATIC = 0;
    public static final int MODE_ACTIVATE_A0 = 1;
    public static final int MODE_SWITCH_RECEIVER_A8 = 2;

    private static final String PREFS_NAME = "tk.glucodata_preferences";
    private static final String KEY_MODE = "libre3_nfc_command_mode";

    private Libre3NfcSettings() {
    }

    private static SharedPreferences prefs() {
        return Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getMode() {
        return prefs().getInt(KEY_MODE, MODE_AUTOMATIC);
    }

    public static void setMode(int mode) {
        prefs().edit().putInt(KEY_MODE, sanitizeMode(mode)).apply();
    }

    public static int sanitizeMode(int mode) {
        if (mode == MODE_ACTIVATE_A0 || mode == MODE_SWITCH_RECEIVER_A8) {
            return mode;
        }
        return MODE_AUTOMATIC;
    }

    public static byte getCommandByte(byte[] nfc1) {
        switch (getMode()) {
            case MODE_ACTIVATE_A0:
                return (byte) 0xA0;
            case MODE_SWITCH_RECEIVER_A8:
                return (byte) 0xA8;
            case MODE_AUTOMATIC:
            default:
                return nfc1[17] == 1 ? (byte) 0xA0 : (byte) 0xA8;
        }
    }
}
