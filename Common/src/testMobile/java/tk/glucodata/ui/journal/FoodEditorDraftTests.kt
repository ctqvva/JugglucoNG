package tk.glucodata.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.food.FoodPortion
import tk.glucodata.data.food.FoodPortionKind
import tk.glucodata.data.journal.JournalFood

class FoodEditorDraftTests {
    private fun food(reference: Float?, portions: List<FoodPortion> = emptyList(), kept: Boolean = true) = JournalFood(
        id = 4, displayName = "Apple", carbsGrams = 11.4f, proteinGrams = 0.3f, fatGrams = null, absorptionMinutes = 110,
        accentColor = 1, isBuiltIn = false, isArchived = false, sortOrder = 9, referenceGrams = reference, portions = portions,
        fiberGrams = 2.4f, kcal = 52f, sourceKey = "common:apple", kept = kept
    )

    @Test
    fun aNewFoodIsWrittenPerHundredGrams() {
        val draft = foodEditorDraft(null, nextSortOrder = 12).copy(displayName = " Syrniki ", carbsText = "18,5")
        val input = foodEditorInput(draft, null)!!
        assertEquals("Syrniki", input.displayName)
        assertEquals(18.5f, input.carbsGrams, 0.001f)
        assertEquals(100f, input.referenceGrams)
        assertEquals(12, input.sortOrder)
        assertTrue(input.portions.isEmpty() && input.kept)
        assertNull(foodEditorInput(draft.copy(displayName = " "), null))
        assertNull(foodEditorInput(draft.copy(carbsText = ""), null))
    }

    @Test
    fun aServingWeightBecomesAPortion() {
        val draft = foodEditorDraft(food(100f, listOf(FoodPortion(FoodPortionKind.PIECE, 182f))), 0).copy(servingText = "150")
        val input = foodEditorInput(draft, food(100f, listOf(FoodPortion(FoodPortionKind.PIECE, 182f))))!!
        assertEquals(
            listOf(FoodPortion(FoodPortionKind.SERVING, 150f), FoodPortion(FoodPortionKind.PIECE, 182f)),
            input.portions
        )
        assertEquals(2.4f, input.fiberGrams!!, 0.001f)
        assertEquals("common:apple", input.sourceKey)
    }

    @Test
    fun anOldPresetOpensAsAServingAndStaysOne() {
        val preset = food(null)
        val draft = foodEditorDraft(preset, 0)
        assertEquals(FoodBasis.PER_SERVING, draft.basis)
        assertEquals("", draft.servingText)
        val input = foodEditorInput(draft, preset)!!
        assertNull(input.referenceGrams)
        assertTrue(input.portions.isEmpty())
        // Giving the serving a weight makes it weighable.
        val weighed = foodEditorInput(draft.copy(servingText = "250"), preset)!!
        assertEquals(250f, weighed.referenceGrams)
        assertEquals(listOf(FoodPortion(FoodPortionKind.SERVING, 250f)), weighed.portions)
        // The per-100 g fibre would be wrong for a serving of another size.
        assertNull(weighed.fiberGrams)
    }

    @Test
    fun switchingWhatTheNumbersAreForConvertsThemWhenTheWeightIsKnown() {
        val per100 = FoodEditorDraft(carbsText = "20", proteinText = "4", fatText = "", servingText = "250")
        val perServing = per100.withBasis(FoodBasis.PER_SERVING)
        assertEquals("50", perServing.carbsText)
        assertEquals("10", perServing.proteinText)
        assertEquals("", perServing.fatText)
        assertEquals("20", perServing.withBasis(FoodBasis.PER_100).carbsText)
        // Nothing to convert by: the numbers stay as typed.
        assertEquals("20", per100.copy(servingText = "").withBasis(FoodBasis.PER_SERVING).carbsText)
    }

    @Test
    fun savingARememberedFoodKeepsIt() {
        val remembered = food(100f, kept = false)
        assertTrue(foodEditorInput(foodEditorDraft(remembered, 0), remembered)!!.kept)
    }
    @Test
    fun referenceAndServingWeightsStayIndependent() {
        val imported = food(250f, listOf(FoodPortion(FoodPortionKind.SERVING, 125f, "half bowl")))
        val input = foodEditorInput(foodEditorDraft(imported, 0), imported)!!
        assertEquals(100f, input.referenceGrams)
        assertEquals(imported.carbsGrams / 250f, input.carbsGrams / input.referenceGrams!!, 0.000001f)
        assertEquals(imported.proteinGrams!! / 250f, input.proteinGrams!! / input.referenceGrams!!, 0.000001f)
        assertEquals(125f, input.portions.single().grams, 0f)
        assertEquals("half bowl", input.portions.single().label)
        assertEquals(imported.fiberGrams!! / 250f, input.fiberGrams!! / input.referenceGrams!!, 0.000001f)
    }

    @Test
    fun savingAnInferredDurationDoesNotMakeItExplicit() {
        val remembered = food(100f).copy(hasOwnAbsorption = false)
        val draft = foodEditorDraft(remembered, 0)
        org.junit.Assert.assertFalse(foodEditorInput(draft, remembered)!!.hasOwnAbsorption)
        assertTrue(foodEditorInput(draft.copy(absorptionText = "150"), remembered)!!.hasOwnAbsorption)
        assertTrue(foodEditorInput(foodEditorDraft(null, 0).copy(displayName = "Milk", carbsText = "5", isLiquid = true), null)!!.isLiquid)
    }

}
