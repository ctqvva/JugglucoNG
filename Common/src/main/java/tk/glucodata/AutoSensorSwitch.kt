package tk.glucodata

import android.content.Context

/**
 * "Whichever device can reach the sensor reads it."
 *
 * [SensorOwnershipRuntime] has always been able to arbitrate this: each device
 * announces whether it holds a sensor, and stands down when the other one is the
 * one getting readings. What was missing was the watch ever being in a position
 * to take part. Its Bluetooth is armed only by the phone's explicit "Direct
 * sensor on watch" handover, so until the user performed that handover the watch
 * never scanned, and the arbitration had exactly one candidate.
 *
 * This switch arms the watch without assigning the sensor to it. The watch scans
 * and connects only when the arbitration says the phone is not reading — so the
 * phone keeps the sensor whenever it can reach it, and the watch picks it up
 * when the phone cannot, in either direction and without the user touching
 * anything.
 *
 * It is deliberately independent of the direct-sensor handover: with both on,
 * the watch is the assigned owner and the phone still takes the sensor back the
 * moment the watch stops being reachable, rather than waiting out the peer
 * silence timeout.
 */
object AutoSensorSwitch {
    private const val LOG_ID = "AutoSensorSwitch"
    private const val PREFS = "tk.glucodata_preferences"

    /** Phone-owned; mirrored to the watch by [WearToggleSync]. */
    const val PREF_KEY = "wear_auto_sensor_switch"

    /** [WearToggleSync] scope-`p` id for the mirrored copy. */
    const val TOGGLE_ID = "autoswitch"

    @JvmStatic
    fun isEnabled(): Boolean = runCatching {
        Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(PREF_KEY, false)
    }.getOrNull() ?: false

    /**
     * Writes the setting on this device and brings its radio into line.
     *
     * Called on the phone when the user flips the switch, and on the watch when
     * the mirrored value arrives.
     */
    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        runCatching {
            Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putBoolean(PREF_KEY, enabled)
                ?.apply()
        }.onFailure { Log.stack(LOG_ID, "persist($enabled)", it) }
        Log.i(LOG_ID, "automatic sensor switching $enabled")
        applyRadioState(enabled)
        UiRefreshBus.requestStatusRefresh()
    }

    /**
     * Re-arms the watch after the app restarts, next to
     * [WearSensorClaim.restoreOnStart]. Without this the setting survives but
     * the scanning it enables does not, which is the failure mode direct mode
     * already had: the switch reads "on" and nothing is listening.
     */
    @JvmStatic
    fun restoreOnStart() {
        if (!Applic.isWearable || !isEnabled()) return
        Log.i(LOG_ID, "re-arming automatic sensor switching after restart")
        applyRadioState(true)
    }

    /**
     * Arming is watch-only. The phone's Bluetooth is driven by its own sensors
     * and by the handover protocol, and must not be switched off here: turning
     * this setting off means "stop taking the sensor automatically", not "stop
     * reading the sensors you own".
     */
    private fun applyRadioState(enabled: Boolean) {
        if (!Applic.isWearable) return
        // A watch that is the assigned direct owner keeps scanning regardless;
        // WearSensorClaim owns that case and must not be undone here.
        if (!enabled && WearSensorClaim.isDirectRequested()) return
        val context = MainActivity.thisone ?: Applic.app ?: return
        runCatching { Applic.setbluetooth(context, enabled) }
            .onFailure { Log.stack(LOG_ID, "setbluetooth($enabled)", it) }
    }
}
