package tk.glucodata.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import tk.glucodata.Applic

/** Dashboard uncertainty visibility. Stored estimator intervals are unaffected by this preference. */
object GlucoseUncertaintyDisplay {

    private const val PREF_NAME = "tk.glucodata_preferences"
    private const val PREF_RIBBON = tk.glucodata.chart.UncertaintyBands.PREFERENCE_KEY

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isRibbonEnabled(context: Context): Boolean =
        tk.glucodata.chart.UncertaintyBands.isEnabled(context)

    /** Reads the app context; for composables that do not hold one. */
    fun isRibbonEnabled(): Boolean =
        Applic.app?.let { isRibbonEnabled(it) } ?: true

    fun setRibbonEnabled(context: Context, enabled: Boolean) {
        runCatching { prefs(context).edit().putBoolean(PREF_RIBBON, enabled).apply() }
        tk.glucodata.UiRefreshBus.requestStatusRefresh()
    }
}

/** Observe the display preference directly, without rebuilding sensor history to redraw it. */
@Composable
internal fun rememberUncertaintyRibbonEnabled(): Boolean {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    }
    val key = tk.glucodata.chart.UncertaintyBands.PREFERENCE_KEY
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean(key, true)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key || changedKey == null) enabled = prefs.getBoolean(key, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        enabled = prefs.getBoolean(key, true)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}
