package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class CloneTransportPresentationTests {
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
            R.string.clone_transport_unknown,
            CloneTransportPresentation.statusTextRes(CloneTransport.UNKNOWN),
        )
        assertEquals(
            R.string.clone_transport_unknown,
            CloneTransportPresentation.statusTextRes(null),
        )
    }
}
