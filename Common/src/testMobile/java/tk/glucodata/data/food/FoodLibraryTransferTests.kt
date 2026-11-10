package tk.glucodata.data.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalFood

class FoodLibraryTransferTests {
    private fun food(id: Long, name: String, carbs: Float, reference: Float?, portions: List<FoodPortion> = emptyList(), kept: Boolean = true, archived: Boolean = false) =
        JournalFood(
            id = id, displayName = name, carbsGrams = carbs, proteinGrams = 2.5f, fatGrams = null, absorptionMinutes = 120,
            accentColor = 0, isBuiltIn = false, isArchived = archived, sortOrder = id.toInt(),
            referenceGrams = reference, portions = portions, fiberGrams = 1.2f, kcal = 250f, brand = "Dr. \"Oats\", Ltd", barcode = "3017620422003", kept = kept
        )

    @Test
    fun anExportReadsBackAsItWas() {
        val foods = listOf(
            food(1, "Oatmeal, with milk", 27f, 250f, listOf(FoodPortion(FoodPortionKind.SERVING, 250f, "bowl"))),
            food(2, "Pizza", 60f, null),
            food(3, "Only remembered", 10f, 100f, kept = false),
            food(4, "Disabled", 10f, 100f, archived = true)
        )
        val text = FoodLibraryTransfer.export(foods)
        assertEquals(FoodLibraryTransfer.COLUMNS.joinToString(","), text.lineSequence().first())
        val parsed = FoodLibraryTransfer.parse(text)
        assertEquals(0, parsed.skipped)
        assertEquals(listOf("Oatmeal, with milk", "Pizza"), parsed.foods.map { it.displayName })
        val oatmeal = parsed.foods[0]
        assertEquals(27f, oatmeal.carbsGrams, 0.001f)
        assertEquals(2.5f, oatmeal.proteinGrams!!, 0.001f)
        assertNull(oatmeal.fatGrams)
        assertEquals(250f, oatmeal.referenceGrams)
        assertEquals(listOf(FoodPortion(FoodPortionKind.SERVING, 250f, "bowl")), oatmeal.portions)
        assertEquals(120, oatmeal.absorptionMinutes)
        assertEquals("Dr. \"Oats\", Ltd", oatmeal.brand)
        assertEquals("3017620422003", oatmeal.barcode)
        // Exported with an empty per_grams: one serving of no recorded weight, as before.
        assertNull(parsed.foods[1].referenceGrams)
    }

    @Test
    fun aHandMadeSpreadsheetIsUnderstood() {
        val text = "﻿Название;Белки;Жиры;Углеводы;Порция\n" +
            "Борщ;1,1;2,2;4,3;300 г\n" +
            "Гречка отварная;3,4;0,6;17,2;\n" +
            ";1;1;1;100\n" +
            "Без углеводов;1;1;;100\n"
        val parsed = FoodLibraryTransfer.parse(text)
        assertEquals(2, parsed.skipped)
        val borscht = parsed.foods[0]
        assertEquals("Борщ", borscht.displayName)
        assertEquals(4.3f, borscht.carbsGrams, 0.001f)
        assertEquals(1.1f, borscht.proteinGrams!!, 0.001f)
        assertEquals(300f, borscht.referenceGrams)
        assertTrue(borscht.kept)
        // The column is there but empty for this row: a serving of unknown weight.
        assertNull(parsed.foods[1].referenceGrams)
    }

    @Test
    fun withoutAWeightColumnTheValuesArePerHundredGrams() {
        val parsed = FoodLibraryTransfer.parse("name,carbs (g),protein,fat\nBanana,20.2,1.1,0.3\n")
        assertEquals(100f, parsed.foods.single().referenceGrams)
        assertEquals(20.2f, parsed.foods.single().carbsGrams, 0.001f)
    }

