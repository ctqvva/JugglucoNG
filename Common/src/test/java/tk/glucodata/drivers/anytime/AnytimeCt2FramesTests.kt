package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Byte-level CT2 builders/parsers against live captures from SN08402458,
 * 2026-09-07 (see docs/ct-driver-plan.md §B0-B1).
 */
class AnytimeCt2FramesTests {

    private fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.replace(" ", "")
        require(cleaned.length % 2 == 0)
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun calendarFor(year: Int, month: Int, day: Int, hour: Int, min: Int, sec: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            clear()
            set(year, month - 1, day, hour, min, sec)
        }

    @Test
    fun handshakeMatchesLiveCapture() {
        val frame = AnytimeFrames.Builders.ct2Handshake("SN08402458")
        assertEquals(listOf(0x48, 0x53, 0x4E, 0x30, 0x38, 0x34, 0x30, 0x32, 0x34, 0x35, 0x38, 0x88),
            frame.map { it.toInt() and 0xFF })
    }

    @Test
    fun setDateMatchesLiveCapture() {
        val cal = calendarFor(2026, 9, 7, 20, 7, 24)
        assertEquals(listOf(0x54, 0x07, 0xEA, 0x09, 0x07, 0x14, 0x07, 0x18, 0x88),
            AnytimeFrames.Builders.ct2SetDate(cal).map { it.toInt() and 0xFF })
    }

    @Test
    fun checkIsStaticFrame() {
        assertEquals(listOf(0x43, 0x55, 0xAA, 0x42),
            AnytimeFrames.Builders.ct2Check().map { it.toInt() and 0xFF })
    }

    @Test
    fun initIsStaticFrame() {
        assertEquals(listOf(0x53, 0x55, 0xAA, 0x52),
            AnytimeFrames.Builders.ct2Init().map { it.toInt() and 0xFF })
    }

    @Test
    fun parseLivePushRecord() {
        val frame = AnytimeFrames.parseCt2GlucoseRecord(hexToBytes("4400001a0907143b008400151d64d7"))
        assertNotNull(frame)
        frame!!
        assertEquals(0, frame.record.glucoseId)
        assertEquals(13.2f, frame.record.iwNa, 0.01f)
        assertEquals(2.1f, frame.record.ibNa, 0.01f)
        assertEquals(29f, frame.record.temperatureC, 0.01f)
        assertFalse(frame.isHistorical)
        assertEquals(100, frame.batteryPercent)
    }

    @Test
    fun parseLivePullResponse() {
        val frame = AnytimeFrames.parseCt2GlucoseRecord(hexToBytes("47000e1a0907152a008100169efff2"))
        assertNotNull(frame)
        frame!!
        assertEquals(14, frame.record.glucoseId)
        assertTrue(frame.isHistorical)
        assertNull(frame.batteryPercent)
    }

    @Test
    fun ibIwOrderNotSwapped() {
        val frame = AnytimeFrames.parseCt2GlucoseRecord(hexToBytes("4400001a0907143b008400151d64d7"))
        assertNotNull(frame)
        frame!!
        assertTrue(frame.record.iwNa != frame.record.ibNa)
        assertEquals(13.2f, frame.record.iwNa, 0.01f)
        assertEquals(2.1f, frame.record.ibNa, 0.01f)
    }

    @Test
    fun checksumRejectsCorruptedFrame() {
        val corrupted = hexToBytes("4400001a0907143b008400151d64d8")
        assertNull(AnytimeFrames.parseCt2GlucoseRecord(corrupted))
    }

    @Test
    fun rejectsWrongSize() {
        assertNull(AnytimeFrames.parseCt2GlucoseRecord(hexToBytes("4400001a0907143b008400151d64")))
        assertNull(AnytimeFrames.parseCt2GlucoseRecord(hexToBytes("4400001a0907143b008400151d64d700")))
    }

