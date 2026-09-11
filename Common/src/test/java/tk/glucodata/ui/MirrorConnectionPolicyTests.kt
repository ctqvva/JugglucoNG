package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorConnectionPolicyTests {
    private fun connection(
        index: Int,
        label: String? = "Local Clone",
        isIce: Boolean = false,
        iceSide: Boolean = false,
        isWearOs: Boolean = false,
        sendsData: Boolean = true,
        receivesData: Boolean = false,
        isDeactivated: Boolean = false,
        isPending: Boolean = true
    ) = MirrorConnectionSnapshot(
        index, label, isIce, iceSide, isWearOs, sendsData, receivesData,
        isDeactivated, isPending
    )

    @Test
    fun hybridQrReusesNewestPendingSenderOnly() {
        val connections = listOf(
            connection(1, label = "ICE-old", isIce = true),
            connection(2, label = "ICE-paired", isIce = true, isPending = false),
            connection(3, label = "ICE-receiver", isIce = true, iceSide = true),
            connection(4, label = "ICE-wear", isIce = true, isWearOs = true),
            connection(5, label = "ICE-new", isIce = true)
        )

        assertEquals(5, reusableQuickPairIndex(connections, QuickPairKind.HYBRID))
    }

    @Test
    fun localQrReusesOnlyGeneratedPendingSender() {
        val connections = listOf(
            connection(1, label = "Kitchen"),
            connection(2, receivesData = true),
            connection(3, isPending = false),
            connection(4)
        )

        assertEquals(4, reusableQuickPairIndex(connections, QuickPairKind.LOCAL))
        assertNull(reusableQuickPairIndex(connections.take(3), QuickPairKind.LOCAL))
    }

    @Test
    fun cloneMasterExcludesWearConnections() {
        val connections = listOf(
            connection(1, isDeactivated = true),
            connection(2, isWearOs = true),
            connection(3, isDeactivated = false)
        )

        assertEquals(listOf(1, 3), cloneConnectionIndices(connections))
        assertTrue(isCloneEnabled(connections))
        assertFalse(isCloneEnabled(connections.map { it.copy(isDeactivated = true) }))
    }

    @Test
    fun backgroundLivenessIsShownOnlyForAnEnabledReceiver() {
        assertFalse(hasActiveCloneReceiver(listOf(connection(1, receivesData = false))))
        assertFalse(
            hasActiveCloneReceiver(
                listOf(connection(1, receivesData = true, isDeactivated = true))
            )
        )
        assertFalse(
            hasActiveCloneReceiver(
                listOf(connection(1, receivesData = true, isWearOs = true))
            )
        )
        assertTrue(hasActiveCloneReceiver(listOf(connection(1, receivesData = true))))
    }

    @Test
    fun announcementDeletesOnlyItsOwnUnusedSender() {
        val pending = connection(1)

        assertTrue(shouldDeleteAnnouncementSender(pending, ownedByAnnouncement = true))
        assertFalse(shouldDeleteAnnouncementSender(pending, ownedByAnnouncement = false))
        assertFalse(
            shouldDeleteAnnouncementSender(
                pending.copy(isPending = false),
                ownedByAnnouncement = true
            )
        )
    }

    @Test
    fun iceConnectionsDoNotExposeTheirEncodedLabelAsAPort() {
        assertNull(mirrorDisplayPort(isIce = true, rawPort = "18755"))
        assertEquals("8795", mirrorDisplayPort(isIce = false, rawPort = "8795"))
    }

    @Test
    fun networkEndpointsAreReadableAndDoNotConfuseIpv6ColonsWithThePort() {
        assertEquals("connect.example.test:6789", formatNetworkEndpoint("connect.example.test", 6789))
        assertEquals("[2001:db8::5]:3478", formatNetworkEndpoint("2001:db8::5", 3478))
        assertNull(formatNetworkEndpoint("", 3478))
        assertNull(formatNetworkEndpoint("connect.example.test", 0))
    }
}
