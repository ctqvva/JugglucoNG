package tk.glucodata.drivers.icanhealth

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression cover for the iCan session-start timezone skew.
 *
 * A trace from an i6 on a UTC+3 phone showed every session-derived timestamp landing exactly
 * 10,798,617 ms — three hours — ahead of the wall clock, because the sensor sends the session
 * start as a local wall clock with no usable Time Zone field and the parser read it as UTC.
 */
class ICanHealthSessionStartTimeTests {

    private val originalDefaultZone: TimeZone = TimeZone.getDefault()

    @Before
    fun useKyivZone() {
        // UTC+3 year round, matching the device that produced the trace.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Kyiv"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalDefaultZone)
    }

    @Test
    fun parseSessionStartTime_readsTimeZoneAndDstAsSeparateBytes() {
        // UTC+1 standard time (tz = 4) with a one-hour DST offset (dst = 4) is UTC+2 overall.
        val parsed = ICanHealthParser.parseSessionStartTime(sessionStartBytes(tz = 4, dst = 4))

        assertNotNull(parsed)
        assertEquals(4, parsed!!.timezoneOffset15Min)
        assertEquals(4, parsed.dstOffset15Min)
        assertEquals(8, parsed.utcOffset15MinOrNull())
    }

    @Test
    fun parseSessionStartTime_readingBytesAsInt16WouldFoldDstIntoTheOffset() {
        // The old parser combined bytes 7 and 8 into one little-endian int16, so a plain
        // one-hour DST offset in byte 8 became 4 + (4 shl 8) = 1028 quarter hours.
        val parsed = ICanHealthParser.parseSessionStartTime(sessionStartBytes(tz = 4, dst = 4))

        assertEquals(8, parsed!!.utcOffset15MinOrNull())
    }

    @Test
    fun parseSessionStartTime_treatsSpecUnknownSentinelsAsAbsent() {
        val parsed = ICanHealthParser.parseSessionStartTime(sessionStartBytes(tz = -128, dst = 255))

        assertNotNull(parsed)
        assertNull(parsed!!.timezoneOffset15Min)
        assertNull(parsed.dstOffset15Min)
        assertNull(parsed.utcOffset15MinOrNull())
    }

    @Test
    fun parseSessionStartTime_rejectsOutOfRangeOffsets() {
        // 100 quarter hours is UTC+25; junk, not a timezone.
        val parsed = ICanHealthParser.parseSessionStartTime(sessionStartBytes(tz = 100, dst = 0))

        assertNull(parsed!!.timezoneOffset15Min)
    }

    @Test
    fun parseSessionStartTime_treatsVendorConstantOffsetAsUnknown() {
        // The vendor app hard-codes 0x14 (+5:00) next to every 2AAA wall clock, whatever the
        // phone's real zone is, so the field cannot be believed. Captures from a +05 iPhone
        // (2026-09-27) and a +08 Android phone (2026-08-19) both carry 0x14 while the 2AA9
        // counter agrees with the local wall clock in both cases.
        val parsed = ICanHealthParser.parseSessionStartTime(sessionStartBytes(tz = 0x14, dst = 0))

        assertNotNull(parsed)
        assertNull(parsed!!.timezoneOffset15Min)
        assertNull(parsed.utcOffset15MinOrNull())
    }

    @Test
    fun toEpochMillis_vendorConstantOffsetResolvesToTheLocalWallClock() {
        // Session start 2026-09-27 02:08:45 stored with the vendor's fake +5:00 on a phone in
        // another zone: the instant must be that wall clock in the device zone, not
        // "02:08:45 local minus five hours".
        val parsed = ICanHealthParser.parseSessionStartTime(
            sessionStartBytes(
                month = 9, day = 27, hour = 2, minute = 8, second = 45, tz = 0x14, dst = 0,
            )
        )!!

        val epochMs = parsed.toEpochMillis()

        assertEquals(localMillis(month = 9, day = 27, hour = 2, minute = 8, second = 45), epochMs)
        // On the UTC+3 test zone the old reading of 0x14 was exactly three hours late.
        assertEquals(THREE_HOURS_MS, utcMillis(month = 9, day = 27, hour = 2, minute = 8, second = 45) - epochMs)
    }

    @Test
    fun parseSessionStartTime_toleratesSevenByteCharacteristic() {
        val short = sessionStartBytes(tz = 0, dst = 0).copyOfRange(0, 7)

        val parsed = ICanHealthParser.parseSessionStartTime(short)

        assertNotNull(parsed)
        assertNull(parsed!!.timezoneOffset15Min)
        assertNull(parsed.dstOffset15Min)
    }

