package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.TrendArrowAngle

/**
 * PERSISTENT_HIGH suppression while a correction visibly works. Field case:
 * "persistent high" fired at 226 mg/dl under a double down arrow - the value
 * over threshold and the duration satisfied, the rate never consulted.
 */
class FallSuppressionPolicyTests {

    @Test
    fun steepFallSuppresses() {
        // Double down arrow territory: the correction is working, the alert's
        // own premise ("not enough") is disproven.
        assertTrue(FallSuppressionPolicy.fallingSuppresses(rate = -2.0f, fallRateSuppress = 0.5f))
        assertTrue(FallSuppressionPolicy.fallingSuppresses(rate = -0.5f, fallRateSuppress = 0.5f))
    }

    @Test
    fun flatOrRisingValueFiresAsBefore() {
        assertFalse(FallSuppressionPolicy.fallingSuppresses(rate = 0f, fallRateSuppress = 0.5f))
        assertFalse(FallSuppressionPolicy.fallingSuppresses(rate = -0.2f, fallRateSuppress = 0.5f))
        assertFalse(FallSuppressionPolicy.fallingSuppresses(rate = 1.0f, fallRateSuppress = 0.5f))
    }

    @Test
    fun zeroLimitDisablesTheSuppression() {
        // Regression path for existing users: 0 = off, today's behaviour.
        assertFalse(FallSuppressionPolicy.fallingSuppresses(rate = -5.0f, fallRateSuppress = 0f))
    }

    @Test
    fun unsetLimitUsesTheDefault() {
        assertTrue(
            FallSuppressionPolicy.fallingSuppresses(
                rate = -(AlertDefaults.FALL_RATE_SUPPRESS_MGDL_PER_MIN),
                fallRateSuppress = null
            )
        )
        assertFalse(FallSuppressionPolicy.fallingSuppresses(rate = -0.1f, fallRateSuppress = null))
    }

    @Test
    fun missingRateNeverSuppresses() {
        // Without a usable rate the alert must not be silenced on a guess.
        assertFalse(FallSuppressionPolicy.fallingSuppresses(rate = Float.NaN, fallRateSuppress = 0.5f))
    }

    @Test
    fun enabledHighSuppressionExactlyMatchesTheDrawnDownArrow() {
        val enabled = AlertDefaults.FALL_RATE_SUPPRESS_MGDL_PER_MIN
        val rates = listOf(-5f, -2f, -1.9f, -1f, -0.51f, -0.5f, -0.49f, 0f, 0.51f, 2f, Float.NaN)

        rates.forEach { rate ->
            val arrowPointsDown = TrendArrowAngle.rotationDegrees(rate) > 0f
            assertTrue(
                "rate=$rate arrowPointsDown=$arrowPointsDown",
                FallSuppressionPolicy.highFallingSuppresses(rate, enabled) == arrowPointsDown
            )
        }
    }

    /** Every former non-zero slider choice was an opt-in and now gets the same arrow rule. */
    @Test
    fun anExistingPositiveHighSettingRemainsEnabled() {
        assertTrue(FallSuppressionPolicy.highFallingSuppresses(rate = -0.6f, fallRateSuppress = 0.2f))
        assertTrue(FallSuppressionPolicy.highFallingSuppresses(rate = -0.6f, fallRateSuppress = 1.0f))
        assertTrue(FallSuppressionPolicy.highFallingSuppresses(rate = -0.6f, fallRateSuppress = 3.0f))
    }

    /** Off unless asked for: no limit means the alarm somebody has today, unchanged. */
    @Test
    fun aHighWithNoLimitSetIsNeverSilenced() {
        assertTrue(AlertDefaults.defaultConfig(AlertType.HIGH, isMmol = true).fallRateSuppress == null)
        assertFalse(FallSuppressionPolicy.highFallingSuppresses(rate = -5.0f, fallRateSuppress = null))
        assertFalse(FallSuppressionPolicy.highFallingSuppresses(rate = -5.0f, fallRateSuppress = 0f))
        assertFalse(
            FallSuppressionPolicy.highFallingSuppresses(
                rate = Float.NaN,
                fallRateSuppress = AlertDefaults.FALL_RATE_SUPPRESS_MGDL_PER_MIN
            )
        )
    }
}
