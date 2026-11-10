package tk.glucodata.ui.journal

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.data.food.*

class FoodQuantityRegressionTests {
    @Test
    fun unitChangesAndDisplayedEditsPreserveNutrientsAcrossWeightsAndLocales() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar"))) {
                Locale.setDefault(locale)
                for (portion in listOf(0.5f, 29f, 125f, 182f, 300f, 500f, 5000f)) {
                    val food = FoodItem("test", FoodSource.CUSTOM, "Label", referenceGrams = 100f,
                        carbs = 35.5f, protein = 7.5f, fat = 2.5f,
                        portions = listOf(FoodPortion(FoodPortionKind.PACKAGE, portion)))
                    for (grams in listOf(0.01f, 1f, 5f, 50f, 123.45f, 500f)) {
                        val before = FoodMath.nutrients(food, FoodAmount(grams))
                        val converted = FoodMath.inUnit(food, FoodAmount(grams), 0)
                        val text = formatFoodQuantity(converted.quantity)
                        val parsed = parseFoodQuantity(text)!!
                        assertTrue("$locale $grams / $portion displayed as $text", parsed > 0f)
                        assertEquals(converted.quantity, parsed, 0f)
                        val after = FoodMath.nutrients(food, FoodAmount(parsed, 0))
                        assertEquals(before.carbs, after.carbs, maxOf(0.00001f, before.carbs * 0.000001f))
                        assertEquals(before.protein!!, after.protein!!, maxOf(0.00001f, before.protein * 0.000001f))
                        assertEquals(grams, FoodMath.inUnit(food, converted, FoodAmount.BASE_UNIT).quantity, maxOf(0.00001f, grams * 0.000001f))
                    }
                }
            }
        } finally { Locale.setDefault(previous) }
        assertEquals(12.5f, parseFoodQuantity("١٢٫٥")!!, 0f)
    }
}
