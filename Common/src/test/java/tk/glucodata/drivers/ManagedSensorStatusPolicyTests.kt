package tk.glucodata.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ManagedSensorStatusPolicyTests {
    @Test
    fun collapseSummaryStatus_suppressesPassiveConnectionText() {
        assertEquals("", ManagedSensorStatusPolicy.collapseSummaryStatus("Connected"))
        assertEquals("", ManagedSensorStatusPolicy.collapseSummaryStatus("Connected (polling)"))
        assertEquals("", ManagedSensorStatusPolicy.collapseSummaryStatus("Searching for sensors"))
    }

    @Test
    fun collapseSummaryStatus_keepsMeaningfulActionStatus() {
        assertEquals("Syncing...", ManagedSensorStatusPolicy.collapseSummaryStatus("Syncing..."))
        assertEquals("No Data", ManagedSensorStatusPolicy.collapseSummaryStatus("No Data"))
    }

    @Test
    fun resolveLifecycleSummary_prefersDriverRemainingHoursAndExpectedEnd() {
        val start = 1_000L
        val expectedEnd = start + (21L * 24L * 60L * 60L * 1000L)
        val summary = ManagedSensorStatusPolicy.resolveLifecycleSummary(
            startTimeMs = start,
            officialEndMs = 0L,
            expectedEndMs = expectedEnd,
            sensorRemainingHours = 37,
            sensorAgeHours = 19 * 24,
            nowMs = expectedEnd + (5L * 60L * 60L * 1000L),
        )

        assertEquals("20 / 21", summary.daysText)
        assertEquals(20, summary.currentDay)
        assertEquals(37L, summary.remainingHours)
        assertTrue(summary.progress > 0.8f)
    }

    @Test
    fun resolveLifecycleSummary_clampsNegativeRemainingHours() {
        val start = 10_000L
        val end = start + (15L * 24L * 60L * 60L * 1000L)
        val summary = ManagedSensorStatusPolicy.resolveLifecycleSummary(
            startTimeMs = start,
            officialEndMs = end,
            expectedEndMs = 0L,
            sensorRemainingHours = -1,
            sensorAgeHours = -1,
            nowMs = end + (142L * 60L * 60L * 1000L),
        )

        assertEquals(0L, summary.remainingHours)
        assertEquals("15 / 15", summary.daysText)
        assertFalse(summary.progress < 0f)
    }

    @Test
    fun resolveLifecycleSummary_showsExpectedEndOverrunAsExtraDay() {
        val start = 10_000L
        val expectedEnd = start + (7_695L * 3L * 60L * 1000L)
        val summary = ManagedSensorStatusPolicy.resolveLifecycleSummary(
            startTimeMs = start,
            officialEndMs = 0L,
            expectedEndMs = expectedEnd,
            sensorRemainingHours = -1,
            sensorAgeHours = (16 * 24) + 16,
            allowExpectedEndOverrun = true,
            nowMs = expectedEnd + (15L * 60L * 60L * 1000L),
        )

        assertEquals("17 / 16", summary.daysText)
        assertEquals(17, summary.currentDay)
        assertEquals(-1L, summary.remainingHours)
        assertEquals(1f, summary.progress)
    }

    @Test
    fun resolveLifecycleSummary_libre2BluetoothWearReadsFourteenAndAHalfDays() {
        val start = 10_000L
        // Start + 14 days + 12 hours: the native expected end of a 14-day Libre 2.
        val expectedEnd = start + 14L * DAY + 12L * HOUR
        fun at(nowMs: Long) = ManagedSensorStatusPolicy.resolveLifecycleSummary(
            startTimeMs = start,
            officialEndMs = start + 14L * DAY,
            expectedEndMs = expectedEnd,
            nowMs = nowMs,
            locale = Locale.US,
        )

        assertEquals("3 / 14.5", at(start + 2L * DAY + HOUR).daysText)
        assertEquals("14 / 14.5", at(start + 13L * DAY + HOUR).daysText)
        // The last 12 hours fall on day 15.
        val lastHalf = at(start + 14L * DAY + 3L * HOUR)
        assertEquals("15 / 14.5", lastHalf.daysText)
        assertEquals(15, lastHalf.currentDay)
        assertEquals(9L, lastHalf.remainingHours)
        assertEquals("15 / 14.5", at(expectedEnd + HOUR).daysText)
    }

    @Test
    fun wearHalfDays_keepsOnlyAHalfThatIsReallyThere() {
        // Libre 2 over Bluetooth, Libre 2 Plus, Dexcom G7: whole days and 12 hours.
        assertEquals(29L, ManagedSensorStatusPolicy.wearHalfDays(14L * DAY + 12L * HOUR))
        assertEquals(31L, ManagedSensorStatusPolicy.wearHalfDays(15L * DAY + 12L * HOUR))
        assertEquals(21L, ManagedSensorStatusPolicy.wearHalfDays(10L * DAY + 12L * HOUR))
        // Within an hour of the half, either way.
        assertEquals(29L, ManagedSensorStatusPolicy.wearHalfDays(14L * DAY + 11L * HOUR))
        assertEquals(29L, ManagedSensorStatusPolicy.wearHalfDays(14L * DAY + 13L * HOUR))
        // Further off, the nearest whole day, as before.
        assertEquals(30L, ManagedSensorStatusPolicy.wearHalfDays(15L * DAY + 30L * MINUTE)) // Ottai
        assertEquals(32L, ManagedSensorStatusPolicy.wearHalfDays(15L * DAY + 14L * HOUR)) // 15.6 d
        assertEquals(32L, ManagedSensorStatusPolicy.wearHalfDays(7_695L * 3L * MINUTE)) // CT5
        assertEquals(46L, ManagedSensorStatusPolicy.wearHalfDays(1_972_800L * 1000L)) // Sibionics
        assertEquals(46L, ManagedSensorStatusPolicy.wearHalfDays(1_966_080L * 1000L))
        assertEquals(28L, ManagedSensorStatusPolicy.wearHalfDays(14L * DAY))
        assertEquals(2L, ManagedSensorStatusPolicy.wearHalfDays(1L))
        assertEquals(2L, ManagedSensorStatusPolicy.wearHalfDays(12L * HOUR))
    }

    @Test
    fun wearDaysText_usesTheLocaleDigits() {
        assertEquals("14.5", ManagedSensorStatusPolicy.wearDaysText(29L, Locale.US))
        assertEquals("14,5", ManagedSensorStatusPolicy.wearDaysText(29L, Locale.GERMANY))
        assertEquals("14", ManagedSensorStatusPolicy.wearDaysText(28L, Locale.GERMANY))
        assertEquals(14L, ManagedSensorStatusPolicy.wearDaysNumber(28L))
        assertEquals(14.5, ManagedSensorStatusPolicy.wearDaysNumber(29L))
    }

    private companion object {
        const val MINUTE = 60L * 1000L
        const val HOUR = 60L * MINUTE
        const val DAY = 24L * HOUR
    }
}
