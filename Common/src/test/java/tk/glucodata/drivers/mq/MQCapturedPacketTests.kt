package tk.glucodata.drivers.mq

import org.junit.Assert.*
import org.junit.Test

class MQCapturedPacketTests {
    @Test fun preservesRawFieldsFromSeptember14CaptureWithoutCallingBatteryAPercentage() {
        val frame = MQParser.parse(byteArrayOf(0x5A, 0xA5.toByte(), 0x04, 0x06,
            0x40, 0xED.toByte(), 0x00, 0x57, 0x00, 0x32, 0x11, 0x61))!!
        val record = MQParser.parseBgRecords(frame).single()
        assertEquals(237, record.packetIndex)
        assertEquals(87, record.sampleCurrent)
        assertEquals(0x32, record.batteryRaw)
        assertArrayEquals(byteArrayOf(0x40, 0xED.toByte(), 0, 0x57, 0, 0x32), record.recordBytes)
    }
}
