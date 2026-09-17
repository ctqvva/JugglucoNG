package tk.glucodata.drivers.icanhealth

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthParserExtraTests {

    private val authKey = "1234567890123456".toByteArray()
    private val challenge = byteArrayOf(0x11, 0x22, 0x33, 0x44)

    private fun authIv(chal: ByteArray) = ByteArray(16).apply {
        this[1] = chal[0]
        this[6] = chal[1]
        this[10] = chal[2]
        this[15] = chal[3]
    }

    private fun sessionStart(
        year: Int = 2024,
        month: Int = 5,
        day: Int = 6,
        hour: Int = 7,
        minute: Int = 8,
        second: Int = 9,
        tz: Byte? = 8,
        dst: Byte? = 0,
    ): ByteArray {
        val out = ArrayList<Byte>(9)
        out += (year and 0xFF).toByte()
        out += ((year ushr 8) and 0xFF).toByte()
        out += month.toByte()
        out += day.toByte()
        out += hour.toByte()
        out += minute.toByte()
        out += second.toByte()
        tz?.let { out += it }
        dst?.let { out += it }
        return out.toByteArray()
    }

    private fun snFrame(subtype: Int, encryptedPayload: ByteArray): ByteArray {
        val out = ByteArray(6 + encryptedPayload.size + 1)
        out[0] = 0x53
        out[1] = 0x4E
        out[2] = (out.size - 3).toByte()
        out[3] = 0x2A
        out[4] = 0x52
        out[5] = subtype.toByte()
        encryptedPayload.copyInto(out, 6)
        return out
    }

    // ---- Session start time ----

    @Test
    fun sessionStartParsesFieldsAndOffsets() {
        val parsed = ICanHealthParser.parseSessionStartTime(sessionStart())!!
        assertEquals(2024, parsed.year)
        assertEquals(5, parsed.month)
        assertEquals(6, parsed.day)
        assertEquals(7, parsed.hour)
        assertEquals(8, parsed.minute)
        assertEquals(9, parsed.second)
        assertEquals(8, parsed.utcOffset15MinOrNull())
    }

    @Test
    fun sessionStartTreatsUnknownAndOutOfRangeFields() {
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStart(tz = 0x80.toByte()))!!.timezoneOffset15Min)
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStart(dst = 0xFF.toByte()))!!.dstOffset15Min)
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStart(tz = 57))!!.timezoneOffset15Min)
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStart(dst = 9))!!.dstOffset15Min)
    }

    @Test
    fun sessionStartRejectsInvalidDatesAndShortData() {
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStart(year = 0)))
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStart(month = 13)))
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStart(day = 32)))
        assertNull(ICanHealthParser.parseSessionStartTime(ByteArray(6)))
    }

    @Test
    fun sessionStartRoundTripsNonZeroOffsets() {
        val epoch = 1_700_000_000_000L
        for (zoneId in listOf("GMT+02:00", "GMT-05:00")) {
            val zone = TimeZone.getTimeZone(zoneId)
            val bytes = ICanHealthParser.buildSessionStartWrite(epoch, zone)
            val parsed = ICanHealthParser.parseSessionStartTime(bytes)!!
            assertEquals(zoneId, epoch, parsed.toEpochMillis())
        }
    }

    @Test
    fun zeroOrMissingOffsetUsesLocalWallClock() {
        val expected = Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(2024, 4, 6, 7, 8, 9)
        }.timeInMillis
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStart(tz = null, dst = null))!!.utcOffset15MinOrNull())
        assertEquals(expected, ICanHealthParser.parseSessionStartTime(sessionStart(tz = null, dst = null))!!.toEpochMillis())
        assertEquals(expected, ICanHealthParser.parseSessionStartTime(sessionStart(tz = 0))!!.toEpochMillis())
    }

    // ---- Status ----

    @Test
    fun cgmStatusParsesSequenceStateAndFlags() {
        val parsed = ICanHealthParser.parseCgmStatus(byteArrayOf(0x34, 0x12, 0xAA.toByte(), 0xBB.toByte()))!!
        assertEquals(0x1234, parsed.sequenceNumber)
        assertEquals(0xAA, parsed.launcherState)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), parsed.rawFlags)
        assertEquals(0x1234, ICanHealthParser.parseTimeOffset(byteArrayOf(0x34, 0x12, 0x03)))
    }

    @Test
    fun shortStatusIsRejected() {
        assertNull(ICanHealthParser.parseCgmStatus(byteArrayOf(1)))
        assertEquals(-1, ICanHealthParser.parseTimeOffset(byteArrayOf(1)))
    }

    // ---- SN frames ----

    @Test
    fun snNotificationAndTypeDetection() {
        assertTrue(ICanHealthParser.isSNNotification(byteArrayOf(0x53, 0x4E)))
        assertFalse(ICanHealthParser.isSNNotification(byteArrayOf(0x00, 0x4E)))
        assertEquals(0x01, ICanHealthParser.parseSNType(byteArrayOf(0x53, 0x4E, 0x10, 0x2A, 0x52, 0x01)))
        assertNull(ICanHealthParser.parseSNType(byteArrayOf(0x53, 0x4E, 0x10, 0x2A, 0x52)))
        assertNull(ICanHealthParser.parseSNType(byteArrayOf(0x53, 0x4E, 0x10, 0x00, 0x52, 0x01)))
    }

    // ---- Auth ----

    @Test
    fun challengeResponseExtraction() {
        val data = byteArrayOf(0x06, 0xF3.toByte(), 0x01, 0x11, 0x22, 0x33, 0x44)
        assertTrue(ICanHealthParser.isChallengeResponse(data))
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), ICanHealthParser.extractChallenge(data))
        assertFalse(ICanHealthParser.isChallengeResponse(ByteArray(6)))
        assertNull(ICanHealthParser.extractChallenge(ByteArray(6)))
    }

    @Test
    fun authSuccessAndResult() {
        val data = byteArrayOf(0x1C, 0xF4.toByte(), 0x01)
        assertTrue(ICanHealthParser.isAuthSuccess(data))
        assertEquals(0x01, ICanHealthParser.parseAuthResult(data))
        assertNull(ICanHealthParser.parseAuthResult(byteArrayOf(0x1C, 0x00, 0x01)))
        assertNull(ICanHealthParser.parseAuthResult(byteArrayOf(0x1C)))
    }

    @Test
    fun authTokenPacketRequires16ByteToken() {
        val token = ByteArray(16) { it.toByte() }
        val packet = ICanHealthParser.buildAuthTokenPacket(token)!!
        assertEquals(18, packet.size)
        assertEquals(ICanHealthConstants.AUTH_TOKEN_PREFIX, packet[0])
        assertEquals(ICanHealthConstants.AUTH_TOKEN_LENGTH_BYTE, packet[1])
        assertArrayEquals(token, packet.copyOfRange(2, 18))
        assertNull(ICanHealthParser.buildAuthTokenPacket(ByteArray(15)))
    }

    @Test
    fun deriveAuthTokenProducesSingleBlock() {
        val token = ICanHealthParser.deriveAuthToken(challenge, "USER123", authKey)
        assertEquals(16, token?.size)
        assertNull(ICanHealthParser.deriveAuthToken(byteArrayOf(1, 2, 3), "USER123", authKey))
    }

    @Test
    fun recoverUserIdFromAuthRejectDecryptsTrailer() {
        val iv = authIv(challenge)
        val cipher = ICanHealthCrypto.encryptCbcPkcs7("AB12".toByteArray(), authKey, iv)!!
        val data = byteArrayOf(0x00, 0xF4.toByte(), 0x02) + cipher + byteArrayOf(0x01)
        assertEquals("AB12", ICanHealthParser.recoverUserIdFromAuthReject(data, challenge, authKey))
    }

    @Test
    fun recoverUserIdRejectsInapplicableResponses() {
        val iv = authIv(challenge)
        val cipher = ICanHealthCrypto.encryptCbcPkcs7("AB12".toByteArray(), authKey, iv)!!
        assertNull(ICanHealthParser.recoverUserIdFromAuthReject(ByteArray(19), challenge, authKey))
        assertNull(ICanHealthParser.recoverUserIdFromAuthReject(byteArrayOf(0, 0xF4.toByte(), 0x03) + cipher + byteArrayOf(1), challenge, authKey))
        assertNull(ICanHealthParser.recoverUserIdFromAuthReject(byteArrayOf(0, 0xF4.toByte(), 0x02) + cipher + byteArrayOf(0), challenge, authKey))
        assertNull(ICanHealthParser.recoverUserIdFromAuthReject(byteArrayOf(0, 0xF4.toByte(), 0x02) + cipher + byteArrayOf(1), null, authKey))
    }

    // ---- Result codes ----

    @Test
    fun racpResultCodes() {
        assertEquals(ICanHealthConstants.RACP_RESULT_SUCCESS, ICanHealthParser.parseRacpResultCode(byteArrayOf(0x06, 0x00, 0xF1.toByte(), 0x01)))
        assertEquals(ICanHealthConstants.RACP_RESULT_NO_DATA, ICanHealthParser.parseRacpResultCode(byteArrayOf(0x06, 0x00, 0x01, 0x06)))
        assertTrue(ICanHealthParser.isRACPComplete(byteArrayOf(0x06, 0x00, 0xF1.toByte(), 0x01)))
        assertFalse(ICanHealthParser.isRACPComplete(byteArrayOf(0x06, 0x00, 0x01, 0x06)))
        assertNull(ICanHealthParser.parseRacpResultCode(byteArrayOf(0x06, 0x01, 0xF1.toByte(), 0x01)))
        assertNull(ICanHealthParser.parseRacpResultCode(byteArrayOf(0x06, 0x00, 0xF1.toByte())))
    }

    @Test
    fun sensorAndCalibrationResults() {
        assertTrue(ICanHealthParser.parseStartSensorResult(byteArrayOf(0x00, 0x1A, 0x01))!!)
        assertFalse(ICanHealthParser.parseStartSensorResult(byteArrayOf(0x00, 0x1A, 0x00))!!)
        assertNull(ICanHealthParser.parseStartSensorResult(byteArrayOf(0x00, 0x1B, 0x01)))

        assertEquals(0x01, ICanHealthParser.parseCalibrationResult(byteArrayOf(0x00, ICanHealthConstants.CALIBRATION_OPCODE, 0x01)))
        assertNull(ICanHealthParser.parseCalibrationResult(byteArrayOf(0x00, 0x00, 0x01)))

        assertTrue(ICanHealthParser.isSensorInfoResponse(byteArrayOf(0x1C, 0xF6.toByte(), 0x01)))
        assertFalse(ICanHealthParser.isSensorInfoResponse(byteArrayOf(0x1C, 0xF6.toByte(), 0x02)))
    }

    // ---- Serial numbers ----

    @Test
    fun rawAndCanonicalDeviceSerial() {
        assertEquals("ABC123", ICanHealthParser.parseRawDeviceSerial("  ABC123  ".toByteArray()))
        assertEquals("00FF", ICanHealthParser.parseRawDeviceSerial(byteArrayOf(0, 0xFF.toByte())))
        assertEquals("", ICanHealthParser.parseRawDeviceSerial(byteArrayOf()))
        assertEquals("ABCDEFGHIJKLMNOP", ICanHealthParser.parseDeviceSerial("abcdefghijklmnop".toByteArray()))
    }

    // ---- Calibration packet ----

    @Test
    fun calibrationPacketIsPrefixedAndEncrypted() {
        val packet = ICanHealthParser.buildCalibrationPacket(180, 5, challenge, authKey)!!
        assertEquals(ICanHealthConstants.CALIBRATION_OPCODE, packet[0])
        assertEquals(16, packet[1].toInt())
        assertEquals(18, packet.size)
        assertNull(ICanHealthParser.buildCalibrationPacket(180, 5, byteArrayOf(1, 2, 3), authKey))
    }

    // ---- SN history batch ----

    @Test
    fun snOriginalHistoryBatchDecodesRecords() {
        val key = ICanHealthCrypto.keyFromASCII(ICanHealthConstants.resolveBundledOriginalHistoryKey(null, null))!!
        val iv = authIv(challenge)
        val plaintext = ByteArray(8)
        plaintext[0] = 0x55
        plaintext[1] = 0x0A
        plaintext[2] = 0x00
        plaintext[3] = 0x01 // current lo
        plaintext[4] = 0x02 // current hi
        plaintext[5] = 0x40 // temperature lo (64 -> 6.4C)
        plaintext[6] = 0x00
        plaintext[7] = 0xAA.toByte()
        val frame = snFrame(ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL, ICanHealthCrypto.encryptCbcPkcs7(plaintext, key, iv)!!)

        val batch = ICanHealthParser.parseSnHistoryBatch(frame, challenge, null, null, null, readingIntervalMinutes = 3)!!
        assertEquals(ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL, batch.subtype)
        assertEquals(0x000A, batch.baseSequenceNumber)
        assertEquals(1, batch.records.size)
        assertEquals(0x000A, batch.records[0].sequenceNumber)
        assertEquals(6.4f, batch.records[0].temperatureC!!, 0.0001f)
    }

    @Test
    fun snGlucoseHistoryBatchDecodesGlucose() {
        val key = ICanHealthCrypto.keyFromASCII(ICanHealthConstants.resolveBundledGlucoseKey(null, null))!!
        val iv = authIv(challenge)
        val plaintext = ByteArray(6)
        plaintext[0] = 0x55
        plaintext[1] = 0x64
        plaintext[2] = 0x00
        plaintext[3] = 0x40
        plaintext[4] = 0x03
        plaintext[5] = 0xAA.toByte()
        val frame = snFrame(ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE, ICanHealthCrypto.encryptCbcPkcs7(plaintext, key, iv)!!)

        val batch = ICanHealthParser.parseSnHistoryBatch(frame, challenge, null, null, null, readingIntervalMinutes = 3)!!
        assertEquals(1, batch.records.size)
        assertEquals(8.32f, batch.records[0].glucoseMmolL!!, 0.0001f)
        assertEquals(8.32f * ICanHealthConstants.MMOL_TO_MGDL, batch.records[0].glucoseMgdl!!, 0.001f)
    }

    @Test
    fun snHistoryBatchRejectsUnknownSubtypeAndShortFrames() {
        val shortFrame = snFrame(ICanHealthConstants.SN_HISTORY_SUBTYPE_GLUCOSE, ByteArray(0))
        assertNull(ICanHealthParser.parseSnHistoryBatch(shortFrame, challenge, null, null, null, 3))

        val unknown = snFrame(0x03, ByteArray(16))
        assertNull(ICanHealthParser.parseSnHistoryBatch(unknown, challenge, null, null, null, 3))
    }

    // ---- Raw encrypted history record ----

    @Test
    fun encryptedOriginalHistoryRecordDecodes() {
        val key = ICanHealthCrypto.keyFromASCII(ICanHealthConstants.resolveBundledOriginalHistoryKey(null, null))!!
        val iv = authIv(challenge)
        val plaintext = ByteArray(15)
        plaintext[11] = 0x40 // temperature 6.4C
        val cipher = ICanHealthCrypto.encryptCbcPkcs7(plaintext, key, iv)!!
        assertEquals(16, cipher.size)

        val record = ICanHealthParser.parseEncryptedHistoryRecord(
            data = cipher,
            authChallenge = challenge,
            readingOriginalHistory = true,
            onboardingDeviceSn = null,
            launcherSerial = null,
            softwareVersion = null,
            warmupMinutes = 0,
        )!!
        assertEquals(ICanHealthConstants.SN_HISTORY_SUBTYPE_ORIGINAL, record.subtype)
        assertEquals(6.4f, record.temperatureC!!, 0.0001f)
    }

    @Test
    fun encryptedGlucoseHistoryRecordDecodes() {
        val key = ICanHealthCrypto.keyFromASCII(ICanHealthConstants.resolveBundledGlucoseKey(null, null))!!
        val iv = authIv(challenge)
        val plaintext = ByteArray(15)
        plaintext[2] = 0x40
        plaintext[3] = 0x03
        val cipher = ICanHealthCrypto.encryptCbcPkcs7(plaintext, key, iv)!!

        val record = ICanHealthParser.parseEncryptedHistoryRecord(
            data = cipher,
            authChallenge = challenge,
            readingOriginalHistory = false,
            onboardingDeviceSn = null,
            launcherSerial = null,
            softwareVersion = null,
            warmupMinutes = 0,
        )!!
        assertEquals(8.32f, record.glucoseMmolL!!, 0.0001f)
    }

    @Test
    fun encryptedHistoryRecordRejectsUnsupportedLength() {
        assertNull(
            ICanHealthParser.parseEncryptedHistoryRecord(
                data = ByteArray(17),
                authChallenge = challenge,
                readingOriginalHistory = true,
                onboardingDeviceSn = null,
                launcherSerial = null,
                softwareVersion = null,
                warmupMinutes = 0,
            )
        )
    }
}
