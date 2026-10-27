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
 * - **Periodic updates** put it there only on the post that announces a reading: every
 *   new reading, or at a chosen interval ([PeriodicGlucoseUpdates]). Unrelated
 *   refreshes of the notification stay without text.
 *
 * The user picks one [mode]: standard text on every post would defeat the interval. The
 * settings screen splits it in two pickers: the [MODE_CHOICES] and, for [MODE_INTERVAL],
 * the [INTERVAL_CHOICES].
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

    const val MODE_OFF = 0
    const val MODE_STANDARD_TEXT = -1
    const val MODE_EVERY_READING = 1

    /** Stands for whichever interval is chosen; [mode] reports the minutes instead. */
    const val MODE_INTERVAL = -2

    /** The mode picker, in order. */
    @JvmField
    val MODE_CHOICES = intArrayOf(MODE_STANDARD_TEXT, MODE_OFF, MODE_EVERY_READING, MODE_INTERVAL)

    /** The interval picker: every interval except the 1-minute one, which is [MODE_EVERY_READING]. */
    @JvmField
    val INTERVAL_CHOICES = INTERVAL_OPTIONS.filter { it > MODE_EVERY_READING }.toIntArray()

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
    fun isPeriodicEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_PERIODIC_ENABLED, false)

    @JvmStatic
    fun isPeriodicEnabled(context: Context): Boolean = isPeriodicEnabled(prefs(context))

    @JvmStatic
    fun periodicIntervalMinutes(prefs: SharedPreferences): Int =
        sanitizeInterval(prefs.getInt(KEY_PERIODIC_INTERVAL, DEFAULT_INTERVAL_MINUTES))

    @JvmStatic
    fun periodicIntervalMinutes(context: Context): Int = periodicIntervalMinutes(prefs(context))

    /**
     * The one choice the settings screen offers: [MODE_OFF], [MODE_STANDARD_TEXT] or an
     * interval in minutes (periodic updates; [MODE_EVERY_READING] is the 1-minute one).
     */
    @JvmStatic
    fun mode(prefs: SharedPreferences): Int = when {
        isPeriodicEnabled(prefs) -> periodicIntervalMinutes(prefs)
        isStandardTextPreferred(prefs) -> MODE_STANDARD_TEXT
        else -> MODE_OFF
    }

    @JvmStatic
    fun mode(context: Context): Int = mode(prefs(context))

    /** The mode picker option for [mode]. */
    @JvmStatic
    fun choice(mode: Int): Int = if (mode in INTERVAL_CHOICES) MODE_INTERVAL else mode

    /**
     * The mode a picker option selects. [MODE_INTERVAL] brings back the last interval chosen,
     * or the default when the stored one is every new reading.
     */
    @JvmStatic
    fun modeForChoice(prefs: SharedPreferences, choice: Int): Int {
        if (choice != MODE_INTERVAL) return choice
        val stored = periodicIntervalMinutes(prefs)
        return if (stored in INTERVAL_CHOICES) stored else DEFAULT_INTERVAL_MINUTES
    }

    @JvmStatic
    fun modeForChoice(context: Context, choice: Int): Int = modeForChoice(prefs(context), choice)

    @JvmStatic
    fun setMode(context: Context, mode: Int) {
        val periodic = INTERVAL_OPTIONS.contains(mode)
        val editor = prefs(context).edit()
            .putBoolean(KEY_STANDARD_TEXT, mode == MODE_STANDARD_TEXT)
            .putBoolean(KEY_PERIODIC_ENABLED, periodic)
        if (periodic) editor.putInt(KEY_PERIODIC_INTERVAL, mode)
        editor.apply()
    }

    @JvmStatic
    fun sanitizeInterval(minutes: Int): Int =
        if (INTERVAL_OPTIONS.contains(minutes)) minutes else DEFAULT_INTERVAL_MINUTES
}
