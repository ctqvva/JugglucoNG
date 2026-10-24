package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression vectors for the live 19-byte `0x2AA7` notification decryption.
 *
 * The official iOS client's HCI capture (iPhone PacketLogger, 2026-09-26/27, iCGM-t3) proves the
 * live payload is AES-128-CBC/PKCS7 with the challenge-derived IV — the same scheme and key family
 * as the history lanes — not the ECB read the driver used before (notes §10.8). These vectors pin
 * that: every one of them was extracted from the capture as a raw ATT indication on the
 * measurement characteristic, paired with the challenge indication (`06 F3 01 <4 bytes>`) that
 * preceded it on the same connection.
 *
 * The capture is `.pklg`-wrapped HCI: records of
 * `u32 length | u32 seconds | u32 microseconds | u8 type | data`, where type 3 is received ACL
 * data carrying `handle+flags u16 | length u16 | L2CAP length u16 | CID u16 | ATT PDU`.
 */
class ICanHealthLiveCbcVectorTests {

    private val newKey = ICanHealthCrypto.keyFromASCII(
        ICanHealthConstants.DEFAULT_NEW_GLUCOSE_AES_KEY_ASCII
    )!!

    /**
     * TECNO iCGM-t6, challenge `F1 18 6B D2` (notes §10.8), old-key family.
     *
     * The wire ciphertext is reconstructed here by ECB-encrypting the captured ECB-decrypted
     * block with the same key, which reproduces the original frame byte-for-byte; the decrypt
     * under test is then the real CBC/PKCS7 path.
     *
     * ECB block: 0D 71 0D 02 48 03 19 00 00 00 6B 00 00 03 03 D1
     * CBC block: 0D 80 0D 02 48 03 01 00 00 00 00 00 00 03 03 03
     */
    @Test
    fun t6CapturedVector_decryptsAsCbcPkcs7() {
        val oldKey = ICanHealthCrypto.keyFromASCII(
            ICanHealthConstants.DEFAULT_OLD_GLUCOSE_AES_KEY_ASCII
        )!!
        val challenge = byteArrayOf(0xF1.toByte(), 0x18, 0x6B, 0xD2.toByte())
        val ecbBlock = byteArrayOf(
            0x0D, 0x71, 0x0D, 0x02, 0x48, 0x03, 0x19, 0x00,
            0x00, 0x00, 0x6B, 0x00, 0x00, 0x03, 0x03, 0xD1.toByte(),
        )
        val ciphertext = ICanHealthCrypto.encryptBlock(ecbBlock, oldKey)!!
        val notification = byteArrayOf(0x48, 0x03, 0x02) + ciphertext // seq 0x0348 = 840

        val reading = ICanHealthParser.parseGlucoseNotification(
            data = notification,
            aesKey = oldKey,
            authChallenge = challenge,
            onboardingDeviceSn = null,
            usesNewBundledCrypto = false,
            warmupMinutes = 0,
        )

        assertNotNull(reading)
        assertEquals(840, reading!!.sequenceNumber)
        assertEquals(525, reading.mantissa)
        assertEquals(5.25f, reading.glucoseMmolL, 0.001f)
        assertEquals(ICanHealthConstants.LAUNCHER_STATE_RUNNING, reading.statusByte)
    }

    /**
     * iPhone capture, iCGM-t3 (new-key family), seq 18.
     *
     * header `12 00 02`; challenge `30 13 AA 84`;
     * ciphertext `C1 CD 90 F4 FD AF 6C 42 AC D5 C9 87 B6 52 2B 7D`;
     * plaintext `0D 80 DB F6 12 00 01 00 00 00 00 00 00 03 03 03`.
     *
     * The value field reads 0x6DB = 1755, which the notes' table flags as a nonce on this sensor:
     * it does not match the history record at sequence 18.
     */
    @Test
    fun iphoneCaptureVector_seq18() {
        val reading = parseCaptured(
            header = byteArrayOf(0x12, 0x00, 0x02),
            challenge = byteArrayOf(0x30, 0x13, 0xAA.toByte(), 0x84.toByte()),
            ciphertext = hex("C1 CD 90 F4 FD AF 6C 42 AC D5 C9 87 B6 52 2B 7D"),
        )

        assertEquals(18, reading.sequenceNumber)
        assertEquals(1755, reading.mantissa)
        assertEquals(17.55f, reading.glucoseMmolL, 0.001f)
        assertEquals(ICanHealthConstants.LAUNCHER_STATE_RUNNING, reading.statusByte)
        assertEquals(0f, reading.temperatureC, 0.0001f)
    }

