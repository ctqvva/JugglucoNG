package tk.glucodata

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneTransportPresentationTests {
    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) {
            it.parentFile
        }.first { File(it, "Common/src/main/res/values/strings.xml").isFile }

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
            R.string.clone_transport_connected,
            CloneTransportPresentation.statusTextRes(CloneTransport.LOCAL_ICE),
        )
        assertEquals(
            R.string.clone_transport_connected,
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

    @Test
    fun routeDetailsStillDistinguishPeerToPeerAndTurn() {
        assertEquals(
            R.string.clone_transport_local_ice,
            CloneTransportPresentation.routeTextRes(CloneTransport.LOCAL_ICE),
        )
        assertEquals(
            R.string.clone_transport_turn_ice,
            CloneTransportPresentation.routeTextRes(CloneTransport.TURN),
        )
        for (transport in listOf(CloneTransport.UNKNOWN, null)) {
            assertEquals(
                R.string.clone_transport_reconnecting,
                CloneTransportPresentation.routeTextRes(transport),
            )
        }
    }

    @Test
    fun discoveryDetailsDistinguishLanAndRendezvous() {
        assertEquals(R.string.clone_signaling_lan, CloneTransportPresentation.discoveryTextRes(1))
        assertEquals(R.string.clone_signaling_rendezvous, CloneTransportPresentation.discoveryTextRes(2))
        for (source in listOf(0, -1, 99)) {
            assertEquals(R.string.unknown, CloneTransportPresentation.discoveryTextRes(source))
        }
    }

    @Test
    fun wordingSeparatesConnectedStatusFromRouteAndDiscovery() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(repoRoot(), "Common/src/main/res/values/strings.xml"))
            .getElementsByTagName("string")
        val values = (0 until strings.length).associate { index ->
            val node = strings.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        assertEquals("Connected", values["clone_transport_connected"])
        assertEquals("Peer-to-peer", values["clone_transport_local_ice"])
        assertEquals("TURN relay", values["clone_transport_turn_ice"])
        assertEquals("Discovery", values["clone_signaling"])
        assertEquals("LAN", values["clone_signaling_lan"])
        assertEquals("Rendezvous", values["clone_signaling_rendezvous"])
        assertEquals("Clone via peer-to-peer", values["clone_source_local_ice_description"])
        assertEquals("Clone via TURN relay", values["clone_source_turn_description"])
    }

    @Test
    fun wordingPreservesNativeAndStoredTransportValues() {
        assertEquals(1, CloneTransport.LOCAL_ICE.code)
        assertEquals(2, CloneTransport.TURN.code)
        assertEquals("clone_local_ice", GlucoseReadingSource.forCloneTransport(CloneTransport.LOCAL_ICE))
        assertEquals("clone_turn", GlucoseReadingSource.forCloneTransport(CloneTransport.TURN))
    }

    @Test
    fun connectionCardKeepsRouteAndDiscoveryInsideDetails() {
        val screen = File(repoRoot(), "Common/src/mobile/java/tk/glucodata/ui/MirrorSettingsScreen.kt").readText()
        val header = screen.substringBefore("private fun CloneConnectionDiagnostics")
        val details = screen.substringAfter("private fun CloneConnectionDiagnostics")
            .substringBefore("private fun CloneDiagnosticRow")
        assertTrue(header.contains("CloneTransportPresentation.statusTextRes(mirror.cloneTransport)"))
        assertFalse(header.contains("CloneTransportPresentation.routeTextRes("))
        assertFalse(header.contains("CloneTransportPresentation.discoveryTextRes("))
        assertTrue(details.contains("CloneTransportPresentation.routeTextRes(mirror.cloneTransport)"))
        assertTrue(details.contains("CloneTransportPresentation.discoveryTextRes(mirror.cloneSignalingSource)"))
        assertFalse(details.contains("CloneTransportPresentation.statusTextRes("))
    }
}
