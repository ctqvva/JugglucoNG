package tk.glucodata.data.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionPlausibilityTests {
    @Test
    fun aNormalLabelPasses() {
        val facts = NutritionFacts(carbsGrams = 52f, proteinGrams = 7f, fatGrams = 27f, fiberGrams = 6.2f, kcal = 496f)
        assertTrue(NutritionPlausibility.check(facts, NutritionBasis.PER_100G).isEmpty())
    }

    @Test
    fun macroSumOverHundredIsFlagged() {
        val facts = NutritionFacts(carbsGrams = 80f, proteinGrams = 20f, fatGrams = 20f)
        assertEquals(setOf(NutritionPlausibilityFlag.MACROS_EXCEED_BASIS), NutritionPlausibility.check(facts, NutritionBasis.PER_100G))
        // The same numbers per serving may be fine (a 150 g serving).
        assertTrue(NutritionPlausibility.check(facts, NutritionBasis.PER_SERVING).isEmpty())
    }

    @Test
    fun energyFarFromFourFourNineIsFlagged() {
        val facts = NutritionFacts(carbsGrams = 50f, proteinGrams = 5f, fatGrams = 5f, kcal = 100f) // expected ~265
        assertEquals(setOf(NutritionPlausibilityFlag.ENERGY_MISMATCH), NutritionPlausibility.check(facts, NutritionBasis.PER_100G))
    }

    @Test
    fun lowCalorieDrinksUseAbsoluteTolerance() {
        val facts = NutritionFacts(carbsGrams = 0f, proteinGrams = 0f, fatGrams = 0f, kcal = 1f)
        assertTrue(NutritionPlausibility.check(facts, NutritionBasis.PER_100ML).isEmpty())
    }

    @Test
    fun negativeValuesAreInvalid() {
        val facts = NutritionFacts(carbsGrams = -1f)
        assertEquals(setOf(NutritionPlausibilityFlag.INVALID_VALUE), NutritionPlausibility.check(facts, NutritionBasis.PER_100G))
    }

    @Test
    fun flagsRoundTripThroughStorage() {
        val flags = setOf(NutritionPlausibilityFlag.MACROS_EXCEED_BASIS, NutritionPlausibilityFlag.ENERGY_MISMATCH)
        assertEquals(flags, NutritionPlausibilityFlag.decode(NutritionPlausibilityFlag.encode(flags)))
        assertEquals(null, NutritionPlausibilityFlag.encode(emptySet()))
        assertTrue(NutritionPlausibilityFlag.decode(null).isEmpty())
    }
}