    /** iPhone capture, iCGM-t3, seq 30: header `1E 00 02`, challenge `C6 18 3B BD`. */
    @Test
    fun iphoneCaptureVector_seq30() {
        val reading = parseCaptured(
            header = byteArrayOf(0x1E, 0x00, 0x02),
            challenge = byteArrayOf(0xC6.toByte(), 0x18, 0x3B, 0xBD.toByte()),
            ciphertext = hex("E2 0C C3 32 50 31 C3 EE FD B3 49 7B 5A A3 05 2B"),
        )

        assertEquals(30, reading.sequenceNumber)
        assertEquals(573, reading.mantissa)
        assertEquals(5.73f, reading.glucoseMmolL, 0.001f)
        assertEquals(ICanHealthConstants.LAUNCHER_STATE_RUNNING, reading.statusByte)
    }

    /** iPhone capture, iCGM-t3, seq 117: header `75 00 02`, challenge `F8 93 7B 8C`. */
    @Test
    fun iphoneCaptureVector_seq117() {
        val reading = parseCaptured(
            header = byteArrayOf(0x75, 0x00, 0x02),
            challenge = byteArrayOf(0xF8.toByte(), 0x93.toByte(), 0x7B, 0x8C.toByte()),
            ciphertext = hex("D4 22 8E 35 9E 64 5F 00 98 00 FF 86 4A CA 2F 4A"),
        )

        assertEquals(117, reading.sequenceNumber)
        assertEquals(3134, reading.mantissa)
        assertEquals(31.34f, reading.glucoseMmolL, 0.001f)
        assertEquals(ICanHealthConstants.LAUNCHER_STATE_RUNNING, reading.statusByte)
    }

    /**
     * The old ECB read stays plausible (the content-length byte is not at an IV position) but
     * reports a garbage status byte — `0x80 XOR challenge[0]` — which is exactly what the driver
     * logged before this fix. Guard against a silent return to ECB.
     */
    @Test
    fun capturedVector_ecbDecodeWouldRemainPlausibleButWrong() {
        val challenge = byteArrayOf(0xC6.toByte(), 0x18, 0x3B, 0xBD.toByte())
        val ciphertext = hex("E2 0C C3 32 50 31 C3 EE FD B3 49 7B 5A A3 05 2B")

        val ecbBlock = ICanHealthCrypto.decryptBlock(ciphertext, newKey)!!
        val ecbStatus = ecbBlock[1].toInt() and 0xFF
        assertEquals(0x0D, ecbBlock[0].toInt() and 0xFF)
        assertEquals(0x80 xor (challenge[0].toInt() and 0xFF), ecbStatus)
        assertNotEquals(ICanHealthConstants.LAUNCHER_STATE_RUNNING, ecbStatus)

        val reading = parseCaptured(
            header = byteArrayOf(0x1E, 0x00, 0x02),
            challenge = challenge,
            ciphertext = ciphertext,
        )
        assertEquals(ICanHealthConstants.LAUNCHER_STATE_RUNNING, reading.statusByte)
    }

    private fun parseCaptured(
        header: ByteArray,
        challenge: ByteArray,
        ciphertext: ByteArray,
    ): ICanHealthGlucoseReading {
        val reading = ICanHealthParser.parseGlucoseNotification(
            data = header + ciphertext,
            aesKey = newKey,
            authChallenge = challenge,
            onboardingDeviceSn = null,
            usesNewBundledCrypto = true,
            warmupMinutes = 0,
        )
        assertNotNull(reading)
        return reading!!
    }

    private fun hex(text: String): ByteArray =
        text.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
