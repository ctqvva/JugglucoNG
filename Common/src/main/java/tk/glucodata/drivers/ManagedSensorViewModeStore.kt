package tk.glucodata.drivers

import android.content.Context
import tk.glucodata.Applic

object ManagedSensorViewModeStore {
    private const val PREFS_NAME = "managed_sensor_view_modes"
    private const val KEY_PREFIX = "view_mode_"

    fun sanitize(mode: Int): Int = mode.coerceIn(0, 3)

    fun read(context: Context? = Applic.app, sensorId: String?, fallback: Int = 0): Int {
        val key = keyFor(sensorId) ?: return sanitize(fallback)
        val prefs = prefs(context) ?: return sanitize(fallback)
        return sanitize(if (prefs.contains(key)) prefs.getInt(key, fallback) else fallback)
    }

    fun readOrNull(context: Context? = Applic.app, sensorId: String?): Int? {
        val key = keyFor(sensorId) ?: return null
        val prefs = prefs(context) ?: return null
        if (!prefs.contains(key)) return null
        return sanitize(prefs.getInt(key, 0))
    }

    /**
     * @param previousMode the mode in force before this write, for a sensor
     *   whose mode so far lived only in native storage. Used when the store
     *   has no entry yet.
     */
    fun write(context: Context? = Applic.app, sensorId: String?, mode: Int, previousMode: Int? = null) {
        val key = keyFor(sensorId) ?: return
        val prefs = prefs(context) ?: return
        val next = sanitize(mode)
        val previous = if (prefs.contains(key)) sanitize(prefs.getInt(key, next)) else previousMode?.let(::sanitize)
        prefs.edit().putInt(key, next).apply()
        // Every user-facing switch lands here; see DisplayLaneHistory.
        if (previous != null && previous != next) {
            tk.glucodata.data.DisplayLaneHistory.noteSwitch(sensorId?.trim(), previous, next, context = context)
        }
    }

    fun clear(context: Context? = Applic.app, sensorId: String?) {
        val key = keyFor(sensorId) ?: return
        val prefs = prefs(context) ?: return
        prefs.edit().remove(key).apply()
    }

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyFor(sensorId: String?): String? {
        val id = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return KEY_PREFIX + id
    }
}
