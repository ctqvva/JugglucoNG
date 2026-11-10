package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The protocol-version line (direction.md §6 Q2). A payload that carries none is version 1 — the
 * build before the line existed — and only a well-formed leading `v:<n>` is read.
 */
class WearProtocolTests {

    @Before
    fun reset() {
        WearProtocol.resetForTest()
    }

    @Test
    fun theCurrentVersionLineIsWellFormed() {
        assertEquals("v:${WearProtocol.VERSION}", WearProtocol.versionLine())
        assertEquals(WearProtocol.VERSION, WearProtocol.declaredVersion("${WearProtocol.versionLine()}\ni:a=1\n"))
    }

    @Test
    fun aPayloadWithoutTheLineIsLegacy() {
        assertNull(WearProtocol.declaredVersion("i:dashboard_prediction_horizon_minutes=120\n"))
        assertNull(WearProtocol.declaredVersion(""))
    }

    @Test
    fun aNewerVersionIsReadAndAMalformedOneIsTreatedAsLegacy() {
        assertEquals(2, WearProtocol.declaredVersion("v:2\ni:a=1\n"))
        assertNull(WearProtocol.declaredVersion("v:not-a-number\ni:a=1\n"))
    }

    @Test
    fun theAcceptPolicyIsCurrentOrOlder() {
        assertTrue("a legacy payload is accepted", WearProtocol.accepts(null))
        assertTrue("the current version is accepted", WearProtocol.accepts(WearProtocol.VERSION))
        assertTrue("an older version is accepted", WearProtocol.accepts(WearProtocol.VERSION - 1))
        assertFalse("a newer version is not", WearProtocol.accepts(WearProtocol.VERSION + 1))
    }

    @Test
    fun aReceivedHandshakeStoresThePeerVersion() {
        assertNull("unknown before any report", WearProtocol.peerVersion())
        assertTrue("unknown counts as matching", WearProtocol.peerMatches())

        assertEquals(
            WearProtocol.VERSION,
            WearProtocol.onPeerVersionReport(WearProtocol.versionLine().toByteArray()),
        )
        assertEquals(WearProtocol.VERSION, WearProtocol.peerVersion())
        assertTrue(WearProtocol.peerMatches())
    }

    @Test
    fun aMismatchedPeerVersionIsStoredAndNotAMatch() {
        val future = WearProtocol.VERSION + 1
        assertEquals(future, WearProtocol.onPeerVersionReport("v:$future\n".toByteArray()))
        assertFalse("a mismatch must be visible, not treated as equal", WearProtocol.peerMatches())
    }

    @Test
    fun aMalformedHandshakeIsIgnored() {
        assertNull(WearProtocol.onPeerVersionReport("not-a-version".toByteArray()))
        assertNull(WearProtocol.peerVersion())
        assertNull(WearProtocol.onPeerVersionReport(null))
    }
}
