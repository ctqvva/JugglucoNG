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
            packagePieces = 6f,
            packagePieceLabel = "sausages",
            servingText = null,
            servingQuantity = 100f,
            servingUnit = AmountUnit.GRAM,
            servingPieces = null,
            servingPieceLabel = null
        )
    )

    @Test
    fun packageContentsRemainEditableAndIndependentOfServingSize() {
        val imported = ProductForm.from(sausages).toProduct(sausages, sausages.barcode)!!
        assertEquals("6 sausages", ProductForm.from(sausages).packageContents)
        assertTrue(quantityChipsFor(imported.reference, "serving", "piece", "package").contains("1 sausages"))
        val importedOne = QuantityResolver.resolve("1 sausages", imported.reference) as QuantityResolution.Resolved
        assertEquals(400f / 6f, importedOne.grams!!, 0.0001f)

        val edited = ProductForm.from(sausages)
            .copy(servingQuantity = "59")
            .toProduct(sausages, sausages.barcode)!!

        assertEquals(59f, edited.reference.servingQuantity!!, 0.0001f)
        assertEquals(6f, edited.reference.packagePieces!!, 0.0001f)
        assertEquals("sausages", edited.reference.packagePieceLabel)
        assertNull(edited.reference.servingText)
        assertTrue(quantityChipsFor(edited.reference, "serving", "piece", "package").contains("1 sausages"))
        val one = QuantityResolver.resolve("1 sausages", edited.reference)
        assertTrue(one is QuantityResolution.Resolved)
        one as QuantityResolution.Resolved
        assertEquals(400f / 6f, one.grams!!, 0.0001f)
        assertEquals((400f / 6f) / 100f, one.factor, 0.0001f)

        val corrected = ProductForm.from(sausages)
            .copy(packageContents = "8 sausages")
            .toProduct(sausages, sausages.barcode)!!
        assertEquals(8f, corrected.reference.packagePieces!!, 0.0001f)
        assertEquals(50f, (QuantityResolver.resolve("1 sausage", corrected.reference) as QuantityResolution.Resolved).grams!!, 0.0001f)

        val cleared = ProductForm.from(sausages)
            .copy(packageContents = "")
            .toProduct(sausages, sausages.barcode)!!
        assertNull(cleared.reference.packagePieces)
        assertTrue(QuantityResolver.resolve("1 sausage", cleared.reference) is QuantityResolution.Missing)
    }

    @Test
    fun energyDerivationScalesKcalWithoutCallingItGrams() {
        val text = MealFormat.macroDerivation(
            perBasis = 264f,
            basis = NutritionBasis.PER_100G,
            factor = 2f / 3f,
            basisWord = "batch",
            locale = java.util.Locale.US,
            unit = "kcal"
        )
        assertEquals("264 kcal / 100 g × 0.67 = 176 kcal", text)
    }
}
