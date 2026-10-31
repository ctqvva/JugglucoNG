package tk.glucodata.ui

import android.content.Context
import tk.glucodata.data.settings.UncertaintySettingsRepository

/** Dashboard uncertainty visibility. Stored estimator intervals are unaffected by this preference. */
object GlucoseUncertaintyDisplay {
    fun isRibbonEnabled(context: Context): Boolean =
        UncertaintySettingsRepository(context).isRibbonEnabled

    fun setRibbonEnabled(context: Context, enabled: Boolean) {
        runCatching { UncertaintySettingsRepository(context).setRibbonEnabled(enabled) }
        tk.glucodata.UiRefreshBus.requestStatusRefresh()
    }
}
