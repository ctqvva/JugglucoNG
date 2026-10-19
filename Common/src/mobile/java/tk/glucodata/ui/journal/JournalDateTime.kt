package tk.glucodata.ui.journal

import java.util.Calendar
import java.util.TimeZone

private val utcTimeZone: TimeZone = TimeZone.getTimeZone("UTC")

internal fun journalTimestampToPickerUtcDateMillis(
    timestamp: Long,
    localTimeZone: TimeZone = TimeZone.getDefault()
): Long {
    val localDate = Calendar.getInstance(localTimeZone).apply { timeInMillis = timestamp }
    return Calendar.getInstance(utcTimeZone).apply {
        clear()
        set(
            localDate.get(Calendar.YEAR),
            localDate.get(Calendar.MONTH),
            localDate.get(Calendar.DAY_OF_MONTH)
        )
    }.timeInMillis
}

internal fun mergeJournalDate(
    currentTimestamp: Long,
    pickerUtcDateMillis: Long,
    localTimeZone: TimeZone = TimeZone.getDefault()
): Long {
    val current = Calendar.getInstance(localTimeZone).apply { timeInMillis = currentTimestamp }
    val selectedUtcDate = Calendar.getInstance(utcTimeZone).apply {
        timeInMillis = pickerUtcDateMillis
    }
    current.set(
        selectedUtcDate.get(Calendar.YEAR),
        selectedUtcDate.get(Calendar.MONTH),
        selectedUtcDate.get(Calendar.DAY_OF_MONTH)
    )
    return current.timeInMillis
}
