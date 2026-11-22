package tk.glucodata

import android.content.Context

/**
 * The journal's own on/off switch. With it off the journal's options are not on screen, so
 * whatever they were left at must not keep working out of sight either.
 */
object JournalEnabled {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val KEY = "dashboard_journal_enabled"

    @JvmStatic
    fun isOn(context: Context?): Boolean {
        context ?: return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }
}
