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
                    isMmol = isMmol
                )
            } else {
                isThresholdConditionActive(type, value, threshold)
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

    private fun isThresholdConditionActive(type: AlertType, value: Float, threshold: Float): Boolean {
        return when (type) {
            AlertType.LOW,
            AlertType.VERY_LOW,
            AlertType.PRE_LOW -> value < threshold
            AlertType.HIGH,
            AlertType.VERY_HIGH,
            AlertType.PRE_HIGH -> value > threshold
            else -> false
        }
    }
}

internal object ForecastThresholdPolicy {
    private const val REARM_MARGIN_MMOL = 0.2f
    private const val REARM_MARGIN_MGDL = 4.0f

    fun isActive(
        type: AlertType,
        currentValue: Float,
        projectedValue: Float,
        threshold: Float,
        wasActive: Boolean,
        isMmol: Boolean
    ): Boolean {
        val margin = if (isMmol) REARM_MARGIN_MMOL else REARM_MARGIN_MGDL
        // Once entered, keep one forecast episode alive through threshold crossing
        // and minor projection jitter; rearm only after meaningful recovery.
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
