package tk.glucodata.alerts

import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.SuperGattCallback

data class AlertRuntimeEvaluation(
    val standardGlucoseAlertHandled: Boolean = false,
    val standardGlucoseAlertStarted: Boolean = false
)

object AlertRuntimeManager {
    private const val LOG_ID = "AlertRuntimeManager"
    private const val CHECK_INTERVAL_MS = 15_000L
    private const val SENSOR_EXPIRY_WARNING_MS = 24L * 60L * 60L * 1000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private var monitorTask: ScheduledFuture<*>? = null

    private var lastReadingTimeMs: Long = 0L
    private var lastDeliveredReadingTimeMs: Long = 0L
    private var lastGlucoseValue: Float = Float.NaN
    private var lastRate: Float = Float.NaN
    private var lastDisplaySnapshot: CurrentDisplaySource.Snapshot? = null
    private var persistentHighStartedAtMs: Long = 0L
    private val sustainedLowStartedAtMs = mutableMapOf<AlertType, Long>()
    private val sustainedLowEligible = EnumSet.of(AlertType.LOW, AlertType.VERY_LOW)
    private val standardEpisodes = AlertEpisodeState<AlertType>()
    private val sensorExpiryState = SensorExpiryAlertState()
    private val calibrationReadingBarrier = ReadingTimestampBarrier()

    private val standardGlucoseAlertTypes = listOf(
        AlertType.VERY_LOW,
        AlertType.LOW,
        AlertType.VERY_HIGH,
        AlertType.HIGH,
        AlertType.PRE_LOW,
        AlertType.PRE_HIGH
    )

    fun onAlertSnoozed(type: AlertType) {
        if (type !in standardGlucoseAlertTypes) {
            return
        }
        synchronized(lock) {
            // Keep an already-active threshold episode eligible to re-fire when snooze expires.
            standardEpisodes.markPendingDelivery(type)
        }
    }

