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
}
