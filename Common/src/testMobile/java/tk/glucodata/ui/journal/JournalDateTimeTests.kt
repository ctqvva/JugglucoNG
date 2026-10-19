package tk.glucodata.ui.journal

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalDateTimeTests {
    @Test
    fun `selected picker date remains July 14 west of UTC`() {
        val localTimeZone = TimeZone.getTimeZone("America/Los_Angeles")
        val current = timestamp(localTimeZone, 2026, Calendar.JULY, 18, 9, 37)
        val selectedJuly14Utc = timestamp(UTC, 2026, Calendar.JULY, 14, 0, 0)

        val merged = Calendar.getInstance(localTimeZone).apply {
            timeInMillis = mergeJournalDate(current, selectedJuly14Utc, localTimeZone)
        }

        assertEquals(2026, merged.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, merged.get(Calendar.MONTH))
        assertEquals(14, merged.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, merged.get(Calendar.HOUR_OF_DAY))
        assertEquals(37, merged.get(Calendar.MINUTE))
    }

    @Test
    fun `picker initialization represents the current local date as UTC`() {
        val localTimeZone = TimeZone.getTimeZone("Asia/Yekaterinburg")
        val lateJuly14 = timestamp(localTimeZone, 2026, Calendar.JULY, 14, 23, 30)

        val pickerDate = Calendar.getInstance(UTC).apply {
            timeInMillis = journalTimestampToPickerUtcDateMillis(lateJuly14, localTimeZone)
        }

        assertEquals(2026, pickerDate.get(Calendar.YEAR))
        assertEquals(Calendar.JULY, pickerDate.get(Calendar.MONTH))
        assertEquals(14, pickerDate.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, pickerDate.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, pickerDate.get(Calendar.MINUTE))
    }

    private fun timestamp(
        timeZone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long = Calendar.getInstance(timeZone).apply {
        clear()
        set(year, month, day, hour, minute)
    }.timeInMillis

    private companion object {
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }
}
