package tk.glucodata.drivers.mq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MQCrc16Tests {

    @Test
    fun knownModbusVector() {
        assertEquals(0x4B37, MQCrc16.compute("123456789".toByteArray()))
    }

    @Test
    fun computeHonoursOffsetAndLength() {
        val data = byteArrayOf(0x00, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39)
        assertEquals(0x4B37, MQCrc16.compute(data, offset = 1, length = 9))
    }

    @Test
    fun stampProducesBigEndianCrcAndVerifies() {
        val frame = byteArrayOf(0x5A, 0xA5.toByte(), 0x06, 0x00, 0x00, 0x00)
        MQCrc16.stamp(frame)
        assertTrue(MQCrc16.verify(frame))
        val expected = MQCrc16.compute(frame, 0, frame.size - 2)
        assertEquals((expected ushr 8) and 0xFF, frame[frame.size - 2].toInt() and 0xFF)
        assertEquals(expected and 0xFF, frame[frame.size - 1].toInt() and 0xFF)
    }

    @Test
    fun protocol02XorOutIsRejectedByVanillaVerify() {
        val vanilla = byteArrayOf(0x5A, 0xA5.toByte(), 0x06, 0x00, 0x00, 0x00)
        MQCrc16.stamp(vanilla)
        val xor = byteArrayOf(0x5A, 0xA5.toByte(), 0x06, 0x00, 0x00, 0x00)
        MQCrc16.stamp(xor, xorOut = 0x0100)
        assertEquals(((vanilla[4].toInt() and 0xFF) xor 0x01), xor[4].toInt() and 0xFF)
        assertEquals(vanilla[5], xor[5])
        assertFalse(MQCrc16.verify(xor))
    }

    @Test
    fun verifyRejectsShortFramesAndStampRejectsTinyFrames() {
        assertFalse(MQCrc16.verify(byteArrayOf(0x00)))
        assertThrows(IllegalArgumentException::class.java) { MQCrc16.stamp(byteArrayOf(0x00)) }
    }
}
