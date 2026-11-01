package tk.glucodata.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The meal arithmetic from discussion #666: weigh each ingredient, see its carbs and the total. */
class JournalMealIngredientTests {

    private fun per100(carbs: Float, grams: String) = JournalDraftFoodItem(
        sourceFoodId = null,
        displayName = "food",
        carbsGrams = carbs,
        proteinGrams = 10f,
        fatGrams = null,
        absorptionMinutes = 90,
        accentColor = 0,
        basis = JournalFoodBasis.PER_100_G,
        quantityText = "100"
    ).withQuantityText(grams)

    @Test
    fun gramsScaleCarbsPer100Grams() {
        // 45 g of dry pasta at 72 g carbs per 100 g.
        val pasta = per100(72f, "45")
        assertEquals(32.4f, pasta.carbsGrams, 0.001f)
        assertEquals(4.5f, pasta.proteinGrams!!, 0.001f)
        assertNull(pasta.fatGrams)
    }

    @Test
    fun servingsScalePerServingFoods() {
        val jar = JournalDraftFoodItem(null, "Pasta sauce, jar", 34f, null, null, 90, 0)
            .withQuantityText("0.5")
        assertEquals(17f, jar.carbsGrams, 0.001f)
    }

    @Test
    fun aWeighedPortionCountsAPresetAs100GramsAServing() {
        // The picker's portion field is grams against a "per 100 g" composition; its default
        // 100 g must stay one serving of a preset, not 100 of them.
        val jar = JournalDraftFoodItem(null, "Pasta sauce, jar", 34f, null, null, 90, 0)
        assertEquals(34f, jar.withPortionGrams(100f).carbsGrams, 0.001f)
        assertEquals(17f, jar.withPortionGrams(50f).carbsGrams, 0.001f)
        assertEquals(32.4f, per100(72f, "100").withPortionGrams(45f).carbsGrams, 0.001f)
    }

    @Test
    fun aWeighedPortionKeepsItsExactServingCount() {
        // 45.5 g is 0.455 servings; it shows as 0.46 but counts as 0.455.
        val preset = JournalDraftFoodItem(null, "preset", 70f, null, null, 90, 0).withPortionGrams(45.5f)
        assertEquals(31.85f, preset.carbsGrams, 0.001f)
        // Typing a quantity in replaces it.
        assertEquals(35f, preset.withQuantityText("0.5").carbsGrams, 0.001f)
    }

    @Test
    fun aCommaDecimalReadsTheSame() {
        assertEquals(17f, JournalDraftFoodItem(null, "jar", 34f, null, null, 90, 0).withQuantityText("0,5").carbsGrams, 0.001f)
    }

    @Test
    fun aFoodFromItsLabelNeedsBothNumbers() {
        val apple = JournalDraftFoodItem.fromLabel("Apple", 90, 0)
        assertEquals(0f, apple.carbsGrams, 0f)
        val weighed = apple.withBasisCarbsText("11.4").withQuantityText("81")
        assertEquals(9.234f, weighed.carbsGrams, 0.001f)
    }

    @Test
    fun anUnfinishedNumberCountsAsNothing() {
        assertEquals(0f, per100(72f, "").carbsGrams, 0f)
        assertEquals(0f, per100(72f, "-").carbsGrams, 0f)
    }
}
