package tk.glucodata.drivers.medtrum

import org.junit.Assert.*
import org.junit.Test

class MedtrumCryptTest {

    @Test
    fun keyGenIsDeterministic() {
        val sn = 12345678L
        assertEquals(MedtrumCrypt.keyGen(sn), MedtrumCrypt.keyGen(sn))
    }

    @Test
    fun keyGenDiffersForDifferentSerials() {
        assertNotEquals(MedtrumCrypt.keyGen(11111111L), MedtrumCrypt.keyGen(22222222L))
    }

    @Test
    fun keyGenFitsInFourBytes() {
        val key = MedtrumCrypt.keyGen(12345678L)
        assertTrue("key must fit in uint32", key in 0L..0xFFFFFFFFL)
    }

    @Test
    fun byteArrayToLongIsLittleEndian() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals(0x04030201L, MedtrumCrypt.bytesToLong(bytes))
    }

    @Test
    fun longToByteArrayIsLittleEndian() {
        val result = MedtrumCrypt.longToBytes(0x04030201L, 4)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), result)
    }

    @Test
    fun roundTripBytesLong() {
        val original = 0xDEADBEEFL
        val bytes = MedtrumCrypt.longToBytes(original, 4)
        assertEquals(original, MedtrumCrypt.bytesToLong(bytes))
    }

    @Test
    fun generateRandomTokenFitsInUint32() {
        val token = MedtrumCrypt.generateRandomToken()
        assertTrue("token must fit in uint32", token in 0L..0xFFFFFFFFL)
    }

    @Test
    fun keyGenGoldenVector() {
        // Pinned value computed from AndroidAPS Crypt.kt reference: keyGen(12345678) = 2305779289
        assertEquals(2305779289L, MedtrumCrypt.keyGen(12345678L))
    }

    @Test
    fun keyGenMatchesCapturedHardwareAuthRequest() {
        // HCI capture, real EasySense patch SN 0x8ADF6216 (2329895446), 2026-10-17.
        // The AUTH_REQ write ends with keyGen(SN) little-endian: 13 44 06 f0.
        // Independent of the AndroidAPS reference, so it pins the algorithm to hardware.
        val key = MedtrumCrypt.keyGen(2329895446L)
        assertEquals(0xF0064413L, key)
        assertArrayEquals(
            byteArrayOf(0x13, 0x44, 0x06, 0xF0.toByte()),
            MedtrumCrypt.longToBytes(key, 4),
        )
    }

    @Test
    fun legacyKeyGenIsTheTwoRoundParkMillerVariant() {
        // AuthorizeManager.passKeyGenerator: two Park-Miller rounds, no S-box. No hardware
        // witness (the captured patch used the modern key); this pins the port to the app.
        val legacy = MedtrumCrypt.legacyKeyGen(2329895446L)
        assertEquals(1227664483L, legacy)
        assertNotEquals(MedtrumCrypt.keyGen(2329895446L), legacy)
    }
}
