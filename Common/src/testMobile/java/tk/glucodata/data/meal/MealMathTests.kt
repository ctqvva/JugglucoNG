package tk.glucodata.data.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealMathTests {
    private fun item(
        id: Long,
        carbs: Float,
        protein: Float? = null,
        fat: Float? = null,
        factor: Float?,
        basis: NutritionBasis = NutritionBasis.PER_100G
    ) = MealItem(
        id = id, mealId = 1L, position = id.toInt(), barcode = null, source = NutritionSource.MANUAL,
        displayName = "item $id", brand = null,
        facts = NutritionFacts(carbsGrams = carbs, proteinGrams = protein, fatGrams = fat),
        reference = NutritionReference(basis = basis),
        quantityText = "x", factor = factor, amountGrams = null, amountMilliliters = null,
        plausibility = emptySet()
    )

    @Test
    fun totalsSumResolvedItemsAndCountUnresolved() {
        val pasta = item(1, carbs = 70f, protein = 12f, fat = 1.5f, factor = 2f)      // 200 g dry pasta
        val sauce = item(2, carbs = 8f, protein = 2f, fat = 4f, factor = 4f)          // 400 g sauce
        val cheese = item(3, carbs = 0f, protein = 30f, fat = 28f, factor = null)     // unresolved
        val totals = MealMath.totals(listOf(pasta, sauce, cheese))
        assertEquals(172f, totals.facts.carbsGrams, 0.001f)
        assertEquals(32f, totals.facts.proteinGrams!!, 0.001f)
        assertEquals(19f, totals.facts.fatGrams!!, 0.001f)
        assertEquals(2, totals.resolvedItems)
        assertEquals(1, totals.unresolvedItems)
        assertFalse(totals.isComplete)
    }

    @Test
    fun perServingDividesTheBatch() {
        val totals = MealMath.totals(listOf(item(1, carbs = 172f, protein = 32f, fat = 19f, factor = 1f)))
        val portion = MealMath.perServing(totals, 4f)!!
        assertEquals(43f, portion.carbsGrams, 0.001f)
        assertEquals(8f, portion.proteinGrams!!, 0.001f)
        assertNull(MealMath.perServing(totals, null))
        assertNull(MealMath.perServing(totals, 0f))
    }

    @Test
    fun eatenShareOfTheMealGoesThroughTheSameEngine() {
        val totals = MealMath.totals(listOf(item(1, carbs = 172f, protein = 32f, fat = 19f, factor = 1f)))
        val meal = NutritionReference.forMeal(servings = 4f, cookedWeightGrams = 1200f)
        val twoPortions = QuantityResolver.resolve("2 Portionen", meal) as QuantityResolution.Resolved
        assertEquals(86f, totals.facts.scaled(twoPortions.factor).carbsGrams, 0.001f)
        val plate = QuantityResolver.resolve("300 g", meal) as QuantityResolution.Resolved
        assertEquals(43f, totals.facts.scaled(plate.factor).carbsGrams, 0.001f)
        val half = QuantityResolver.resolve("die Hälfte", meal) as QuantityResolution.Resolved
        assertEquals(86f, totals.facts.scaled(half.factor).carbsGrams, 0.001f)
    }

    @Test
    fun emptyMealHasNoMacros() {
        val totals = MealMath.totals(emptyList())
        assertEquals(0f, totals.facts.carbsGrams, 0.001f)
        assertNull(totals.facts.proteinGrams)
        assertTrue(totals.isComplete)
    }
}