    /**
     * Calibration can change the dashboard value without producing a new sensor sample.
     * Keep the existing threshold episode untouched until a newer reading arrives.
     */
    fun onDisplayCalibrationChanged() {
        val currentReadingTimeMs = try {
            CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout)?.timeMillis ?: 0L
        } catch (t: Throwable) {
            0L
        }
        synchronized(lock) {
            val suppressThroughMs = maxOf(lastReadingTimeMs, currentReadingTimeMs)
            calibrationReadingBarrier.suppressThrough(suppressThroughMs)
            if (suppressThroughMs > 0L) {
                Log.i(LOG_ID, "Calibration changed; glucose alerts wait for reading after $suppressThroughMs")
            }
        }
    }

    fun ensureMonitoring() {
        synchronized(lock) {
            bootstrapLastReadingLocked()
            ensureTaskLocked()
            evaluateLocked(System.currentTimeMillis())
        }
    }

    fun onNewReading(glucoseValue: Float, rate: Float, readingTimeMs: Long): AlertRuntimeEvaluation {
        return onNewReading(null, glucoseValue, rate, readingTimeMs, 0)
    }

    @JvmOverloads
    fun onNewReading(
        sensorId: String?,
        glucoseValue: Float,
        rate: Float,
        readingTimeMs: Long,
        sensorGen: Int = 0
    ): AlertRuntimeEvaluation {
        val snapshot = try {
            CurrentDisplaySource.resolveIncomingReading(
                liveNumericValue = glucoseValue,
                rate = rate,
                targetTimeMillis = readingTimeMs,
                preferredSensorId = sensorId,
                sensorGen = sensorGen
            )
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "resolveIncomingReading", t)
            null
        }
        synchronized(lock) {
            lastReadingTimeMs = maxOf(lastReadingTimeMs, readingTimeMs)
            if (snapshot != null && snapshot.primaryValue.isFinite()) {
                lastGlucoseValue = snapshot.primaryValue
                lastRate = snapshot.rate
                lastDisplaySnapshot = snapshot
            } else {
                lastGlucoseValue = Float.NaN
                lastRate = Float.NaN
                lastDisplaySnapshot = null
                lastDeliveredReadingTimeMs = maxOf(lastDeliveredReadingTimeMs, readingTimeMs)
                ensureTaskLocked()
                return AlertRuntimeEvaluation()
            }
            lastDeliveredReadingTimeMs = maxOf(lastDeliveredReadingTimeMs, readingTimeMs)
            ensureTaskLocked()
            return evaluateLocked(readingTimeMs)
        }
    }

    private fun ensureTaskLocked() {
        if (monitorTask == null || monitorTask?.isCancelled == true) {
            monitorTask = scheduler.scheduleAtFixedRate(
                {
                    synchronized(lock) {
                        evaluateLocked(System.currentTimeMillis())
                    }
                },
                CHECK_INTERVAL_MS,
                CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun evaluateLocked(nowMs: Long): AlertRuntimeEvaluation {
        bootstrapLastReadingLocked()
        syncCurrentReadingLocked()

        val glucoseAlertsBlocked = calibrationReadingBarrier.blocks(lastReadingTimeMs)
        val standardAlertEvaluation = if (glucoseAlertsBlocked) {
            AlertRuntimeEvaluation()
        } else {
            evaluateStandardGlucoseAlertsLocked(nowMs)
        }
        evaluateMissedReadingLocked(nowMs)
        if (!glucoseAlertsBlocked) {
            evaluatePersistentHighLocked(nowMs)
        }
        evaluateSensorExpiryLocked(nowMs)
        return standardAlertEvaluation
    }

    private fun syncCurrentReadingLocked() {
        val latest = try {
            CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout)
        } catch (t: Throwable) {
            null
        } ?: return

        if (latest.timeMillis <= 0L || !latest.primaryValue.isFinite()) {
            return
        }
        if (latest.timeMillis >= lastReadingTimeMs || !lastGlucoseValue.isFinite()) {
            lastReadingTimeMs = latest.timeMillis
            lastGlucoseValue = latest.primaryValue
            lastRate = latest.rate
            lastDisplaySnapshot = latest
        }

        if (latest.timeMillis <= lastDeliveredReadingTimeMs) {
            return
        }

        lastDeliveredReadingTimeMs = latest.timeMillis
        if (latest.source == "callback") {
            return
        }

        try {
            SuperGattCallback.processExternalCurrentReading(
                latest.sensorId,
                latest.primaryValue,
                latest.rate,
                latest.timeMillis,
                latest.sensorGen
            )
            Log.i(LOG_ID, "Processed external reading source=${latest.source} time=${latest.timeMillis}")
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "syncCurrentReadingLocked", t)
        }
    }

    private fun evaluateStandardGlucoseAlertsLocked(nowMs: Long): AlertRuntimeEvaluation {
        val glucoseValue = currentGlucoseValueLocked() ?: return AlertRuntimeEvaluation()
        val rate = currentRateLocked()
        val configs = standardGlucoseAlertTypes.associateWith { AlertRepository.loadConfig(it) }
        val activeConditions = resolveActiveStandardGlucoseAlerts(glucoseValue, rate, configs)
        val activeTypes = activeConditions.keys
        val transition = standardEpisodes.update(activeTypes)

        transition.cleared.forEach { type ->
            clearRuntimeAlert(type, "standard-condition-cleared")
        }

        updateSustainedLowTimersLocked(activeTypes, configs, nowMs)

        val type = standardGlucoseAlertTypes.firstOrNull { it in activeTypes }
            ?: return AlertRuntimeEvaluation()
        val condition = activeConditions[type] ?: return AlertRuntimeEvaluation()

        if (!transition.shouldTryFire(type)) {
            return AlertRuntimeEvaluation(standardGlucoseAlertHandled = true)
        }

        if (!isSustainedLowSatisfiedLocked(type, configs[type], nowMs)) {
            standardEpisodes.markPendingDelivery(type)
            return AlertRuntimeEvaluation(standardGlucoseAlertHandled = true)
        }

        logStandardCondition(type, condition, rate)

        if (SnoozeManager.isSnoozed(type)) {
            standardEpisodes.markPendingDelivery(type)
            return AlertRuntimeEvaluation()
        }

        val message = buildStandardAlertMessage(type, condition, configs[type])
        val triggered = triggerAlert(type, condition.glucoseValue, rate, message)
        if (triggered) {
            standardEpisodes.clearPending(type)
        } else if (AlertStateTracker.isWaitingForRearmCooldown(type)) {
            // A threshold entry during the short rearm cooldown must remain eligible;
            // otherwise the alert is lost until glucose first returns to normal.
            standardEpisodes.markPendingDelivery(type)
        } else {
            standardEpisodes.clearPending(type)
        }
        return AlertRuntimeEvaluation(
            standardGlucoseAlertHandled = true,
            standardGlucoseAlertStarted = triggered
        )
    }

    private fun updateSustainedLowTimersLocked(
        activeTypes: Set<AlertType>,
        configs: Map<AlertType, AlertConfig>,
        nowMs: Long
    ) {
        activeTypes.filter { it in sustainedLowEligible }.forEach { type ->
            val cfg = configs[type]
            val sustainedMs = (cfg?.durationMinutes ?: 0) * 60_000L
            if (sustainedMs > 0L && sustainedLowStartedAtMs[type] == null) {
                sustainedLowStartedAtMs[type] = lastReadingTimeMs.takeIf { it > 0L } ?: nowMs
            }
        }
        sustainedLowEligible.forEach { type ->
            if (type !in activeTypes) {
                sustainedLowStartedAtMs.remove(type)
            }
        }
    }

    private fun isSustainedLowSatisfiedLocked(type: AlertType, config: AlertConfig?, nowMs: Long): Boolean {
        if (type !in sustainedLowEligible) return true
        val minutes = config?.durationMinutes ?: return true
        if (minutes <= 0) return true
        val started = sustainedLowStartedAtMs[type] ?: return true
        return nowMs - started >= minutes * 60_000L
    }

    private fun resolveActiveStandardGlucoseAlerts(
        glucoseValue: Float,
        rate: Float,
        configs: Map<AlertType, AlertConfig>
    ): Map<AlertType, StandardGlucoseAlertCondition> {
        return StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = glucoseValue,
            rate = rate,
            configs = configs,
            alertTypes = standardGlucoseAlertTypes,
            isMmol = Applic.unit == 1,
            wasConditionActive = standardEpisodes::isActive
        )
    }

    private fun buildStandardAlertMessage(
        type: AlertType,
        condition: StandardGlucoseAlertCondition,
        config: AlertConfig?
    ): String {
        val label = Applic.app.getString(type.nameResId)
        if (type != AlertType.PRE_LOW && type != AlertType.PRE_HIGH) {
            return "$label ${Notify.glucosestr(condition.glucoseValue)}"
        }
        val horizonMinutes = AlertGlucoseMath.normalizedForecastMinutes(config?.forecastMinutes)
        val horizon = Applic.app.getString(R.string.minutes_short_format, horizonMinutes)
        return "$label: ${Notify.glucosestr(condition.evaluatedValue)} ($horizon)"
    }

    private fun logStandardCondition(
        type: AlertType,
        condition: StandardGlucoseAlertCondition,
        rate: Float
    ) {
        val snapshot = lastDisplaySnapshot
        Log.i(
            LOG_ID,
            "Standard condition active type=${type.name} primary=${condition.glucoseValue} " +
                "evaluated=${condition.evaluatedValue} threshold=${condition.threshold} " +
                "rate=$rate viewMode=${snapshot?.viewMode ?: -1} " +
                "auto=${snapshot?.autoValue ?: Float.NaN} raw=${snapshot?.rawValue ?: Float.NaN} " +
                "source=${snapshot?.source ?: "none"} sensor=${snapshot?.sensorId ?: "none"}"
        )
    }

    private fun evaluateMissedReadingLocked(nowMs: Long) {
        val type = AlertType.MISSED_READING
        val config = AlertRepository.loadConfig(type)
        val durationMs = (config.durationMinutes ?: 0) * 60_000L

        if (!config.enabled || durationMs <= 0L || lastReadingTimeMs <= 0L) {
            clearRuntimeAlert(type, "missed-reading-disabled")
            return
        }

        if (!config.isActiveNow()) {
            clearRuntimeAlert(type, "missed-reading-time-inactive")
            return
        }
        if (SnoozeManager.isSnoozed(type)) {
            return
        }

        val missed = nowMs - lastReadingTimeMs >= durationMs
        if (!missed) {
            clearRuntimeAlert(type, "new-reading-arrived")
            return
        }

        val glucoseValue = currentGlucoseValueLocked() ?: return
        val message = Applic.app.getString(R.string.alert_missed_reading) + " - " +
            Applic.app.getString(R.string.minutes_short_format, config.durationMinutes ?: 0)

        triggerAlert(type, glucoseValue, currentRateLocked(), message)
    }

    private fun evaluatePersistentHighLocked(nowMs: Long) {
        val type = AlertType.PERSISTENT_HIGH
        val config = AlertRepository.loadConfig(type)
        val threshold = config.threshold
        val durationMs = (config.durationMinutes ?: 0) * 60_000L
        val glucoseValue = currentGlucoseValueLocked()

        if (!config.enabled || threshold == null || durationMs <= 0L || glucoseValue == null) {
            persistentHighStartedAtMs = 0L
            clearRuntimeAlert(type, "persistent-high-disabled")
            return
        }

        if (glucoseValue <= threshold) {
            persistentHighStartedAtMs = 0L
            clearRuntimeAlert(type, "persistent-high-cleared")
            return
        }

        if (persistentHighStartedAtMs == 0L) {
            persistentHighStartedAtMs = lastReadingTimeMs.takeIf { it > 0L } ?: nowMs
        }

        if (!config.isActiveNow()) {
            persistentHighStartedAtMs = 0L
            clearRuntimeAlert(type, "persistent-high-time-inactive")
            return
        }
        if (SnoozeManager.isSnoozed(type)) {
            return
        }

        if (nowMs - persistentHighStartedAtMs < durationMs) {
            return
        }

        val message = Applic.app.getString(R.string.alert_persistent_high) + " " + Notify.glucosestr(glucoseValue)
        triggerAlert(type, glucoseValue, currentRateLocked(), message)
    }

    private fun evaluateSensorExpiryLocked(nowMs: Long) {
        val type = AlertType.SENSOR_EXPIRY
        val config = AlertRepository.loadConfig(type)
        if (!config.enabled) {
            sensorExpiryState.reset()
            clearRuntimeAlert(type, "sensor-expiry-disabled")
            return
        }

        val endTimeMs = try {
            Natives.getendtime()
        } catch (t: Throwable) {
            0L
        }

        val activeNow = config.isActiveNow()
        val snoozed = SnoozeManager.isSnoozed(type)
        val shouldTrigger = sensorExpiryState.shouldTrigger(
            enabled = true,
            activeNow = activeNow,
            snoozed = snoozed,
            endTimeMs = endTimeMs,
            nowMs = nowMs,
            warningMs = SENSOR_EXPIRY_WARNING_MS
        )

        if (endTimeMs <= 0L || endTimeMs - nowMs > SENSOR_EXPIRY_WARNING_MS) {
            clearRuntimeAlert(type, "sensor-expiry-not-due")
            return
        }

        if (!activeNow) {
            clearRuntimeAlert(type, "sensor-expiry-time-inactive")
            return
        }
        if (snoozed || !shouldTrigger) {
            return
        }

        val glucoseValue = currentGlucoseValueLocked() ?: return
        val remainingHours = ((endTimeMs - nowMs).coerceAtLeast(0L) / 3_600_000L).toInt().coerceAtLeast(1)
        val message = Applic.app.getString(R.string.alert_sensor_expiry) + " - " +
            Applic.app.getString(R.string.hours_short, remainingHours)

        triggerAlert(type, glucoseValue, currentRateLocked(), message)
    }

    private fun triggerAlert(type: AlertType, glucoseValue: Float, rate: Float, message: String): Boolean {
        try {
            val triggered = Notify.triggerSupplementalGlucoseAlert(type.id, glucoseValue, rate, message)
            if (triggered) {
                Log.i(LOG_ID, "Triggered ${type.name}: $message")
            }
            return triggered
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "triggerAlert ${type.name}", t)
            return false
        }
    }

    private fun clearRuntimeAlert(type: AlertType, reason: String) {
        AlertStateTracker.resetState(type)
        Notify.cancelRetrySession(type.id, reason)
    }

    private fun bootstrapLastReadingLocked() {
        if (lastReadingTimeMs > 0L && lastGlucoseValue.isFinite() && lastDisplaySnapshot != null) {
            return
        }
        val latest = try {
            CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout)
        } catch (t: Throwable) {
            null
        } ?: return

        if (lastReadingTimeMs <= 0L) {
            lastReadingTimeMs = latest.timeMillis
        }
        if (lastDeliveredReadingTimeMs <= 0L) {
            lastDeliveredReadingTimeMs = latest.timeMillis
        }
        if (!lastGlucoseValue.isFinite()) {
            lastGlucoseValue = latest.primaryValue
        }
        if (!lastRate.isFinite()) {
            lastRate = latest.rate
        }
        lastDisplaySnapshot = latest
    }

    private fun currentGlucoseValueLocked(): Float? {
        val snapshot = lastDisplaySnapshot
        if (snapshot != null && snapshot.primaryValue.isFinite()) {
            return snapshot.primaryValue
        }
        bootstrapLastReadingLocked()
        return lastDisplaySnapshot?.primaryValue?.takeIf { it.isFinite() }
    }

    private fun currentRateLocked(): Float {
        val snapshot = lastDisplaySnapshot
        if (snapshot != null && snapshot.rate.isFinite()) {
            return snapshot.rate
        }
        bootstrapLastReadingLocked()
        return lastDisplaySnapshot?.rate?.takeIf { it.isFinite() } ?: Float.NaN
    }
}
