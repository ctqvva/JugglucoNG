package tk.glucodata.ui.journal

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33], application = FoodPickerActionsTests.TestApplication::class,
    qualifiers = "en-w411dp-h1000dp-night-xxhdpi")
class FoodPickerActionsTests {
    class TestApplication : Applic() { override fun onCreate() {} }
    @get:Rule val compose = createComposeRule()
    private val foods = listOf(
        JournalFood(1, "Meal oats", 45f, 12f, 8f, 210, 0, false, false, 0),
        JournalFood(2, "Meal yogurt", 30f, 8f, 3f, 95, 0, false, false, 1),
        JournalFood(3, "Meal rice", 70f, 15f, 8f, 180, 0, false, false, 2)
    )
    private fun label(id: Int) = Applic.app.getString(id)
    private fun settle() { compose.mainClock.advanceTimeBy(1500); compose.waitForIdle() }
    private fun show(onSave: (JournalEntryInput) -> Unit) {
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                JournalEntrySheet(unit = "mmol/L", selectedTimestamp = 1L, insulinPresets = emptyList(),
                    foods = foods, foodMacrosEnabled = true, foodOnlineEnabled = false,
                    initialType = JournalEntryType.CARBS, onDismiss = {}, onSave = onSave,
                    sensorSerialProvider = { null })
            }
        }
        settle()
    }
    private fun search() {
        compose.onNodeWithText(label(R.string.journal_food_search)).performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("Meal")
        settle()
    }
    private fun plus(name: String) {
        compose.onNode(hasContentDescription(label(R.string.journal_add_food)) and hasAnyAncestor(hasText(name)))
            .performScrollTo().performClick()
        settle()
    }
    private fun assertSearchStillOpen() {
        compose.onNode(hasSetTextAction() and hasText("Meal")).assertExists()
        compose.onNodeWithText(label(R.string.close)).assertExists()
    }
    private fun save() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(R.string.save)))
        compose.onNodeWithText(label(R.string.save)).performScrollTo().performClick()
    }
    private fun capture(name: String) {
        val output = File("build/reports/food-picker/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("journal_entry_sheet", useUnmergedTree = true)
                .captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun plusAccumulatesIngredientsIncludingRepeatsWithoutClosingOrClearingSearch() {
        var saved: JournalEntryInput? = null
        show { saved = it }
        search()
        capture("separate-select-and-add")
        plus("Meal oats")
        assertSearchStillOpen()
        plus("Meal yogurt")
        assertSearchStillOpen()
        plus("Meal oats")
        assertSearchStillOpen()
        compose.onNodeWithText(label(R.string.close)).performScrollTo().performClick()
        settle()
        save()
        val input = requireNotNull(saved)
        assertEquals("Meal oats + Meal yogurt + Meal oats", input.title)
        assertEquals(120f, input.amount!!, 0f)
        assertEquals(32f, input.proteinGrams!!, 0f)
        assertEquals(19f, input.fatGrams!!, 0f)
        assertEquals(3, input.foodItemCount)
        assertEquals(listOf(1L, 2L, 1L), input.foodUses.map { it.food.libraryId })
        assertNull(input.foodId)
    }

    @Test
    fun rowSelectsOnlyThatFoodAndClosesSearchAfterBuildingAMeal() {
        var saved: JournalEntryInput? = null
        show { saved = it }
        search()
        plus("Meal oats")
        plus("Meal yogurt")
        compose.onNodeWithText("Meal rice").performScrollTo().performClick()
        settle()
        compose.onNodeWithText(label(R.string.close)).assertDoesNotExist()
        compose.onNodeWithText("Meal oats").assertDoesNotExist()
        compose.onNodeWithText("Meal yogurt").assertDoesNotExist()
        capture("single-selection")
        save()
        val input = requireNotNull(saved)
        assertEquals("Meal rice", input.title)
        assertEquals(70f, input.amount!!, 0f)
        assertEquals(15f, input.proteinGrams!!, 0f)
        assertEquals(8f, input.fatGrams!!, 0f)
        assertEquals(180, input.durationMinutes)
        assertEquals(3L, input.foodId)
        assertEquals(1, input.foodItemCount)
        assertEquals(3L, input.foodUses.single().food.libraryId)
    }

    @Test
    fun plusAddsToAnAlreadySelectedFoodInsteadOfReplacingIt() {
        var saved: JournalEntryInput? = null
        show { saved = it }
        search()
        compose.onNodeWithText("Meal oats").performScrollTo().performClick()
        settle()
        search()
        plus("Meal yogurt")
        assertSearchStillOpen()
        compose.onNodeWithText(label(R.string.close)).performScrollTo().performClick()
        settle()
        save()
        val input = requireNotNull(saved)
        assertEquals(75f, input.amount!!, 0f)
        assertEquals(listOf(1L, 2L), input.foodUses.map { it.food.libraryId })
        assertEquals(2, input.foodItemCount)
    }
}
