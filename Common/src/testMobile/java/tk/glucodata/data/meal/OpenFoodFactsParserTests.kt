package tk.glucodata.data.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes taken from real v2 answers (2026-08-21), trimmed to the requested fields. */
class OpenFoodFactsParserTests {
    private val ritterSport = """
        {"code":"4000417025005","product":{"brands":"RITTER SPORT","code":"4000417025005",
         "nutriments":{"carbohydrates_100g":52,"energy-kcal_100g":496,"fat_100g":27,"fiber_100g":6.2,
                       "proteins_100g":7,"sugars_100g":51,"polyols_100g":0},
         "nutrition_data_per":"100g","product_name":"Ritter Sport Marzapane chocolate",
         "product_quantity":100,"product_quantity_unit":"g","quantity":"100g",
         "serving_quantity":6.52,"serving_quantity_unit":"g","serving_size":"1 Cube (6.52 g)"},
         "status":1,"status_verbose":"product found"}
    """

    private val cocaCola = """
        {"code":"5449000000996","product":{"brands":"Coca-Cola","code":"5449000000996",
         "nutriments":{"carbohydrates_100g":10.6,"energy-kcal_100g":42,"fat_100g":0,"proteins_100g":0,"sugars_100g":10.6},
         "nutrition_data_per":"100g","product_name":"coca-cola",
         "product_quantity":330,"product_quantity_unit":"ml","quantity":"330 ml",
         "serving_quantity":330,"serving_quantity_unit":"ml","serving_size":"1 portion (330 ml)"},
         "status":1,"status_verbose":"product found"}
    """

    @Test
    fun chocolateBarParsesWithServingAndNet() {
        val product = OpenFoodFactsParser.parse("4000417025005", ritterSport)!!
        assertEquals("Ritter Sport Marzapane chocolate", product.displayName)
        assertEquals("RITTER SPORT", product.brand)
        assertEquals(NutritionSource.OPEN_FOOD_FACTS, product.source)
        assertEquals(NutritionBasis.PER_100G, product.reference.basis)
        assertEquals(52f, product.facts.carbsGrams, 0.0001f)
        assertEquals(7f, product.facts.proteinGrams!!, 0.0001f)
        assertEquals(27f, product.facts.fatGrams!!, 0.0001f)
        assertEquals(6.2f, product.facts.fiberGrams!!, 0.0001f)
        assertEquals(496f, product.facts.kcal!!, 0.0001f)
        assertEquals(100f, product.reference.netQuantity!!, 0.0001f)
        assertEquals(AmountUnit.GRAM, product.reference.netUnit)
        assertEquals(6.52f, product.reference.servingQuantity!!, 0.0001f)
        assertEquals(AmountUnit.GRAM, product.reference.servingUnit)
        assertEquals(1f, product.reference.servingPieces!!, 0.0001f)
        assertEquals("Cube", product.reference.servingPieceLabel)
        assertTrue(product.plausibility.isEmpty())
    }

    @Test
    fun drinkIsPer100mlEvenThoughOffSays100g() {
        val product = OpenFoodFactsParser.parse("5449000000996", cocaCola)!!
        assertEquals(NutritionBasis.PER_100ML, product.reference.basis)
        assertEquals(AmountUnit.MILLILITER, product.reference.netUnit)
        assertEquals(330f, product.reference.netQuantity!!, 0.0001f)
        assertEquals(AmountUnit.MILLILITER, product.reference.servingUnit)
        // A can: "1 Dose" resolves through the serving, "250 ml" resolves directly.
        val can = QuantityResolver.resolve("1 Dose", product.reference) as QuantityResolution.Resolved
        assertEquals(3.3f, can.factor, 0.0001f)
        val glass = QuantityResolver.resolve("250 ml", product.reference) as QuantityResolution.Resolved
        assertEquals(2.5f, glass.factor, 0.0001f)
    }

