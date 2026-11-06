package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanExchangeOutputPolicyTests {
    @Test
    fun icanOnlyDestinationIsIndependentOfMinuteGateAndOtherDestinations() {
        val policy = ExchangeOutputPolicy()
        val first = policy.decide("SN", 180_000L, 3, false, false, false, false, false, false,
            icanHealthEnabled = true)
        assertTrue(first.sendICanHealth)
        assertFalse(first.sendJuggluco)
        assertFalse(first.sendOutboundApi)
        assertFalse(first.sendDexcomShare)
        val sameChunk = policy.decide("SN", 240_000L, 3, true, false, false, false, false, false,
            icanHealthEnabled = true)
        assertFalse(sameChunk.sendICanHealth)
        val nextChunk = policy.decide("SN", 360_000L, 3, false, false, false, false, false, false,
            icanHealthEnabled = true)
        assertTrue(nextChunk.sendICanHealth)
    }

    @Test
    fun disabledIcanDoesNotConsumeItsInterval() {
        val policy = ExchangeOutputPolicy()
        assertFalse(policy.decide("SN", 180_000L, 3, true, true, false, false, false, false).sendICanHealth)
        assertTrue(policy.decide("SN", 180_000L, 3, false, false, false, false, false, false,
            icanHealthEnabled = true).sendICanHealth)
    }
}
