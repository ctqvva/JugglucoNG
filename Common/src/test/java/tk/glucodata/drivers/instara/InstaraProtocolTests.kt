package tk.glucodata.drivers.instara

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.native.crypto.Crc8Maxim

class InstaraProtocolTests {

    @Test
    fun frameHasHeaderLengthPayloadAndValidCrc() {
        val f = InstaraProtocol.frame(0x07, byteArrayOf(0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38))
        assertEquals(0x55.toByte(), f[0])
        assertEquals(0xAA.toByte(), f[1])
        assertEquals(0x07.toByte(), f[2])
        assertEquals(8.toByte(), f[3])
        assertEquals(13, f.size)
        assertEquals(Crc8Maxim.checksum(f.copyOfRange(0, 12)).toByte(), f[12])
    }

    @Test
    fun frameRejectsPayloadLongerThan255() {
        assertThrows(IllegalArgumentException::class.java) {
            InstaraProtocol.frame(0x07, ByteArray(256))
        }
    }

    @Test
    fun parseRealtimeGlucose() {
        val f = InstaraProtocol.frame(0x10, byteArrayOf(0, 0x2A, 0x02, 0xBC.toByte()))
        val p = InstaraProtocol.parse(f) as InstaraProtocol.Packet.Realtime
        assertEquals(42, p.sequenceId)
        assertEquals(70.0f, p.glucoseRaw, 0.0001f)
    }

    @Test
    fun parseHistoryPayloadUsesVendorFieldLayout() {
        val payload = byteArrayOf(0, 5, 0, 0, 0x01, 0xF4.toByte(), 0, 0x64)
        val p = InstaraProtocol.parse(InstaraProtocol.frame(0x30, payload)) as InstaraProtocol.Packet.HistoryPayload
        assertEquals(8, p.bloodDataLength)
        assertEquals(5, p.startSequenceId)
        assertEquals(0, p.duration)
        assertEquals(listOf(50.0f, 10.0f), p.values)
    }

    @Test
    fun parseReturnsNullForShortBadCrcAndUnknownHeader() {
        assertNull(InstaraProtocol.parse(byteArrayOf(0x55.toByte(), 0xAA.toByte())))
        val bad = InstaraProtocol.frame(0x10, byteArrayOf(0, 0, 0, 0))
        bad[bad.lastIndex] = (bad.last().toInt() xor 0xFF).toByte()
        assertNull(InstaraProtocol.parse(bad))
        assertNull(InstaraProtocol.parse(byteArrayOf(0x11.toByte(), 0x22.toByte(), 0x10.toByte(), 0, 0)))
    }

    @Test
    fun malformedPayloadLengthsAreRejectedWithoutReadingBeyondPayload() {
        assertNull(InstaraProtocol.parse(InstaraProtocol.frame(0x10, byteArrayOf(0, 0, 0))))
        assertNull(InstaraProtocol.parse(InstaraProtocol.frame(0x03, ByteArray(0))))
        assertNull(InstaraProtocol.parse(InstaraProtocol.frame(0x0A, ByteArray(0))))
        assertNull(InstaraProtocol.parse(InstaraProtocol.frame(0x07, ByteArray(0))))
        assertNull(InstaraProtocol.parse(InstaraProtocol.frame(0x02, ByteArray(0))))
    }

    @Test
    fun needsAckMirrorsVendorPolicy() {
        assertTrue(InstaraProtocol.needsAck(0x10))
        assertTrue(InstaraProtocol.needsAck(0x30))
        assertTrue(InstaraProtocol.needsAck(0xF0))
        assertFalse(InstaraProtocol.needsAck(0x07))
        assertFalse(InstaraProtocol.needsAck(0x03))
        assertFalse(InstaraProtocol.needsAck(0x60))
    }
}
