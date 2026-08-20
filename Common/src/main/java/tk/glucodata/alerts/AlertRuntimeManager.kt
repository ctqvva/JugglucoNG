package tk.glucodata.alerts

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.GlucoseDelta
import tk.glucodata.Log
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

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private var monitorTask: ScheduledFuture<*>? = null

    private var lastReadingTimeMs: Long = 0L
    private var lastDeliveredReadingTimeMs: Long = 0L
    private var lastGlucoseValue: Float = Float.NaN
    private var lastRate: Float = Float.NaN
    private var lastDisplaySnapshot: CurrentDisplaySource.Snapshot? = null
    private var persistentHighStartedAtMs: Long = 0L
    private var lastLoggedExpiryEndMs: Long = Long.MIN_VALUE
    private var warnedForecastRateUntrusted = false
    private var preHighIobSuppressed = false
    private val standardEpisodes = AlertEpisodeState<AlertType>()
    private val sensorExpiryState = SensorExpiryAlertState(AlertRepository.sensorExpiryWarnedStore)
    private val fallingDeltaState = DeltaAlarmState(falling = true)
    private val risingDeltaState = DeltaAlarmState(falling = false)
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
            // A recalibration shifts the displayed value without a real new sample; drop the delta
            // baseline so the jump can't be mistaken for a steep fall/rise.
            fallingDeltaState.resetBaseline()
            risingDeltaState.resetBaseline()
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
            SensorHandoverRuntime.onReading(sensorId)
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
            evaluateStandardGlucoseAlertsLocked()
        }
        evaluateMissedReadingLocked(nowMs)
        if (!glucoseAlertsBlocked) {
            evaluatePersistentHighLocked(nowMs)
            evaluateDeltaAlarmsLocked()
        }
        evaluateSensorExpiryLocked(nowMs)
        SensorHandoverRuntime.evaluate(nowMs)
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

    private fun evaluateStandardGlucoseAlertsLocked(): AlertRuntimeEvaluation {
        val glucoseValue = currentGlucoseValueLocked() ?: return AlertRuntimeEvaluation()
        val rate = currentRateLocked()
        val configs = standardGlucoseAlertTypes.associateWith { AlertRepository.loadConfig(it) }
        val activeConditions = suppressIobCoveredPreHigh(
            resolveActiveStandardGlucoseAlerts(glucoseValue, rate, configs),
            configs
        )
        val activeTypes = activeConditions.keys
        val transition = standardEpisodes.update(activeTypes)

        transition.cleared.forEach { type ->
            clearRuntimeAlert(type, standardClearReason(type, configs[type], glucoseValue, rate))
        }

        val type = standardGlucoseAlertTypes.firstOrNull { it in activeTypes }
            ?: return AlertRuntimeEvaluation()
        val condition = activeConditions[type] ?: return AlertRuntimeEvaluation()

        if (!transition.shouldTryFire(type)) {
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

    private fun resolveActiveStandardGlucoseAlerts(
        glucoseValue: Float,
        rate: Float,
        configs: Map<AlertType, AlertConfig>
    ): Map<AlertType, StandardGlucoseAlertCondition> {
        val forecastRateTrusted = tk.glucodata.TrendAccess.hasProvider()
        if (!forecastRateTrusted && !warnedForecastRateUntrusted) {
            // TrendAccess already logs each fallback use; this line records the
            // consequence once per gap: forecasts are withheld, not guessed.
            Log.e(LOG_ID, "No TrendVelocityProvider registered - forecast alerts suppressed")
            warnedForecastRateUntrusted = true
        } else if (forecastRateTrusted) {
            warnedForecastRateUntrusted = false
        }
        return StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = glucoseValue,
            rate = rate,
            configs = configs,
            alertTypes = standardGlucoseAlertTypes,
            isMmol = Applic.unit == 1,
            wasConditionActive = standardEpisodes::isActive,
            forecastRateTrusted = forecastRateTrusted
        )
    }

    /**
     * PRE_HIGH ONLY: drop the condition while the remaining insulin on board
     * covers the projected overshoot - what the alert would announce is
     * already treated. Never applied to PRE_LOW, where insulin makes the
     * predicted low MORE likely (see [ForecastIobCoverage]). Without a
     * user-maintained sensitivity the check never engages: suppressing an
     * alarm on a default ISF the user never confirmed would guess with their
     * safety. On variants without the journal the IOB snapshot is null and
     * nothing changes.
     */
    private fun suppressIobCoveredPreHigh(
        conditions: Map<AlertType, StandardGlucoseAlertCondition>,
        configs: Map<AlertType, AlertConfig>
    ): Map<AlertType, StandardGlucoseAlertCondition> {
        val condition = conditions[AlertType.PRE_HIGH] ?: run {
            preHighIobSuppressed = false
            return conditions
        }
        val config = configs[AlertType.PRE_HIGH]
        val factor = config?.iobCoverageFactor ?: AlertDefaults.PRE_HIGH_IOB_COVERAGE_FACTOR
        val covered = factor > 0f && try {
            val prefs = Applic.app.getSharedPreferences(
                "tk.glucodata_preferences",
                android.content.Context.MODE_PRIVATE
            )
            val store = tk.glucodata.data.prediction.PredictionModelProfileStore
            val sensitivityMaintained =
                prefs.contains(tk.glucodata.data.prediction.PredictionModelProfileStore.INSULIN_SENSITIVITY_KEY) ||
                    prefs.contains(tk.glucodata.data.prediction.PredictionModelProfileStore.PROFILE_KEY)
            if (!sensitivityMaintained) {
                false
            } else {
                val nowMs = System.currentTimeMillis()
                val iobUnits = tk.glucodata.JournalIobAccess.snapshot(nowMs)?.getOrNull(0) ?: Float.NaN
                ForecastIobCoverage.covered(
                    projectedValue = condition.evaluatedValue,
                    threshold = condition.threshold,
                    isMmol = Applic.unit == 1,
                    iobUnits = iobUnits,
                    insulinSensitivityMgdlPerUnit = store.parametersAt(prefs, nowMs).insulinSensitivityMgDlPerUnit,
                    coverageFactor = factor
                )
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "suppressIobCoveredPreHigh", t)
            false
        }
        if (covered && !preHighIobSuppressed) {
            Log.i(
                LOG_ID,
                "PRE_HIGH suppressed: remaining IOB covers projected overshoot " +
                    "(projected=${condition.evaluatedValue} threshold=${condition.threshold} factor=$factor)"
            )
        }
        preHighIobSuppressed = covered
        return if (covered) conditions - AlertType.PRE_HIGH else conditions
    }

    /**
     * Names WHY a forecast episode ended: "forecast-falsified" when the
     * prediction was refuted (direction flipped, projection clear of the
     * threshold), "forecast-iob-covered" when the remaining insulin took over,
     * so the log distinguishes them from ordinary recovery. Same retry-cancel
     * path either way.
     */
    private fun standardClearReason(
        type: AlertType,
        config: AlertConfig?,
        glucoseValue: Float,
        rate: Float
    ): String {
        if (type != AlertType.PRE_LOW && type != AlertType.PRE_HIGH) {
            return "standard-condition-cleared"
        }
        if (type == AlertType.PRE_HIGH && preHighIobSuppressed) {
            return "forecast-iob-covered"
        }
        val threshold = config?.threshold ?: return "standard-condition-cleared"
        val isMmol = Applic.unit == 1
        val margin = config.rearmMargin ?: ForecastThresholdPolicy.defaultRearmMargin(isMmol)
        if (margin <= 0f) {
            return "standard-condition-cleared"
        }
        val projected = AlertGlucoseMath.projectedDisplayValue(
            glucoseValue = glucoseValue,
            rateMgdlPerMinute = rate,
            forecastMinutes = config.forecastMinutes,
            isMmol = isMmol
        )
        return if (
            ForecastThresholdPolicy.isFalsified(type, projected, threshold, rate, margin, isMmol, config.forecastMinutes)
        ) {
            "forecast-falsified"
        } else {
            "standard-condition-cleared"
        }
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
        if (SensorHandoverRuntime.missedReadingSuppressed(nowMs)) {
            // Post-handover window: the successor is still warming up; a short
            // data gap during the switch must not alarm as an outage.
            clearRuntimeAlert(type, "sensor-handover-window")
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

        // A steep fall is proof the correction works - suppress, cancel any
        // running retries, but do NOT reset the timer: if the value stagnates
        // above the threshold again, the high phase was continuous and the
        // alarm must not wait out a fresh full duration.
        if (PersistentHighPolicy.fallingSuppresses(currentRateLocked(), config.fallRateSuppress)) {
            clearRuntimeAlert(type, "persistent-high-falling")
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

        val endTimeMs = resolveSensorExpiryEndMs(lastDisplaySnapshot?.sensorId, nowMs)
        if (endTimeMs != lastLoggedExpiryEndMs) {
            // A sane source logs once per sensor; a bad one (0, past, or moving
            // every tick like the old Natives.getendtime()) becomes visible log
            // churn instead of silently swallowed warnings.
            Log.i(LOG_ID, "Sensor expiry end resolved: $endTimeMs (now=$nowMs)")
            lastLoggedExpiryEndMs = endTimeMs
        }
        if (endTimeMs <= 0L) {
            // No plausible end: keep the latch untouched so a transient gatt
            // dropout cannot re-baseline the episode.
            clearRuntimeAlert(type, "sensor-expiry-no-endtime")
            return
        }

        val thresholds = config.expiryWarningMinutes
        val activeNow = config.isActiveNow()
        val snoozed = SnoozeManager.isSnoozed(type)
        val triggered = sensorExpiryState.triggeredThresholds(
            enabled = true,
            activeNow = activeNow,
            snoozed = snoozed,
            endTimeMs = endTimeMs,
            nowMs = nowMs,
            thresholdsMinutes = thresholds
        )

        // Not yet within even the longest configured lead time -> nothing pending.
        val largestThresholdMs = (thresholds.maxOrNull() ?: 0).toLong() * 60_000L
        if (thresholds.isEmpty() || endTimeMs - nowMs > largestThresholdMs) {
            clearRuntimeAlert(type, "sensor-expiry-not-due")
            return
        }

        if (!activeNow) {
            clearRuntimeAlert(type, "sensor-expiry-time-inactive")
            return
        }
        if (snoozed || triggered.isEmpty()) {
            return
        }

        // The sensor ages whether or not a reading is available, and readings
        // often stop exactly when it is about to expire. Without a value the
        // alarm goes out message-only rather than waiting for a reading that may
        // never come (#98); should even that fail, the threshold stays pending
        // and is offered again on the next tick instead of counting as warned.
        val glucoseValue = currentGlucoseValueLocked()
        val message = SensorHandoverRuntime.decorateExpiryMessage(sensorExpiryMessage(triggered.first()), nowMs)

        val delivered = if (glucoseValue != null) {
            triggerAlert(type, glucoseValue, currentRateLocked(), message)
        } else {
            triggerMessageAlert(type, message)
        }
        if (delivered) {
            sensorExpiryState.confirmDelivered(triggered.first())
        }
    }

    private fun evaluateDeltaAlarmsLocked() {
        evaluateDeltaAlarmLocked(AlertType.FALLING_FAST, fallingDeltaState)
        evaluateDeltaAlarmLocked(AlertType.RISING_FAST, risingDeltaState)
    }

    private fun deltaIntervalMinutesLocked(): Int {
        return try {
            GlucoseDelta.sanitizeIntervalMinutes(
                Applic.app
                    .getSharedPreferences("tk.glucodata_preferences", android.content.Context.MODE_PRIVATE)
                    .getInt("delta_interval_minutes", GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
            )
        } catch (t: Throwable) {
            GlucoseDelta.DEFAULT_INTERVAL_MINUTES
        }
    }

    private fun evaluateDeltaAlarmLocked(type: AlertType, state: DeltaAlarmState) {
        val config = AlertRepository.loadConfig(type)
        val deltaThreshold = config.deltaThreshold
        val deltaCount = config.deltaCount
        val deltaBorder = config.deltaBorder

        if (!config.enabled || deltaThreshold == null || deltaCount == null || deltaBorder == null) {
            state.reset()
            clearRuntimeAlert(type, "delta-alarm-disabled")
            return
        }

        val glucoseValue = currentGlucoseValueLocked()
        if (glucoseValue == null) {
            // Keep the run intact; a missing sample is not a movement.
            return
        }

        val activeNow = config.isActiveNow()
        val snoozed = SnoozeManager.isSnoozed(type)
        // The state advances its run counter at interval checkpoints and only returns true while
        // active and not snoozed. The delta is measured over the alert's own window when one is
        // set, else over the global interval that drives the Δ readout; the state resets itself
        // when the effective window changes, so a run never mixes windows.
        val shouldTrigger = state.shouldTrigger(
            enabled = true,
            activeNow = activeNow,
            snoozed = snoozed,
            value = glucoseValue,
            readingTimeMs = lastReadingTimeMs,
            deltaThreshold = deltaThreshold,
            deltaCount = deltaCount,
            deltaBorder = deltaBorder,
            intervalMinutes = config.deltaIntervalMinutes ?: deltaIntervalMinutesLocked(),
            earlyTriggerEnabled = config.earlyTriggerEnabled
        )

        if (!activeNow) {
            clearRuntimeAlert(type, "delta-alarm-time-inactive")
            return
        }
        if (snoozed || !shouldTrigger) {
            return
        }

        val label = Applic.app.getString(type.nameResId)
        val message = "$label ${Notify.glucosestr(glucoseValue)}"
        if (!triggerAlert(type, glucoseValue, currentRateLocked(), message)) {
            // The latch disarmed on the offer; a failed delivery must not consume
            // the alarm. Re-armed, it fires again while the run stands and dies
            // with it if the run breaks.
            state.rearmAfterFailedDelivery()
        }
    }

    /** Notification text naming the threshold that fired ("... in 3 days" / "... in 6 hours"). */
    private fun sensorExpiryMessage(thresholdMinutes: Int): String {
        val res = Applic.app.resources
        return when {
            thresholdMinutes >= 1440 && thresholdMinutes % 1440 == 0 -> {
                val days = thresholdMinutes / 1440
                res.getQuantityString(R.plurals.sensor_expires_in_days, days, days)
            }
            else -> {
                val hours = (thresholdMinutes / 60).coerceAtLeast(1)
                res.getQuantityString(R.plurals.sensor_expires_in_hours, hours, hours)
            }
        }
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

    private fun triggerMessageAlert(type: AlertType, message: String): Boolean {
        try {
            val triggered = Notify.triggerSupplementalMessageAlert(type.id, message)
            if (triggered) {
                Log.i(LOG_ID, "Triggered ${type.name} without reading: $message")
            }
            return triggered
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "triggerMessageAlert ${type.name}", t)
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
