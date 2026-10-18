package tk.glucodata

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a Libre 1/2's own 15-minute history is drawn beside its BLE stream.
 *
 * Chart-only. The stream stays the main line, the hero value and what alerts
 * read; this only adds or removes the thin History lane. Kept per sensor, like
 * the managed sensors' Data mode it sits in place of on the sensor card.
 */
object SensorHistoryLanePreference {
    private const val PREFS_NAME = "sensor_history_lane"
    private const val KEY_PREFIX = "show_"

    /** On by default: Juggluco draws both, and the stream alone hides how far it strays. */
    const val DEFAULT_SHOWN = true

    private val _revision = MutableStateFlow(0)
    /** Bumps on every write, so a chart can reload without polling preferences. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun isShown(sensorId: String?, context: Context? = Applic.app): Boolean {
        val key = keyFor(sensorId) ?: return DEFAULT_SHOWN
        val prefs = prefs(context) ?: return DEFAULT_SHOWN
        return prefs.getBoolean(key, DEFAULT_SHOWN)
    }

    fun setShown(sensorId: String?, shown: Boolean, context: Context? = Applic.app) {
        val key = keyFor(sensorId) ?: return
        val prefs = prefs(context) ?: return
        prefs.edit().putBoolean(key, shown).apply()
        _revision.value = _revision.value + 1
    }

    private fun prefs(context: Context?) =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyFor(sensorId: String?): String? {
        val id = sensorId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return KEY_PREFIX + (SensorIdentity.resolveAppSensorId(id) ?: id)
    }
}
