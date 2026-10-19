package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardGlucoseAlertEvaluatorTests {

    private val activeConfig: (AlertConfig) -> Boolean = { true }

    @Test
    fun lowAndHighConditionsOnlyActivateInTheirOwnDirection() {
        val configs = mapOf(
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 4.0f),
            AlertType.HIGH to AlertConfig(AlertType.HIGH, enabled = true, threshold = 9.0f)
        )

        val low = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 3.9f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW, AlertType.HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )
        val high = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 9.1f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW, AlertType.HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(AlertType.LOW in low)
        assertFalse(AlertType.HIGH in low)
        assertTrue(AlertType.HIGH in high)
        assertFalse(AlertType.LOW in high)
    }

    @Test
    fun exactThresholdIsNotAnActiveCondition() {
        val configs = mapOf(
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 4.0f),
            AlertType.HIGH to AlertConfig(AlertType.HIGH, enabled = true, threshold = 9.0f)
        )

        val lowBoundary = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.0f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW),
            isMmol = true,
            isConfigActive = activeConfig
        )
        val highBoundary = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 9.0f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(lowBoundary.isEmpty())
        assertTrue(highBoundary.isEmpty())
    }

    @Test
    fun forecastUsesProjectedDisplayValueWithoutReplacingPrimaryValue() {
        val configs = mapOf(
            AlertType.PRE_HIGH to AlertConfig(
                type = AlertType.PRE_HIGH,
                enabled = true,
                threshold = 9.0f,
                forecastMinutes = 20
            )
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 8.4f,
            rate = 2.0f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_HIGH),
            isMmol = true,
            isConfigActive = activeConfig
        )

        val condition = active.getValue(AlertType.PRE_HIGH)
        assertEquals(8.4f, condition.glucoseValue, 0.001f)
        assertEquals(10.62f, condition.evaluatedValue, 0.02f)
    }

    @Test
    fun forecastWithMissingRateDoesNotFire() {
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(
                type = AlertType.PRE_LOW,
                enabled = true,
                threshold = 4.0f,
                forecastMinutes = 20
            )
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.2f,
            rate = Float.NaN,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(active.isEmpty())
    }

    @Test
    fun forecastDoesNotEnterAfterCurrentValueAlreadyCrossedForecastThreshold() {
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(
                type = AlertType.PRE_LOW,
                enabled = true,
                threshold = 3.9f,
                forecastMinutes = 20
            )
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 3.85f,
            rate = -1.0f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig
        )

        assertTrue(active.isEmpty())
    }

    @Test
    fun activeForecastSurvivesBoundaryJitterUntilMeaningfulRecovery() {
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(
                type = AlertType.PRE_LOW,
                enabled = true,
                threshold = 3.9f,
                forecastMinutes = 20
            )
        )

        val jitter = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.0f,
            rate = 0.05f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig,
            wasConditionActive = { true }
        )
        // Meaningful recovery = the MEASURED value clears threshold + margin
        // (3.9 + 1.1 default). 4.2 used to end the episode under the old
        // 0.2 mmol margin; the rework keeps it alive on purpose.
        val stillUndecided = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 4.2f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig,
            wasConditionActive = { true }
        )
        val recovered = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 5.1f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW),
            isMmol = true,
            isConfigActive = activeConfig,
            wasConditionActive = { true }
        )

        assertTrue(AlertType.PRE_LOW in jitter)
        assertTrue(AlertType.PRE_LOW in stillUndecided)
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun forecastJitterDoesNotCreateASecondEpisodeEntry() {
        val episodes = AlertEpisodeState<AlertType>()
        val config = AlertConfig(
            type = AlertType.PRE_LOW,
            enabled = true,
            threshold = 3.9f,
            forecastMinutes = 20
        )

        fun evaluate(glucose: Float, rate: Float): AlertEpisodeTransition<AlertType> {
            val active = StandardGlucoseAlertEvaluator.resolveActive(
                glucoseValue = glucose,
                rate = rate,
                configs = mapOf(AlertType.PRE_LOW to config),
                alertTypes = listOf(AlertType.PRE_LOW),
                isMmol = true,
                isConfigActive = activeConfig,
                wasConditionActive = episodes::isActive
            )
            return episodes.update(active.keys)
        }

        assertTrue(evaluate(4.2f, -1.0f).shouldTryFire(AlertType.PRE_LOW))
        assertFalse(evaluate(4.0f, 0.05f).shouldTryFire(AlertType.PRE_LOW))
        // Recovery past threshold + margin (3.9 + 1.1) ends the episode ...
        assertTrue(AlertType.PRE_LOW in evaluate(5.1f, 0f).cleared)
        // ... and only then is a fresh entry a fresh episode.
        assertTrue(evaluate(4.2f, -1.0f).shouldTryFire(AlertType.PRE_LOW))
    }

    @Test
    fun highForecastUsesSymmetricSafeSideAndRecoveryRules() {
        assertFalse(
            ForecastThresholdPolicy.isActive(
                type = AlertType.PRE_HIGH,
                currentValue = 9.1f,
                projectedValue = 10.0f,
                threshold = 9.0f,
                wasActive = false,
                isMmol = true
            )
        )
        assertTrue(
            ForecastThresholdPolicy.isActive(
                type = AlertType.PRE_HIGH,
                currentValue = 8.8f,
                projectedValue = 9.3f,
                threshold = 9.0f,
                wasActive = false,
                isMmol = true
            )
        )
        // 8.7 has not recovered past threshold - margin (9.0 - 1.1 = 7.9): the
        // prediction is undecided and the episode survives.
        assertTrue(
            ForecastThresholdPolicy.isActive(
                type = AlertType.PRE_HIGH,
                currentValue = 8.7f,
                projectedValue = 8.7f,
                threshold = 9.0f,
                wasActive = true,
                isMmol = true
            )
        )
        assertFalse(
            ForecastThresholdPolicy.isActive(
                type = AlertType.PRE_HIGH,
                currentValue = 7.8f,
                projectedValue = 7.8f,
                threshold = 9.0f,
                wasActive = true,
                isMmol = true
            )
        )
    }

    @Test
    fun disabledInactiveAndInvalidThresholdConfigsAreIgnored() {
        val configs = mapOf(
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = false, threshold = 4.0f),
            AlertType.HIGH to AlertConfig(AlertType.HIGH, enabled = true, threshold = Float.NaN),
            AlertType.VERY_HIGH to AlertConfig(AlertType.VERY_HIGH, enabled = true, threshold = 12.0f)
        )

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 13.0f,
            rate = 0f,
            configs = configs,
            alertTypes = listOf(AlertType.LOW, AlertType.HIGH, AlertType.VERY_HIGH),
            isMmol = true,
            isConfigActive = { it.type != AlertType.VERY_HIGH }
        )

        assertTrue(active.isEmpty())
    }

    @Test
    fun conditionBeyondThresholdEntersWhenTimeWindowOpens() {
        val episodes = AlertEpisodeState<AlertType>()
        val config = AlertConfig(AlertType.HIGH, enabled = true, threshold = 6.4f)

        val inactive = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 6.5f,
            rate = 0f,
            configs = mapOf(AlertType.HIGH to config),
            alertTypes = listOf(AlertType.HIGH),
            isMmol = true,
            isConfigActive = { false }
        )
        episodes.update(inactive.keys)

        val active = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 6.5f,
            rate = 0f,
            configs = mapOf(AlertType.HIGH to config),
            alertTypes = listOf(AlertType.HIGH),
            isMmol = true,
            isConfigActive = { true }
        )

        assertTrue(episodes.update(active.keys).shouldTryFire(AlertType.HIGH))
    }

    @Test
    fun untrustedRateSilencesForecastsButLeavesThresholdAlertsUntouched() {
        // Post-reboot window: no TrendVelocityProvider registered yet, the rate
        // is a two-point fallback slope. A -3 mg/dl/min artifact at 125 mg/dl
        // projects to 33 over 30 minutes - the forecast must stay silent, while
        // threshold alerts (which measure the actual value) keep working.
        val configs = mapOf(
            AlertType.PRE_LOW to AlertConfig(
                type = AlertType.PRE_LOW,
                enabled = true,
                threshold = 70.2f,
                forecastMinutes = 30
            ),
            AlertType.LOW to AlertConfig(AlertType.LOW, enabled = true, threshold = 130f)
        )

        val untrusted = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 125f,
            rate = -3.05f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW, AlertType.LOW),
            isMmol = false,
            isConfigActive = activeConfig,
            forecastRateTrusted = false
        )
        val trusted = StandardGlucoseAlertEvaluator.resolveActive(
            glucoseValue = 125f,
            rate = -3.05f,
            configs = configs,
            alertTypes = listOf(AlertType.PRE_LOW, AlertType.LOW),
            isMmol = false,
            isConfigActive = activeConfig,
            forecastRateTrusted = true
        )

        assertFalse(AlertType.PRE_LOW in untrusted)
        assertTrue(AlertType.LOW in untrusted)
        assertTrue(AlertType.PRE_LOW in trusted)
    }
}
