package tk.glucodata.drivers.nightscout

import android.content.Context

/** Upload/Follow selection, intentionally independent from the master enabled state. */
object NightscoutModePreference {
    enum class Mode { UPLOAD, FOLLOW }

    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_MODE = "nightscout_selected_mode_v1"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(
        context: Context,
        legacyUploaderActive: Boolean,
        legacyFollowerEnabled: Boolean,
    ): Mode = resolve(
        stored = prefs(context).getString(PREF_MODE, null),
        legacyUploaderActive = legacyUploaderActive,
        legacyFollowerEnabled = legacyFollowerEnabled,
    )

    fun save(context: Context, mode: Mode) {
        prefs(context).edit().putString(PREF_MODE, mode.name).apply()
    }

    internal fun resolve(
        stored: String?,
        legacyUploaderActive: Boolean,
        legacyFollowerEnabled: Boolean,
    ): Mode {
        Mode.entries.firstOrNull { it.name == stored }?.let { return it }
        return when {
            legacyUploaderActive -> Mode.UPLOAD
            legacyFollowerEnabled -> Mode.FOLLOW
            else -> Mode.UPLOAD
        }
    }
}
