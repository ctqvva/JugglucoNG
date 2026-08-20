package tk.glucodata.alerts

import tk.glucodata.R

/**
 * Types of glucose alerts supported by the system.
 */
enum class AlertType(val id: Int, val nameResId: Int) {
    LOW(0, R.string.alert_low),
    HIGH(1, R.string.alert_high),
    AVAILABLE(2, R.string.alert_value_available),      
    AMOUNT(3, R.string.alert_amount),            
    LOSS(4, R.string.alert_loss),
    VERY_LOW(5, R.string.alert_very_low),
    VERY_HIGH(6, R.string.alert_very_high),
    PRE_LOW(7, R.string.alert_forecast_low),           
    PRE_HIGH(8, R.string.alert_forecast_high),         
    MISSED_READING(9, R.string.alert_missed_reading),
    PERSISTENT_HIGH(10, R.string.alert_persistent_high),
    SENSOR_EXPIRY(11, R.string.alert_sensor_expiry),
    FALLING_FAST(12, R.string.alert_falling_fast),
    RISING_FAST(13, R.string.alert_rising_fast);

    companion object {
        fun fromId(id: Int): AlertType? = entries.find { it.id == id }

        fun isLegacyOnlyId(id: Int): Boolean {
            return id == AVAILABLE.id || id == AMOUNT.id
        }

        // Compose alert settings intentionally hide old legacy-only alert types.
        val settingsEntries = listOf(
            LOW,
            HIGH,
            VERY_LOW,
            VERY_HIGH,
            PRE_LOW,
            PRE_HIGH,
            MISSED_READING,
            PERSISTENT_HIGH,
            LOSS,
            SENSOR_EXPIRY,
            FALLING_FAST,
            RISING_FAST
        )
    }
}

enum class HapticProfile(val displayName: String) {
    SOFT("Soft"),
    STEADY("Steady"),
    STRONG("Strong"),
    ESCALATING("Escalating")
}

const val MIN_ALERT_DURATION_SECONDS = 1
const val MAX_ALERT_DURATION_SECONDS = 60
const val DEFAULT_ALERT_DURATION_SECONDS = 5

fun sanitizeAlertDurationSeconds(value: Int): Int {
    return value.takeIf { it in MIN_ALERT_DURATION_SECONDS..MAX_ALERT_DURATION_SECONDS }
        ?: DEFAULT_ALERT_DURATION_SECONDS
}

// --- Sound-delay caps (single source of truth for the hypo-safety policy) ---
// A delayed audible alarm on a hypo is a double-edged sword, so LOW and
// VERY_LOW are capped tighter than the rest. Change the policy here only.
const val MAX_SOUND_DELAY_SECONDS = 300
const val MAX_SOUND_DELAY_SECONDS_LOW = 60
const val MAX_SOUND_DELAY_SECONDS_VERY_LOW = 30

fun maxSoundDelaySecondsFor(type: AlertType): Int = when (type) {
    AlertType.LOW -> MAX_SOUND_DELAY_SECONDS_LOW
    AlertType.VERY_LOW -> MAX_SOUND_DELAY_SECONDS_VERY_LOW
    else -> MAX_SOUND_DELAY_SECONDS
}

/** Clamp a requested delay to [0, cap-for-type]. Holds on every write path. */
fun sanitizeSoundDelaySeconds(type: AlertType, value: Int): Int =
    value.coerceIn(0, maxSoundDelaySecondsFor(type))

// Sensor-expiry pre-warning thresholds (minutes before end), single source of
// truth for the UI chips and validation. Descending = longest lead time first.
val EXPIRY_WARNING_PRESETS: List<Int> = listOf(4320, 2880, 1440, 720, 360, 120, 60)
const val DEFAULT_EXPIRY_WARNING_MINUTES = 1440   // 24h — preserves today's behaviour

/** Keep only recognised presets, so stray persisted values can't leak through. */
fun sanitizeExpiryWarningMinutes(values: Set<Int>): Set<Int> =
    values.filter { it in EXPIRY_WARNING_PRESETS }.toSet()

/**
 * Delivery mode for alerts.
 */
enum class AlertDeliveryMode(val displayName: String) {
    NOTIFICATION_ONLY("Notification"),    // High-priority notification
    SYSTEM_ALARM("Alarm"),              // Full-screen AlarmActivity
    BOTH("Both")                               // Both Notification and System Alarm
}

