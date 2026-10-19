package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertType

class AlertTestValuePolicyTests {

    @Test
    fun everyAlertTypeGetsATypeAppropriateTestValue() {
        AlertType.entries.forEach { type ->
            val threshold = when (type) {
                AlertType.LOW,
                AlertType.VERY_LOW,
                AlertType.PRE_LOW -> 4.0f

                AlertType.HIGH,
                AlertType.VERY_HIGH,
                AlertType.PRE_HIGH,
                AlertType.PERSISTENT_HIGH -> 12.0f

                AlertType.AVAILABLE,
                AlertType.AMOUNT,
                AlertType.LOSS,
                AlertType.MISSED_READING,
                AlertType.SENSOR_EXPIRY,
                AlertType.FALLING_FAST,
                AlertType.RISING_FAST -> null
            }
            val config = AlertConfig(type = type, threshold = threshold)
            val value = AlertTestValuePolicy.resolve(type, config, true, 8.8f)

            when (type) {
                AlertType.LOW,
                AlertType.VERY_LOW,
                AlertType.PRE_LOW -> assertTrue("$type test value must be low", value < threshold!!)

                AlertType.HIGH,
                AlertType.VERY_HIGH,
                AlertType.PRE_HIGH,
                AlertType.PERSISTENT_HIGH -> assertTrue("$type test value must be high", value > threshold!!)

                AlertType.AVAILABLE,
                AlertType.AMOUNT,
                AlertType.LOSS,
                AlertType.MISSED_READING,
                AlertType.SENSOR_EXPIRY,
                AlertType.FALLING_FAST,
                AlertType.RISING_FAST -> assertEquals(8.8f, value, 0.001f)
            }
        }
    }

    @Test
    fun veryHighNoLongerFallsBackToLowDummyValue() {
        val config = AlertConfig(type = AlertType.VERY_HIGH, threshold = 12.0f)

        assertEquals(12.5f, AlertTestValuePolicy.resolve(AlertType.VERY_HIGH, config, true, Float.NaN), 0.001f)
    }

    @Test
    fun nonThresholdAlertUsesCurrentDashboardValue() {
        val config = AlertConfig(type = AlertType.MISSED_READING)

        assertEquals(
            8.8f,
            AlertTestValuePolicy.resolve(AlertType.MISSED_READING, config, true, 8.8f),
            0.001f
        )
    }

    @Test
    fun nonThresholdAlertHasNeutralFallbackWithoutCurrentValue() {
        val config = AlertConfig(type = AlertType.SENSOR_EXPIRY)

        assertEquals(
            100.0f,
            AlertTestValuePolicy.resolve(AlertType.SENSOR_EXPIRY, config, false, Float.NaN),
            0.001f
        )
    }

    @Test
    fun mgDlThresholdTestsStayOnTheConfiguredSide() {
        val low = AlertConfig(type = AlertType.VERY_LOW, threshold = 55.0f)
        val high = AlertConfig(type = AlertType.VERY_HIGH, threshold = 250.0f)

        assertTrue(AlertTestValuePolicy.resolve(low.type, low, false, Float.NaN) < low.threshold!!)
        assertTrue(AlertTestValuePolicy.resolve(high.type, high, false, Float.NaN) > high.threshold!!)
    }
}
