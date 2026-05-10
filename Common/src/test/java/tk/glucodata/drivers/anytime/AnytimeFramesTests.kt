package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
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
}