enum class AlertNotificationDismissAction(val displayName: String) {
    DISMISS("Dismiss"),
    SNOOZE("Snooze")
}

/**
 * Configuration for a single alert type.
 */
data class AlertConfig(
    val type: AlertType,
    val enabled: Boolean = false,
    
    // Threshold settings
    val threshold: Float? = null,           // Glucose value (null for non-threshold alerts)
    val durationMinutes: Int? = null,       // For persistent high / missed reading alerts
    val forecastMinutes: Int? = null,       // For forecast alerts (how far ahead to predict)

    // Hysteresis: an already-active episode survives until the measured value
    // recovers past threshold -/+ this margin (display units). null = type
    // default; 0 opts out and restores the pre-margin behaviour.
    val rearmMargin: Float? = null,
    // Forecast only: after a firing, no re-entry for this many minutes,
    // whatever value and projection do. A value margin only guards against a
    // given amplitude; the time guard covers any. null/0 = only the built-in
    // short cooldown.
    val rearmMinIntervalMinutes: Int? = null,
    // PRE_HIGH ONLY: suppress the forecast while remaining IOB x sensitivity
    // covers the projected overshoot x this factor. 0 = off. Never applied to
    // PRE_LOW - insulin makes a predicted low MORE likely.
    val iobCoverageFactor: Float? = null,
    // PERSISTENT_HIGH only: suppress while the value falls at least this fast
    // (mg/dl per minute, magnitude). The alert exists to say "your correction
    // was not enough"; a steep fall is direct proof it was. 0 = off.
    val fallRateSuppress: Float? = null,

    // Delta-counter settings (FALLING_FAST / RISING_FAST): GDH-style robust rate-of-change alarm.
    val deltaThreshold: Float? = null,      // Min change per interval (display units) to count as steep
    val deltaCount: Int? = null,            // How many consecutive steep intervals must accumulate
    val deltaBorder: Float? = null,         // Only alarm once the value is past this bound (below/above)
    val deltaIntervalMinutes: Int? = null,  // Own delta window (1 or 5); null = follow the Δ readout's global interval
    val earlyTriggerEnabled: Boolean = false, // Also fire once the total distance (count x threshold) is covered

    // Delivery settings
    val deliveryMode: AlertDeliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
    val overrideDND: Boolean = false,       // Override Do Not Disturb
    
    // Sound & vibration
    val soundEnabled: Boolean = true,
    val customSoundUri: String? = null,     // null = use default
    val vibrationEnabled: Boolean = true,
    val hapticProfile: HapticProfile = HapticProfile.STRONG,
    val flashEnabled: Boolean = false,

    // Sound delay: vibrate first, add the audible alarm only after this delay.
    // Only meaningful when soundEnabled && vibrationEnabled. 0 = immediate
    // (today's behaviour). Capped per type for hypo safety (see caps below).
    val soundDelayEnabled: Boolean = false,
    val soundDelaySeconds: Int = 0,
    
    // Snooze settings
    val defaultSnoozeMinutes: Int = 15,
    
    // Alarm duration (how long sound plays before auto-stop)
    val alarmDurationSeconds: Int = DEFAULT_ALERT_DURATION_SECONDS,
    
    // === NEW: Time range settings ===
    // Alert only active between these times. null = always active
    val activeStartHour: Int? = null,       // e.g., 22 for 10 PM
    val activeStartMinute: Int? = null,     // e.g., 30 for 10:30 PM
    val activeEndHour: Int? = null,         // e.g., 8 for 8 AM (next day if start > end)
    val activeEndMinute: Int? = null,       // e.g., 15 for 8:15 AM
    val timeRangeEnabled: Boolean = false,  // Master toggle for time range
    
    // === NEW: Retry settings ("try again if no reaction") ===
    val retryEnabled: Boolean = false,
    val retryIntervalMinutes: Int = 5,      // Re-alert every X minutes
    val retryCount: Int = 3,                // Max number of retries (0 = unlimited until dismissed)

    // Sensor-expiry only: warn this many minutes before the sensor ends. Each
    // selected threshold fires once per sensor. Empty = no pre-warning.
    val expiryWarningMinutes: Set<Int> = emptySet()
) {
    /**
     * Whether this alert should use the old system alarm path (AlarmActivity).
     */
    val useSystemAlarm: Boolean
        get() = deliveryMode == AlertDeliveryMode.SYSTEM_ALARM
    
    /**
     * Check if alert is currently active based on time range.
     */
    fun isActiveNow(): Boolean {
        if (!timeRangeEnabled || activeStartHour == null || activeEndHour == null) {
            return true  // No time restriction
        }

        val now = java.util.Calendar.getInstance()
        val currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(java.util.Calendar.MINUTE)
        return isActiveAtMinutes(currentHour * 60 + currentMinute)
    }

    internal fun isActiveAtMinutes(currentMins: Int): Boolean {
        if (!timeRangeEnabled || activeStartHour == null || activeEndHour == null) {
            return true
        }

        val startMins = activeStartHour * 60 + (activeStartMinute ?: 0)
        val endMins = activeEndHour * 60 + (activeEndMinute ?: 0)

        return if (startMins <= endMins) {
            // Same day range: e.g., 9:00 AM to 5:00 PM
            currentMins in startMins until endMins
        } else {
            // Overnight range: e.g., 10:30 PM to 8:15 AM
            currentMins >= startMins || currentMins < endMins
        }
    }
}

