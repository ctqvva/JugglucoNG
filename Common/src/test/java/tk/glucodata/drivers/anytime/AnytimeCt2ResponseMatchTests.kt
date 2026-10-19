package tk.glucodata.drivers.anytime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CT2 request/response pairing. A response releases its request only when the
 * opcodes are known to answer each other; anything else leaves the write queue
 * blocked until a timeout.
 */
class AnytimeCt2ResponseMatchTests {

    @Test
    fun thePullResponseReleasesItsRequestDespiteTheDifferentOpcode() {
        // Request 0x55, response 0x47: without this mapping the protocol response slot
        // never cleared on a pull response and the write queue stalled on every record.
        assertTrue(
            anytimeResponseMatchesRequest(
                AnytimeConstants.TX_CT2_PULL_GLUCOSE,
                AnytimeConstants.RX_CT2_PULL_RESPONSE,
            )
        )
        assertFalse(
            anytimeResponseMatchesRequest(
                AnytimeConstants.TX_CT2_PULL_GLUCOSE,
                AnytimeConstants.RX_CT2_PUSH_GLUCOSE,
            )
        )
    }
}
