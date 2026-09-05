package tk.glucodata.ui

import android.content.Context
import tk.glucodata.Applic

/**
 * Whether the uncertainty ribbon is drawn on the chart.
 *
 * Display only. The estimator still computes and stores its credible interval
 * when this is off, so the value details and tooltip keep working, turning it
 * back on needs no history rebuild, and nothing about the estimate changes —
 * this is a preference about the chart, not about the model.
 */
object GlucoseUncertaintyDisplay {

    private const val PREF_NAME = "tk.glucodata_preferences"
    private const val PREF_RIBBON = "glucose_uncertainty_ribbon"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isRibbonEnabled(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(PREF_RIBBON, true) }.getOrDefault(true)

    /** Reads the app context; for composables that do not hold one. */
    fun isRibbonEnabled(): Boolean =
        Applic.app?.let { isRibbonEnabled(it) } ?: true

    fun setRibbonEnabled(context: Context, enabled: Boolean) {
        runCatching { prefs(context).edit().putBoolean(PREF_RIBBON, enabled).apply() }
        tk.glucodata.UiRefreshBus.requestDataRefresh()
    }
}
