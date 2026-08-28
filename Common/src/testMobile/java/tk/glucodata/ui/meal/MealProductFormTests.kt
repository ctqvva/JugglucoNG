package tk.glucodata.ui.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.meal.AmountUnit
import tk.glucodata.data.meal.NutritionBasis
import tk.glucodata.data.meal.NutritionFacts
import tk.glucodata.data.meal.NutritionReference
import tk.glucodata.data.meal.NutritionSource
import tk.glucodata.data.meal.QuantityResolution
import tk.glucodata.data.meal.QuantityResolver
import tk.glucodata.data.meal.ScannedProduct

class MealProductFormTests {
    private val sausages = ScannedProduct(
        barcode = "5010000000000",
        displayName = "6 sausages",
        brand = "Test",
        source = NutritionSource.OPEN_FOOD_FACTS,
        facts = NutritionFacts(carbsGrams = 2.7f, proteinGrams = 17.7f, fatGrams = 20.1f),
        reference = NutritionReference(
            basis = NutritionBasis.PER_100G,
            netQuantity = 400f,
            netUnit = AmountUnit.GRAM,
            servingText = "6 sausages",
            servingQuantity = 100f,
            servingUnit = AmountUnit.GRAM,
            servingPieces = 6f,
            servingPieceLabel = "sausages"
        )
    )

    @Test
    fun editingServingSizeRemovesTheHiddenSixPieceDivisor() {
        val imported = ProductForm.from(sausages).toProduct(sausages, sausages.barcode)!!
        assertTrue(quantityChipsFor(imported.reference, "serving", "piece", "package").none { it.contains("sausages") })

        val edited = ProductForm.from(sausages)
            .copy(servingQuantity = "59")
            .toProduct(sausages, sausages.barcode)!!

        assertEquals(59f, edited.reference.servingQuantity!!, 0.0001f)
        assertEquals(1f, edited.reference.effectiveServingPieces!!, 0.0001f)
        assertEquals("sausages", edited.reference.servingPieceLabel)
        assertNull(edited.reference.servingText)
        assertTrue(quantityChipsFor(edited.reference, "serving", "piece", "package").contains("1 sausages"))
        val one = QuantityResolver.resolve("1 sausages", edited.reference)
        assertTrue(one is QuantityResolution.Resolved)
        one as QuantityResolution.Resolved
        assertEquals(59f, one.grams!!, 0.0001f)
        assertEquals(0.59f, one.factor, 0.0001f)
    }
}
