package tk.glucodata.ui.journal

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(R.string.journal_food_search)))
        // A selected meal adds quantity editors to the lazy list. Its semantics order
        // can put one before search, so address the search field itself when typing.
        compose.onNodeWithText(label(R.string.journal_food_search))
            .performScrollTo().performClick().performTextInput("Meal")
        settle()
    }
    private fun plus(name: String) {
        val button = hasContentDescription(label(R.string.journal_add_food)) and hasAnyAncestor(hasText(name))
        compose.onNode(hasScrollAction()).performScrollToNode(button)
        compose.onNode(button).performScrollTo().performClick()
        settle()
    }
    private fun mealAmount(): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("food_meal_list"))
        return compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("food_meal_list")))
    }
    private fun assertSearchStillOpen() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasSetTextAction() and hasText("Meal"))
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
    fun successiveRowSelectionsKeepEarlierFoodsAndTheirEditedQuantities() {
        var saved: JournalEntryInput? = null
        show { saved = it }
        search()
        compose.onNodeWithText("Meal oats").performScrollTo().performClick()
        settle()
        mealAmount().performScrollTo().performTextReplacement("2")
        settle()
        search()
        compose.onNodeWithText("Meal yogurt").performScrollTo().performClick()
        settle()
        search()
        compose.onNodeWithText("Meal rice").performScrollTo().performClick()
        settle()
        compose.onNodeWithText(label(R.string.close)).assertDoesNotExist()
        capture("combined-row-selections")
        save()
        val input = requireNotNull(saved)
        assertEquals("Meal oats + Meal yogurt + Meal rice", input.title)
        assertEquals(190f, input.amount!!, 0f)
        assertEquals(47f, input.proteinGrams!!, 0f)
        assertEquals(27f, input.fatGrams!!, 0f)
        assertNull(input.foodId)
        assertEquals(3, input.foodItemCount)
        assertEquals(listOf(1L, 2L, 3L), input.foodUses.map { it.food.libraryId })
        assertEquals(listOf(2f, 1f, 1f), input.foodUses.map { it.amount.quantity })
    }

    @Test
    fun rowTapAddsToAMealAlreadyBuiltWithPlus() {
        var saved: JournalEntryInput? = null
        show { saved = it }
        search()
        plus("Meal oats")
        plus("Meal yogurt")
        compose.onNodeWithText("Meal rice").performScrollTo().performClick()
        settle()
        save()
        val input = requireNotNull(saved)
        assertEquals(145f, input.amount!!, 0f)
        assertEquals(listOf(1L, 2L, 3L), input.foodUses.map { it.food.libraryId })
    }

    @Test
    fun selectedFoodAmountStaysEditableDuringSearchAndIsPreservedWhenBuildingAMeal() {
        var saved: JournalEntryInput? = null
        show { saved = it }
        search()
        compose.onNodeWithText("Meal oats").performScrollTo().performClick()
        settle()
        search()
        mealAmount().performScrollTo().performTextReplacement("2")
        settle()
        plus("Meal yogurt")
        assertSearchStillOpen()
        capture("editable-meal-below-results")
        compose.onNodeWithText(label(R.string.close)).performScrollTo().performClick()
        settle()
        save()
        val input = requireNotNull(saved)
        assertEquals(120f, input.amount!!, 0f)
        assertEquals(listOf(1L, 2L), input.foodUses.map { it.food.libraryId })
        assertEquals(listOf(2f, 1f), input.foodUses.map { it.amount.quantity })
        assertEquals(2, input.foodItemCount)
    }

    @Test
    fun mealSitsBetweenSearchResultsAndCarbsAndAddedFeedbackTracksRemoval() {
        show {}
        search()
        fun result() = compose.onNode(hasText("Meal oats") and
            hasAnyDescendant(hasContentDescription(label(R.string.journal_add_food))))
        fun addButton() = compose.onNode(hasContentDescription(label(R.string.journal_add_food)) and
            hasAnyAncestor(hasText("Meal oats")))
        result().assertIsNotSelected()
        plus("Meal oats")
        val carbs = hasSetTextAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("45"))
        compose.onNode(hasScrollAction()).performScrollToNode(carbs)
        val meal = compose.onNodeWithTag("food_meal_list").fetchSemanticsNode().boundsInRoot
        val searchEnd = compose.onNodeWithText(Applic.app.getString(R.string.food_enter_values, "Meal"))
            .fetchSemanticsNode().boundsInRoot
        val carbsBounds = compose.onNode(carbs).fetchSemanticsNode().boundsInRoot
        assertTrue("the meal must follow search results", searchEnd.bottom <= meal.top)
        assertTrue("the meal must precede total carbs", meal.bottom <= carbsBounds.top)
        capture("meal-below-results-above-carbs")
        result().assertIsSelected()
        addButton().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            Applic.app.getString(R.string.selected_count, 1)))
        plus("Meal oats")
        addButton().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            Applic.app.getString(R.string.selected_count, 2)))
        capture("search-results-with-added-feedback")
        fun removeIngredient() {
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("food_meal_list"))
            compose.onNode(hasContentDescription(label(R.string.remove)) and
                hasAnyAncestor(hasTestTag("food_meal_list"))).performClick()
            settle()
        }
        removeIngredient()
        result().assertIsSelected()
        addButton().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            Applic.app.getString(R.string.selected_count, 1)))
        removeIngredient()
        result().assertIsNotSelected()
        addButton().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithTag("food_meal_list").assertDoesNotExist()
        assertSearchStillOpen()
    }

    @Test
    fun minusRemovesTheLatestMatchingAdditionAndKeepsEarlierEditedAmounts() {
        var saved: JournalEntryInput? = null
        show { saved = it }
        search()
        compose.onNodeWithText("Meal oats").performScrollTo().performClick()
        settle()
        search()
        mealAmount().performScrollTo().performTextReplacement("2")
        settle()
        plus("Meal oats")
        plus("Meal yogurt")
        fun result() = hasText("Meal oats") and hasAnyDescendant(hasContentDescription(label(R.string.journal_add_food)))
        fun minus() = compose.onNode(hasContentDescription(label(R.string.remove)) and hasAnyAncestor(result()))
        val minusBounds = minus().fetchSemanticsNode().boundsInRoot
        val plusBounds = compose.onNode(hasContentDescription(label(R.string.journal_add_food)) and
            hasAnyAncestor(result())).fetchSemanticsNode().boundsInRoot
        assertTrue("minus and plus must have a visible gap", minusBounds.right < plusBounds.left)
        capture("result-minus-and-plus")
        minus().performScrollTo().performClick()
        settle()
        assertSearchStillOpen()
        mealAmount().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("2")))
        compose.onNode(result()).assertIsSelected()
        minus().performScrollTo().performClick()
        settle()
        minus().assertDoesNotExist()
        compose.onNode(result()).assertIsNotSelected()
        assertSearchStillOpen()
        compose.onNodeWithText(label(R.string.close)).performScrollTo().performClick()
        settle()
        save()
        val input = requireNotNull(saved)
        assertEquals(30f, input.amount!!, 0f)
        assertEquals(2L, input.foodUses.single().food.libraryId)
    }
}
