package tk.glucodata.alerts

internal data class StandardGlucoseAlertCondition(
    val glucoseValue: Float,
    val evaluatedValue: Float,
    val threshold: Float
)

internal object StandardGlucoseAlertEvaluator {
    fun resolveActive(
        glucoseValue: Float,
        rate: Float,
        configs: Map<AlertType, AlertConfig>,
        alertTypes: Iterable<AlertType>,
        isMmol: Boolean,
        isConfigActive: (AlertConfig) -> Boolean = { it.isActiveNow() },
        wasConditionActive: (AlertType) -> Boolean = { false },
        forecastRateTrusted: Boolean = true
    ): Map<AlertType, StandardGlucoseAlertCondition> {
        if (!glucoseValue.isFinite()) {
            return emptyMap()
        }

        return alertTypes.mapNotNull { type ->
            // A degraded rate estimator is harmless for threshold alerts (they
            // measure the actual value) but a forecast scales it over the whole
            // horizon. Without a trusted rate the forecast types stay silent
            // rather than guess.
            if (isForecastAlert(type) && !forecastRateTrusted) return@mapNotNull null
            val config = configs[type] ?: return@mapNotNull null
            if (!config.enabled) return@mapNotNull null
            if (!isConfigActive(config)) return@mapNotNull null
            val threshold = config.threshold?.takeIf { it.isFinite() && it > 0f } ?: return@mapNotNull null
            val value = if (isForecastAlert(type)) {
                AlertGlucoseMath.projectedDisplayValue(
                    glucoseValue = glucoseValue,
                    rateMgdlPerMinute = rate,
                    forecastMinutes = config.forecastMinutes,
                    isMmol = isMmol
                )
            } else {
                glucoseValue
            }
            if (!value.isFinite()) return@mapNotNull null

            val conditionActive = if (isForecastAlert(type)) {
                ForecastThresholdPolicy.isActive(
                    type = type,
                    currentValue = glucoseValue,
                    projectedValue = value,
                    threshold = threshold,
                    wasActive = wasConditionActive(type),
                    isMmol = isMmol,
                    rate = rate,
                    rearmMargin = config.rearmMargin,
                    forecastMinutes = config.forecastMinutes
                )
            } else {
                isThresholdConditionActive(
                    type = type,
                    value = value,
                    threshold = threshold,
                    wasActive = wasConditionActive(type),
                    rearmMargin = config.rearmMargin,
                    isMmol = isMmol
                )
            }

            if (conditionActive) {
                type to StandardGlucoseAlertCondition(glucoseValue, value, threshold)
            } else {
                null
            }
        }.toMap()
    }

    private fun isForecastAlert(type: AlertType): Boolean {
        return type == AlertType.PRE_LOW || type == AlertType.PRE_HIGH
    }

    /**
     * Entry stays strict (value must actually cross the threshold), but an
     * already-active episode survives until the value recovers past the rearm
     * margin. Without it a single reading landing exactly ON the threshold
     * ended the episode and consumed the dismiss: a trace shows VERY_HIGH at
     * threshold 280 firing twice with the value never below 280 - one sample
     * AT 280 (strictly-greater = false) ended the episode, and 281 was a
     * regular re-entry. Edge case, documented on purpose: at exactly the
     * threshold an active episode stays ACTIVE, it does not end.
     */
    private fun isThresholdConditionActive(
        type: AlertType,
        value: Float,
        threshold: Float,
        wasActive: Boolean,
        rearmMargin: Float?,
        isMmol: Boolean
    ): Boolean {
        val margin = if (wasActive) {
            (rearmMargin ?: defaultThresholdRearmMargin(isMmol)).coerceAtLeast(0f)
        } else {
            0f
        }
        return when (type) {
            AlertType.LOW,
            AlertType.VERY_LOW,
            AlertType.PRE_LOW -> value < threshold + margin
            AlertType.HIGH,
            AlertType.VERY_HIGH,
            AlertType.PRE_HIGH -> value > threshold - margin
            else -> false
        }
    }

    internal fun defaultThresholdRearmMargin(isMmol: Boolean): Float =
        if (isMmol) AlertDefaults.THRESHOLD_REARM_MARGIN_MMOL else AlertDefaults.THRESHOLD_REARM_MARGIN_MGDL
}

internal object ForecastThresholdPolicy {
    // The pre-margin-rework values, kept only for the explicit margin <= 0
    // opt-out that restores the old behaviour.
    private const val LEGACY_REARM_MARGIN_MMOL = 0.2f
    private const val LEGACY_REARM_MARGIN_MGDL = 4.0f