    @Test
    fun bareMultiPieceServingAtThe100gBasisBecomesEditablePackageContents() {
        val product = OpenFoodFactsParser.parse(
            "5010000000000",
            """{"code":"5010000000000","product":{"product_name":"6 sausages","nutrition_data_per":"100g",
                "product_quantity":400,"product_quantity_unit":"g","serving_size":"6 sausages",
                "serving_quantity":100,"serving_quantity_unit":"g",
                "nutriments":{"carbohydrates_100g":2.7,"proteins_100g":17.7,"fat_100g":20.1}},"status":1}"""
        )!!

        assertEquals(6f, product.reference.packagePieces!!, 0.0001f)
        assertEquals("sausages", product.reference.packagePieceLabel)
        assertNull(product.reference.servingPieces)
        assertEquals(400f / 6f, product.reference.effectivePieceGrams!!, 0.0001f)
        val one = QuantityResolver.resolve("1 sausage", product.reference) as QuantityResolution.Resolved
        assertEquals(400f / 6f, one.grams!!, 0.0001f)
        assertEquals((400f / 6f) / 100f, one.factor, 0.0001f)

        val cached = product.toProductEntity(product.barcode!!, now = 1L, existing = null).toScannedProduct()
        assertEquals(6f, cached.reference.packagePieces!!, 0.0001f)
        assertEquals("sausages", cached.reference.packagePieceLabel)

        val corrected = product.copy(
            reference = product.reference.copy(
                packagePieces = 8f,
                packagePiecesUserEdited = true
            )
        ).toProductEntity(product.barcode!!, now = 2L, existing = null)
        val refreshed = product.toProductEntity(
            product.barcode!!,
            now = 3L,
            existing = corrected,
            preserveExistingPackageContents = true
        ).toScannedProduct()
        assertEquals(8f, refreshed.reference.packagePieces!!, 0.0001f)
        assertTrue(refreshed.reference.packagePiecesUserEdited)

        val cleared = product.copy(
            reference = product.reference.copy(
                packagePieces = null,
                packagePieceLabel = null,
                packagePiecesUserEdited = true
            )
        ).toProductEntity(product.barcode!!, now = 4L, existing = null)
        val refreshedAfterClear = product.toProductEntity(
            product.barcode!!,
            now = 5L,
            existing = cleared,
            preserveExistingPackageContents = true
        ).toScannedProduct()
        assertNull(refreshedAfterClear.reference.packagePieces)
        assertTrue(refreshedAfterClear.reference.packagePiecesUserEdited)
    }

    @Test
    fun repeated100gReferenceDoesNotInventAPieceWeightWithoutPackageData() {
        val product = OpenFoodFactsParser.parse(
            "5010525358000",
            """{"code":"5010525358000","product":{"product_name":"Bacon & Cheddar Pork Sausages",
                "brands":"Morrisons","serving_size":"6 sausages (100 g)","serving_quantity":100,
                "serving_quantity_unit":"g","nutriments":{"carbohydrates_100g":2.7,
                "proteins_100g":17.7,"fat_100g":20.1,"energy-kcal_100g":264}},"status":1}"""
        )!!

        assertNull(product.reference.netQuantity)
        assertNull(product.reference.packagePieces)
        assertNull(product.reference.effectiveServingPieces)
        assertNull(product.reference.effectivePieceGrams)
        assertTrue(QuantityResolver.resolve("1 sausage", product.reference) is QuantityResolution.Missing)
    }

    @Test
    fun quantityPackageCountStaysSeparateFromARealServingCount() {
        val product = OpenFoodFactsParser.parse(
            "5010000000001",
            """{"code":"5010000000001","product":{"product_name":"Meatballs","nutrition_data_per":"100g",
                "quantity":"8 meatballs (480 g)","product_quantity":480,"product_quantity_unit":"g",
                "serving_size":"2 meatballs (120 g)","serving_quantity":120,"serving_quantity_unit":"g",
                "nutriments":{"carbohydrates_100g":3,"energy-kcal_100g":240}},"status":1}"""
        )!!

        assertEquals(8f, product.reference.packagePieces!!, 0.0001f)
        assertEquals(2f, product.reference.servingPieces!!, 0.0001f)
        assertEquals(60f, product.reference.effectivePieceGrams!!, 0.0001f)
        assertEquals(2.4f, (QuantityResolver.resolve("2 servings", product.reference) as QuantityResolution.Resolved).factor, 0.0001f)
    }

