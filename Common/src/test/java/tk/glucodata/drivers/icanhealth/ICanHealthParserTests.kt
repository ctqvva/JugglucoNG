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
    fun parseGlucoseNotification_warmupXorUsesFirstChallengeByte() {
        val sequence = 60
        // Distinct bytes, so keying the offset on any other challenge byte decodes a different value.
        val challenge = byteArrayOf(0x07, 0x02, 0x03, 0x04)
        val table = ICanHealthConstants.LEGACY_HISTORY_GLUCOSE_XOR_TABLE_NEW
        val offset = 7
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

    /**
     * Warm-up frames from the official iOS client's capture of an iCGM-t3 (iPhone PacketLogger,
     * 2026-09-26/27): the raw ATT notification and the `06 F3 01` challenge that preceded it on the
     * same connection. The expected values are the sensor's own SN history records at the same
     * sequence in that capture, which carry no warm-up obfuscation.
     */
    @Test
    fun parseGlucoseNotification_capturedWarmupFramesMatchHistory() {
        val seq18 = parse(
            data = hex("12 00 02 C1 CD 90 F4 FD AF 6C 42 AC D5 C9 87 B6 52 2B 7D"),
            authChallenge = hex("30 13 AA 84"),
            warmupMinutes = 120,
        )
        assertNotNull(seq18)
        assertEquals(18, seq18!!.sequenceNumber)
        assertEquals(0, seq18.dataState)
        assertEquals(597, seq18.mantissa)

        val seq117 = parse(
            data = hex("75 00 02 D4 22 8E 35 9E 64 5F 00 98 00 FF 86 4A CA 2F 4A"),
            authChallenge = hex("F8 93 7B 8C"),
            warmupMinutes = 120,
        )
        assertNotNull(seq117)
        assertEquals(117, seq117!!.sequenceNumber)
        assertEquals(0, seq117.dataState)
        assertEquals(385, seq117.mantissa)
    }

    /**
     * The frame from issue #731 (iCan i3, fw v3.2.8, seq 99 of warm-up), discarded as 731 mg/dL while
     * the sensor's history held 8.2 mmol/L. The log does not print the challenge, but its ECB-decoded
     * bytes at IV positions 1/6/10/15 are 86/D5/8E/F9. XOR with the fixed CBC plaintext bytes
     * 80/01/00/03 recovers the full challenge 06/D4/8E/FA. The old ECB test needed only byte 0;
     * CBC also needs the remaining bytes to recover the frame and validate its padding.
     */
    @Test
    fun parseGlucoseNotification_issue731WarmupFrameDecodesToHistoryValue() {
        val reading = parse(
            data = hex("63 00 02 DB 0C 40 27 65 C0 5F 94 2F B2 0A 39 AB 3D 0F 72"),
            authChallenge = hex("06 D4 8E FA"),
            warmupMinutes = 120,
        )

        assertNotNull(reading)
        assertEquals(99, reading!!.sequenceNumber)
        assertEquals(0, reading.dataState)
        assertEquals(822, reading.mantissa)
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

    private fun hex(text: String): ByteArray =
        text.split(" ").map { it.toInt(16).toByte() }.toByteArray()

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
