package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class CloneLiveTransportPolicyTests {
    @Test
    fun mappedConnectedHostRemainsAuthoritative() {
        assertEquals(
            CloneTransport.LOCAL_ICE,
            CloneLiveTransportPolicy.resolve(
                CloneTransport.LOCAL_ICE,
                listOf(CloneTransport.TURN),
            ),
        )
    }

    @Test
    fun soleConnectedHostRepairsAMissingMapping() {
        assertEquals(
            CloneTransport.TURN,
            CloneLiveTransportPolicy.resolve(
                CloneTransport.UNKNOWN,
                listOf(CloneTransport.UNKNOWN, CloneTransport.TURN),
            ),
        )
    }

    @Test
    fun multipleConnectedHostsRemainAmbiguousWithoutAMapping() {
        assertEquals(
            CloneTransport.UNKNOWN,
            CloneLiveTransportPolicy.resolve(
                null,
                listOf(CloneTransport.LOCAL_ICE, CloneTransport.TURN),
            ),
        )
    }
}