    @Test
    fun compactMultipackQuantityProvidesAnEditableGenericPieceCount() {
        val product = OpenFoodFactsParser.parse(
            "5010000000002",
            """{"code":"5010000000002","product":{"product_name":"Sausages","nutrition_data_per":"100g",
                "quantity":"6 x 67 g","product_quantity":400,"product_quantity_unit":"g",
                "nutriments":{"carbohydrates_100g":2.7}},"status":1}"""
        )!!

        assertEquals(6f, product.reference.packagePieces!!, 0.0001f)
        assertNull(product.reference.packagePieceLabel)
        assertEquals(400f / 6f, product.reference.effectivePieceGrams!!, 0.0001f)
    }

    @Test
    fun notFoundAndInvalidBodiesAreNull() {
        assertNull(OpenFoodFactsParser.parse("4999999999999", """{"code":"4999999999999","status":0,"status_verbose":"product not found"}"""))
        assertNull(OpenFoodFactsParser.parse("1", "not json"))
        assertNull(OpenFoodFactsParser.parse("1", """{"status":1,"product":{"product_name":"no nutriments"}}"""))
    }

    @Test
    fun missingNameFallsBackToBarcodeAndMissingMacrosStayNull() {
        val product = OpenFoodFactsParser.parse(
            "4012345678901",
            """{"code":"4012345678901","product":{"nutriments":{"carbohydrates_100g":"50"}},"status":1}"""
        )!!
        assertEquals("EAN 4012345678901", product.displayName)
        assertNull(product.brand)
        assertEquals(50f, product.facts.carbsGrams, 0.0001f)
        assertNull(product.facts.proteinGrams)
        assertNull(product.reference.netQuantity)
        assertNull(product.reference.servingQuantity)
    }

    @Test
    fun perServingOnlyProductsUseServingBasis() {
        val product = OpenFoodFactsParser.parse(
            "0012345678905",
            """{"code":"0012345678905","product":{"product_name":"US snack","nutrition_data_per":"serving",
                "serving_size":"1 bar (40 g)","serving_quantity":40,"serving_quantity_unit":"g",
                "nutriments":{"carbohydrates_serving":22,"proteins_serving":3,"fat_serving":9,"fiber_serving":2}},"status":1}"""
        )!!
        assertEquals(NutritionBasis.PER_SERVING, product.reference.basis)
        assertEquals(22f, product.facts.carbsGrams, 0.0001f)
        assertEquals(40f, product.reference.servingQuantity!!, 0.0001f)
        val two = QuantityResolver.resolve("2 bars", product.reference) as QuantityResolution.Resolved
        assertEquals(2f, two.factor, 0.0001f)
        val grams = QuantityResolver.resolve("60 g", product.reference) as QuantityResolution.Resolved
        assertEquals(1.5f, grams.factor, 0.0001f)
    }

    @Test
    fun implausibleDataIsFlaggedNotAltered() {
        val product = OpenFoodFactsParser.parse(
            "4000417025005",
            """{"code":"4000417025005","product":{"product_name":"Typo bar","nutrition_data_per":"100g",
                "nutriments":{"carbohydrates_100g":520,"proteins_100g":7,"fat_100g":27,"energy-kcal_100g":496}},"status":1}"""
        )!!
        assertEquals(520f, product.facts.carbsGrams, 0.0001f)
        assertTrue(product.plausibility.contains(NutritionPlausibilityFlag.MACROS_EXCEED_BASIS))
        assertTrue(product.plausibility.contains(NutritionPlausibilityFlag.ENERGY_MISMATCH))
    }

    @Test
    fun requestedFieldsAreInTheUrl() {
        val url = OpenFoodFactsClient.productUrl("4000417025005")
        assertTrue(url.startsWith("https://world.openfoodfacts.org/api/v2/product/4000417025005.json?fields="))
        assertTrue(url.contains("nutriments"))
        assertTrue(url.contains("serving_size"))
        assertTrue(url.contains("product_quantity"))
    }
}