/**
 * Snooze state for tracking when an alert is temporarily suppressed.
 */
data class SnoozeState(
    val alertType: AlertType,
    val snoozeUntilMillis: Long,
    val isPreemptive: Boolean = false      // Preemptive snooze before alert triggered
) {
    val isSnoozed: Boolean
        get() = System.currentTimeMillis() < snoozeUntilMillis
    
    val remainingMinutes: Int
        get() = ((snoozeUntilMillis - System.currentTimeMillis()) / 60000).toInt().coerceAtLeast(0)
}

/**
 * Defaults for each alert type following xDrip+ best practices.
 */
object AlertDefaults {
    // Default thresholds (mmol/L - converted to mg/dL at runtime if needed)
    const val LOW_THRESHOLD_MMOL = 3.6f
    const val HIGH_THRESHOLD_MMOL = 9.0f
    const val VERY_LOW_THRESHOLD_MMOL = 3.2f
    const val VERY_HIGH_THRESHOLD_MMOL = 12.0f
    const val FORECAST_LOW_THRESHOLD_MMOL = 3.9f
    const val FORECAST_HIGH_THRESHOLD_MMOL = 8.0f
    const val PERSISTENT_HIGH_THRESHOLD_MMOL = 10.0f
    const val LEGACY_HIGH_THRESHOLD_MMOL = 10.0f
    const val LEGACY_VERY_HIGH_THRESHOLD_MMOL = 13.9f
    const val LEGACY_LOW_THRESHOLD_MMOL = 3.9f
    const val LEGACY_VERY_LOW_THRESHOLD_MMOL = 3.0f
    const val LEGACY_FORECAST_LOW_THRESHOLD_MMOL = 4.4f
    
    // mg/dL equivalents
    const val LOW_THRESHOLD_MGDL = 65f
    const val HIGH_THRESHOLD_MGDL = 162f
    const val VERY_LOW_THRESHOLD_MGDL = 58f
    const val VERY_HIGH_THRESHOLD_MGDL = 216f
    const val FORECAST_LOW_THRESHOLD_MGDL = 70f
    const val FORECAST_HIGH_THRESHOLD_MGDL = 144f
    const val PERSISTENT_HIGH_THRESHOLD_MGDL = 180f
    const val LEGACY_HIGH_THRESHOLD_MGDL = 180f
    const val LEGACY_VERY_HIGH_THRESHOLD_MGDL = 250f
    const val LEGACY_LOW_THRESHOLD_MGDL = 70f
    const val LEGACY_VERY_LOW_THRESHOLD_MGDL = 55f
    const val LEGACY_FORECAST_LOW_THRESHOLD_MGDL = 80f
    
    // Duration defaults
    const val MISSED_READING_MINUTES = 30
    const val PERSISTENT_HIGH_MINUTES = 60
    const val FORECAST_LOOK_AHEAD_MINUTES = 20

