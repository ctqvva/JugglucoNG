package tk.glucodata.drivers.ottai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bytes the activation writes hand to the sensor, and the decode of the maxActive readback
 * that the driver takes the sensor's lifetime from.
 *
 * These are regression pins computed from the current code (the AES block is an openssl known
 * answer for the same key and plaintext). They are not a byte comparison with an official-app
 * capture: a change to any of these commands still needs one, on the real sensor.
 */
class OttaiActivationPayloadTests {

    private val key = "0123456789abcdef0123456789abcdef"
    private val dayMs = 24L * 3600L * 1000L

    private fun hex(bytes: ByteArray?) = OttaiCrypto.bytesToHex(bytes ?: error("null payload"))

    @Test
    fun theRtcIsTheWallClockInSecondsLittleEndian() {
        val now = 1_700_000_282_345L
        assertEquals("1af25365", hex(OttaiBleManager.rtcPayload(now)))
        assertArrayEquals(OttaiBleAuth.intToBytesLE(1_700_000_282), OttaiBleManager.rtcPayload(now))
    }

    /**
     * Official: p.w0(retainTime / 1000) || 0x04, plaintext. A small duration, never an epoch —
     * writing now + lifetime here made the sensor drop the link.
     */
    @Test
    fun theDestructionPayloadIsTheRetainDurationThenFour() {
        val default = "00a302000000000004" // 172800 s, LE8, then 0x04
        assertEquals(default, hex(OttaiBleManager.destructionPayload(0L)))
        assertEquals(default, hex(OttaiBleManager.destructionPayload(-1L)))
        assertEquals(default, hex(OttaiBleManager.destructionPayload(OttaiConstants.DEFAULT_RETAIN_TIME_MS)))
        assertEquals("805101000000000004", hex(OttaiBleManager.destructionPayload(dayMs)))
    }

    /** Official: p.U(p.w0(duration / 1000), sessionKey), one AES-ECB block. */
    @Test
    fun theMaxActivePayloadIsOneEncryptedBlockOfTheSeconds() {
        val cipher = OttaiBleManager.maxActivePayload(28L * dayMs, key)
        assertEquals("e12489790d87956580a72f339e1806e2", hex(cipher))
        assertEquals("00ea240000000000", hex(OttaiCrypto.decryptPayload(cipher!!, key)))
        // A session key that cannot encrypt yields no payload, and the writer fails the activation.
        assertNull(OttaiBleManager.maxActivePayload(28L * dayMs, "zz"))
        assertNull(OttaiBleManager.maxActivePayload(28L * dayMs, ""))
    }

    @Test
    fun theReadbackDecodesWhatTheWriteSent() {
        for (ms in OttaiConstants.activationMaxActiveCandidatesMs(0L)) {
            val secs = OttaiBleManager.decodeMaxActiveSeconds(OttaiBleManager.maxActivePayload(ms, key)!!, key)
            assertEquals(ms / 1000L, secs)
            assertTrue(OttaiBleManager.plausibleMaxActiveSeconds(secs!!))
        }
    }

    @Test
    fun aPlaintextReadbackIsReadAsLittleEndianSeconds() {
        // 25 d as LE8, and the official 15 d 30 min as LE4.
        val twentyFive = byteArrayOf(0x80.toByte(), 0xF5.toByte(), 0x20, 0, 0, 0, 0, 0)
        assertEquals(2_160_000L, OttaiBleManager.decodeMaxActiveSeconds(twentyFive, key))
        val fifteenAndHalfHour = byteArrayOf(0x88.toByte(), 0xCD.toByte(), 0x13, 0)
        assertEquals(15L * 86_400L + 1_800L, OttaiBleManager.decodeMaxActiveSeconds(fifteenAndHalfHour, key))
    }

    @Test
    fun anUnreadableReadbackDecodesToNothing() {
        assertNull(OttaiBleManager.decodeMaxActiveSeconds(ByteArray(0), key))
        assertNull(OttaiBleManager.decodeMaxActiveSeconds(byteArrayOf(1, 2, 3), key))
        assertNull(OttaiBleManager.decodeMaxActiveSeconds(ByteArray(8), key))
    }

    @Test
    fun onlyTenToFortyFiveDaysIsAdopted() {
        val day = 86_400L
        assertTrue(OttaiBleManager.plausibleMaxActiveSeconds(10L * day))
        assertTrue(OttaiBleManager.plausibleMaxActiveSeconds(45L * day))
        assertTrue(OttaiBleManager.plausibleMaxActiveSeconds(28L * day))
        assertFalse(OttaiBleManager.plausibleMaxActiveSeconds(10L * day - 1L))
        assertFalse(OttaiBleManager.plausibleMaxActiveSeconds(45L * day + 1L))
        assertFalse(OttaiBleManager.plausibleMaxActiveSeconds(5L * day))
        assertFalse(OttaiBleManager.plausibleMaxActiveSeconds(60L * day))
    }
}
