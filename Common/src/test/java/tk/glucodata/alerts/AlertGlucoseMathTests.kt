package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertGlucoseMathTests {

    @Test
    fun forecastProjectionUsesMgdlRateWithMmolGlucose() {
        val projected = AlertGlucoseMath.projectedDisplayValue(
            glucoseValue = 8.4f,
            rateMgdlPerMinute = 2.0f,
            forecastMinutes = 20,
            isMmol = true
        )

        assertEquals(10.62f, projected, 0.02f)
    }

    @Test
    fun forecastProjectionDoesNotUseLegacyNativeScaleOnMmolValues() {
        val projected = AlertGlucoseMath.projectedDisplayValue(
            glucoseValue = 8.4f,
            rateMgdlPerMinute = 2.0f,
            forecastMinutes = 20,
            isMmol = true
        )

        assertTrue(projected < 11f)
    }

    @Test
    fun forecastProjectionClampsLookahead() {
        val projected = AlertGlucoseMath.projectedDisplayValue(
            glucoseValue = 100f,
            rateMgdlPerMinute = 1f,
            forecastMinutes = 120,
            isMmol = false
        )

        assertEquals(160f, projected, 0.001f)
    }

    @Test
    fun forecastDefaultsRemainDisabled() {
        assertTrue(!AlertDefaults.defaultConfig(AlertType.PRE_LOW, true).enabled)
        assertTrue(!AlertDefaults.defaultConfig(AlertType.PRE_HIGH, false).enabled)
    }
}