    // Forecast rearm hysteresis. The projection moves 30 mg/dL for a
    // 1 mg/dL/min rate change at a 30-minute horizon; the old 4 mg/dL margin
    // was no protection at that amplitude. The episode now hangs on the
    // measured value, and this is how far it must recover to end the episode.
    const val FORECAST_REARM_MARGIN_MGDL = 20f
    const val FORECAST_REARM_MARGIN_MMOL = 1.1f
    // Second guard: no forecast re-entry for this long after a firing.
    const val FORECAST_REARM_MIN_INTERVAL_MINUTES = 20

    // Hard-threshold hysteresis (LOW/HIGH/VERY_LOW/VERY_HIGH): an active
    // episode survives until the value recovers this far past the threshold.
    // One mg/dl of wobble at the line must not consume a dismiss.
    const val THRESHOLD_REARM_MARGIN_MGDL = 10f
    const val THRESHOLD_REARM_MARGIN_MMOL = 0.6f

    // PERSISTENT_HIGH: silent while falling at least this fast (mg/dl/min).
    const val PERSISTENT_HIGH_FALL_RATE_MGDL_PER_MIN = 0.5f

    // PRE_HIGH IOB coverage: suppress when remaining insulin effect covers the
    // full projected overshoot (factor 1.0). Conservative: only a complete
    // cover silences the alert; setting it below 1 suppresses earlier, 0 = off.
    const val PRE_HIGH_IOB_COVERAGE_FACTOR = 1.0f

    // Delta-counter defaults (FALLING_FAST / RISING_FAST). Tunable; disabled by default.
    // Change over the delta interval that counts as steep (~10 mg/dL / 0.6 mmol per 5 min).
    const val DELTA_THRESHOLD_MGDL = 10f
    const val DELTA_THRESHOLD_MMOL = 0.6f
    // Consecutive steep readings required.
    const val DELTA_COUNT_DEFAULT = 3
    // Only warn once the value is already heading toward trouble.
    const val FALLING_BORDER_MGDL = 120f     // alarm only at/below this
    const val FALLING_BORDER_MMOL = 6.7f
    const val RISING_BORDER_MGDL = 180f      // alarm only at/above this
    const val RISING_BORDER_MMOL = 10.0f
    
    // Snooze presets (minutes)
    val SNOOZE_PRESETS = listOf(5, 10, 15, 30, 60, 90, 120)
    
