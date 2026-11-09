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

    @Test
    fun withNoReadingsEveryEntryGetsARow() {
        val rows = journalRowsAfterReadings(
            entries = listOf(fingerstick(1, minute(1)), fingerstick(2, minute(5))),
            readingEntries = emptyMap(),
            newestReadingMillis = null
        )
        assertEquals(listOf(minute(5), minute(1)), rows.map { it.timestamp })
    }

    @Test
    fun entriesLoggedInTheSameMinuteShareARow() {
        val rows = journalRowsAfterReadings(
            entries = listOf(fingerstick(1, minute(1)), fingerstick(2, minute(1) + 20_000L)),
            readingEntries = emptyMap(),
            newestReadingMillis = null
        )
        assertEquals(1, rows.size)
        assertEquals(listOf(2L, 1L), rows.single().entries.map { it.id })
    }

    @Test
    fun onlyEntriesAfterTheNewestReadingGetTheirOwnRow() {
        // The sensor went away at minute 10. Older entries would land above newer readings,
        // and one the reading row already carries must not show twice.
        val carried = fingerstick(2, minute(11))
        val rows = journalRowsAfterReadings(
            entries = listOf(fingerstick(1, minute(4)), carried, fingerstick(3, minute(60))),
            readingEntries = mapOf(minute(10) to listOf(carried)),
            newestReadingMillis = minute(10)
        )
        assertEquals(listOf(listOf(3L)), rows.map { row -> row.entries.map { it.id } })
    }

    @Test
    fun tempBasalsAreNotJournalRows() {
        val rows = journalRowsAfterReadings(
            entries = listOf(fingerstick(1, minute(1)).copy(type = JournalEntryType.TEMP_BASAL)),
            readingEntries = emptyMap(),
            newestReadingMillis = null
        )
        assertEquals(emptyList<JournalOnlyRow>(), rows)
    }

    @Test
    fun rowCountStaysBounded() {
        val rows = journalRowsAfterReadings(
            entries = (1L..30L).map { fingerstick(it, minute(it.toInt())) },
            readingEntries = emptyMap(),
            newestReadingMillis = null,
            limit = 10
        )
        assertEquals(10, rows.size)
        assertEquals(30L, rows.first().entries.single().id)
    }
}