    @Test
    fun toEpochMillis_withoutOffsetUsesDeviceLocalTimeNotUtc() {
        // The trace's session start: 2026-08-02 17:06:41 local on a UTC+3 phone.
        val parsed = ICanHealthParser.parseSessionStartTime(
            sessionStartBytes(hour = 17, minute = 6, second = 41, tz = -128, dst = 255)
        )!!

        assertEquals(localMillis(hour = 17, minute = 6, second = 41), parsed.toEpochMillis())
    }

    @Test
    fun toEpochMillis_zeroOffsetIsTreatedAsUnknownRatherThanUtc() {
        // The sensor in the trace sent a zero offset alongside a local wall clock. Reading that
        // as UTC is what put the session start three hours into the future.
        val parsed = ICanHealthParser.parseSessionStartTime(
            sessionStartBytes(hour = 17, minute = 6, second = 41, tz = 0, dst = 0)
        )!!

        val epochMs = parsed.toEpochMillis()

        assertEquals(localMillis(hour = 17, minute = 6, second = 41), epochMs)
        assertEquals(THREE_HOURS_MS, utcMillis(hour = 17, minute = 6, second = 41) - epochMs)
    }

    @Test
    fun toEpochMillis_honoursAGenuineNonZeroOffset() {
        // tz = 12 quarter hours is UTC+3 stated explicitly; the wall clock is local, so the
        // resulting instant must match the local reading above.
        val parsed = ICanHealthParser.parseSessionStartTime(
            sessionStartBytes(hour = 17, minute = 6, second = 41, tz = 12, dst = 0)
        )!!

        assertEquals(localMillis(hour = 17, minute = 6, second = 41), parsed.toEpochMillis())
    }

    @Test
    fun toEpochMillis_negativeOffsetSensorsResolveToTheSameLocalInstant() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

        // -16 quarter hours is UTC-4, New York's summer offset.
        val stated = ICanHealthParser.parseSessionStartTime(
            sessionStartBytes(hour = 17, minute = 6, second = 41, tz = -16, dst = 0)
        )!!
        val unknown = ICanHealthParser.parseSessionStartTime(
            sessionStartBytes(hour = 17, minute = 6, second = 41, tz = -128, dst = 255)
        )!!

        assertEquals(stated.toEpochMillis(), unknown.toEpochMillis())
    }

    @Test
    fun toEpochMillis_localFallbackAppliesTheDstRulesInForceAtThatWallTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Amsterdam"))

        val summer = ICanHealthParser.parseSessionStartTime(
            sessionStartBytes(month = 8, day = 2, hour = 12, tz = -128, dst = 255)
        )!!
        val winter = ICanHealthParser.parseSessionStartTime(
            sessionStartBytes(month = 1, day = 2, hour = 12, tz = -128, dst = 255)
        )!!

        // Noon local in August is UTC+2, noon local in January is UTC+1. A fixed offset could
        // not tell those apart; Calendar in the device zone can.
        assertEquals(2 * 60 * 60 * 1000L, utcMillis(month = 8, day = 2, hour = 12) - summer.toEpochMillis())
        assertEquals(1 * 60 * 60 * 1000L, utcMillis(month = 1, day = 2, hour = 12) - winter.toEpochMillis())
    }

    @Test
    fun parseSessionStartTime_rejectsImplausibleDates() {
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStartBytes(month = 13)))
        assertNull(ICanHealthParser.parseSessionStartTime(sessionStartBytes(year = 0)))
        assertNull(ICanHealthParser.parseSessionStartTime(ByteArray(6)))
    }

    private fun sessionStartBytes(
        year: Int = 2026,
        month: Int = 8,
        day: Int = 2,
        hour: Int = 17,
        minute: Int = 6,
        second: Int = 41,
        tz: Int = 0,
        dst: Int = 0,
    ): ByteArray = byteArrayOf(
        (year and 0xFF).toByte(),
        ((year ushr 8) and 0xFF).toByte(),
        month.toByte(),
        day.toByte(),
        hour.toByte(),
        minute.toByte(),
        second.toByte(),
        tz.toByte(),
        dst.toByte(),
    )

    private fun localMillis(
        year: Int = 2026,
        month: Int = 8,
        day: Int = 2,
        hour: Int = 17,
        minute: Int = 6,
        second: Int = 41,
    ): Long = millisIn(TimeZone.getDefault(), year, month, day, hour, minute, second)

    private fun utcMillis(
        year: Int = 2026,
        month: Int = 8,
        day: Int = 2,
        hour: Int = 17,
        minute: Int = 6,
        second: Int = 41,
    ): Long = millisIn(TimeZone.getTimeZone("UTC"), year, month, day, hour, minute, second)

    private fun millisIn(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis

    private companion object {
        const val THREE_HOURS_MS = 3 * 60 * 60 * 1000L
    }
}
