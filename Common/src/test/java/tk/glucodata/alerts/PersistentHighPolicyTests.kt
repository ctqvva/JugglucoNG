package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERSISTENT_HIGH suppression while a correction visibly works. Field case:
 * "persistent high" fired at 226 mg/dl under a double down arrow - the value
 * over threshold and the duration satisfied, the rate never consulted.
 */
class PersistentHighPolicyTests {

    @Test
    fun steepFallSuppresses() {
        // Double down arrow territory: the correction is working, the alert's
        // own premise ("not enough") is disproven.
        assertTrue(PersistentHighPolicy.fallingSuppresses(rate = -2.0f, fallRateSuppress = 0.5f))
        assertTrue(PersistentHighPolicy.fallingSuppresses(rate = -0.5f, fallRateSuppress = 0.5f))
    }

    @Test
    fun flatOrRisingValueFiresAsBefore() {
        assertFalse(PersistentHighPolicy.fallingSuppresses(rate = 0f, fallRateSuppress = 0.5f))
        assertFalse(PersistentHighPolicy.fallingSuppresses(rate = -0.2f, fallRateSuppress = 0.5f))
        assertFalse(PersistentHighPolicy.fallingSuppresses(rate = 1.0f, fallRateSuppress = 0.5f))
    }

    @Test
    fun zeroLimitDisablesTheSuppression() {
        // Regression path for existing users: 0 = off, today's behaviour.
        assertFalse(PersistentHighPolicy.fallingSuppresses(rate = -5.0f, fallRateSuppress = 0f))
    }

    @Test
    fun unsetLimitUsesTheDefault() {
        assertTrue(
            PersistentHighPolicy.fallingSuppresses(
                rate = -(AlertDefaults.PERSISTENT_HIGH_FALL_RATE_MGDL_PER_MIN),
                fallRateSuppress = null
            )
        )
        assertFalse(PersistentHighPolicy.fallingSuppresses(rate = -0.1f, fallRateSuppress = null))
    }

    @Test
    fun missingRateNeverSuppresses() {
        // Without a usable rate the alert must not be silenced on a guess.
        assertFalse(PersistentHighPolicy.fallingSuppresses(rate = Float.NaN, fallRateSuppress = 0.5f))
    }
}
