package tk.glucodata.ui

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Whether a sensor card shows its detail rows. Each card keeps its own choice, by serial,
 * across restarts: opening one card's details never opens another's.
 */
internal object SensorCardDetails {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY = "sensor_card_details_expanded_"

    private val state = mutableStateMapOf<String, Boolean>()

    fun expanded(context: Context, serial: String): Boolean =
        state[serial]
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY + serial, false)

    fun setExpanded(context: Context, serial: String, expanded: Boolean) {
        state[serial] = expanded
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY + serial, expanded).apply()
    }
}
