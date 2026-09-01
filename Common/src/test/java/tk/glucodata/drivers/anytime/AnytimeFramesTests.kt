package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeFramesTests {

    @Test
    fun parseCheckResponseUsesOfficialBigEndianCurrentFieldWhenDiagnosticLooksImplausible() {
        val status = AnytimeFrames.parseCheckResponse(
            byteArrayOf(
                0x05,
                0x12,
                0x05,
                0x64,
                0x00,
                0x01,
                0xC0.toByte(),
                0x48,
                0x62,
                0x04,
                0x1B,
                0x06,
                0xD1.toByte(),
                0x06,
                0xD2.toByte(),
                0x05,
                0x18,
                0x00,
                0x00,
                0xD6.toByte(),
            ),
            AnytimeConstants.BATTERY_LOW_VOLTS_CT3_A,
        )

        assertTrue(status.isHealthy)
        assertEquals(1380, status.sensorAgeReadings)
        assertEquals(13.8f, status.workingElectrodeCurrentNa, 0.001f)
        assertEquals(4.27f, status.batteryVolts, 0.001f)
    }

    @Test
    fun ct4CheckDoesNotTreat398VoltsAsLowBattery() {
        val status = AnytimeFrames.parseCheckResponse(
            byteArrayOf(
                0x05,
                0x12,
                0x02,
                0x78,
                0x00,
                0x01,
                0x42,
                0x49,
                0x0B,
                0x03,
                0x62,
                0x06,
                0xD1.toByte(),
                0x06,
                0xD1.toByte(),
                0x05,
                0x18,
                0x00,
                0x00,
                0x58,
            ),
            AnytimeConstants.BATTERY_LOW_VOLTS_CT4,
        )

        assertTrue(status.isHealthy)
        assertNull(status.failure)
        assertEquals(3.98f, status.batteryVolts, 0.001f)
        assertEquals(82, AnytimeFrames.batteryPercent(status.batteryVolts, AnytimeConstants.BATTERY_LOW_VOLTS_CT4))
    }

    @Test
    fun lowBatteryCheckIsAdvisoryNotSessionBlocking() {
        val status = AnytimeFrames.parseCheckResponse(
            byteArrayOf(
                0x05,
                0x12,
                0x02,
                0x78,
                0x00,
                0x01,
                0x42,
                0x49,
                0x0B,
                0x03,
                0x62,
                0x06,
                0xD1.toByte(),
                0x06,
                0xD1.toByte(),
                0x05,
                0x18,
                0x00,
                0x00,
                0x58,
            ),
            AnytimeConstants.BATTERY_LOW_VOLTS_CT3,
        )

        assertTrue(status.isHealthy)
        assertEquals(AnytimeCheckStatus.CheckFailure.LOW_BATTERY, status.failure)
    }

    @Test
    fun parseWideRawRecordsMatchesOfficialCt4ByteOrder() {
        val records = AnytimeFrames.parseRawRecords(
            byteArrayOf(
                0x07,
                0x93.toByte(),
                0x00,
                0x01,
                0xB7.toByte(),
                0x05,
                0x5E,
                0x49,
                0x0A,
                0x04,
                0x1B,
                0x27,
            ),
            wideRecords = true,
        )

        assertEquals(1, records.size)
        val rec = records.single()
        assertEquals(147, rec.glucoseId)
        assertEquals(4.39f, rec.ibNa, 0.001f)
        assertEquals(13.74f, rec.iwNa, 0.001f)
        assertEquals(33.10f, rec.temperatureC, 0.001f)
    }

    @Test
    fun pullGlucoseSeriesSummedBuildsOfficialCt4BatchRequest() {
        val frame = AnytimeFrames.Builders.pullGlucoseSeriesSummed(147, 15)

        assertEquals(
            listOf(0x22, 0x93, 0x00, 0x0F, 0xC4),
            frame.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun parseWideRawSeriesRecordsMatchesOfficialCt4BatchLayout() {
        val frame = byteArrayOf(
            0x22,
            0x93.toByte(),
            0x00,
            0x01,
            0xB7.toByte(),
            0x05,
            0x5E,
            0x49,
            0x0A,
            0x01,
            0xC2.toByte(),
            0x05,
            0x64,
            0x49,
            0x19,
            0x00,
        )
        frame[frame.lastIndex] = AnytimeFrames.sum(frame, 0, frame.lastIndex - 1)

        val records = AnytimeFrames.parseWideRawSeriesRecords(frame)

        assertEquals(2, records.size)
        assertEquals(147, records[0].glucoseId)
        assertEquals(4.39f, records[0].ibNa, 0.001f)
        assertEquals(13.74f, records[0].iwNa, 0.001f)
        assertEquals(33.10f, records[0].temperatureC, 0.001f)
        assertEquals(148, records[1].glucoseId)
        assertEquals(4.50f, records[1].ibNa, 0.001f)
        assertEquals(13.80f, records[1].iwNa, 0.001f)
        assertEquals(33.25f, records[1].temperatureC, 0.001f)
    }

    @Test
    fun parseWideRawSeriesRecordsAcceptsEmptyCaughtUpFrame() {
        val frame = byteArrayOf(0x22, 0x93.toByte(), 0x00, 0x00)
        frame[frame.lastIndex] = AnytimeFrames.sum(frame, 0, frame.lastIndex - 1)

        assertTrue(AnytimeFrames.parseWideRawSeriesRecords(frame).isEmpty())
    }

    @Test
    fun parseWideRawSeriesRecordsAcceptsLegacyRawDumpOpcode() {
        val frame = byteArrayOf(
            0x0D,
            0x93.toByte(),
            0x00,
            0x01,
            0xB7.toByte(),
            0x05,
            0x5E,
            0x49,
            0x0A,
            0x01,
            0xC2.toByte(),
            0x05,
            0x64,
            0x49,
            0x19,
            0x00,
        )
        frame[frame.lastIndex] = AnytimeFrames.sum(frame, 0, frame.lastIndex - 1)

        val records = AnytimeFrames.parseWideRawSeriesRecords(frame)

        assertEquals(2, records.size)
        assertEquals(147, records[0].glucoseId)
        assertEquals(4.39f, records[0].ibNa, 0.001f)
        assertEquals(13.74f, records[0].iwNa, 0.001f)
        assertEquals(33.10f, records[0].temperatureC, 0.001f)
        assertEquals(148, records[1].glucoseId)
        assertEquals(4.50f, records[1].ibNa, 0.001f)
        assertEquals(13.80f, records[1].iwNa, 0.001f)
        assertEquals(33.25f, records[1].temperatureC, 0.001f)
    }

    @Test
    fun inputBgRoundsToOfficialOneDecimalMmol() {
        assertEquals(
            listOf(0x09, 0x05, 0x06),
            AnytimeFrames.Builders.inputBgMg(100).map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(0x09, 0x05, 0x06, 0x14),
            AnytimeFrames.Builders.inputBgMgSummed(100).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun ct5InputBgUsesOfficialBigEndianMgdlFrame() {
        assertEquals(
            listOf(0x09, 0x00, 0x64, 0x6D),
            AnytimeFrames.Builders.ct5InputBgMg(100).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun ct5EndCycleBuildsOfficialAppFrame() {
        assertEquals(
            listOf(0x58, 0x55, 0xAA, 0x57),
            AnytimeFrames.Builders.ct5EndCycle().map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun ct5EncodeDecodeRoundTripMatchesOfficialInversePair() {
        val plain = byteArrayOf(0x01, 0x09, 0x01, 0x00, 0x03, 0x53, 0x55, 0x00, 0x31, 0x32, 0x33, 0x34)
        val key = 0x5A

        val encrypted = AnytimeFrames.ct5Decode(plain, key)

        assertTrue(encrypted.toList() != plain.toList())
        assertEquals(plain.toList(), AnytimeFrames.ct5Encode(encrypted, key).toList())
    }

    @Test
    fun parseCt5CurrentRecordDecryptsOfficialLayout() {
        val key = 0x5A
        val plainPayload = byteArrayOf(
            0x01,
            0xB7.toByte(), // Ib 4.39 nA
            0x05,
            0x5E, // Iw 13.74 nA
            0x49,
            0x0A, // 33.10 C
            0x50, // trend 5, glucose high nibble 0
            0x7B, // 123 mg/dL
            0x00, // error
            0x00,
            0x00,
        )
        val encrypted = AnytimeFrames.ct5Decode(plainPayload, key)
        val frame = ByteArray(15)
        frame[0] = AnytimeConstants.RX_CT5_PUSH_GLUCOSE
        frame[1] = 0x93.toByte()
        frame[2] = 0x00
        encrypted.copyInto(frame, destinationOffset = 3)
        frame[14] = AnytimeFrames.sum(frame, 0, 13)

        val rec = AnytimeFrames.parseCt5CurrentRecord(frame, key)

        assertNotNull(rec)
        rec!!
        assertEquals(147, rec.glucoseId)
        assertEquals(4.39f, rec.ibNa, 0.001f)
        assertEquals(13.74f, rec.iwNa, 0.001f)
        assertEquals(33.10f, rec.temperatureC, 0.001f)
        assertEquals(123, rec.gluMgdl)
        assertEquals(5, rec.trend)
        assertEquals(0, rec.errorCode)
    }

    @Test
    fun inferCt5CipherFromObservedUsedSensorLivePush() {
        val frame = byteArrayOf(
            0x35,
            0xE7.toByte(),
            0x00,
            0xD8.toByte(),
            0xD8.toByte(),
            0xD9.toByte(),
            0xD6.toByte(),
            0x1E,
            0xC8.toByte(),
            0xE7.toByte(),
            0x14,
            0x27,
            0x25,
            0x31,
            0xD9.toByte(),
        )

        val candidate = AnytimeFrames.inferCt5CipherFromCurrentRecord(frame)

        assertNotNull(candidate)
        candidate!!
        assertTrue(candidate.cipherKey in 0..255)
        assertEquals(231, candidate.record.glucoseId)
        assertEquals(0.0f, candidate.record.ibNa, 0.001f)
        assertEquals(7.87f, candidate.record.iwNa, 0.001f)
        assertEquals(34.48f, candidate.record.temperatureC, 0.001f)
        assertEquals(85, candidate.record.gluMgdl)
        assertEquals(4, candidate.record.trend)
        assertEquals(0, candidate.record.errorCode)
    }

    @Test
    fun inferCt5CipherRejectsBadChecksum() {
        val frame = byteArrayOf(
            0x35,
            0xE7.toByte(),
            0x00,
            0xD8.toByte(),
            0xD8.toByte(),
            0xD9.toByte(),
            0xD6.toByte(),
            0x1E,
            0xC8.toByte(),
            0xE7.toByte(),
            0x14,
            0x27,
            0x25,
            0x31,
            0x00,
        )

        assertNull(AnytimeFrames.inferCt5CipherFromCurrentRecord(frame))
    }
}