    @Test
    fun rejectsUnknownOpcode() {
        val bytes = hexToBytes("4400001a0907143b008400151d64d7")
        bytes[0] = 0x45
        // fix checksum so only the opcode differs
        bytes[bytes.lastIndex] = AnytimeFrames.sum(bytes, 0, bytes.lastIndex - 1)
        assertNull(AnytimeFrames.parseCt2GlucoseRecord(bytes))
    }

    @Test
    fun parseCheckResponseMatchesLiveCapture() {
        // Live self-check: 43 00 82 00 17 1c 64 5c → Iw=13.0 Ib=2.3 T=28.0 power=100
        val result = AnytimeFrames.parseCt2CheckResponse(hexToBytes("43008200171c645c"))
        assertNotNull(result)
        result!!
        assertEquals(13.0f, result.iwNa, 0.01f)
        assertEquals(2.3f, result.ibNa, 0.01f)
        assertEquals(28.0f, result.temperatureC, 0.01f)
        assertEquals(100, result.powerByte)
        assertTrue(result.passed)
    }

    @Test
    fun checkResponseLowPowerFails() {
        // power byte 0x10 = 16 < 50
        val bytes = hexToBytes("43008200171c645c")
        bytes[6] = 0x10
        bytes[bytes.lastIndex] = AnytimeFrames.sum(bytes, 0, bytes.lastIndex - 1)
        val result = AnytimeFrames.parseCt2CheckResponse(bytes)
        assertNotNull(result)
        assertFalse(result!!.passed)
    }

    @Test
    fun temperatureDecodeHalfDegreeForHighBit() {
        assertEquals(28.5f, AnytimeFrames.decodeCt2Temperature(0x9C.toByte()), 0.01f)
        assertEquals(28f, AnytimeFrames.decodeCt2Temperature(0x1C.toByte()), 0.01f)
    }

    @Test
    fun pullGlucoseBuildsOfficialFrame() {
        assertEquals(listOf(0x55, 0x00, 0x0E, 0x63),
            AnytimeFrames.Builders.ct2PullGlucose(14).map { it.toInt() and 0xFF })
    }

    @Test
    fun inputBgMgBuildsOfficialFrame() {
        assertEquals(listOf(0x08, 0x00, 0x64, 0x6C),
            AnytimeFrames.Builders.ct2InputBgMg(100).map { it.toInt() and 0xFF })
    }

    @Test
    fun pullResponseWithMaxIdIsEndOfHistorySentinel() {
        // id = 0xFFFF on a pull response (0x47) — the transmitter's own
        // end-of-history marker, mirrored from the original POCTech driver's
        // signed-short id check (see docs/ct14-implementation-plan.md §5.4.1).
        val frame = AnytimeFrames.parseCt2GlucoseRecord(hexToBytes("47ffff1a0907143b008400151d64d8"))
        assertNotNull(frame)
        frame!!
        assertTrue(frame.isHistorical)
        assertTrue(frame.isEndOfHistory)
    }

    @Test
    fun livePushWithMaxIdIsNotTreatedAsSentinel() {
        // The same id=0xFFFF on a live push (0x44) is not the backfill
        // termination signal — the sentinel only applies to pull responses.
        val frame = AnytimeFrames.parseCt2GlucoseRecord(hexToBytes("44ffff1a0907143b008400151d64d5"))
        assertNotNull(frame)
        frame!!
        assertFalse(frame.isHistorical)
        assertFalse(frame.isEndOfHistory)
    }

    @Test
    fun ordinaryRecordsAreNotFlaggedAsEndOfHistory() {
        val push = AnytimeFrames.parseCt2GlucoseRecord(hexToBytes("4400001a0907143b008400151d64d7"))
        val pull = AnytimeFrames.parseCt2GlucoseRecord(hexToBytes("47000e1a0907152a008100169efff2"))
        assertFalse(push!!.isEndOfHistory)
        assertFalse(pull!!.isEndOfHistory)
    }
}
