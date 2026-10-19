package tk.glucodata.drivers.mq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MQParserTests {

    private fun frame(cmd: Int, payload: ByteArray, xorOut: Int = 0): ByteArray {
        val f = ByteArray(MQConstants.FRAME_OVERHEAD + payload.size)
        f[0] = MQConstants.HEADER0
        f[1] = MQConstants.HEADER1
        f[2] = cmd.toByte()
        f[3] = payload.size.toByte()
        payload.copyInto(f, MQConstants.OFFSET_PAYLOAD)
        return MQCrc16.stamp(f, xorOut)
    }

    @Test
    fun parseValidFrameExtractsFields() {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val parsed = MQParser.parse(frame(MQConstants.CMD_NOTIFY_BG_DATA.toInt(), payload))!!
        assertEquals(MQConstants.CMD_NOTIFY_BG_DATA, parsed.cmd)
        assertEquals(3, parsed.len)
        assertArrayEquals(payload, parsed.payload)
        assertTrue(parsed.crcValid)
        assertEquals(3 + MQConstants.FRAME_OVERHEAD, parsed.raw.size)
        assertEquals(MQConstants.CMD_NOTIFY_BG_DATA.toInt() and 0xFF, parsed.cmdUnsigned)
    }

    @Test
    fun parseRejectsNullShortAndWrongHeader() {
        assertNull(MQParser.parse(null))
        assertNull(MQParser.parse(ByteArray(MQConstants.FRAME_OVERHEAD - 1)))
        val badHeader = frame(MQConstants.CMD_NOTIFY_WORKING.toInt(), ByteArray(0))
        badHeader[0] = 0x00
        assertNull(MQParser.parse(badHeader))
    }

    @Test
    fun parseRejectsTruncatedPayload() {
        val f = frame(MQConstants.CMD_NOTIFY_BG_DATA.toInt(), ByteArray(4))
        assertNull(MQParser.parse(f.copyOf(f.size - 1)))
    }

    @Test
    fun parseTruncatesTrailingBytesAndAcceptsBadCrc() {
        val f = frame(MQConstants.CMD_NOTIFY_BG_DATA.toInt(), byteArrayOf(1, 2, 3))
        val withTrailing = f + byteArrayOf(0x7F)
        val parsed = MQParser.parse(withTrailing)!!
        assertEquals(f.size, parsed.raw.size)

        val badCrc = f.copyOf()
        badCrc[badCrc.size - 1] = (badCrc[badCrc.size - 1] + 1).toByte()
        val mismatched = MQParser.parse(badCrc)!!
        assertFalse(mismatched.crcValid)
        assertEquals(MQConstants.CMD_NOTIFY_BG_DATA, mismatched.cmd)
    }

    @Test
    fun parseBgRecordsDecodesLittleEndianFields() {
        val payload = byteArrayOf(
            0x40, 0x01, 0x02, 0x03, 0x04, 0x50,
            0x40, 0x05, 0x06, 0x07, 0x08, 0x64,
        )
        val parsed = MQParser.parse(frame(MQConstants.CMD_NOTIFY_BG_DATA.toInt(), payload))!!
        val records = MQParser.parseBgRecords(parsed)
        assertEquals(2, records.size)
        assertEquals(0, records[0].indexInPacket)
        assertEquals(0x40, records[0].marker)
        assertEquals(0x0201, records[0].packetIndex)
        assertEquals(0x0403, records[0].sampleCurrent)
        assertEquals(0x50, records[0].batteryRaw)
        assertArrayEquals(payload.copyOfRange(0, 6), records[0].recordBytes)
        assertEquals(1, records[1].indexInPacket)
        assertEquals(0x0605, records[1].packetIndex)
        assertEquals(0x64, records[1].batteryRaw)
    }

    @Test
    fun parseBgRecordsIgnoresOtherCommandsAndEmptyPayload() {
        val wrongCmd = MQParser.parse(frame(MQConstants.CMD_NOTIFY_WORKING.toInt(), ByteArray(6)))!!
        assertTrue(MQParser.parseBgRecords(wrongCmd).isEmpty())

        val empty = MQParser.parse(frame(MQConstants.CMD_NOTIFY_BG_DATA.toInt(), ByteArray(0)))!!
        assertTrue(MQParser.parseBgRecords(empty).isEmpty())
    }

    @Test
    fun buildConfirmFramesCarryCommandAndValidCrc() {
        val generic = MQParser.buildConfirm(MQConstants.CMD_NOTIFY_WORKING)
        assertEquals(7, generic.size)
        assertEquals(MQConstants.HEADER0, generic[0])
        assertEquals(MQConstants.HEADER1, generic[1])
        assertEquals(MQConstants.CMD_NOTIFY_WORKING, generic[2])
        assertEquals(1, generic[3].toInt() and 0xFF)
        assertTrue(MQCrc16.verify(generic))

        assertEquals(MQConstants.CMD_WRITE_BG_DATA_CONFIRM, MQParser.buildConfirmBgData()[2])
        assertEquals(MQConstants.CMD_WRITE_CONFIRM_WITH_INIT, MQParser.buildConfirmWithInit()[2])
        assertEquals(MQConstants.CMD_WRITE_CONFIRM_WITHOUT_INIT, MQParser.buildConfirmWithoutInit()[2])
        assertEquals(MQConstants.CMD_WRITE_CONFIRM_RESET, MQParser.buildConfirmReset()[2])
    }
}
