package tk.glucodata.alerts

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAlertConfigTests {

    @Test
    fun localMinutesOfDayUsesDeviceTimeZone() {
        val timeZone = TimeZone.getTimeZone("Asia/Yekaterinburg")
        val timeMillis = GregorianCalendar(timeZone).apply {
            set(2026, 4, 24, 19, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(19 * 60 + 4, CustomAlertConfig.localMinutesOfDay(timeMillis, timeZone))
    }

    @Test
    fun activeAtUsesLocalClockTime() {
        val timeZone = TimeZone.getTimeZone("Asia/Yekaterinburg")
        val timeMillis = GregorianCalendar(timeZone).apply {
            set(2026, 4, 24, 19, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val alert = CustomAlertConfig(
            startTimeMinutes = 18 * 60,
            endTimeMinutes = 20 * 60
        )

        assertTrue(alert.isActiveAt(timeMillis, timeZone))
    }

    @Test
    fun overnightRangeWrapsAcrossMidnight() {
        val alert = CustomAlertConfig(
            startTimeMinutes = 22 * 60,
            endTimeMinutes = 7 * 60
        )

        assertTrue(alert.isActiveTime(23 * 60 + 30))
        assertTrue(alert.isActiveTime(6 * 60 + 30))
        assertFalse(alert.isActiveTime(12 * 60))
    }

    @Test
    fun allDayCustomRangeAllowsEndOfDaySentinel() {
        val alert = CustomAlertConfig(
            startTimeMinutes = 0,
            endTimeMinutes = CustomAlertConfig.MINUTES_PER_DAY
        )

        assertTrue(alert.isActiveTime(0))
        assertTrue(alert.isActiveTime(23 * 60 + 59))
    }
}
