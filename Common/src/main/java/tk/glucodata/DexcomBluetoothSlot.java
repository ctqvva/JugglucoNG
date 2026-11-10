package tk.glucodata;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The G7 authentication slot this device presents to a sensor.
 *
 * A G7 keeps three independent pairings: 1 for a receiver or pump, 2 for the phone app and
 * 3 for a smart watch. The slot is the last byte of the 0x02 authentication request; the
 * J-PAKE exchange before it is the same for every slot. A pairing made on one slot is not
 * valid on another, so a sensor keeps the slot it was paired on, and changing it means
 * pairing that sensor again.
 *
 * Slot 2 is what this app always sent, so it stays the default and the value every sensor
 * paired before this setting existed is pinned to. A watch build pairs new sensors on slot 3,
 * the watch slot, so it reads next to a phone on slot 2 instead of taking its pairing.
 */
public final class DexcomBluetoothSlot {
    public static final int RECEIVER_OR_PUMP = 1;
    public static final int MOBILE_APP = 2;
    public static final int SMART_WATCH = 3;
    public static final int DEFAULT = MOBILE_APP;

    // Shared with DexGattCallback's connect-path preferences.
    private static final String PREFS = "dex_connect";
    private static final String NEW_PAIRING_KEY = "new_pairing_auth_slot";
    private static final String SENSOR_PREFIX = "auth_slot_";

    private DexcomBluetoothSlot() {
    }

    public static boolean isValid(int slot) {
        return slot == RECEIVER_OR_PUMP || slot == MOBILE_APP || slot == SMART_WATCH;
    }

    private static SharedPreferences prefs() {
        return Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int validOrDefault(int slot) {
        return isValid(slot) ? slot : DEFAULT;
    }

    private static int newPairingDefault() {
        return Applic.isWearable ? SMART_WATCH : DEFAULT;
    }

    /** The slot the next newly paired sensor will use. */
    public static int forNewPairings() {
        final int slot = prefs().getInt(NEW_PAIRING_KEY, newPairingDefault());
        return isValid(slot) ? slot : newPairingDefault();
    }

    public static void setForNewPairings(int slot) {
        if (!isValid(slot)) return;
        prefs().edit().putInt(NEW_PAIRING_KEY, slot).apply();
    }

    static boolean isPinned(String serial) {
        return serial != null && prefs().contains(SENSOR_PREFIX + serial);
    }

    /** The slot this sensor is paired on, or will pair on. */
    public static int forSensor(String serial) {
        if (serial == null) return DEFAULT;
        return validOrDefault(prefs().getInt(SENSOR_PREFIX + serial, DEFAULT));
    }

    static void pin(String serial, int slot) {
        if (serial == null || !isValid(slot)) return;
        prefs().edit().putInt(SENSOR_PREFIX + serial, slot).apply();
    }

    static void pinIfUnset(String serial, int slot) {
        if (!isPinned(serial)) pin(serial, slot);
    }
}
