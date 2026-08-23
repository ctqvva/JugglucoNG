package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    /**
     * HIGH is the alarm somebody relies on to hear about a high at all, and it fires the
     * moment the line is crossed, so silencing it takes a fall this app would call a fall.
     * Its own vocabulary: under 0.5 is drawn level, and it does not tell other apps
     * "falling" until 2.0.
     */
    @Test
    fun aHighIsOnlySilencedByAFallThisAppWouldCallOne() {
        val asked = AlertDefaults.HIGH_FALL_RATE_MGDL_PER_MIN

        assertTrue(FallSuppressionPolicy.highFallingSuppresses(rate = -2.5f, fallRateSuppress = asked))
        assertTrue(FallSuppressionPolicy.highFallingSuppresses(rate = -2.0f, fallRateSuppress = asked))
        // A value coming down thirty an hour is not the correction plainly working.
        assertFalse(FallSuppressionPolicy.highFallingSuppresses(rate = -0.5f, fallRateSuppress = asked))
        assertFalse(FallSuppressionPolicy.highFallingSuppresses(rate = -1.9f, fallRateSuppress = asked))
    }

    /** Nothing quieter than the floor counts, whatever is stored or edited into the file. */
    @Test
    fun aStoredLimitBelowTheFloorDoesNotSilenceAHigh() {
        assertFalse(FallSuppressionPolicy.highFallingSuppresses(rate = -0.6f, fallRateSuppress = 0.2f))
        assertFalse(FallSuppressionPolicy.highFallingSuppresses(rate = -0.9f, fallRateSuppress = 0.5f))
        // At the floor it does, since that is where this app stops calling the movement level.
        assertTrue(
            FallSuppressionPolicy.highFallingSuppresses(
                rate = -1.0f,
                fallRateSuppress = AlertDefaults.HIGH_FALL_RATE_FLOOR_MGDL_PER_MIN
            )
        )
    }

    /** Off unless asked for: no limit means the alarm somebody has today, unchanged. */
    @Test
    fun aHighWithNoLimitSetIsNeverSilenced() {
        assertFalse(FallSuppressionPolicy.highFallingSuppresses(rate = -5.0f, fallRateSuppress = null))
        assertFalse(FallSuppressionPolicy.highFallingSuppresses(rate = -5.0f, fallRateSuppress = 0f))
        assertFalse(
            FallSuppressionPolicy.highFallingSuppresses(
                rate = Float.NaN,
                fallRateSuppress = AlertDefaults.HIGH_FALL_RATE_MGDL_PER_MIN
            )
        )
    }
}