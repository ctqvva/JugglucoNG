package tk.glucodata.data.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes taken from real answers of the product and the search service, trimmed to the asked fields. */
class OpenFoodFactsParserTests {
    private val chocolate = """
        {"code":"4000417025005","product":{"brands":"RITTER SPORT, Ritter","code":"4000417025005",
         "nutriments":{"carbohydrates_100g":52,"energy-kcal_100g":496,"fat_100g":27,"fiber_100g":6.2,"proteins_100g":7},
         "product_name":"Ritter Sport Marzapane chocolate","product_name_de":"Ritter Sport Marzipan",
         "product_quantity":100,"product_quantity_unit":"g","quantity":"100g",
         "serving_quantity":6.52,"serving_quantity_unit":"g","serving_size":"1 Cube (6.52 g)"},
         "status":1,"status_verbose":"product found"}
    """

    private val cola = """
        {"code":"5449000000996","product":{"brands":"Coca-Cola","code":"5449000000996",
         "nutriments":{"carbohydrates_100g":10.6,"energy-kj_100g":180,"fat_100g":0,"proteins_100g":0},
         "product_name":"coca-cola","product_quantity":330,"product_quantity_unit":"ml","quantity":"330 ml",
         "serving_quantity":330,"serving_quantity_unit":"ml","serving_size":"1 portion (330 ml)"},
         "status":1}
    """

    @Test
    fun aProductParsesWithItsMeasures() {
        val food = OpenFoodFactsParser.parseProduct(chocolate, "en")!!
        assertEquals("off:4000417025005", food.key)
        assertEquals(FoodSource.OPEN_FOOD_FACTS, food.source)
        assertEquals("Ritter Sport Marzapane chocolate", food.name)
        assertEquals("RITTER SPORT", food.brand)
        assertEquals(100f, food.referenceGrams)
        assertEquals(52f, food.carbs, 0.001f)
        assertEquals(6.2f, food.fiber!!, 0.001f)
        assertEquals(496f, food.kcal!!, 0.001f)
        assertEquals("4000417025005", food.barcode)
        // The whole bar is the reference amount again, so only the cube is worth offering.
        assertEquals(listOf(FoodPortion(FoodPortionKind.SERVING, 6.52f, "Cube")), food.portions.take(1))
        assertTrue(!food.isLiquid)
    }

    @Test
    fun theNameComesInTheLanguageOfTheApp() {
        assertEquals("Ritter Sport Marzipan", OpenFoodFactsParser.parseProduct(chocolate, "de")!!.name)
    }

    @Test
    fun aDrinkIsCountedInMillilitres() {
        val food = OpenFoodFactsParser.parseProduct(cola, "en")!!
        assertTrue(food.isLiquid)
        assertEquals(43.02f, food.kcal!!, 0.01f)
        assertEquals(listOf(FoodPortion(FoodPortionKind.SERVING, 330f, "portion")), food.portions)
    }

    @Test
    fun aProductWithoutCarbsIsNoResult() {
        val body = """{"status":1,"product":{"code":"12345670","product_name":"Mystery","nutriments":{"fat_100g":3}}}"""
        assertNull(OpenFoodFactsParser.parseProduct(body, "en"))
        assertNull(OpenFoodFactsParser.parseProduct("""{"status":0,"status_verbose":"product not found"}""", "en"))
        assertNull(OpenFoodFactsParser.parseProduct("<html>Page temporarily unavailable</html>", "en"))
    }

    @Test
    fun valuesPerServingKeepTheServingAsTheirReference() {
        val body = """{"status":1,"product":{"code":"12345670","product_name":"Bar",
            "serving_size":"1 bar (40 g)","nutriments":{"carbohydrates_serving":22,"proteins_serving":3}}}"""
        val food = OpenFoodFactsParser.parseProduct(body, "en")!!
        assertEquals(40f, food.referenceGrams)
        assertEquals(22f, food.carbs, 0.001f)
        val weightless = """{"status":1,"product":{"code":"12345670","product_name":"Bar",
            "nutriments":{"carbohydrates_serving":22}}}"""
        assertNull(OpenFoodFactsParser.parseProduct(weightless, "en")!!.referenceGrams)
    }

    @Test
    fun searchHitsParseAndRepeatsAreDropped() {
        val body = """{"hits":[
            {"code":"4813494063866","brands":["Любимая Бабушкина кухня","X"],"nutriments":{"fat_100g":3,"proteins_100g":13,"energy-kcal_100g":340,"carbohydrates_100g":66},"product_name":"Гречка"},
            {"code":"4813494063866","nutriments":{"carbohydrates_100g":66},"product_name":"Гречка"},
            {"code":"4602248009492","brands":["Ярмарка"],"nutriments":{"energy-kcal_100g":192,"proteins_100g":8},"product_name":"Гречка"},
            {"code":"4820205980176","nutriments":{"carbohydrates_100g":"62,1"},"product_name":"Гречка ядрица"}
        ],"page":1,"count":4}"""
        val foods = OpenFoodFactsParser.parseSearch(body, "ru")
        assertEquals(listOf("off:4813494063866", "off:4820205980176"), foods.map { it.key })
        assertEquals("Любимая Бабушкина кухня", foods[0].brand)
        assertEquals(62.1f, foods[1].carbs, 0.001f)
        assertTrue(OpenFoodFactsParser.parseSearch("not json", "ru").isEmpty())
    }

    @Test
    fun theRequestsCarryOnlyWhatWasAskedFor() {
        val search = OpenFoodFactsClient.searchUrl(" гречка ядрица ", "ru")
        assertTrue(search, search.startsWith("https://search.openfoodfacts.org/search?q=%D0%B3"))
        assertTrue(search, "+" in search.substringAfter("q=").substringBefore("&") && " " !in search)
        assertTrue(search, "&langs=ru,en&" in search)
        assertTrue(OpenFoodFactsClient.searchUrl("milk", "en").contains("&langs=en&"))
        assertTrue(OpenFoodFactsClient.productUrl("3017620422003", "de").startsWith("https://world.openfoodfacts.org/api/v2/product/3017620422003.json?fields=code,product_name,product_name_de,product_name_en"))
    }

    @Test
    fun onlyARealBarcodeIsLookedUp() {
        assertEquals("3017620422003", ProductBarcode.normalize("3017620422003"))
        assertEquals("0036000291452", ProductBarcode.normalize("036000291452"))
        assertEquals("96385074", ProductBarcode.normalize(" 96385074 "))
        assertNull(ProductBarcode.normalize("3017620422004"))
        assertNull(ProductBarcode.normalize("https://example.com/sensor"))
        assertNull(ProductBarcode.normalize("12345"))
        assertNull(ProductBarcode.normalize(null))
    }
}
