package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class CloneTransportPresentationTests {
    @Test
    fun sensorRouteUsesLastConfirmedRouteOnlyWhileLiveRouteIsUnavailable() {
        assertEquals(
            CloneTransport.TURN,
            CloneTransportPresentation.sensorTransport(
                CloneTransport.TURN,
                CloneTransport.LOCAL_ICE,
            ),
        )
        assertEquals(
            CloneTransport.LOCAL_ICE,
            CloneTransportPresentation.sensorTransport(
                CloneTransport.UNKNOWN,
                CloneTransport.LOCAL_ICE,
            ),
        )
        assertEquals(
            CloneTransport.TURN,
            CloneTransportPresentation.sensorTransport(null, CloneTransport.TURN),
        )
        assertEquals(
            null,
            CloneTransportPresentation.sensorTransport(CloneTransport.UNKNOWN, null),
        )
    }

    @Test
    fun liveCloneStatusNeverFallsBackToABluetoothSearchLabel() {
        assertEquals(
            R.string.clone_transport_local_ice,
            CloneTransportPresentation.statusTextRes(CloneTransport.LOCAL_ICE),
        )
        assertEquals(
            R.string.clone_transport_turn_ice,
            CloneTransportPresentation.statusTextRes(CloneTransport.TURN),
        )
        assertEquals(
            R.string.clone_transport_reconnecting,
            CloneTransportPresentation.statusTextRes(CloneTransport.UNKNOWN),
        )
        assertEquals(
            R.string.clone_transport_reconnecting,
            CloneTransportPresentation.statusTextRes(null),
        )
    }
}
