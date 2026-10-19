package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MinuteTimeFormatTests {
    private val originalLocale = Locale.getDefault()
    private val originalZone = TimeZone.getDefault()

    @After
    fun restore() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    private fun reference(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun check(zone: String, locale: Locale) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Locale.setDefault(locale)
        // Two years at seven-minute steps, crossing every DST change in the range.
        val start = 1_700_000_000_000L
        var timestamp = start
        while (timestamp < start + 2L * 366L * 24L * 60L * 60L * 1000L) {
            assertEquals("$zone $locale $timestamp", reference(timestamp), MinuteTimeFormat.format(timestamp))
            timestamp += 7L * 60L * 1000L + 13L
        }
    }

    @Test
    fun matchesSimpleDateFormatAcrossZonesAndLocales() {
        check("Europe/Amsterdam", Locale.GERMANY)
        check("America/New_York", Locale.US)
        check("Asia/Kolkata", Locale("hi", "IN"))
        check("Asia/Tashkent", Locale("ru", "RU"))
        check("Australia/Lord_Howe", Locale.UK)
        check("Asia/Tehran", Locale("fa", "IR"))
        check("Africa/Cairo", Locale("ar", "EG"))
    }

    @Test
    fun followsAZoneChangeWithoutARestart() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        val t = 1_700_000_000_000L
        assertEquals(reference(t), MinuteTimeFormat.format(t))
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        assertEquals(reference(t), MinuteTimeFormat.format(t))
        Locale.setDefault(Locale("ar", "EG"))
        assertEquals(reference(t), MinuteTimeFormat.format(t))
    }
}
