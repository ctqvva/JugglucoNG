package tk.glucodata

import android.content.Context

object Libre2ReadingInterval {
    const val DEFAULT_MINUTES = 1

    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_KEY = "libre2_reading_interval_minutes"
    private val allowedMinutes = intArrayOf(1, 2, 5)

    @JvmStatic
    fun options(): IntArray = allowedMinutes.copyOf()

    @JvmStatic
    fun sanitize(minutes: Int): Int =
        if (allowedMinutes.contains(minutes)) minutes else DEFAULT_MINUTES

    @JvmStatic
    fun getMinutes(): Int {
        val context = Applic.app ?: return DEFAULT_MINUTES
        return getMinutes(context)
    }

    @JvmStatic
    fun getMinutes(context: Context): Int =
        sanitize(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_KEY, DEFAULT_MINUTES)
        )

    @JvmStatic
    fun setMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_KEY, sanitize(minutes))
            .apply()
    }
}

internal class Libre2ReadingIntervalGate {
    private var intervalMinutes = Libre2ReadingInterval.DEFAULT_MINUTES
    private var readingsToSkip = 0

    @Synchronized
    fun shouldPublish(configuredMinutes: Int): Boolean {
        val sanitizedMinutes = Libre2ReadingInterval.sanitize(configuredMinutes)
        if (sanitizedMinutes != intervalMinutes) {
            intervalMinutes = sanitizedMinutes
            readingsToSkip = 0
        }
        if (readingsToSkip > 0) {
            readingsToSkip--
            return false
        }
        readingsToSkip = intervalMinutes - 1
        return true
    }
}
