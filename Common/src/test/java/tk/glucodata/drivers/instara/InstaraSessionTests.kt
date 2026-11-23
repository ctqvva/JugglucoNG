package tk.glucodata.drivers.instara

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstaraSessionTests {

    private fun authorizedSession() = InstaraSession("ABC12345", 1_000L, 300_000L).also {
        it.onPacket(InstaraProtocol.Packet.SnAuth(true))
    }

    @Test
    fun handshakeEmitsAckAndDelayedSnCommand() {
        val s = InstaraSession("ABC12345", 1_000L, 300_000L)
        val actions = s.onPacket(InstaraProtocol.Packet.Handshake(0x12, 0x34))
        val sends = actions.filterIsInstance<InstaraSession.Action.Send>()
        assertTrue(sends.any { it.bytes[2] == 0xF0.toByte() && it.bytes[3] == 0.toByte() })
        val sn = sends.first { it.bytes[2] == 0x07.toByte() }
        assertEquals(100L, sn.delayMs)
        assertEquals("ABC12345", sn.bytes.copyOfRange(4, 12).toString(Charsets.US_ASCII))
    }

    @Test
    fun snAuthSuccessRequestsUseTimeAndDurationWithNoAck() {
        val s = InstaraSession("ABC12345", 1_000L, 300_000L)
        val sends = s.onPacket(InstaraProtocol.Packet.SnAuth(true))
            .filterIsInstance<InstaraSession.Action.Send>()
        assertTrue(s.isAuthorized)
        assertFalse(sends.any { it.bytes[2] == 0x07.toByte() })
        assertTrue(sends.any { it.bytes[2] == 0x0B.toByte() })
        assertTrue(sends.any { it.bytes[2] == 0x09.toByte() })
    }

    @Test
    fun snAuthFailureLeavesSessionUnauthorized() {
        val s = InstaraSession("ABC12345", 1_000L, 300_000L)
        val actions = s.onPacket(InstaraProtocol.Packet.SnAuth(false))
        assertFalse(s.isAuthorized)
        assertTrue(actions.any { it is InstaraSession.Action.StatusChanged })
    }

    @Test
    fun realtimePacketPublishesOnceWithAck() {
        val s = authorizedSession()
        val actions = s.onPacket(InstaraProtocol.Packet.Realtime(3, 70.0f))
        assertEquals(1, actions.filterIsInstance<InstaraSession.Action.PublishReading>().size)
        assertTrue(actions.any { it is InstaraSession.Action.Send && it.bytes[2] == 0x10.toByte() })
        assertEquals(3, s.currentSequenceId)
    }

    @Test
    fun unauthorizedSessionDoesNotPublish() {
        val s = InstaraSession("ABC12345", 1_000L, 300_000L)
        assertTrue(s.onPacket(InstaraProtocol.Packet.Realtime(3, 70.0f)).isEmpty())
    }

    @Test
    fun historyCompletesOnFrameCountAndUsesStartSequenceId() {
        val s = authorizedSession()
        s.onPacket(InstaraProtocol.Packet.HistoryHeader(2))
        val first = s.onPacket(InstaraProtocol.Packet.HistoryPayload(8, 10, 5, listOf(80f, 90f)))
        assertFalse(s.historyComplete)
        val readings = first.filterIsInstance<InstaraSession.Action.PublishReading>()
        assertEquals(2, readings.size)
        assertEquals(10, readings[0].sequenceId)
        assertEquals(11, readings[1].sequenceId)
        assertTrue(first.any { it is InstaraSession.Action.Send && it.bytes[2] == 0x30.toByte() })

        val last = s.onPacket(InstaraProtocol.Packet.HistoryPayload(6, 12, 5, listOf(100f)))
        assertTrue(s.historyComplete)
        assertTrue(last.any { it is InstaraSession.Action.HistoryComplete })
        assertEquals(12, s.currentSequenceId)
    }

    @Test
    fun timestampsBackdateBySequenceIdAndClampToNow() {
        assertEquals(1_000L + 2 * 300_000L, InstaraSession.timestampFor(1_000L, 2, 300_000L, 10_000_000L))
        assertTrue(InstaraSession.timestampFor(0L, 0, 300_000L, 10_000_000L) > 0L)
        assertEquals(10_000L, InstaraSession.timestampFor(1_000L, 100, 300_000L, 10_000L))
    }

    @Test
    fun mmolToMgdlUsesVendorFactor() {
        assertEquals(5.5f * 18.0182f, InstaraSession.mmolToMgdl(5.5f), 0.001f)
    }

    @Test
    fun dedupKeepsFirstOccurrenceOfEachSequenceId() {
        assertEquals(
            listOf(1 to 5f, 2 to 6f),
            InstaraSession.dedupBySequenceId(listOf(1 to 5f, 2 to 6f, 1 to 9f)),
        )
    }
}
