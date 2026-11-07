package tk.glucodata.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal enum class ChildFriendlyProtection {
    CALIBRATION,
    SENSOR_RESET,
    ADVANCED_SENSOR,
    SMOOTHING,
    DATA_CLEARING,
}

internal data class ChildFriendlyModeSettings(
    val hideCalibration: Boolean,
    val hideSensorReset: Boolean,
    val hideAdvancedSensor: Boolean,
    val hideSmoothing: Boolean,
    val hideDataClearing: Boolean,
) {
    val enabled: Boolean
        get() = hideCalibration || hideSensorReset || hideAdvancedSensor ||
            hideSmoothing || hideDataClearing

    fun hides(protection: ChildFriendlyProtection): Boolean = when (protection) {
        ChildFriendlyProtection.CALIBRATION -> hideCalibration
        ChildFriendlyProtection.SENSOR_RESET -> hideSensorReset
        ChildFriendlyProtection.ADVANCED_SENSOR -> hideAdvancedSensor
        ChildFriendlyProtection.SMOOTHING -> hideSmoothing
        ChildFriendlyProtection.DATA_CLEARING -> hideDataClearing
    }

    fun withProtection(protection: ChildFriendlyProtection, enabled: Boolean) = when (protection) {
        ChildFriendlyProtection.CALIBRATION -> copy(hideCalibration = enabled)
        ChildFriendlyProtection.SENSOR_RESET -> copy(hideSensorReset = enabled)
        ChildFriendlyProtection.ADVANCED_SENSOR -> copy(hideAdvancedSensor = enabled)
        ChildFriendlyProtection.SMOOTHING -> copy(hideSmoothing = enabled)
        ChildFriendlyProtection.DATA_CLEARING -> copy(hideDataClearing = enabled)
    }
}

internal object ChildFriendlyMode {
    private const val PREFERENCES_NAME = "tk.glucodata_preferences"
    const val PREFERENCE_KEY = "child_friendly_mode"

    private val protectionKeys = mapOf(
        ChildFriendlyProtection.CALIBRATION to "child_friendly_mode_hide_calibration",
        ChildFriendlyProtection.SENSOR_RESET to "child_friendly_mode_hide_sensor_reset",
        ChildFriendlyProtection.ADVANCED_SENSOR to "child_friendly_mode_hide_advanced_sensor",
        ChildFriendlyProtection.SMOOTHING to "child_friendly_mode_hide_smoothing",
        ChildFriendlyProtection.DATA_CLEARING to "child_friendly_mode_hide_data_clearing",
    )

    var revision by mutableIntStateOf(0)
        private set

    fun settings(context: Context): ChildFriendlyModeSettings {
        val preferences = preferences(context)
        val hasConfiguredProtection = protectionKeys.values.any(preferences::contains)
        val defaultEnabled = !hasConfiguredProtection &&
            preferences.getBoolean(PREFERENCE_KEY, false)
        return ChildFriendlyModeSettings(
            hideCalibration = preferences.getBoolean(
                protectionKeys.getValue(ChildFriendlyProtection.CALIBRATION),
                defaultEnabled,
            ),
            hideSensorReset = preferences.getBoolean(
                protectionKeys.getValue(ChildFriendlyProtection.SENSOR_RESET),
                defaultEnabled,
            ),
            hideAdvancedSensor = preferences.getBoolean(
                protectionKeys.getValue(ChildFriendlyProtection.ADVANCED_SENSOR),
                defaultEnabled,
            ),
            hideSmoothing = preferences.getBoolean(
                protectionKeys.getValue(ChildFriendlyProtection.SMOOTHING),
                defaultEnabled,
            ),
            hideDataClearing = preferences.getBoolean(
                protectionKeys.getValue(ChildFriendlyProtection.DATA_CLEARING),
                defaultEnabled,
            ),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val editor = preferences(context).edit().putBoolean(PREFERENCE_KEY, enabled)
        protectionKeys.values.forEach { editor.putBoolean(it, enabled) }
        editor.apply()
    }

    fun setProtection(
        context: Context,
        protection: ChildFriendlyProtection,
        enabled: Boolean,
    ) {
        val updated = settings(context).withProtection(protection, enabled)
        val editor = preferences(context).edit().putBoolean(PREFERENCE_KEY, updated.enabled)
        protectionKeys.forEach { (key, preferenceKey) ->
            editor.putBoolean(preferenceKey, updated.hides(key))
        }
        editor.apply()
    }

    fun isManagedPreference(key: String): Boolean =
        key == PREFERENCE_KEY || key in protectionKeys.values

    fun refresh() {
        revision++
    }

    fun observe(context: Context): AutoCloseable {
        val preferences = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || isManagedPreference(key)) {
                refresh()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
