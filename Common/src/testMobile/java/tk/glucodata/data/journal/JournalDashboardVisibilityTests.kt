package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The main screen's journal type filter. Hiding is visual only, so what is tested is the
 * stored form (which must survive unknown values and new types) and the filter itself.
 */
class JournalDashboardVisibilityTests {

    private fun entry(id: Long, type: JournalEntryType) = JournalEntry(
        id = id,
        timestamp = 1_700_000_000_000L + id,
        sensorSerial = null,
        type = type,
        title = type.name,
        note = null,
        amount = 1f,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = null,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.MANUAL,
        sourceRecordId = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun nothingStoredHidesNothing() {
        assertTrue(JournalDashboardVisibility.decode(null).isEmpty())
        assertTrue(JournalDashboardVisibility.decode(emptySet()).isEmpty())
    }

    @Test
    fun theStoredFormRoundTripsAndDropsUnknownValues() {
        val hidden = setOf(JournalEntryType.INSULIN, JournalEntryType.TEMP_BASAL)
        val stored = JournalDashboardVisibility.encode(hidden)

        assertEquals(setOf("insulin", "temp_basal"), stored)
        assertEquals(hidden, JournalDashboardVisibility.decode(stored + "no_such_type"))
    }

    @Test
    fun toggledShowsAndHidesOneType() {
        val hidden = JournalDashboardVisibility.toggled(emptySet(), JournalEntryType.CARBS, visible = false)
        assertEquals(setOf(JournalEntryType.CARBS), hidden)
        assertTrue(JournalDashboardVisibility.toggled(hidden, JournalEntryType.CARBS, visible = true).isEmpty())
    }

    @Test
    fun visibleDropsOnlyTheHiddenTypes() {
        val entries = listOf(
            entry(1, JournalEntryType.INSULIN),
            entry(2, JournalEntryType.CARBS),
            entry(3, JournalEntryType.NOTE),
        )

        val visible = JournalDashboardVisibility.visible(
            entries,
            setOf(JournalEntryType.INSULIN, JournalEntryType.CARBS),
        )

        assertEquals(listOf(3L), visible.map { it.id })
        assertSame(entries, JournalDashboardVisibility.visible(entries, emptySet()))
    }
}
