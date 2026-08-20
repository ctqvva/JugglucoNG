package tk.glucodata.alerts

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import tk.glucodata.Applic
import tk.glucodata.GlucoseDelta
import tk.glucodata.Natives
import tk.glucodata.SuperGattCallback

/**
 * Repository for loading and saving alert configurations.
 * 
 * Uses SharedPreferences for new alert types (missed reading, persistent high, forecast)
 * and bridges to existing Natives methods for legacy alert types.
 */
object AlertRepository {
    
    private const val PREFS_NAME = "tk.glucodata.alerts"
    private const val DEFAULT_THRESHOLD_MIGRATION_KEY = "alert_threshold_defaults_v3"
    private const val KEY_NOTIFICATION_DISMISS_ACTION = "notification_dismiss_action"
    @Volatile
    private var hiddenLegacyAlertCleanupDone = false
    @Volatile
    private var defaultThresholdMigrationDone = false
    @Volatile
    private var cachedNativeLossWaitMinutes: Int? = null
    
    private val prefs: SharedPreferences by lazy {
        Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    // Keys for SharedPreferences
    private fun keyEnabled(type: AlertType) = "alert_${type.id}_enabled"
    private fun keyThreshold(type: AlertType) = "alert_${type.id}_threshold"
    private fun keyDuration(type: AlertType) = "alert_${type.id}_duration"
    private fun keyForecast(type: AlertType) = "alert_${type.id}_forecast"
    private fun keyDeliveryMode(type: AlertType) = "alert_${type.id}_delivery"
    private fun keyHapticProfile(type: AlertType) = "alert_${type.id}_haptic"
    private fun keyOverrideDND(type: AlertType) = "alert_${type.id}_dnd"
    private fun keySoundEnabled(type: AlertType) = "alert_${type.id}_sound"
    private fun keyCustomSound(type: AlertType) = "alert_${type.id}_soundUri"
    private fun keyVibration(type: AlertType) = "alert_${type.id}_vibration"
    private fun keyFlash(type: AlertType) = "alert_${type.id}_flash"
    private fun keySnooze(type: AlertType) = "alert_${type.id}_snooze"
    private fun keyAlarmDuration(type: AlertType) = "alert_${type.id}_alarmDur"
    // NEW: Time range and retry keys
    private fun keyTimeRangeEnabled(type: AlertType) = "alert_${type.id}_timeRange"
    private fun keyActiveStartHour(type: AlertType) = "alert_${type.id}_startHour"
    private fun keyActiveStartMinute(type: AlertType) = "alert_${type.id}_startMin"
    private fun keyActiveEndHour(type: AlertType) = "alert_${type.id}_endHour"
    private fun keyActiveEndMinute(type: AlertType) = "alert_${type.id}_endMin"
    private fun keyRetryEnabled(type: AlertType) = "alert_${type.id}_retryOn"
    private fun keyRetryInterval(type: AlertType) = "alert_${type.id}_retryInt"
    private fun keyRetryCount(type: AlertType) = "alert_${type.id}_retryCnt"
    // Delta-counter keys (FALLING_FAST / RISING_FAST)
    private fun keyDeltaThreshold(type: AlertType) = "alert_${type.id}_deltaThreshold"
    private fun keyDeltaCount(type: AlertType) = "alert_${type.id}_deltaCount"
    private fun keyDeltaBorder(type: AlertType) = "alert_${type.id}_deltaBorder"
    private fun keyDeltaInterval(type: AlertType) = "alert_${type.id}_deltaInterval"
    private fun keyEarlyTrigger(type: AlertType) = "alert_${type.id}_earlyTrigger"

    // Sound delay ("vibrate first, audio after N seconds")
    private fun keySoundDelayEnabled(type: AlertType) = "alert_${type.id}_soundDelayEnabled"
    private fun keySoundDelaySeconds(type: AlertType) = "alert_${type.id}_soundDelay"

    // Rearm hysteresis. 0 is a meaningful stored value (explicit opt-out), so
    // reads are contains()-guarded instead of takeIf { it > 0 }-normalised.
    private fun keyRearmMargin(type: AlertType) = "alert_${type.id}_rearmMargin"
    private fun keyRearmMinInterval(type: AlertType) = "alert_${type.id}_rearmMinIntervalMin"

    private fun readRearmMargin(type: AlertType, default: Float?): Float? {
        val key = keyRearmMargin(type)
        if (!prefs.contains(key)) return default
        return prefs.getFloat(key, 0f).takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: default
    }

    private fun readRearmMinInterval(type: AlertType, default: Int?): Int? {
        val key = keyRearmMinInterval(type)
        if (!prefs.contains(key)) return default
        return prefs.getInt(key, 0).coerceAtLeast(0)
    }

    // PRE_HIGH only: IOB coverage factor. 0 = feature off, so contains()-guarded.
    private fun keyIobCoverage(type: AlertType) = "alert_${type.id}_iobCoverage"

    private fun readIobCoverage(type: AlertType, default: Float?): Float? {
        val key = keyIobCoverage(type)
        if (!prefs.contains(key)) return default
        return prefs.getFloat(key, 0f).takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: default
    }

    // PERSISTENT_HIGH only: fall-rate suppression (mg/dl/min). 0 = off.
    private fun keyFallRateSuppress(type: AlertType) = "alert_${type.id}_fallRateSuppress"

    private fun readFallRateSuppress(type: AlertType, default: Float?): Float? {
        val key = keyFallRateSuppress(type)
        if (!prefs.contains(key)) return default
        return prefs.getFloat(key, 0f).takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: default
    }

    // Cap enforced on every read so a value written by any path (incl. the
    // apply-to-all bulk edit onto LOW/VERY_LOW) can never exceed the hypo cap.
    private fun readSoundDelaySeconds(type: AlertType): Int =
        sanitizeSoundDelaySeconds(type, prefs.getInt(keySoundDelaySeconds(type), 0))

    // Sensor-expiry pre-warning thresholds (minutes), stored as a StringSet.
    private fun keyExpiryWarnings(type: AlertType) = "alert_${type.id}_expiryWarnings"

    // Thresholds already warned for, entries "endTimeMs:minutes". Saving keeps
    // only the current sensor's entries, so the set stays bounded.
    private fun keyExpiryWarned(type: AlertType) = "alert_${type.id}_expiryWarned"

    internal val sensorExpiryWarnedStore: ExpiryWarnedStore = object : ExpiryWarnedStore {
        override fun load(endTimeMs: Long): Set<Int> {
            val raw = prefs.getStringSet(keyExpiryWarned(AlertType.SENSOR_EXPIRY), null)
                ?: return emptySet()
            val prefix = "$endTimeMs:"
            return raw.mapNotNull { entry ->
                entry.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
            }.toSet()
        }

        override fun save(endTimeMs: Long, thresholds: Set<Int>) {
            prefs.edit {
                putStringSet(
                    keyExpiryWarned(AlertType.SENSOR_EXPIRY),
                    thresholds.map { "$endTimeMs:$it" }.toSet()
                )
            }
        }
    }

    /**
     * A threshold enabled mid-episode whose warning window is already open must
     * not fire retroactively - not even after a process restart. Persist the
     * adoption at save time; the runtime baseline then treats it like any
     * already-warned window. Thresholds that were already configured keep their
     * state: one that is due but was never warned (process was down at window
     * entry) stays eligible for the catch-up warning. That includes thresholds
     * configured while the alert was switched off - toggling the alert off and
     * on is not a fresh start, and treating it as one silenced every open
     * window for the rest of the sensor.
     */
    private fun adoptOpenWindowsForNewExpiryThresholds(config: AlertConfig) {
        if (!config.enabled) {
            return
        }
        val nowMs = System.currentTimeMillis()
        val endTimeMs = resolveSensorExpiryEndMs(preferredSensorId = null, nowMs = nowMs)
        if (endTimeMs <= 0L) {
            return
        }
        val previous = loadConfig(config.type)
        val newlyOpen = newlyOpenExpiryThresholds(
            previousMinutes = previous.expiryWarningMinutes,
            currentMinutes = config.expiryWarningMinutes,
            endTimeMs = endTimeMs,
            nowMs = nowMs
        )
        if (newlyOpen.isEmpty()) {
            return
        }
        sensorExpiryWarnedStore.save(
            endTimeMs,
            sensorExpiryWarnedStore.load(endTimeMs) + newlyOpen
        )
    }

    /** Missing key -> default (24h migration); present-but-empty -> no pre-warning. */
    private fun readExpiryWarnings(type: AlertType, default: Set<Int>): Set<Int> {
        if (!prefs.contains(keyExpiryWarnings(type))) {
            return default
        }
        val raw = prefs.getStringSet(keyExpiryWarnings(type), null) ?: return default
        return sanitizeExpiryWarningMinutes(raw.mapNotNull { it.toIntOrNull() }.toSet())
    }

    private inline fun <reified T : Enum<T>> parseEnumPref(value: String?, fallback: T): T {
        return value?.let { raw ->
            runCatching { enumValueOf<T>(raw.uppercase()) }.getOrNull()
        } ?: fallback
    }

    private fun readAlarmDurationSeconds(type: AlertType, fallback: Int): Int {
        val value = if (prefs.contains(keyAlarmDuration(type))) {
            prefs.getInt(keyAlarmDuration(type), fallback)
        } else {
            fallback
        }
        return sanitizeAlertDurationSeconds(value)
    }

    fun loadNotificationDismissAction(): AlertNotificationDismissAction {
        return parseEnumPref(
            prefs.getString(KEY_NOTIFICATION_DISMISS_ACTION, null),
            AlertNotificationDismissAction.DISMISS
        )
    }

    fun saveNotificationDismissAction(action: AlertNotificationDismissAction) {
        prefs.edit {
            putString(KEY_NOTIFICATION_DISMISS_ACTION, action.name)
        }
    }
    
    /**
     * Load configuration for an alert type.
     * For legacy types, reads from Natives. For new types, reads from SharedPreferences.
     */
    fun loadConfig(type: AlertType): AlertConfig {
        ensureDefaultThresholdMigration()
        ensureHiddenLegacyAlertCleanup()
        val isMmol = Applic.unit == 1
        val default = AlertDefaults.defaultConfig(type, isMmol)

        return when (type) {
            // Legacy types - bridge to Natives
            AlertType.LOW -> loadLegacyConfig(type, default) {
                copy(
                    enabled = Natives.hasalarmlow(),
                    threshold = Natives.alarmlow()
                )
            }
            AlertType.HIGH -> loadLegacyConfig(type, default) {
                copy(
                    enabled = Natives.hasalarmhigh(),
                    threshold = Natives.alarmhigh()
                )
            }
            AlertType.VERY_LOW -> loadLegacyConfig(type, default) {
                copy(
                    enabled = Natives.hasalarmverylow(),
                    threshold = Natives.alarmverylow()
                )
            }
            AlertType.VERY_HIGH -> loadLegacyConfig(type, default) {
                copy(
                    enabled = Natives.hasalarmveryhigh(),
                    threshold = Natives.alarmveryhigh()
                )
            }
            AlertType.PRE_LOW -> loadLegacyConfig(type, default) {
                copy(
                    enabled = Natives.hasalarmprelow(),
                    threshold = Natives.alarmprelow()
                )
            }
            AlertType.PRE_HIGH -> loadLegacyConfig(type, default) {
                copy(
                    enabled = Natives.hasalarmprehigh(),
                    threshold = Natives.alarmprehigh()
                )
            }
            AlertType.LOSS -> loadLegacyConfig(type, default) {
                copy(enabled = Natives.hasalarmloss())
            }
            // New types - use SharedPreferences
            else -> loadFromPrefs(type, default)
        }
    }
    
    /**
     * Load legacy config with overrides from SharedPreferences for new fields.
     */
    private inline fun loadLegacyConfig(
        type: AlertType,
        default: AlertConfig,
        legacyOverrides: AlertConfig.() -> AlertConfig
    ): AlertConfig {
        val base = default.legacyOverrides()
        return applyLegacyOverrides(type, base)
    }

    private fun legacyDurationMinutesFallback(type: AlertType, base: AlertConfig): Int? {
        return when (type) {
            AlertType.LOSS -> {
                val nativeMinutes = readNativeLossWaitMinutes()
                nativeMinutes.takeIf { it > 0 } ?: base.durationMinutes
            }
            else -> base.durationMinutes
        }
    }

    private fun applyLegacyOverrides(type: AlertType, base: AlertConfig): AlertConfig {
        val durationKey = keyDuration(type)
        val durationFromPrefs = if (prefs.contains(durationKey)) {
            prefs.getInt(durationKey, 0).takeIf { it > 0 }
        } else {
            null
        }
        val durationMinutes = durationFromPrefs ?: legacyDurationMinutesFallback(type, base)
        if (type == AlertType.LOSS && durationFromPrefs != null) {
            writeNativeLossWaitMinutes(durationFromPrefs)
        }
        return base.copy(
            durationMinutes = durationMinutes,
            forecastMinutes = prefs.getInt(keyForecast(type), base.forecastMinutes ?: 0)
                .takeIf { it > 0 },
            deliveryMode = parseEnumPref(prefs.getString(keyDeliveryMode(type), null), base.deliveryMode),
            hapticProfile = parseEnumPref(prefs.getString(keyHapticProfile(type), null), base.hapticProfile),
            overrideDND = prefs.getBoolean(keyOverrideDND(type), Natives.getalarmdisturb(type.id)),
            soundEnabled = prefs.getBoolean(keySoundEnabled(type), Natives.alarmhassound(type.id)),
            vibrationEnabled = prefs.getBoolean(keyVibration(type), Natives.alarmhasvibration(type.id)),
            flashEnabled = prefs.getBoolean(keyFlash(type), Natives.alarmhasflash(type.id)),
            customSoundUri = prefs.getString(keyCustomSound(type), null),
            defaultSnoozeMinutes = prefs.getInt(keySnooze(type), base.defaultSnoozeMinutes),
            alarmDurationSeconds = readAlarmDurationSeconds(type, base.alarmDurationSeconds),
            // NEW: Time range and retry
            timeRangeEnabled = prefs.getBoolean(keyTimeRangeEnabled(type), false),
            activeStartHour = prefs.getInt(keyActiveStartHour(type), -1).takeIf { it >= 0 },
            activeStartMinute = prefs.getInt(keyActiveStartMinute(type), -1).takeIf { it >= 0 },
            activeEndHour = prefs.getInt(keyActiveEndHour(type), -1).takeIf { it >= 0 },
            activeEndMinute = prefs.getInt(keyActiveEndMinute(type), -1).takeIf { it >= 0 },
            retryEnabled = prefs.getBoolean(keyRetryEnabled(type), false),
            retryIntervalMinutes = prefs.getInt(keyRetryInterval(type), 5),
            retryCount = prefs.getInt(keyRetryCount(type), 3),
            soundDelayEnabled = prefs.getBoolean(keySoundDelayEnabled(type), false),
            soundDelaySeconds = readSoundDelaySeconds(type),
            rearmMargin = readRearmMargin(type, base.rearmMargin),
            rearmMinIntervalMinutes = readRearmMinInterval(type, base.rearmMinIntervalMinutes),
            iobCoverageFactor = readIobCoverage(type, base.iobCoverageFactor)
        )
    }

    private fun readNativeLossWaitMinutes(): Int {
        cachedNativeLossWaitMinutes?.let { return it }
        return Natives.readalarmsuspension(AlertType.LOSS.id).toInt().also {
            cachedNativeLossWaitMinutes = it
        }
    }

    private fun writeNativeLossWaitMinutes(durationMinutes: Int) {
        val waitMinutes = durationMinutes.coerceIn(1, Short.MAX_VALUE.toInt())
        if (cachedNativeLossWaitMinutes == waitMinutes) {
            return
        }
        Natives.writealarmsuspension(AlertType.LOSS.id, waitMinutes.toShort())
        cachedNativeLossWaitMinutes = waitMinutes
    }
    
    /**
     * Load config entirely from SharedPreferences (new alert types).
     */
    private fun loadFromPrefs(type: AlertType, default: AlertConfig): AlertConfig {
        if (!prefs.contains(keyEnabled(type))) {
            return default  // First time - use defaults
        }
        
        return default.copy(
            enabled = prefs.getBoolean(keyEnabled(type), default.enabled),
            threshold = prefs.getFloat(keyThreshold(type), default.threshold ?: 0f).takeIf { it > 0 },
            durationMinutes = prefs.getInt(keyDuration(type), default.durationMinutes ?: 0).takeIf { it > 0 },
            forecastMinutes = prefs.getInt(keyForecast(type), default.forecastMinutes ?: 0).takeIf { it > 0 },
            deliveryMode = parseEnumPref(prefs.getString(keyDeliveryMode(type), default.deliveryMode.name), default.deliveryMode),
            hapticProfile = parseEnumPref(prefs.getString(keyHapticProfile(type), default.hapticProfile.name), default.hapticProfile),
            overrideDND = prefs.getBoolean(keyOverrideDND(type), default.overrideDND),
            soundEnabled = prefs.getBoolean(keySoundEnabled(type), default.soundEnabled),
            customSoundUri = prefs.getString(keyCustomSound(type), default.customSoundUri),
            vibrationEnabled = prefs.getBoolean(keyVibration(type), default.vibrationEnabled),
            flashEnabled = prefs.getBoolean(keyFlash(type), default.flashEnabled),
            defaultSnoozeMinutes = prefs.getInt(keySnooze(type), default.defaultSnoozeMinutes),
            alarmDurationSeconds = readAlarmDurationSeconds(type, default.alarmDurationSeconds),
            // NEW: Time range and retry
            timeRangeEnabled = prefs.getBoolean(keyTimeRangeEnabled(type), false),
            activeStartHour = prefs.getInt(keyActiveStartHour(type), -1).takeIf { it >= 0 },
            activeStartMinute = prefs.getInt(keyActiveStartMinute(type), -1).takeIf { it >= 0 },
            activeEndHour = prefs.getInt(keyActiveEndHour(type), -1).takeIf { it >= 0 },
            activeEndMinute = prefs.getInt(keyActiveEndMinute(type), -1).takeIf { it >= 0 },
            retryEnabled = prefs.getBoolean(keyRetryEnabled(type), false),
            retryIntervalMinutes = prefs.getInt(keyRetryInterval(type), 5),
            retryCount = prefs.getInt(keyRetryCount(type), 3),
            deltaThreshold = prefs.getFloat(keyDeltaThreshold(type), default.deltaThreshold ?: 0f).takeIf { it > 0 },
            deltaCount = prefs.getInt(keyDeltaCount(type), default.deltaCount ?: 0).takeIf { it > 0 },
            deltaBorder = prefs.getFloat(keyDeltaBorder(type), default.deltaBorder ?: 0f).takeIf { it > 0 },
            // Missing/0 = follow the Δ readout's global interval.
            deltaIntervalMinutes = prefs.getInt(keyDeltaInterval(type), 0).takeIf { it > 0 }
                ?.let { GlucoseDelta.sanitizeIntervalMinutes(it) },
            earlyTriggerEnabled = prefs.getBoolean(keyEarlyTrigger(type), false),
            soundDelayEnabled = prefs.getBoolean(keySoundDelayEnabled(type), false),
            soundDelaySeconds = readSoundDelaySeconds(type),
            expiryWarningMinutes = readExpiryWarnings(type, default.expiryWarningMinutes),
            rearmMargin = readRearmMargin(type, default.rearmMargin),
            rearmMinIntervalMinutes = readRearmMinInterval(type, default.rearmMinIntervalMinutes),
            iobCoverageFactor = readIobCoverage(type, default.iobCoverageFactor),
            fallRateSuppress = readFallRateSuppress(type, default.fallRateSuppress)
        )
    }

    /**
     * Save configuration for an alert type.
     * For legacy types, writes to both SharedPreferences and Natives.
     */
    fun saveConfig(config: AlertConfig) {
        ensureHiddenLegacyAlertCleanup()
        // FIRST: Save to SharedPreferences
        saveToPrefs(config)
        
        // THEN: Sync to Natives for legacy types (uses the just-saved config)

        
        // Sync native alarm settings for legacy types
        syncNativeAlarmSettings(config)

        // Both devices raise alerts, so an enable that only lands on one of
        // them leaves the pair firing different sets. The phone reports the
        // change; on the watch this is a no-op, since a watch-side change has
        // already gone through the phone to get here.
        runCatching { tk.glucodata.WearToggleSync.push() }
    }
    
    private fun saveToPrefs(config: AlertConfig) {
        if (config.type == AlertType.SENSOR_EXPIRY) {
            adoptOpenWindowsForNewExpiryThresholds(config)
        }
        prefs.edit {
            putBoolean(keyEnabled(config.type), config.enabled)
            if (config.threshold != null) putFloat(keyThreshold(config.type), config.threshold) else remove(keyThreshold(config.type))
            if (config.durationMinutes != null) putInt(keyDuration(config.type), config.durationMinutes) else remove(keyDuration(config.type))
            if (config.forecastMinutes != null) putInt(keyForecast(config.type), config.forecastMinutes) else remove(keyForecast(config.type))
            putString(keyDeliveryMode(config.type), config.deliveryMode.name)
            putString(keyHapticProfile(config.type), config.hapticProfile.name)
            putBoolean(keyOverrideDND(config.type), config.overrideDND)
            putBoolean(keySoundEnabled(config.type), config.soundEnabled)
            // Save customSoundUri - if null, remove the key so it defaults to app default
            if (config.customSoundUri != null) {
                putString(keyCustomSound(config.type), config.customSoundUri)
            } else {
                remove(keyCustomSound(config.type))
            }
            putBoolean(keyVibration(config.type), config.vibrationEnabled)
            putBoolean(keyFlash(config.type), config.flashEnabled)
            putInt(keySnooze(config.type), config.defaultSnoozeMinutes)
            putInt(keyAlarmDuration(config.type), sanitizeAlertDurationSeconds(config.alarmDurationSeconds))
            putBoolean(keyTimeRangeEnabled(config.type), config.timeRangeEnabled)
            if (config.activeStartHour != null) putInt(keyActiveStartHour(config.type), config.activeStartHour) else remove(keyActiveStartHour(config.type))
            if (config.activeStartMinute != null) putInt(keyActiveStartMinute(config.type), config.activeStartMinute) else remove(keyActiveStartMinute(config.type))
            if (config.activeEndHour != null) putInt(keyActiveEndHour(config.type), config.activeEndHour) else remove(keyActiveEndHour(config.type))
            if (config.activeEndMinute != null) putInt(keyActiveEndMinute(config.type), config.activeEndMinute) else remove(keyActiveEndMinute(config.type))
            putBoolean(keyRetryEnabled(config.type), config.retryEnabled)
            putInt(keyRetryInterval(config.type), config.retryIntervalMinutes)
            putInt(keyRetryCount(config.type), config.retryCount)
            if (config.deltaThreshold != null) putFloat(keyDeltaThreshold(config.type), config.deltaThreshold) else remove(keyDeltaThreshold(config.type))
            if (config.deltaCount != null) putInt(keyDeltaCount(config.type), config.deltaCount) else remove(keyDeltaCount(config.type))
            if (config.deltaBorder != null) putFloat(keyDeltaBorder(config.type), config.deltaBorder) else remove(keyDeltaBorder(config.type))
            if (config.deltaIntervalMinutes != null) putInt(keyDeltaInterval(config.type), config.deltaIntervalMinutes) else remove(keyDeltaInterval(config.type))
            putBoolean(keyEarlyTrigger(config.type), config.earlyTriggerEnabled)
            putBoolean(keySoundDelayEnabled(config.type), config.soundDelayEnabled)
            putInt(keySoundDelaySeconds(config.type), sanitizeSoundDelaySeconds(config.type, config.soundDelaySeconds))
            if (config.rearmMargin != null) putFloat(keyRearmMargin(config.type), config.rearmMargin.coerceAtLeast(0f)) else remove(keyRearmMargin(config.type))
            if (config.rearmMinIntervalMinutes != null) putInt(keyRearmMinInterval(config.type), config.rearmMinIntervalMinutes.coerceAtLeast(0)) else remove(keyRearmMinInterval(config.type))
            if (config.iobCoverageFactor != null) putFloat(keyIobCoverage(config.type), config.iobCoverageFactor.coerceAtLeast(0f)) else remove(keyIobCoverage(config.type))
            if (config.fallRateSuppress != null) putFloat(keyFallRateSuppress(config.type), config.fallRateSuppress.coerceAtLeast(0f)) else remove(keyFallRateSuppress(config.type))
            // Type-specific: only the sensor-expiry alert carries pre-warnings.
            if (config.type == AlertType.SENSOR_EXPIRY) {
                putStringSet(
                    keyExpiryWarnings(config.type),
                    sanitizeExpiryWarningMinutes(config.expiryWarningMinutes).map { it.toString() }.toSet()
                )
            }
        }
    }
    
    private fun syncNativeAlarmSettings(config: AlertConfig) {
        val typeId = config.type.id

        // Compose alert settings must not silently toggle hidden legacy-only alert types
        // like AVAILABLE(2) / AMOUNT(3).
        if (typeId == AlertType.LOW.id || typeId == AlertType.HIGH.id || typeId == AlertType.LOSS.id) {
            Natives.setAlertConfig(
                typeId,
                config.threshold ?: 0f,
                config.enabled
            )

            // Sync extra settings (DND, duration, sound) which are still per-ID in Natives
            Natives.setalarmdisturb(typeId, config.overrideDND)

            if (config.type == AlertType.LOSS) {
                writeNativeLossWaitMinutes(config.durationMinutes ?: AlertDefaults.MISSED_READING_MINUTES)
            }
            Natives.writealarmduration(typeId, sanitizeAlertDurationSeconds(config.alarmDurationSeconds))
            
            Natives.writering(
                typeId,
                config.customSoundUri ?: "",
                config.soundEnabled,
                config.flashEnabled,
                config.vibrationEnabled
            );
            if (config.type == AlertType.LOSS && config.enabled) {
                SuperGattCallback.glucosealarms?.setLossAlarm()
            }
        } else if (typeId == AlertType.VERY_LOW.id || typeId == AlertType.VERY_HIGH.id ||
            typeId == AlertType.PRE_LOW.id || typeId == AlertType.PRE_HIGH.id) {
            Natives.setAlertConfig(
                typeId,
                config.threshold ?: 0f,
                config.enabled
            )

            Natives.setalarmdisturb(typeId, config.overrideDND)
            Natives.writealarmduration(typeId, sanitizeAlertDurationSeconds(config.alarmDurationSeconds))
            Natives.writering(
                typeId,
                config.customSoundUri ?: "",
                config.soundEnabled,
                config.flashEnabled,
                config.vibrationEnabled
            );
        }
    }
    
    /**
     * Sync Low/High/Loss alarms directly from the config being saved.
     */

    
    /**
     * Load all alert configurations.
     */
    fun loadAllConfigs(): List<AlertConfig> {
        ensureHiddenLegacyAlertCleanup()
        return AlertType.settingsEntries.map { loadConfig(it) }
    }
    
    /**
     * Check if any critical alerts are enabled (for UI indicators).
     */
    fun hasAnyCriticalAlertEnabled(): Boolean {
        return loadConfig(AlertType.LOW).enabled ||
               loadConfig(AlertType.VERY_LOW).enabled ||
               loadConfig(AlertType.MISSED_READING).enabled
    }

    private fun ensureDefaultThresholdMigration() {
        if (defaultThresholdMigrationDone) {
            return
        }
        synchronized(this) {
            if (defaultThresholdMigrationDone) {
                return
            }
            if (prefs.getBoolean(DEFAULT_THRESHOLD_MIGRATION_KEY, false)) {
                defaultThresholdMigrationDone = true
                return
            }

            val isMmol = Applic.unit == 1
            migrateLegacyThresholdIfUnchanged(
                type = AlertType.LOW,
                currentThreshold = Natives.alarmlow(),
                enabled = Natives.hasalarmlow(),
                oldDefault = if (isMmol) AlertDefaults.LEGACY_LOW_THRESHOLD_MMOL else AlertDefaults.LEGACY_LOW_THRESHOLD_MGDL,
                newDefault = if (isMmol) AlertDefaults.LOW_THRESHOLD_MMOL else AlertDefaults.LOW_THRESHOLD_MGDL,
                isMmol = isMmol
            )
            migrateLegacyThresholdIfUnchanged(
                type = AlertType.HIGH,
                currentThreshold = Natives.alarmhigh(),
                enabled = Natives.hasalarmhigh(),
                oldDefault = if (isMmol) AlertDefaults.LEGACY_HIGH_THRESHOLD_MMOL else AlertDefaults.LEGACY_HIGH_THRESHOLD_MGDL,
                newDefault = if (isMmol) AlertDefaults.HIGH_THRESHOLD_MMOL else AlertDefaults.HIGH_THRESHOLD_MGDL,
                isMmol = isMmol
            )
            migrateLegacyThresholdIfUnchanged(
                type = AlertType.VERY_LOW,
                currentThreshold = Natives.alarmverylow(),
                enabled = Natives.hasalarmverylow(),
                oldDefault = if (isMmol) AlertDefaults.LEGACY_VERY_LOW_THRESHOLD_MMOL else AlertDefaults.LEGACY_VERY_LOW_THRESHOLD_MGDL,
                newDefault = if (isMmol) AlertDefaults.VERY_LOW_THRESHOLD_MMOL else AlertDefaults.VERY_LOW_THRESHOLD_MGDL,
                isMmol = isMmol
            )
            migrateLegacyThresholdIfUnchanged(
                type = AlertType.VERY_HIGH,
                currentThreshold = Natives.alarmveryhigh(),
                enabled = Natives.hasalarmveryhigh(),
                oldDefault = if (isMmol) AlertDefaults.LEGACY_VERY_HIGH_THRESHOLD_MMOL else AlertDefaults.LEGACY_VERY_HIGH_THRESHOLD_MGDL,
                newDefault = if (isMmol) AlertDefaults.VERY_HIGH_THRESHOLD_MMOL else AlertDefaults.VERY_HIGH_THRESHOLD_MGDL,
                isMmol = isMmol
            )
            migrateLegacyThresholdIfUnchanged(
                type = AlertType.PRE_LOW,
                currentThreshold = Natives.alarmprelow(),
                enabled = Natives.hasalarmprelow(),
                oldDefault = if (isMmol) AlertDefaults.LEGACY_FORECAST_LOW_THRESHOLD_MMOL else AlertDefaults.LEGACY_FORECAST_LOW_THRESHOLD_MGDL,
                newDefault = if (isMmol) AlertDefaults.FORECAST_LOW_THRESHOLD_MMOL else AlertDefaults.FORECAST_LOW_THRESHOLD_MGDL,
                isMmol = isMmol
            )
            migrateLegacyThresholdIfUnchanged(
                type = AlertType.PRE_HIGH,
                currentThreshold = Natives.alarmprehigh(),
                enabled = Natives.hasalarmprehigh(),
                oldDefault = if (isMmol) AlertDefaults.LEGACY_HIGH_THRESHOLD_MMOL else AlertDefaults.LEGACY_HIGH_THRESHOLD_MGDL,
                newDefault = if (isMmol) AlertDefaults.FORECAST_HIGH_THRESHOLD_MMOL else AlertDefaults.FORECAST_HIGH_THRESHOLD_MGDL,
                isMmol = isMmol
            )

            prefs.edit {
                putBoolean(DEFAULT_THRESHOLD_MIGRATION_KEY, true)
            }
            defaultThresholdMigrationDone = true
        }
    }

    private fun migrateLegacyThresholdIfUnchanged(
        type: AlertType,
        currentThreshold: Float,
        enabled: Boolean,
        oldDefault: Float,
        newDefault: Float,
        isMmol: Boolean
    ) {
        if (prefs.contains(keyThreshold(type)) || !approximatelyEquals(currentThreshold, oldDefault)) {
            return
        }

        val migrated = applyLegacyOverrides(
            type,
            AlertDefaults.defaultConfig(type, isMmol).copy(
                enabled = enabled,
                threshold = newDefault
            )
        )
        saveToPrefs(migrated)
        syncNativeAlarmSettings(migrated)
    }

    private fun approximatelyEquals(first: Float, second: Float): Boolean {
        return kotlin.math.abs(first - second) < 0.11f
    }

    private fun ensureHiddenLegacyAlertCleanup() {
        if (hiddenLegacyAlertCleanupDone) {
            return
        }
        synchronized(this) {
            if (hiddenLegacyAlertCleanupDone) {
                return
            }
            val hiddenPrefixes = listOf(
                "alert_${AlertType.AVAILABLE.id}_",
                "alert_${AlertType.AMOUNT.id}_"
            )
            val hiddenKeys = prefs.all.keys.filter { key ->
                hiddenPrefixes.any { prefix -> key.startsWith(prefix) }
            }
            if (hiddenKeys.isNotEmpty()) {
                prefs.edit {
                    hiddenKeys.forEach(::remove)
                }
            }
            // Compose should never have been the owner of the legacy "value available" alert.
            // Force-disable the old native flag on every startup so later alarm saves can't
            // silently resurrect it.
            Natives.setAlertConfig(AlertType.AVAILABLE.id, 0f, false)
            hiddenLegacyAlertCleanupDone = true
        }
    }
}
