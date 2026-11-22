package tk.glucodata.glucosemeter

import android.content.Context
import tk.glucodata.Applic

/**
 * The name the user gave a Bluetooth meter (#922), shown instead of the one it advertises
 * ("meter+08003459"). The advertised name stays the meter's identity: the native table and the
 * scanner match on it, so the user's name is kept beside it and never replaces it.
 */
object MeterNames {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_PREFIX = "glucose_meter_name:"

    const val MAX_LENGTH = 40

    private val whitespace = Regex("\\s+")

    /** One line, trimmed, at most [MAX_LENGTH] characters. */
    @JvmStatic
    fun clean(input: String?): String =
        input.orEmpty().replace(whitespace, " ").trim().take(MAX_LENGTH).trim()

    /** The user's name when there is one, the advertised name otherwise. */
    @JvmStatic
    fun resolve(deviceName: String, custom: String?): String = clean(custom).ifEmpty { deviceName }

    /** The user's own name for the meter, empty when none is set. */
    @JvmStatic
    fun custom(deviceName: String): String = clean(
        Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + deviceName, null)
    )

    @JvmStatic
    fun display(deviceName: String): String = resolve(deviceName, custom(deviceName))

    /** An empty name, or the advertised one, goes back to the advertised name. */
    @JvmStatic
    fun set(deviceName: String, name: String?) {
        val cleaned = clean(name)
        val editor = Applic.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (cleaned.isEmpty() || cleaned == deviceName) {
            editor.remove(KEY_PREFIX + deviceName)
        } else {
            editor.putString(KEY_PREFIX + deviceName, cleaned)
        }
        editor.apply()
    }
}
