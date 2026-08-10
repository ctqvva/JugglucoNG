package tk.glucodata

import android.content.Context

/**
 * What the user asked each watch to do, as opposed to what the watch has since
 * proved it is doing.
 *
 * Direct-sensor routing is a two-phase protocol: the phone asks, the watch only
 * claims ownership once a connected driver has accepted a reading, and phone BLE
 * keeps running in between. The config screen used to bind its switches straight
 * to the confirmed native state, so every toggle sprang back to off within a
 * second and the request looked like it had been ignored. Remembering the
 * request lets the switch hold while the status line reports the real phase.
 */
object WearRoutingRequest {
    private const val PREFS = "wear_routing_request"
    private const val KEY_DIRECT = "direct."
    private const val KEY_ENTER = "enter."
    private const val KEY_SENSOR = "sensor."

    private fun prefs() = Applic.app
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun directRequested(nodeId: String): Boolean =
        prefs()?.getBoolean(KEY_DIRECT + nodeId, false) ?: false

    @JvmStatic
    fun enterRequested(nodeId: String): Boolean =
        prefs()?.getBoolean(KEY_ENTER + nodeId, false) ?: false

    @JvmStatic
    fun record(nodeId: String, direct: Boolean, enter: Boolean) {
        val editor = prefs()?.edit()
            ?.putBoolean(KEY_DIRECT + nodeId, direct)
            ?.putBoolean(KEY_ENTER + nodeId, enter)
            ?: return
        if (direct) {
            SensorIdentity.canonicalSensorId(SensorIdentity.resolveMainSensor())
                ?.takeIf { it.isNotBlank() }
                ?.let { editor.putString(KEY_SENSOR + nodeId, it) }
        } else {
            editor.remove(KEY_SENSOR + nodeId)
        }
        editor.apply()
    }

    /** Dropped when routing is reset to defaults, so nothing stale is shown. */
    @JvmStatic
    fun clear(nodeId: String) {
        prefs()?.edit()
            ?.remove(KEY_DIRECT + nodeId)
            ?.remove(KEY_ENTER + nodeId)
            ?.remove(KEY_SENSOR + nodeId)
            ?.apply()
    }
}
