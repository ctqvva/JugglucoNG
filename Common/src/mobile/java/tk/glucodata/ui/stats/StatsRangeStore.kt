package tk.glucodata.ui.stats

import android.content.Context
import android.content.SharedPreferences

/** The persisted statistics range selection: either a quick preset or a custom date range. */
sealed interface StatsRangeSelection {
    data class Preset(val range: StatsTimeRange) : StatsRangeSelection
    data class Custom(val range: StatsDateRange) : StatsRangeSelection
}

/**
 * Persists the selected statistics time range in the shared preference file the rest
 * of the app already uses, so the choice survives a process restart. Preset and custom
 * are mutually exclusive: writing one clears the other.
 */
object StatsRangeStore {

    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_PRESET = "stats_range_preset"
    private const val KEY_CUSTOM_START = "stats_range_custom_start"
    private const val KEY_CUSTOM_END = "stats_range_custom_end"

    fun load(context: Context?): StatsRangeSelection? {
        val store = prefs(context) ?: return null
        return resolveStoredStatsRange(
            presetName = store.getString(KEY_PRESET, null),
            customStartMillis = store.getLong(KEY_CUSTOM_START, Long.MIN_VALUE),
            customEndMillis = store.getLong(KEY_CUSTOM_END, Long.MIN_VALUE)
        )
    }

    fun savePreset(context: Context?, range: StatsTimeRange) {
        prefs(context)?.edit()
            ?.putString(KEY_PRESET, range.name)
            ?.remove(KEY_CUSTOM_START)
            ?.remove(KEY_CUSTOM_END)
            ?.apply()
    }

    fun saveCustom(context: Context?, range: StatsDateRange) {
        prefs(context)?.edit()
            ?.remove(KEY_PRESET)
            ?.putLong(KEY_CUSTOM_START, range.startMillis)
            ?.putLong(KEY_CUSTOM_END, range.endMillis)
            ?.apply()
    }

    private fun prefs(context: Context?): SharedPreferences? =
        context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Pure so it stays unit-testable. A preset name that no longer resolves (saved by an
 * older or newer version) and garbage millis both come back as null, which callers
 * treat as "nothing stored".
 */
internal fun resolveStoredStatsRange(
    presetName: String?,
    customStartMillis: Long,
    customEndMillis: Long
): StatsRangeSelection? {
    if (presetName != null) {
        return runCatching { StatsTimeRange.valueOf(presetName) }
            .getOrNull()
            ?.let { StatsRangeSelection.Preset(it) }
    }
    if (customStartMillis in 0..customEndMillis) {
        return StatsRangeSelection.Custom(
            StatsDateRange(startMillis = customStartMillis, endMillis = customEndMillis)
        )
    }
    return null
}
