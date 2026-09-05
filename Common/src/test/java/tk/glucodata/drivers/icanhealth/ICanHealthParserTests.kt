package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ICanHealthParserTests {

    @Test
    fun parseGlucoseNotification_acceptsIntegrityCheckedLiveFrame() {
        val sequence = 1551
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, glucoseRaw = 484)
        )

        val reading = parse(data, warmupMinutes = 0)

        assertNotNull(reading)
        assertEquals(sequence, reading!!.sequenceNumber)
        assertEquals(4.84f, reading.glucoseMmolL, 0.001f)
        assertEquals(36.5f, reading.temperatureC, 0.001f)
    }

    @Test
    fun parseGlucoseNotification_rejectsBadSizeMarker() {
        val sequence = 1551
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, marker = 0x0C)
        )

        assertNull(parse(data, warmupMinutes = 0))
    }

    @Test
    fun parseGlucoseNotification_rejectsSequenceMismatch() {
        val sequence = 1551
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, embeddedSequence = sequence - 3)
        )

        assertNull(parse(data, warmupMinutes = 0))
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
            )
        )

        val reading = parse(data, authChallenge = challenge, warmupMinutes = 120)

        assertNotNull(reading)
        assertEquals(4.50f, reading!!.glucoseMmolL, 0.001f)
    }

    @Test
    fun parseGlucoseNotification_rejectsWarmupFrameWithoutChallenge() {
        val sequence = 60
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, glucoseRaw = 2960)
        )

        assertNull(parse(data, authChallenge = null, warmupMinutes = 120))
    }

    @Test
    fun parseGlucoseNotification_rejectsWarmupFrameWithShortChallenge() {
        val sequence = 60
        val data = encryptedNotification(
            sequence = sequence,
            plaintext = glucosePlaintext(sequence = sequence, glucoseRaw = 2960)
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

    private fun encryptedNotification(sequence: Int, plaintext: ByteArray): ByteArray {
        val key = ICanHealthCrypto.keyFromASCII(ICanHealthConstants.DEFAULT_NEW_GLUCOSE_AES_KEY_ASCII)!!
        val encrypted = ICanHealthCrypto.encryptBlock(plaintext, key)!!
        return byteArrayOf(
            (sequence and 0xFF).toByte(),
            ((sequence ushr 8) and 0xFF).toByte(),
            ICanHealthConstants.MEASUREMENT_TYPE_BYTE,
        ) + encrypted
    }

    private fun glucosePlaintext(
        sequence: Int,
        glucoseRaw: Int = 484,
        glucoseLow: Int = glucoseRaw and 0xFF,
        glucoseHigh: Int = (glucoseRaw ushr 8) and 0xFF,
        marker: Int = 0x0D,
        embeddedSequence: Int = sequence,
    ): ByteArray = intArrayOf(
        marker,
        0xB6,
        glucoseLow,
        glucoseHigh,
        embeddedSequence and 0xFF,
        (embeddedSequence ushr 8) and 0xFF,
        0x4A,
        0x00,
        0x00,
        0x88,
        0x88,
        0x6D,
        0x01,
        0x03,
        0x03,
        0x00,
    ).map { it.toByte() }.toByteArray()
}
