package tk.glucodata.ui.journal

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.data.food.*
import tk.glucodata.data.journal.*

class JournalFoodDraftRegressionTests {
    private val food = JournalFood(4L, "Saved", 20f, 4f, 2f, 90, 0, false, false, 0, referenceGrams = 100f)

    @Test
    fun reopeningASingleFoodRestoresItsAssociationAndConsumedAmount() {
        val entry = JournalEntry(1, 0, null, JournalEntryType.CARBS, "Saved", null, 10f, null, 90, null,
            null, food.id, 2f, 1f, JournalEntrySource.MANUAL, null, 0, 0)
        val restored = restoredFoodItem(food, entry)
        assertEquals(food.id, restored.food.libraryId)
        assertEquals(50f, restored.amount.quantity, 0f)
        assertEquals(10f, restored.nutrients.carbs, 0f)
        val preset = restoredFoodItem(food.copy(referenceGrams = null), entry)
        assertEquals(0.5f, preset.amount.quantity, 0f)
    }

    @Test
    fun manualAggregateOverrideDoesNotRememberStaleIngredientAmounts() {
        val initial = JournalEntryDraft(type = JournalEntryType.CARBS, timestamp = 0)
            .withFoodItems(listOf(JournalDraftFoodItem(food.toFoodItem(), FoodAmount(50f))), true)
        assertEquals(1, initial.toInput("mmol/L", null, emptyMap(), true)!!.foodUses.size)
        val override = initial.copy(amountText = "30", recordFoodUses = false)
        val input = override.toInput("mmol/L", null, emptyMap(), true)!!
        assertEquals(30f, input.amount!!, 0f)
        assertTrue(input.foodUses.isEmpty())
        // Editing an ingredient returns to totals derived from the ingredients.
        val changed = override.withFoodItems(listOf(initial.foodItems.single().copy(amount = FoodAmount(75f))), true)
        assertEquals(75f, changed.toInput("mmol/L", null, emptyMap(), true)!!.foodUses.single().amount.quantity, 0f)
    }
    @Test
    fun zeroCarbFoodRestoresFromProteinOrFatWithoutShrinkingTheSavedMeal() {
        val zeroCarb = food.copy(carbsGrams = 0f, proteinGrams = 20f, fatGrams = 4f)
        val entry = JournalEntry(1, 0, null, JournalEntryType.CARBS, "Saved", null, 0f, null, 90, null,
            null, food.id, 50f, 10f, JournalEntrySource.MANUAL, null, 0, 0)
        val restored = restoredFoodItem(zeroCarb, entry)
        assertEquals(250f, restored.amount.quantity, 0f)
        val fatOnly = restoredFoodItem(zeroCarb.copy(proteinGrams = null), entry.copy(proteinGrams = null))
        assertEquals(250f, fatOnly.amount.quantity, 0f)
        val another = JournalDraftFoodItem(food.toFoodItem().copy(libraryId = 5), FoodAmount(50f))
        val draft = JournalEntryDraft(type = JournalEntryType.CARBS, timestamp = 0)
            .withFoodItems(listOf(restored, another), true)
        val input = draft.toInput("mmol/L", null, emptyMap(), true)!!
        assertEquals(52f, input.proteinGrams!!, 0f)
        assertEquals(1, input.foodUses.size)
        assertEquals(5L, input.foodUses.single().food.libraryId)
    }

    @Test
    fun unknownOrChangedFoodDefinitionsRetainMealTotalsWithoutGuessingGrams() {
        val entry = JournalEntry(1, 0, null, JournalEntryType.CARBS, "Original meal", null, 0f, null, 90, null,
            null, food.id, 50f, 10f, JournalEntrySource.MANUAL, null, 0, 0)
        val restored = restoredFoodItem(food.copy(carbsGrams = 0f, proteinGrams = null, fatGrams = null), entry)
        assertNull(restored.food.referenceGrams)
        assertTrue(restored.food.portions.isEmpty())
        assertEquals(50f, restored.nutrients.protein!!, 0f)
        assertFalse(restored.rememberUse)
        val changed = restoredFoodItem(food.copy(carbsGrams = 0f, proteinGrams = 20f, fatGrams = 50f), entry)
        assertNull(changed.food.referenceGrams)
        assertEquals(10f, changed.nutrients.fat!!, 0f)
        val added = JournalEntryDraft(type = JournalEntryType.CARBS, timestamp = 0)
            .withFoodItems(listOf(restored, JournalDraftFoodItem(food.toFoodItem(), FoodAmount(50f))), true)
            .toInput("mmol/L", null, emptyMap(), true)!!
        assertEquals(52f, added.proteinGrams!!, 0f)
    }

}
