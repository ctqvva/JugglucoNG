package tk.glucodata.data.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodMathTests {
    private val pasta = FoodItem(key = "common:pasta_dry", source = FoodSource.COMMON, name = "Pasta, dry", referenceGrams = 100f, carbs = 72f, protein = 13f, fat = 1.5f)
    private val bread = FoodItem(
        key = "common:bread", source = FoodSource.COMMON, name = "Bread", referenceGrams = 100f, carbs = 46.7f,
        portions = listOf(FoodPortion(FoodPortionKind.SLICE, 29f))
    )
    private val rice = FoodItem(
        key = "common:rice", source = FoodSource.COMMON, name = "Rice", referenceGrams = 100f, carbs = 27.8f,
        portions = listOf(FoodPortion(FoodPortionKind.CUP, 158f))
    )
    private val preset = FoodItem(key = "lib:1", source = FoodSource.LIBRARY, name = "Pizza", referenceGrams = null, carbs = 60f, protein = 25f, fat = 30f)

    @Test
    fun aWeighedAmountScalesFromTheReference() {
        // The request the meal builder started from: 45 g of dry pasta at 72 g per 100 g.
        val nutrients = FoodMath.nutrients(pasta, FoodAmount(45f))
        assertEquals(32.4f, nutrients.carbs, 0.001f)
        assertEquals(5.85f, nutrients.protein!!, 0.001f)
        assertEquals(45f, nutrients.grams!!, 0.001f)
    }

    @Test
    fun aPortionCountsInItsOwnWeight() {
        val nutrients = FoodMath.nutrients(bread, FoodAmount(2f, portionIndex = 0))
        assertEquals(58f, nutrients.grams!!, 0.001f)
        assertEquals(27.086f, nutrients.carbs, 0.001f)
    }

    @Test
    fun aFoodWithoutAWeightCountsInServings() {
        val nutrients = FoodMath.nutrients(preset, FoodAmount(0.5f))
        assertNull(nutrients.grams)
        assertEquals(30f, nutrients.carbs, 0.001f)
        assertEquals(15f, nutrients.fat!!, 0.001f)
    }

    @Test
    fun aLibraryServingIsItsOwnReference() {
        val bowl = FoodItem(
            key = "lib:2", source = FoodSource.LIBRARY, name = "Oatmeal", referenceGrams = 250f, carbs = 27f,
            portions = listOf(FoodPortion(FoodPortionKind.SERVING, 250f))
        )
        assertEquals(27f, FoodMath.nutrients(bowl, FoodAmount(1f, 0)).carbs, 0.001f)
        assertEquals(13.5f, FoodMath.nutrients(bowl, FoodAmount(125f)).carbs, 0.001f)
    }

    @Test
    fun aFoodStartsAtACountablePortionOrAHundredGrams() {
        assertEquals(FoodAmount(1f, 0), FoodMath.defaultAmount(bread))
        assertEquals(FoodAmount(100f), FoodMath.defaultAmount(pasta))
        // A cup is not how a serving is thought of where food is weighed.
        assertEquals(FoodAmount(100f), FoodMath.defaultAmount(rice))
        assertEquals(FoodAmount(1f), FoodMath.defaultAmount(preset))
    }

    @Test
    fun theAmountEatenLastTimeComesFirst() {
        assertEquals(FoodAmount(45f), FoodMath.defaultAmount(pasta.copy(lastAmount = FoodAmount(45f))))
        // A remembered portion the food no longer has is not an amount of anything.
        assertEquals(FoodAmount(100f), FoodMath.defaultAmount(pasta.copy(lastAmount = FoodAmount(2f, 3))))
    }

    @Test
    fun steppingLandsOnTheGrid() {
        assertEquals(110f, FoodMath.stepped(pasta, FoodAmount(100f), 1).quantity, 0.001f)
        assertEquals(50f, FoodMath.stepped(pasta, FoodAmount(45f), 1).quantity, 0.001f)
        assertEquals(40f, FoodMath.stepped(pasta, FoodAmount(45f), -1).quantity, 0.001f)
        assertEquals(1.5f, FoodMath.stepped(bread, FoodAmount(1f, 0), 1).quantity, 0.001f)
        // Never down to nothing: taking a food out is its own button.
        assertEquals(10f, FoodMath.stepped(pasta, FoodAmount(10f), -1).quantity, 0.001f)
        assertEquals(0.5f, FoodMath.stepped(bread, FoodAmount(0.5f, 0), -1).quantity, 0.001f)
    }

    @Test
    fun changingTheUnitKeepsThePlate() {
        val inGrams = FoodMath.inUnit(bread, FoodAmount(2f, 0), FoodAmount.BASE_UNIT)
        assertEquals(FoodAmount(58f), inGrams)
        assertEquals(2f, FoodMath.inUnit(bread, inGrams, 0).quantity, 0.001f)
    }

    @Test
    fun anAbsurdAmountIsCapped() {
        assertEquals(100f, FoodMath.factor(pasta, FoodAmount(1_000_000f)), 0.001f)
        assertEquals(0f, FoodMath.factor(pasta, FoodAmount(Float.NaN)), 0.001f)
    }

    @Test
    fun aMealTakesAsLongAsItsMacrosSay() {
        val light = FoodMath.estimatedAbsorptionMinutes(carbs = 20f, protein = 1f, fat = 0f)
        val heavy = FoodMath.estimatedAbsorptionMinutes(carbs = 60f, protein = 25f, fat = 30f)
        assertEquals(true, heavy > light)
        assertEquals(true, light in 45..480 && heavy in 45..480)
    }
    @Test
    fun decrementNeverIncreasesATinyManuallyEnteredAmount() {
        for (quantity in listOf(0.002f, 0.25f, 1f, 5f, 9.9f, 5001f)) {
            org.junit.Assert.assertTrue(FoodMath.stepped(pasta, FoodAmount(quantity), -1).quantity <= quantity)
        }
        assertEquals(0.25f, FoodMath.stepped(bread, FoodAmount(0.25f, 0), -1).quantity, 0f)
    }

}
