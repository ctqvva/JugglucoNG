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
    private val foods = listOf("Alba", "Beta", "Gamma", "Delta").mapIndexed { index, name ->
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
        onMove: MoveFoods = { _, _, done -> done(true) }, onArchive: (Set<Long>, Boolean) -> Unit = { _, _ -> }) =
        FoodLibraryActions({}, {}, onEdit, {}, {}, {}, {},
            onDelete = { ids, done -> onDelete(ids); done(true) }, onMove = onMove,
            onArchive = { ids, disabled, done -> onArchive(ids, disabled); done(true) })

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
        compose.onNodeWithText(label(R.string.selected_count, 4)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.delete)).performClick()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithText(label(R.string.food_delete_title, 4)).assertExists()
        compose.onNodeWithText(label(R.string.delete)).performClick()
        assertEquals(setOf(1L, 2L, 3L, 4L), deleted)
        compose.onNodeWithText(label(R.string.selected_count, 4)).assertDoesNotExist()
    }

    @Test
    fun draggingTheHandleReordersWithoutEnteringSelectionMode() {
        var moved = emptySet<Long>()
        var destination: Long? = null
        show(actions(onMove = { ids, before, done -> moved = ids; destination = before; done(true) }))
        compose.onNodeWithText("Alba").performScrollTo()
        val handle = compose.onNodeWithTag("food_drag:1")
        val delta = compose.onNodeWithText("Gamma").fetchSemanticsNode().boundsInRoot.center.y -
            compose.onNodeWithText("Alba").fetchSemanticsNode().boundsInRoot.center.y
        handle.performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, delta), durationMillis = 1000)
        }
        compose.waitForIdle()
        assertEquals(setOf(1L), moved)
        assertEquals(4L, destination)
        compose.onNodeWithText(label(R.string.selected_count, 1)).assertDoesNotExist()
    }

    @Test
    fun tappingEditsAndSelectionHasDirectCalibrationStyleActions() {
        var edited: Long? = null
        var archived = emptySet<Long>()
        show(actions(onEdit = { edited = it.id }, onArchive = { ids, disabled -> assertTrue(disabled); archived = ids }))
        compose.onNodeWithText("Alba").performScrollTo().performClick()
        assertEquals(1L, edited)
        compose.onNodeWithText("Beta").performTouchInput { longClick() }
        compose.onNodeWithContentDescription(label(R.string.disable)).assertIsDisplayed().performClick()
        assertEquals(setOf(2L), archived)
    }

    @Test
    fun selectAllInSearchCannotDeleteUnmatchedFoods() {
        var deleted = emptySet<Long>()
        show(actions(onDelete = { deleted = it }))
        compose.onNodeWithText(label(R.string.journal_food_search)).performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("ta")
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
        compose.onNodeWithText("Beta").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithContentDescription(label(R.string.select_all)).performClick()
        compose.onNodeWithText(label(R.string.selected_count, 2)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.delete)).performClick()
        compose.onNodeWithText(label(R.string.delete)).performClick()
        assertEquals(setOf(2L, 4L), deleted)
    }

    @Test
    fun swipesOfferDeleteAndDisableWithoutAccidentallyEditing() {
        var edited = false
        var archived = emptySet<Long>()
        var deleted = emptySet<Long>()
        show(actions(onEdit = { edited = true }, onDelete = { deleted = it },
            onArchive = { ids, disabled -> assertTrue(disabled); archived = ids }))
        compose.onNodeWithText("Alba").performScrollTo().performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertEquals(setOf(1L), deleted)
        compose.onNodeWithText("Beta").performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertEquals(setOf(2L), archived)
        assertFalse(edited)
    }
}
