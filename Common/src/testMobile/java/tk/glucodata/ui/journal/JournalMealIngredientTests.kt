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
