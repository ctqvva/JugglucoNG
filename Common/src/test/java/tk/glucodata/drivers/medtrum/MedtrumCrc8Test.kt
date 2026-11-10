package tk.glucodata.drivers.medtrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MedtrumCrc8Test {

    @Test
    fun emptyArrayGivesZero() {
        assertEquals(0.toByte(), MedtrumCrc8.calc(byteArrayOf(), 0))
    }

    @Test
    fun singleZeroByteGivesZero() {
        // table[0 xor 0] = table[0] = 0
        assertEquals(0.toByte(), MedtrumCrc8.calc(byteArrayOf(0), 1))
    }

    @Test
    fun lengthLimitRespected() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val crc2 = MedtrumCrc8.calc(data, 2)
        val crc4 = MedtrumCrc8.calc(data, 4)
        // size param must actually stop processing at the given byte count
        assertNotEquals("size=2 and size=4 must produce different CRCs", crc2, crc4)
        // size=2 on full array must equal size=2 on 2-byte array
        assertEquals(crc2, MedtrumCrc8.calc(byteArrayOf(0x01, 0x02), 2))
    }

    @Test
    fun knownSingleByte() {
        // table[0x09 xor 0] = table[9] = 130  (index 9 in the Rijndael table: 0,155,173,54,193,90,108,247,25,130,...)
        assertEquals(130.toByte(), MedtrumCrc8.calc(byteArrayOf(0x09), 1))
    }
}
