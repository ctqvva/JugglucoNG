package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class CloneTransportPresentationTests {
    @Test
    fun sensorRouteFollowsTheLiveConnectionState() {
        assertEquals(
            CloneTransport.TURN,
            CloneTransportPresentation.sensorTransport(CloneTransport.TURN),
        )
        assertEquals(null, CloneTransportPresentation.sensorTransport(CloneTransport.UNKNOWN))
        assertEquals(
            null,
            CloneTransportPresentation.sensorTransport(null),
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
