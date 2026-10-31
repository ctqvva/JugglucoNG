package tk.glucodata.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import tk.glucodata.chart.UncertaintyBands

/** Storage and observation for the display preference; estimator data is unaffected. */
class UncertaintySettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    private val key = UncertaintyBands.PREFERENCE_KEY

    val isRibbonEnabled: Boolean get() = prefs.getBoolean(key, true)

    val ribbonEnabled: Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key || changedKey == null) trySend(isRibbonEnabled)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(isRibbonEnabled)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.buffer(Channel.CONFLATED).distinctUntilChanged()

    fun setRibbonEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(key, enabled).apply()
    }
}
