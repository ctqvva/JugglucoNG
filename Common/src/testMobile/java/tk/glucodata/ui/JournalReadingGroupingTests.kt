package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType

class JournalReadingGroupingTests {
    private val start = 1_760_000_000_000L
    private fun minute(i: Int) = start + i * 60_000L
    private fun point(i: Int) = GlucosePoint(value = 120f, time = "", timestamp = minute(i))
    private fun fingerstick(id: Long, timestamp: Long) = JournalEntry(
        id = id,
        timestamp = timestamp,
        sensorSerial = null,
        type = JournalEntryType.FINGERSTICK,
        title = "",
        note = null,
        amount = null,
        glucoseValueMgDl = 130f,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = null,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.METER,
        sourceRecordId = null,
        createdAt = timestamp,
        updatedAt = timestamp
    )

    // History 15:00..16:05 a reading a minute; the dashboard shows the newest ten, 15:56..16:05.
    private val history = List(66) { point(it) }
    private val shown = buildDisplayReadings(history, limit = 10)

    @Test
    fun anEntryOlderThanTheShownRowsIsNotPulledOntoTheOldestOne() {
        // #730: a 15:39 meter reading sat on the 15:56 row.
        val at1539 = fingerstick(1, minute(39))
        assertEquals(emptyMap<Long, List<JournalEntry>>(), groupJournalEntriesByReading(shown, listOf(at1539), history = history))
    }

    @Test
    fun entriesWithinTheShownRowsKeepTheirOwnReading() {
        val at1601 = fingerstick(1, minute(61) + 20_000L)
        val at1556 = fingerstick(2, minute(56))
        val grouped = groupJournalEntriesByReading(shown, listOf(at1601, at1556), history = history)
        assertEquals(mapOf(minute(61) to listOf(at1601), minute(56) to listOf(at1556)), grouped)
    }

    @Test
    fun anEntryAfterTheNewestReadingStaysOnTheNewestRow() {
        val at1607 = fingerstick(1, minute(67))
        assertEquals(
            mapOf(minute(65) to listOf(at1607)),
            groupJournalEntriesByReading(shown, listOf(at1607), history = history)
        )
    }

    @Test
    fun withoutAHistoryTheRowsAreTheHistory() {
        val at1539 = fingerstick(1, minute(39))
        assertEquals(
            groupJournalEntriesByReading(history, listOf(at1539)),
            groupJournalEntriesByReading(history, listOf(at1539), history = history)
        )
        assertEquals(mapOf(minute(39) to listOf(at1539)), groupJournalEntriesByReading(history, listOf(at1539)))
    }
}
