package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ICanHealthParserTests {

    @Test
    fun parseGlucoseNotification_acceptsIntegrityCheckedLiveFrame() {
        val sequence = 1551
        val challenge = byteArrayOf(0x21, 0x33, 0x45, 0x57)
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, glucoseRaw = 484),
            challenge = challenge,
        )

        val reading = parse(data, authChallenge = challenge, warmupMinutes = 0)

        assertNotNull(reading)
        assertEquals(sequence, reading!!.sequenceNumber)
        assertEquals(484, reading.mantissa)
        assertEquals(4.84f, reading.glucoseMmolL, 0.001f)
        assertEquals(ICanHealthConstants.LAUNCHER_STATE_RUNNING, reading.statusByte)
        // The verified live frame has no temperature: those bytes are zero fill in the CBC
        // content, unlike the synthetic ECB-era layout this test used to build.
        assertEquals(0f, reading.temperatureC, 0.0001f)
    }

    @Test
    fun parseGlucoseNotification_rejectsBadSizeMarker() {
        val sequence = 1551
        val challenge = byteArrayOf(0x21, 0x33, 0x45, 0x57)
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, marker = 0x0C),
            challenge = challenge,
        )

        assertNull(parse(data, authChallenge = challenge, warmupMinutes = 0))
    }

    @Test
    fun parseGlucoseNotification_rejectsSequenceMismatch() {
        val sequence = 1551
        val challenge = byteArrayOf(0x21, 0x33, 0x45, 0x57)
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, embeddedSequence = sequence - 3),
            challenge = challenge,
        )

        assertNull(parse(data, authChallenge = challenge, warmupMinutes = 0))
    }

    @Test
    fun parseGlucoseNotification_rejectsLiveFrameWithoutChallenge() {
        val sequence = 1551
        val challenge = byteArrayOf(0x21, 0x33, 0x45, 0x57)
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, glucoseRaw = 484),
            challenge = challenge,
        )

        // CBC has no self-contained IV: without all four challenge bytes the frame cannot be
        // decrypted, so the old ECB-era behaviour of accepting it must not come back.
        assertNull(parse(data, authChallenge = null, warmupMinutes = 0))
        assertNull(parse(data, authChallenge = byteArrayOf(0x21, 0x33, 0x45), warmupMinutes = 0))
    }

    @Test
    fun parseGlucoseNotification_warmupXorUsesFourthChallengeByte() {
        val sequence = 60
        val challenge = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val table = ICanHealthConstants.LEGACY_HISTORY_GLUCOSE_XOR_TABLE_NEW
        val offset = (challenge[3].toInt() and 0xFF) % (table.size - 2)
        val glucoseRaw = 450
        val encodedLow = (glucoseRaw and 0xFF) xor (table[offset].toInt() and 0xFF)
        val encodedHigh = ((glucoseRaw ushr 8) and 0xFF) xor (table[offset + 1].toInt() and 0xFF)
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(
                sequence = sequence,
                glucoseLow = encodedLow,
                glucoseHigh = encodedHigh
            ),
            challenge = challenge,
        )

        val reading = parse(data, authChallenge = challenge, warmupMinutes = 120)

        assertNotNull(reading)
        assertEquals(4.50f, reading!!.glucoseMmolL, 0.001f)
    }

    @Test
    fun parseGlucoseNotification_rejectsWarmupFrameWithoutChallenge() {
        val sequence = 60
        val challenge = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, glucoseRaw = 2960),
            challenge = challenge,
        )

        assertNull(parse(data, authChallenge = null, warmupMinutes = 120))
    }

    @Test
    fun parseGlucoseNotification_rejectsWarmupFrameWithShortChallenge() {
        val sequence = 60
        val challenge = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, glucoseRaw = 2960),
            challenge = challenge,
        )

        assertNull(
            parse(
                data,
                authChallenge = byteArrayOf(0x01, 0x02, 0x03),
                warmupMinutes = 120,
            )
        )
    }

    private fun parse(
        data: ByteArray,
        authChallenge: ByteArray? = null,
        warmupMinutes: Int,
    ): ICanHealthGlucoseReading? {
        val key = ICanHealthCrypto.keyFromASCII(ICanHealthConstants.DEFAULT_NEW_GLUCOSE_AES_KEY_ASCII)!!
        return ICanHealthParser.parseGlucoseNotification(
            data = data,
            aesKey = key,
            authChallenge = authChallenge,
            onboardingDeviceSn = null,
            usesNewBundledCrypto = true,
            warmupMinutes = warmupMinutes,
        )
    }

    /**
     * Build a live 19-byte notification the way the sensor does: CBC/PKCS7 over the 13-byte
     * content with the challenge-derived IV, prefixed by the cleartext header.
     */
    private fun encryptedNotification(
        sequence: Int,
        plaintext: ByteArray,
        challenge: ByteArray,
    ): ByteArray {
        val key = ICanHealthCrypto.keyFromASCII(ICanHealthConstants.DEFAULT_NEW_GLUCOSE_AES_KEY_ASCII)!!
        val encrypted = ICanHealthCrypto.encryptCbcPkcs7(plaintext, key, authIv(challenge))!!
        return byteArrayOf(
            (sequence and 0xFF).toByte(),
            ((sequence ushr 8) and 0xFF).toByte(),
            ICanHealthConstants.MEASUREMENT_TYPE_BYTE,
        ) + encrypted
    }

    /** Same IV layout the parser derives from the challenge: positions 1/6/10/15. */
    private fun authIv(challenge: ByteArray): ByteArray = ByteArray(16).apply {
        this[1] = challenge[0]
        this[6] = challenge[1]
        this[10] = challenge[2]
        this[15] = challenge[3]
    }

    /** The verified 13-byte live content, before PKCS7 padding is added on encryption. */
    private fun glucosePlaintext(
        sequence: Int,
        glucoseRaw: Int = 484,
        glucoseLow: Int = glucoseRaw and 0xFF,
        glucoseHigh: Int = (glucoseRaw ushr 8) and 0xFF,
        marker: Int = 0x0D,
        embeddedSequence: Int = sequence,
        launcherState: Int = 0x80,
    ): ByteArray = intArrayOf(
        marker,
        launcherState,
        glucoseLow,
        glucoseHigh,
        embeddedSequence and 0xFF,
        (embeddedSequence ushr 8) and 0xFF,
        0x01,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
    ).map { it.toByte() }.toByteArray()
}
