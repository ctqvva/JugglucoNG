package tk.glucodata.data.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFoodFactsContributorTests {
    private val product = ScannedProduct(
        barcode = "5000159484695",
        displayName = "Marzipan bar",
        brand = "Ritter Sport",
        source = NutritionSource.OCR_LABEL,
        facts = NutritionFacts(carbsGrams = 52f, proteinGrams = 7f, fatGrams = 27f, fiberGrams = 6.2f, sugarsGrams = 51f, kcal = 496f, saturatedFatGrams = 11f, saltGrams = 0.1f),
        category = "Chocolate bars",
        reference = NutritionReference(
            basis = NutritionBasis.PER_100G,
            netQuantity = 100f, netUnit = AmountUnit.GRAM,
            servingText = "1 Riegel (25 g)", servingQuantity = 25f, servingUnit = AmountUnit.GRAM
        )
    )

    @Test
    fun fieldsFollowTheOffWriteApi() {
        val f = OpenFoodFactsContributor.productFields(
            barcode = "5000159484695", product = product, userId = "panni", password = "pw",
            languageCode = "de", appUuid = "abc", comment = "from label photo"
        )
        assertEquals("5000159484695", f["code"])
        assertEquals("panni", f["user_id"])
        assertEquals("pw", f["password"])
        assertEquals("de", f["lc"])
        assertEquals("Marzipan bar", f["product_name_de"])
        assertEquals("Ritter Sport", f["brands"])
        assertEquals("100 g", f["quantity"])
        assertEquals("1 Riegel (25 g)", f["serving_size"])
        assertEquals("100g", f["nutrition_data_per"])
        assertEquals("52", f["nutriment_carbohydrates"])
        assertEquals("g", f["nutriment_carbohydrates_unit"])
        assertEquals("6.2", f["nutriment_fiber"])
        assertEquals("11", f["nutriment_saturated-fat"])
        assertEquals("0.1", f["nutriment_salt"])
        assertEquals("51", f["nutriment_sugars"])
        assertEquals("Chocolate bars", f["categories"])
        assertEquals("496", f["nutriment_energy-kcal"])
        assertEquals("kcal", f["nutriment_energy-kcal_unit"])
        assertEquals("abc", f["app_uuid"])
        assertEquals("JugglucoNG", f["app_name"])
        assertFalse(f.containsKey("nutriment_polyols"))
    }

    @Test
    fun perServingProductsSaySo() {
        val perServing = product.copy(reference = product.reference.copy(basis = NutritionBasis.PER_SERVING))
        val f = OpenFoodFactsContributor.productFields("1", perServing, "u", "p", "en", null, "c")
        assertEquals("serving", f["nutrition_data_per"])
        assertFalse(f.containsKey("app_uuid"))
        assertTrue(f["product_name_en"] == "Marzipan bar")
    }

    @Test
    fun imageUploadAnswersAreReadTheWayOffSendsThem() {
        assertTrue(OpenFoodFactsContributor.imageUploadSucceeded("""{"status":"status ok","imgid":123,"image":{"imgid":123}}"""))
        assertTrue(OpenFoodFactsContributor.imageUploadSucceeded("""{"status":1}"""))
        assertFalse(OpenFoodFactsContributor.imageUploadSucceeded("""{"status":"status not ok","error":"field imgupload_front_en not set"}"""))
        assertFalse(OpenFoodFactsContributor.imageUploadSucceeded("not json"))
    }

    @Test
    fun photoKindsMapToOffImageFields() {
        assertEquals("front", LabelPhotoKind.FRONT.offImageField)
        assertEquals("nutrition", LabelPhotoKind.NUTRITION.offImageField)
        assertEquals(null, LabelPhotoKind.OTHER.offImageField)
        assertEquals(LabelPhotoKind.NUTRITION, LabelPhotoKind.fromStorage("nutrition"))
    }
}
