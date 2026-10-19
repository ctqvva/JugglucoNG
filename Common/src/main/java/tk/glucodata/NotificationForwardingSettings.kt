package tk.glucodata

import android.content.Context
import android.content.SharedPreferences

/**
 * Phone notification settings that decide what notification-listener apps (watch and
 * band companions such as Mi Fitness or Haylou Fun) can read:
 *
 * - **Standard text** puts the reading into the ongoing notification's title/text, as
 *   1.2.3–1.2.4 did. Listener apps that forward any titled notification then forward
 *   every update of it (#582, #645).
 * - **Periodic updates** put it there only on the post that announces a reading, at a
 *   chosen interval ([PeriodicGlucoseUpdates]).
 *
 * The two are exclusive: standard text on every post would defeat the interval.
 * Alarm notifications are not affected by either setting.
 */
object NotificationForwardingSettings {
    const val PREFS = "tk.glucodata_preferences"
    const val KEY_STANDARD_TEXT = "notification_standard_text"
    const val KEY_PERIODIC_ENABLED = "periodic_glucose_notifications"
    const val KEY_PERIODIC_INTERVAL = "periodic_glucose_interval_minutes"

    /** 1 means every new reading; the interval is never shorter than the sensor's. */
    @JvmField
    val INTERVAL_OPTIONS = intArrayOf(1, 3, 5, 10)
    const val DEFAULT_INTERVAL_MINUTES = 5

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Pins the standard-text default once per installation. A fresh install gets the
     * richer representation; an update keeps the 1.2.5 behaviour (no standard text),
     * so nobody's watch starts buzzing on every reading after updating. An explicit
     * choice, or a default pinned earlier, is never overwritten.
     */
    @JvmStatic
    fun pinStandardTextDefault(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(KEY_STANDARD_TEXT)) return
        val freshInstall = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        } catch (_: Throwable) {
            false
        }
        prefs.edit().putBoolean(KEY_STANDARD_TEXT, defaultStandardText(freshInstall)).apply()
    }

    @JvmStatic
    fun defaultStandardText(freshInstall: Boolean): Boolean = freshInstall

    /** The stored choice, before the periodic exclusion is applied. */
    @JvmStatic
    fun isStandardTextPreferred(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_STANDARD_TEXT, false)

    /** Whether the ongoing notification carries standard title/text right now. */
    @JvmStatic
    fun isStandardTextEnabled(prefs: SharedPreferences): Boolean =
        isStandardTextPreferred(prefs) && !isPeriodicEnabled(prefs)

    @JvmStatic
    fun isStandardTextEnabled(context: Context): Boolean = isStandardTextEnabled(prefs(context))

    @JvmStatic
    fun setStandardText(context: Context, enabled: Boolean) {
        val prefs = prefs(context)
        // Both on is not a state this setting can reach; the switch is disabled then.
        if (enabled && isPeriodicEnabled(prefs)) return
        prefs.edit().putBoolean(KEY_STANDARD_TEXT, enabled).apply()
    }

    @JvmStatic
    fun isPeriodicEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_PERIODIC_ENABLED, false)

    @JvmStatic
    fun isPeriodicEnabled(context: Context): Boolean = isPeriodicEnabled(prefs(context))

    /**
     * Turning periodic notifications on also turns standard text off and leaves it off
     * when they are turned off again: the user re-enables it deliberately or not at all.
     */
    @JvmStatic
    fun setPeriodicEnabled(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit().putBoolean(KEY_PERIODIC_ENABLED, enabled)
        if (enabled) editor.putBoolean(KEY_STANDARD_TEXT, false)
        editor.apply()
    }

    @JvmStatic
    fun periodicIntervalMinutes(prefs: SharedPreferences): Int =
        sanitizeInterval(prefs.getInt(KEY_PERIODIC_INTERVAL, DEFAULT_INTERVAL_MINUTES))

    @JvmStatic
    fun periodicIntervalMinutes(context: Context): Int = periodicIntervalMinutes(prefs(context))

    @JvmStatic
    fun setPeriodicIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_PERIODIC_INTERVAL, sanitizeInterval(minutes)).apply()
    }

    @JvmStatic
    fun sanitizeInterval(minutes: Int): Int =
        if (INTERVAL_OPTIONS.contains(minutes)) minutes else DEFAULT_INTERVAL_MINUTES
}