    /**
     * Get default configuration for an alert type.
     */
    fun defaultConfig(type: AlertType, isMmol: Boolean): AlertConfig {
        return when (type) {
            AlertType.LOW -> AlertConfig(
                type = type,
                enabled = true,
                threshold = if (isMmol) LOW_THRESHOLD_MMOL else LOW_THRESHOLD_MGDL,
                rearmMargin = if (isMmol) THRESHOLD_REARM_MARGIN_MMOL else THRESHOLD_REARM_MARGIN_MGDL,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
                hapticProfile = HapticProfile.STRONG,
                overrideDND = true,
                defaultSnoozeMinutes = 15
            )
            AlertType.HIGH -> AlertConfig(
                type = type,
                enabled = true,
                threshold = if (isMmol) HIGH_THRESHOLD_MMOL else HIGH_THRESHOLD_MGDL,
                rearmMargin = if (isMmol) THRESHOLD_REARM_MARGIN_MMOL else THRESHOLD_REARM_MARGIN_MGDL,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
                hapticProfile = HapticProfile.STEADY,
                overrideDND = false,
                defaultSnoozeMinutes = 30
            )
            AlertType.VERY_LOW -> AlertConfig(
                type = type,
                enabled = true,
                threshold = if (isMmol) VERY_LOW_THRESHOLD_MMOL else VERY_LOW_THRESHOLD_MGDL,
                rearmMargin = if (isMmol) THRESHOLD_REARM_MARGIN_MMOL else THRESHOLD_REARM_MARGIN_MGDL,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,  // Critical - use system alarm
                hapticProfile = HapticProfile.ESCALATING,
                overrideDND = true,
                flashEnabled = true,
                defaultSnoozeMinutes = 10
            )
            AlertType.VERY_HIGH -> AlertConfig(
                type = type,
                enabled = true,
                threshold = if (isMmol) VERY_HIGH_THRESHOLD_MMOL else VERY_HIGH_THRESHOLD_MGDL,
                rearmMargin = if (isMmol) THRESHOLD_REARM_MARGIN_MMOL else THRESHOLD_REARM_MARGIN_MGDL,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
                hapticProfile = HapticProfile.STRONG,
                overrideDND = true,
                defaultSnoozeMinutes = 30
            )
            AlertType.PRE_LOW -> AlertConfig(
                type = type,
                enabled = false,
                threshold = if (isMmol) FORECAST_LOW_THRESHOLD_MMOL else FORECAST_LOW_THRESHOLD_MGDL,
                forecastMinutes = FORECAST_LOOK_AHEAD_MINUTES,
                rearmMargin = if (isMmol) FORECAST_REARM_MARGIN_MMOL else FORECAST_REARM_MARGIN_MGDL,
                rearmMinIntervalMinutes = FORECAST_REARM_MIN_INTERVAL_MINUTES,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
                hapticProfile = HapticProfile.SOFT,
                defaultSnoozeMinutes = 20
            )
            AlertType.PRE_HIGH -> AlertConfig(
                type = type,
                enabled = false,
                threshold = if (isMmol) FORECAST_HIGH_THRESHOLD_MMOL else FORECAST_HIGH_THRESHOLD_MGDL,
                forecastMinutes = FORECAST_LOOK_AHEAD_MINUTES,
                rearmMargin = if (isMmol) FORECAST_REARM_MARGIN_MMOL else FORECAST_REARM_MARGIN_MGDL,
                rearmMinIntervalMinutes = FORECAST_REARM_MIN_INTERVAL_MINUTES,
                iobCoverageFactor = PRE_HIGH_IOB_COVERAGE_FACTOR,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
                hapticProfile = HapticProfile.SOFT,
                defaultSnoozeMinutes = 30
            )
            AlertType.MISSED_READING -> AlertConfig(
                type = type,
                enabled = false,
                durationMinutes = MISSED_READING_MINUTES,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
                hapticProfile = HapticProfile.STEADY,
                defaultSnoozeMinutes = 30
            )
            AlertType.PERSISTENT_HIGH -> AlertConfig(
                type = type,
                enabled = false,
                threshold = if (isMmol) PERSISTENT_HIGH_THRESHOLD_MMOL else PERSISTENT_HIGH_THRESHOLD_MGDL,
                durationMinutes = PERSISTENT_HIGH_MINUTES,
                fallRateSuppress = PERSISTENT_HIGH_FALL_RATE_MGDL_PER_MIN,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
                hapticProfile = HapticProfile.STEADY,
                defaultSnoozeMinutes = 60
            )
            AlertType.SENSOR_EXPIRY -> AlertConfig(
                type = type,
                enabled = false,
                deliveryMode = AlertDeliveryMode.NOTIFICATION_ONLY,
                hapticProfile = HapticProfile.SOFT,
                soundEnabled = true,
                defaultSnoozeMinutes = 120,
                expiryWarningMinutes = setOf(DEFAULT_EXPIRY_WARNING_MINUTES)
            )
            AlertType.LOSS -> AlertConfig(
                type = type,
                enabled = false,
                durationMinutes = 30,
                deliveryMode = AlertDeliveryMode.NOTIFICATION_ONLY,
                hapticProfile = HapticProfile.STEADY,
                defaultSnoozeMinutes = 30
            )
            AlertType.FALLING_FAST -> AlertConfig(
                type = type,
                enabled = false,
                deltaThreshold = if (isMmol) DELTA_THRESHOLD_MMOL else DELTA_THRESHOLD_MGDL,
                deltaCount = DELTA_COUNT_DEFAULT,
                deltaBorder = if (isMmol) FALLING_BORDER_MMOL else FALLING_BORDER_MGDL,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
                hapticProfile = HapticProfile.SOFT,
                defaultSnoozeMinutes = 20
            )
            AlertType.RISING_FAST -> AlertConfig(
                type = type,
                enabled = false,
                deltaThreshold = if (isMmol) DELTA_THRESHOLD_MMOL else DELTA_THRESHOLD_MGDL,
                deltaCount = DELTA_COUNT_DEFAULT,
                deltaBorder = if (isMmol) RISING_BORDER_MMOL else RISING_BORDER_MGDL,
                deliveryMode = AlertDeliveryMode.SYSTEM_ALARM,
                hapticProfile = HapticProfile.SOFT,
                defaultSnoozeMinutes = 30
            )
            else -> AlertConfig(type = type)
        }
    }
}
