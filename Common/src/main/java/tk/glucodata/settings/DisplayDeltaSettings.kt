package tk.glucodata.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import tk.glucodata.DisplayDeltaSource
import tk.glucodata.GlucoseDelta

/** Watch-local opt-in; the shared interval remains the single delta interval setting. */
class DisplayDeltaSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)

    data class State(val wearEnabled: Boolean = false, val intervalMinutes: Int = GlucoseDelta.DEFAULT_INTERVAL_MINUTES)

    private fun current() = State(
        prefs.getBoolean(DisplayDeltaSource.WEAR_ENABLED, false),
        GlucoseDelta.sanitizeIntervalMinutes(prefs.getInt(DisplayDeltaSource.INTERVAL, GlucoseDelta.DEFAULT_INTERVAL_MINUTES))
    )

    val state = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == DisplayDeltaSource.WEAR_ENABLED || key == DisplayDeltaSource.INTERVAL) {
                trySend(current())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(current())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setWearEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(DisplayDeltaSource.WEAR_ENABLED, enabled).apply()
    }
}
