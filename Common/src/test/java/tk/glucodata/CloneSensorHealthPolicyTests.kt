package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneSensorHealthPolicyTests {
    @Test
    fun freshDataIsReceivingEvenWhenRouteIsTemporarilyUnknown() {
        val health = CloneSensorHealthPolicy.resolve(
            hasRecentData = true,
            transport = CloneTransport.UNKNOWN,
        )

        assertTrue(health.isReceiving)
        assertFalse(health.isDisconnected)
        assertNull(health.liveTransport)
    }

    @Test
    fun knownRouteIsPreservedWithoutDecidingDataFreshness() {
        val health = CloneSensorHealthPolicy.resolve(
            hasRecentData = false,
            transport = CloneTransport.TURN,
        )

        assertFalse(health.isReceiving)
        assertFalse(health.isDisconnected)
        assertEquals(CloneTransport.TURN, health.liveTransport)
    }

    @Test
    fun staleDataAndUnknownRouteAreDisconnected() {
        val health = CloneSensorHealthPolicy.resolve(
            hasRecentData = false,
            transport = null,
        )

        assertFalse(health.isReceiving)
        assertTrue(health.isDisconnected)
        assertNull(health.liveTransport)
    }
}
