package tk.glucodata.data.food

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled table itself, as it ships: every food has a name in every language of the app. */
class CommonFoodsTests {
    private val asset = listOf("src/mobile/assets", "Common/src/mobile/assets")
        .map { File(it, CommonFoods.ASSET_PATH) }
        .first { it.isFile }

    private fun load(language: String): CommonFoods = asset.bufferedReader().use { CommonFoods.parse(it, language) }

    private val appLanguages: List<String> = File(asset.parentFile.parentFile.parentFile.parentFile, "main/res")
        .listFiles { file -> file.isDirectory && Regex("values-[a-z]{2}").matches(file.name) }!!
        .map { it.name.removePrefix("values-") }

    @Test
    fun everyAppLanguageHasEveryFood() {
        val lines = asset.readLines()
        val header = lines.first().split('\t')
        assertTrue("app languages $appLanguages", appLanguages.size >= 15)
        (appLanguages + "en").forEach { language ->
            assertTrue("no column for $language", language in header)
        }
        lines.drop(1).forEach { line ->
            val cells = line.split('\t')
            assertEquals("columns in ${cells[0]}", header.size, cells.size)
            assertTrue("blank name in ${cells[0]}", cells.drop(7).none { it.isBlank() })
        }
        assertTrue(lines.size > 200)
    }

    @Test
    fun theNumbersArePossible() {
        val table = load("en")
        val foods = table.search("a", 1000) + table.search("e", 1000) + table.search("o", 1000)
        assertTrue(foods.size > 150)
        foods.forEach { food ->
            assertEquals(100f, food.referenceGrams)
            assertTrue(food.name, food.carbs in 0f..100f)
            assertTrue(food.name, (food.protein ?: 0f) + (food.fat ?: 0f) + food.carbs <= 101f)
            assertNotNull(food.name, food.fiber)
            food.portions.forEach { assertTrue(food.name, it.grams in 1f..600f) }
        }
    }

    @Test
    fun aFoodIsFoundInTheLanguageOfTheApp() {
        val russian = load("ru")
        val buckwheat = russian.search("греч", 5).first()
        assertEquals("Гречка сухая", buckwheat.name)
        assertEquals("common:buckwheat_dry", buckwheat.key)
        assertEquals("Apfel", load("de").search("apf", 5).first().name)
        assertEquals("苹果", load("zh").search("苹果", 5).first().name)
    }

    @Test
    fun englishStillFindsAFoodInAnotherLanguage() {
        val found = load("ru").search("buckwheat", 5)
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.name.startsWith("Гречка") })
    }

    @Test
    fun anUnknownLanguageFallsBackToEnglish() {
        assertEquals("Apple", load("xx").search("apple", 1).single().name)
    }

    @Test
    fun aKnownFoodCarriesItsNumbersAndMeasures() {
        val apple = load("en").byKey("common:apple")!!
        assertEquals(11.4f, apple.carbs, 0.001f)
        assertEquals(FoodPortion(FoodPortionKind.PIECE, 182f), apple.portions.first())
    }
}
