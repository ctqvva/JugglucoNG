package tk.glucodata.alerts

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

enum class CustomAlertType {
    HIGH, LOW
}

private const val MIN_CUSTOM_ALERT_DURATION_SECONDS = 1
private const val MAX_CUSTOM_ALERT_DURATION_SECONDS = 60
private const val DEFAULT_CUSTOM_ALERT_DURATION_SECONDS = 5

private fun sanitizeCustomAlertDurationSeconds(value: Int): Int {
    return value.takeIf { it in MIN_CUSTOM_ALERT_DURATION_SECONDS..MAX_CUSTOM_ALERT_DURATION_SECONDS }
        ?: DEFAULT_CUSTOM_ALERT_DURATION_SECONDS
}

data class CustomAlertConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: CustomAlertType = CustomAlertType.HIGH,
    val threshold: Float = 0f,
    val enabled: Boolean = true,
    
    val timeRangeEnabled: Boolean = true,
    
    // Time range in minutes from midnight (0-1439). 
    // e.g. 360 = 06:00.
    val startTimeMinutes: Int = 0,
    val endTimeMinutes: Int = 1440,
    
    // Standard Alert Features
    val sound: Boolean = true,
    val soundVolumePercent: Int = AlertSoundVolume.DEFAULT_PERCENT,
    val vibrate: Boolean = true,
    val flash: Boolean = false,
    val style: String = "alarm", // notification, alarm, both
    val hapticProfile: String = "strong", // soft, steady, strong, escalating
    val overrideDnd: Boolean = false,
    val retryEnabled: Boolean = false,
    val retryIntervalMinutes: Int = 0,
    val retryCount: Int = 0,
    
    val snoozedUntil: Long = 0L,
    val soundUri: String? = null,
    val durationSeconds: Int = DEFAULT_CUSTOM_ALERT_DURATION_SECONDS
) {
    fun isActiveTime(currentMinutes: Int): Boolean {
        if (!timeRangeEnabled) return true // Always active if time range disabled

        val current = normalizeClockMinutes(currentMinutes)
        val start = startTimeMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endTimeMinutes.coerceIn(0, MINUTES_PER_DAY)

        if (start == 0 && end == MINUTES_PER_DAY) {
            return true
        }
        if (start == end) {
            return false
        }

        return if (start < end) {
            current in start until end
        } else {
            // Crosses midnight (e.g. 22:00 to 07:00)
            current >= start || current < end
        }
    }

    fun isActiveAt(timeMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Boolean {
        return isActiveTime(localMinutesOfDay(timeMillis, timeZone))
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("type", type.name)
            put("threshold", threshold)
            put("enabled", enabled)
            
            put("timeRangeEnabled", timeRangeEnabled)
            put("startTimeMinutes", startTimeMinutes)
            put("endTimeMinutes", endTimeMinutes)
            
            put("sound", sound)
            put("soundVolumePercent", AlertSoundVolume.sanitize(soundVolumePercent))
            put("vibrate", vibrate)
            put("flash", flash)
            put("style", style)
            put("hapticProfile", hapticProfile)
            put("overrideDnd", overrideDnd)
            
            put("retryEnabled", retryEnabled)
            put("retryInterval", retryIntervalMinutes) // Keep JSON key simple or match property? preserving legacy 'retryInterval' key if any, but mapping to new prop
            put("retryCount", retryCount)
            
            put("snoozedUntil", snoozedUntil)
            put("soundUri", soundUri)
            put("durationSeconds", durationSeconds)
        }
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        fun localMinutesOfDay(timeMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
            val calendar = Calendar.getInstance(timeZone)
            calendar.timeInMillis = timeMillis
            return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        }

        private fun normalizeClockMinutes(minutes: Int): Int {
            return ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        }

        fun fromJson(json: JSONObject): CustomAlertConfig {
            return CustomAlertConfig(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                type = CustomAlertType.valueOf(json.optString("type", "HIGH")),
                threshold = json.optDouble("threshold", 0.0).toFloat(),
                enabled = json.optBoolean("enabled", true),
                
                timeRangeEnabled = json.optBoolean("timeRangeEnabled", true),
                startTimeMinutes = json.optInt("startTimeMinutes", 0),
                endTimeMinutes = json.optInt("endTimeMinutes", 1440),
                
                sound = json.optBoolean("sound", true),
                soundVolumePercent = AlertSoundVolume.sanitize(json.optInt("soundVolumePercent", AlertSoundVolume.DEFAULT_PERCENT)),
                vibrate = json.optBoolean("vibrate", true),
                flash = json.optBoolean("flash", false),
                style = json.optString("style", "alarm"),
                hapticProfile = json.optString(
                    "hapticProfile",
                    legacyHapticProfile(json.optString("intensity", "medium"))
                ),
                overrideDnd = json.optBoolean("overrideDnd", false),
                durationSeconds = sanitizeCustomAlertDurationSeconds(
                    json.optInt("durationSeconds", DEFAULT_CUSTOM_ALERT_DURATION_SECONDS)
                ),
                
                retryEnabled = json.optBoolean("retryEnabled", false),
                retryIntervalMinutes = json.optInt("retryInterval", 0),
                retryCount = json.optInt("retryCount", 0),
                
                snoozedUntil = json.optLong("snoozedUntil", 0L),
                soundUri = if (json.isNull("soundUri")) null else json.getString("soundUri")
            )
        }

        private fun legacyHapticProfile(legacyProfile: String): String {
            return when (legacyProfile.lowercase()) {
                "medium" -> "steady"
                "ascending" -> "escalating"
                "low" -> "soft"
                "silent" -> "soft"
                else -> "strong"
            }
        }
    }
}
