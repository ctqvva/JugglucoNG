package tk.glucodata.ui.journal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.data.journal.JournalEntryType

class JournalEntryNavigationTests {
    @Test
    fun linkedFoodOpensItsMealInsteadOfTheEntry() = runBlocking {
        val actions = mutableListOf<String>()
        navigateJournalEntry(
            JournalEntryType.CARBS, 42L,
            mealExists = { id ->
                assertEquals(42L, id)
                true
            },
            onOpenMeal = { actions.add("meal:$it") },
            onEdit = { actions.add("edit") }
        )
        assertEquals(listOf("meal:42"), actions)
    }

    @Test
    fun unlinkedFoodOpensItsEditor() = runBlocking {
        assertEditorDestination(JournalEntryType.CARBS, null)
    }

    @Test
    fun linkedInsulinStillOpensItsEditor() = runBlocking {
        assertEditorDestination(JournalEntryType.INSULIN, 42L)
    }

    @Test
    fun retainedFoodFromADeletedMealRemainsEditable() = runBlocking {
        val actions = mutableListOf<String>()
        navigateJournalEntry(
            JournalEntryType.CARBS, 42L,
            mealExists = { false },
            onOpenMeal = { actions.add("meal:$it") },
            onEdit = { actions.add("edit") }
        )
        assertEquals(listOf("edit"), actions)
    }

    @Test
    fun editorRemainsAvailableWithoutAMealNavigationHandler() = runBlocking {
        val actions = mutableListOf<String>()
        navigateJournalEntry(
            JournalEntryType.CARBS, 42L,
            mealExists = { error("No meal lookup needed") },
            onOpenMeal = null,
            onEdit = { actions.add("edit") }
        )
        assertEquals(listOf("edit"), actions)
    }

    private suspend fun assertEditorDestination(type: JournalEntryType, mealId: Long?) {
        val actions = mutableListOf<String>()
        navigateJournalEntry(
            type, mealId,
            mealExists = { error("Entry should not look up a meal") },
            onOpenMeal = { actions.add("meal:$it") },
            onEdit = { actions.add("edit") }
        )
        assertEquals(listOf("edit"), actions)
    }
}
