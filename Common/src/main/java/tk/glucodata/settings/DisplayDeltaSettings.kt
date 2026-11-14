package tk.glucodata.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import tk.glucodata.DisplayDeltaSource
import tk.glucodata.GlucoseDelta

/** Watch-local opt-in; the shared interval remains the single delta interval setting. */
class DisplayDeltaSettings internal constructor(
    context: Context,
    private val publishToWear: (Context) -> Unit
) {
    constructor(context: Context) : this(context, { tk.glucodata.WearPrefsSync.push() })

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)

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

    /** Publish a phone interval edit to every surface that reads it, without waiting for glucose. */
    fun setIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(DisplayDeltaSource.INTERVAL, GlucoseDelta.sanitizeIntervalMinutes(minutes)).apply()
        publishToWear(appContext)
        tk.glucodata.GlucoseUpdateBroadcaster.sendNow(appContext)
    }

    fun setWearEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(DisplayDeltaSource.WEAR_ENABLED, enabled).apply()
    }
}