    @Test
    fun aJsonListIsUnderstood() {
        val text = """[{"name":"Сырники","carbs":18.5,"protein":17,"fat":9,"per_grams":100},
            {"title":"Kefir","carbohydrates":"4,0 g","portion":250},
            {"protein":1}]"""
        val parsed = FoodLibraryTransfer.parse(text)
        assertEquals(1, parsed.skipped)
        assertEquals(listOf("Сырники", "Kefir"), parsed.foods.map { it.displayName })
        assertEquals(250f, parsed.foods[1].referenceGrams)
        val wrapped = FoodLibraryTransfer.parse("""{"foods":[{"name":"A","carbs":1}]}""")
        assertEquals("A", wrapped.foods.single().displayName)
    }

    @Test
    fun aFileThatIsNotATableImportsNothing() {
        assertTrue(FoodLibraryTransfer.parse("").foods.isEmpty())
        assertTrue(FoodLibraryTransfer.parse("just some text\nand more\n").foods.isEmpty())
        assertTrue(FoodLibraryTransfer.parse("{broken").foods.isEmpty())
    }

    @Test
    fun portionsSurviveAsText() {
        val portions = listOf(FoodPortion(FoodPortionKind.PIECE, 182f), FoodPortion(FoodPortionKind.SERVING, 12.5f, "Riegel: klein, 2"))
        val decoded = FoodPortions.decode(FoodPortions.encode(portions))
        assertEquals(portions[0], decoded[0])
        assertEquals(portions, decoded)
        assertEquals(FoodPortionKind.SERVING, decoded[1].kind)
        assertEquals(12.5f, decoded[1].grams, 0.001f)
        assertTrue(FoodPortions.decode("nonsense:5,slice:-1,").isEmpty())
    }

    @Test
    fun detailsSurviveTheBackup() {
        val rows = listOf(
            FoodDetailsEntity(7, 100f, "piece:182", 2.4f, 52f, "Brand", "3017620422003", "common:apple", false, false, 3, 1700000000000L, 1.5f, 0),
            FoodDetailsEntity(8, null, "", null, null, null, null, null, true, true, 0, 0L, null, null)
        )
        assertEquals(rows.map { it.copy(portions = FoodPortions.encode(FoodPortions.decode(it.portions))) }, FoodDetailsBackup.fromJson(FoodDetailsBackup.toJson(rows)))
        assertTrue(FoodDetailsBackup.fromJson(null).isEmpty())
    }
    @Test
    fun liquidMultilineNamesPunctuationAndInferredDurationsRoundTrip() {
        val portion = FoodPortion(FoodPortionKind.SERVING, 125f, "small: bowl, 2\nlines \"quoted\"")
        val original = food(1, "Kefir,\nplain", 4.5f, 100f, listOf(portion)).copy(isLiquid = true, hasOwnAbsorption = false)
        val parsed = FoodLibraryTransfer.parse(FoodLibraryTransfer.export(listOf(original)))
        assertEquals(0, parsed.skipped)
        val result = parsed.foods.single()
        assertEquals(original.displayName, result.displayName)
        assertEquals(original.portions, result.portions)
        assertTrue(result.isLiquid)
        org.junit.Assert.assertFalse(result.hasOwnAbsorption)
        val json = FoodLibraryTransfer.parse("""[{"name":"Milk","carbs":5,"is_liquid":true}]""").foods.single()
        assertTrue(json.isLiquid)
    }

    @Test
    fun quotedCommaDecimalsAndArabicNumbersAreParsedAsCompleteCells() {
        val parsed = FoodLibraryTransfer.parse("name,carbs,protein\nA,\"12,5\",\"2,25\"\nB,١٢٫٥,٢٫٢٥\n")
        assertEquals(2, parsed.foods.size)
        parsed.foods.forEach {
            assertEquals(12.5f, it.carbsGrams, 0f)
            assertEquals(2.25f, it.proteinGrams!!, 0f)
        }
    }

    @Test
    fun scientificNotationUsedByFloatExportKeepsSmallNutrients() {
        val original = food(1, "Trace", 0.0001f, 100f)
        assertEquals(original.carbsGrams, FoodLibraryTransfer.parse(FoodLibraryTransfer.export(listOf(original))).foods.single().carbsGrams, 0f)
    }

}
