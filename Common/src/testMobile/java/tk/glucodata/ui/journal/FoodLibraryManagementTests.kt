package tk.glucodata.ui.journal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33], application = FoodLibraryManagementTests.TestApplication::class,
    qualifiers = "ru-w411dp-h1000dp-night-xxhdpi")
class FoodLibraryManagementTests {
    class TestApplication : Applic() { override fun onCreate() {} }
    @get:Rule val compose = createComposeRule()
    private val foods = listOf("Alba", "Beta", "Gamma").mapIndexed { index, name ->
        JournalFood(index + 1L, name, 10f, null, null, 90, 0, false, false, index)
    }
    private fun label(id: Int, vararg args: Any) = Applic.app.getString(id, *args)
    private fun show(actions: FoodLibraryActions) {
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryLayout(foods, true, true, true, {}, actions)
            }
        }
    }
    private fun actions(onEdit: (JournalFood) -> Unit = {}, onDelete: (Set<Long>) -> Unit = {},
        onMove: (Set<Long>, Long?) -> Unit = { _, _ -> }, onArchive: (Set<Long>, Boolean) -> Unit = { _, _ -> }) =
        FoodLibraryActions({}, {}, onEdit, {}, {}, {}, {}, onDelete = onDelete, onMove = onMove, onArchive = onArchive)

    @Test
    fun longPressAndSelectAllSupportBulkDeletionWithConfirmation() {
        var deleted = emptySet<Long>()
        show(actions(onDelete = { deleted = it }))
        compose.onNodeWithText("Alba").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText(label(R.string.selected_count, 1)).assertIsDisplayed()
        compose.onNodeWithText("Alba").assertIsOn()
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithText(label(R.string.selected_count, 2)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.select_all)).performClick()
        compose.onNodeWithText(label(R.string.selected_count, 3)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.delete)).performClick()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithText(label(R.string.food_delete_title, 3)).assertExists()
        compose.onNodeWithText(label(R.string.delete)).performClick()
        assertEquals(setOf(1L, 2L, 3L), deleted)
        compose.onNodeWithText(label(R.string.selected_count, 3)).assertDoesNotExist()
    }

    @Test
    fun selectionCanMoveTogetherBeforeADestination() {
        var moved = emptySet<Long>()
        var destination: Long? = null
        show(actions(onMove = { ids, before -> moved = ids; destination = before }))
        compose.onNodeWithText("Alba").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("Gamma").performClick()
        compose.onNodeWithContentDescription(label(R.string.food_actions)).performClick()
        compose.onNodeWithText(label(R.string.food_move)).performClick()
        compose.onNodeWithText(label(R.string.food_move_before, "Beta")).performClick()
        assertEquals(setOf(1L, 3L), moved)
        assertEquals(2L, destination)
        compose.onNodeWithText(label(R.string.food_move_title, 2)).assertDoesNotExist()
    }

    @Test
    fun rowMenuEditsTheRightFoodAndBulkDisableUsesOnlyTheSelection() {
        var edited: Long? = null
        var archived = emptySet<Long>()
        show(actions(onEdit = { edited = it.id }, onArchive = { ids, disabled -> assertTrue(disabled); archived = ids }))
        compose.onNodeWithText("Alba").performScrollTo()
        compose.onAllNodesWithContentDescription(label(R.string.food_actions))[0].performClick()
        compose.onNodeWithText(label(R.string.edit)).performClick()
        assertEquals(1L, edited)
        compose.onNodeWithText("Beta").performTouchInput { longClick() }
        compose.onNodeWithContentDescription(label(R.string.food_actions)).performClick()
        compose.onNodeWithText(label(R.string.disable)).performClick()
        assertEquals(setOf(2L), archived)
    }

    @Test
    fun selectAllInSearchCannotDeleteUnmatchedFoods() {
        var deleted = emptySet<Long>()
        show(actions(onDelete = { deleted = it }))
        compose.onNodeWithText(label(R.string.journal_food_search)).performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Beta")
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(label(R.string.food_select)).performClick()
        compose.onNodeWithContentDescription(label(R.string.select_all)).performClick()
        compose.onNodeWithText(label(R.string.selected_count, 1)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.delete)).performClick()
        compose.onNodeWithText(label(R.string.delete)).performClick()
        assertEquals(setOf(2L), deleted)
    }

    @Test
    fun swipesOfferDeleteAndDisableWithoutAccidentallyEditing() {
        var edited = false
        var archived = emptySet<Long>()
        var deleted = emptySet<Long>()
        show(actions(onEdit = { edited = true }, onDelete = { deleted = it },
            onArchive = { ids, disabled -> assertTrue(disabled); archived = ids }))
        compose.onNodeWithText("Alba").performScrollTo().performTouchInput { swipeLeft() }
        compose.onNodeWithText(label(R.string.food_delete_title, 1)).assertExists()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithText("Beta").performTouchInput { swipeRight() }
        assertEquals(setOf(2L), archived)
        assertFalse(edited)
    }
}
