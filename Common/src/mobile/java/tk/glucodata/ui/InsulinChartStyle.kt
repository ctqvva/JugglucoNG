package tk.glucodata.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import tk.glucodata.Applic

/** How logged insulin is drawn along the bottom of the glucose chart. */
enum class InsulinChartStyle(val storageValue: String) {
    /** One band of units an hour, each insulin stacked on the ones below. */
    STACKED("stacked"),

    /** The stacked band, with each dose's own curve as a light line over it. */
    STACKED_WITH_DOSES("stacked_with_doses"),

    /** Each dose's own curve, as before the band, sized by its dose. */
    SEPARATE("separate");

    companion object {
        fun fromStorage(value: String?): InsulinChartStyle = entries.firstOrNull { it.storageValue == value } ?: STACKED
    }
}

/** The chosen [InsulinChartStyle], read by every chart and set from the journal settings. */
object InsulinChartStyleState {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY = "dashboard_journal_insulin_chart_style"

    private val state by lazy {
        mutableStateOf(
            InsulinChartStyle.fromStorage(
                Applic.app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null)
            )
        )
    }

    /** Read in a composable to follow changes. */
    val style: InsulinChartStyle get() = state.value

    fun set(context: Context, style: InsulinChartStyle) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, style.storageValue)
            .apply()
        state.value = style
    }
}
