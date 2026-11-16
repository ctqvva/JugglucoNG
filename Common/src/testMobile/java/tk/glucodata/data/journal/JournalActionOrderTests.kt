package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The add menus' stored order: it must survive junk, duplicates and types it never saw. */
class JournalActionOrderTests {

    private val note = JournalEntryType.NOTE
    private val activity = JournalEntryType.ACTIVITY
    private val glucose = JournalEntryType.FINGERSTICK
    private val food = JournalEntryType.CARBS
    private val insulin = JournalEntryType.INSULIN

    @Test
    fun nothingStoredIsTheDefaultOrder() {
        assertEquals(listOf(note, activity, glucose, food, insulin), JournalActionOrder.decode(null))
        assertEquals(JournalActionOrder.DEFAULT, JournalActionOrder.decode(""))
    }

    @Test
    fun storedOrderRoundTrips() {
        val order = listOf(insulin, food, glucose, note, activity)
        assertEquals(order, JournalActionOrder.decode(JournalActionOrder.encode(order)))
    }

    @Test
    fun junkDuplicatesAndTempBasalAreDropped() {
        assertEquals(
            listOf(insulin, food, glucose, note, activity),
            JournalActionOrder.decode("insulin,bogus,carbs,insulin,temp_basal,fingerstick,note,activity")
        )
    }

    @Test
    fun aMissingTypeReturnsToItsDefaultPlace() {
        // An order saved before glucose existed: glucose goes back to slot 2.
        assertEquals(
            listOf(insulin, food, glucose, note, activity),
            JournalActionOrder.decode("insulin,carbs,note,activity")
        )
        // The rest fill in around what was stored, each at its default slot.
        assertEquals(
            listOf(note, activity, glucose, food, insulin),
            JournalActionOrder.decode("carbs")
        )
    }

    @Test
    fun theSheetLeadsWithTheTypeNearestTheButton() {
        // The default keeps the sheet as it always was.
        assertEquals(JournalEntryType.journalRowTypes, JournalActionOrder.sheetOrder(JournalActionOrder.DEFAULT))
        assertEquals(
            listOf(activity, note, insulin, glucose, food),
            JournalActionOrder.sheetOrder(listOf(food, glucose, insulin, note, activity))
        )
    }

    @Test
    fun movedTakesOutAndPutsBack() {
        val order = JournalActionOrder.DEFAULT
        assertEquals(listOf(insulin, note, activity, glucose, food), JournalActionOrder.moved(order, 4, 0))
        assertEquals(listOf(activity, glucose, note, food, insulin), JournalActionOrder.moved(order, 0, 2))
        assertSame(order, JournalActionOrder.moved(order, 1, 1))
        assertSame(order, JournalActionOrder.moved(order, -1, 2))
        assertSame(order, JournalActionOrder.moved(order, 0, 5))
    }
}