    fun defaultRearmMargin(isMmol: Boolean): Float =
        if (isMmol) AlertDefaults.FORECAST_REARM_MARGIN_MMOL else AlertDefaults.FORECAST_REARM_MARGIN_MGDL

    /**
     * A forecast episode ends when the prediction is RESOLVED - fulfilled (the
     * value crossed the threshold; HIGH/LOW take over as their own alert
     * family), refuted ([isFalsified]), or off the table (the measured value
     * recovered past the rearm margin). While the value sits undecided below
     * the threshold the prediction is neither confirmed nor refuted, and
     * repeating it carries no new information.
     *
     * Survival therefore hangs on the MEASURED value. A forecast alert fires
     * by construction while the measured value is still on the safe side of
     * the threshold, so the old `currentValue > threshold - 4` survival term
     * was false in practice and the episode hung entirely on the projection -
     * the most volatile number in the system (a 1 mg/dl/min rate change moves
     * a 30-minute projection by 30 mg/dl, against which a 4 mg/dl margin does
     * nothing). Projection jitter alone must neither end nor re-open an
     * episode.
     */
    fun isActive(
        type: AlertType,
        currentValue: Float,
        projectedValue: Float,
        threshold: Float,
        wasActive: Boolean,
        isMmol: Boolean,
        rate: Float = Float.NaN,
        rearmMargin: Float? = null,
        forecastMinutes: Int? = null
    ): Boolean {
        val margin = rearmMargin ?: defaultRearmMargin(isMmol)
        if (margin <= 0f) {
            return legacyIsActive(type, currentValue, projectedValue, threshold, wasActive, isMmol)
        }
        // Entry conditions are deliberately untouched.
        return when (type) {
            AlertType.PRE_LOW -> if (wasActive) {
                !isFalsified(type, projectedValue, threshold, rate, margin, isMmol, forecastMinutes) &&
                    currentValue < threshold + margin
            } else {
                currentValue >= threshold && projectedValue < threshold
            }

            AlertType.PRE_HIGH -> if (wasActive) {
                !isFalsified(type, projectedValue, threshold, rate, margin, isMmol, forecastMinutes) &&
                    currentValue > threshold - margin
            } else {
                currentValue <= threshold && projectedValue > threshold
            }

            else -> false
        }
    }

    /**
     * The prediction is refuted: the rate has flipped direction STRONGLY (its
     * magnitude alone would move the value by a full margin within one
     * horizon - a fluctuating rate around zero never qualifies) AND the
     * projection is clear of the threshold by the full margin. A "forecast
     * high" episode must not keep retrying while the curve falls steeply away
     * from the threshold it once predicted to cross - but a wobble in the rate
     * is not a refutation, or the episode would end and re-fire on every
     * fluctuation, which is the exact bug the margin exists to fix.
     */
    fun isFalsified(
        type: AlertType,
        projectedValue: Float,
        threshold: Float,
        rate: Float,
        margin: Float,
        isMmol: Boolean,
        forecastMinutes: Int? = null
    ): Boolean {
        if (!rate.isFinite() || !projectedValue.isFinite() || margin <= 0f) {
            return false
        }
        val horizon = AlertGlucoseMath.normalizedForecastMinutes(forecastMinutes)
        // rate is mg/dl/min; compare against the margin expressed in mg/dl.
        val marginMgdl = if (isMmol) margin * 18.0182f else margin
        val strongRate = marginMgdl / horizon
        return when (type) {
            AlertType.PRE_LOW -> rate >= strongRate && projectedValue >= threshold + margin
            AlertType.PRE_HIGH -> rate <= -strongRate && projectedValue <= threshold - margin
            else -> false
        }
    }

    private fun legacyIsActive(
        type: AlertType,
        currentValue: Float,
        projectedValue: Float,
        threshold: Float,
        wasActive: Boolean,
        isMmol: Boolean
    ): Boolean {
        val margin = if (isMmol) LEGACY_REARM_MARGIN_MMOL else LEGACY_REARM_MARGIN_MGDL
        return when (type) {
            AlertType.PRE_LOW -> if (wasActive) {
                currentValue < threshold + margin || projectedValue < threshold + margin
            } else {
                currentValue >= threshold && projectedValue < threshold
            }

            AlertType.PRE_HIGH -> if (wasActive) {
                currentValue > threshold - margin || projectedValue > threshold - margin
            } else {
                currentValue <= threshold && projectedValue > threshold
            }

            else -> false
        }
    }
}
