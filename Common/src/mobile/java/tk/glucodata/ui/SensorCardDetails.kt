package tk.glucodata.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the sensor cards show their detail rows. One choice for every card, kept across
 * restarts: opening the details on one card opens them on all, at once.
 */
internal object SensorCardDetails {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY = "sensor_card_details_expanded"

    private var loaded = false
    private var state by mutableStateOf(false)

    fun expanded(context: Context): Boolean {
        if (!loaded) {
            state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
            loaded = true
        }
        return state
    }

    fun setExpanded(context: Context, expanded: Boolean) {
        loaded = true
        state = expanded
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, expanded).apply()
    }
}
