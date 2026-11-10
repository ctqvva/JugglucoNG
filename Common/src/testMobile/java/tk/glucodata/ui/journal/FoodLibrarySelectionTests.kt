package tk.glucodata.ui.journal

import androidx.compose.ui.geometry.Offset
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
@Config(sdk = [33], application = FoodLibrarySelectionTests.TestApplication::class,
    qualifiers = "ru-w411dp-h1000dp-night-xxhdpi")
class FoodLibrarySelectionTests {
    class TestApplication : Applic() { override fun onCreate() {} }
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int, vararg args: Any) = Applic.app.getString(id, *args)
    private fun foods(count: Int) = (1L..count).map {
        JournalFood(it, "Food $it", 10f, null, null, 90, 0, it == 1L, false, it.toInt())
    }

    private fun point(name: String): Offset {
        val row = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("food_library_list").fetchSemanticsNode().boundsInRoot
        return row.center - viewport.topLeft
    }

    @Test
    fun heldDragSelectsARangeAndReversingShrinksItWithoutMovingRowsOrEditing() {
        var edited = false
        var mutated = false
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryLayout(foods(20), false, true, true, {},
                    FoodLibraryActions({}, {}, { edited = true }, {}, {}, {}, {},
                        onArchive = { _, _, _ -> mutated = true }, onDelete = { _, _ -> mutated = true },
                        onMove = { _, _, _ -> mutated = true }))
            }
        }
        val list = compose.onNodeWithTag("food_library_list")
        list.performScrollToNode(hasText("Food 1"))
        val start = point("Food 1")
        val end = point("Food 4")
        val reverse = point("Food 2")
        val before = compose.onNodeWithText("Food 1").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        list.performTouchInput {
            down(start)
            moveTo(start, delayMillis = 700)
            moveTo(end, delayMillis = 300)
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText(label(R.string.selected_count, 4)).assertIsDisplayed()
        (1..4).forEach { compose.onNodeWithText("Food $it").assertIsOn() }
        assertEquals(before, compose.onNodeWithText("Food 1").fetchSemanticsNode().boundsInRoot)
        list.performTouchInput { moveTo(reverse, delayMillis = 300); up() }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText(label(R.string.selected_count, 2)).assertIsDisplayed()
        compose.onNodeWithText("Food 3").assertIsOff()
        assertFalse(edited)
        assertFalse(mutated)

        // The same gesture on a selected anchor removes that range.
        list.performTouchInput {
            down(start)
            moveTo(start, delayMillis = 700)
            moveTo(reverse, delayMillis = 300)
            up()
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText(label(R.string.selected_count, 2)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.journal_add_food)).assertIsDisplayed()
        assertFalse(edited)
    }

    @Test
    fun holdingNearBottomScrollsAndSelectsOffscreenFoods() {
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryLayout(foods(60), false, true, true, {}, FoodLibraryActions({}, {}, {}, {}, {}, {}, {}))
            }
        }
        val list = compose.onNodeWithTag("food_library_list")
        list.performScrollToNode(hasText("Food 1"))
        val start = point("Food 1")
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val bottom = Offset(start.x, viewport.height - 8f)
        compose.mainClock.autoAdvance = false
        list.performTouchInput {
            down(start)
            moveTo(start, delayMillis = 700)
            moveTo(bottom, delayMillis = 300)
        }
        compose.mainClock.advanceTimeBy(2200)
        list.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Food 1").assertDoesNotExist()
        compose.onNodeWithText("Food 15").assertIsDisplayed().assertIsOn()
    }

    @Test
    fun builtinsCanBeBulkDeletedWithCustomFoods() {
        var deleted = emptySet<Long>()
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                FoodLibraryLayout(foods(2), false, true, true, {},
                    FoodLibraryActions({}, {}, {}, {}, {}, {}, {}, onDelete = { ids, done -> deleted = ids; done(true) }))
            }
        }
        compose.onNodeWithText("Food 1").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithContentDescription(label(R.string.select_all)).performClick()
        compose.onNodeWithContentDescription(label(R.string.delete)).assertIsEnabled().performClick()
        compose.onNodeWithText(label(R.string.delete)).performClick()
        assertEquals(setOf(1L, 2L), deleted)
    }
}
