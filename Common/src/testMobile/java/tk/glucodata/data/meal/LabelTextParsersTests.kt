package tk.glucodata.data.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCR output as ML Kit typically returns it: one line per table row, noise included. */
class LabelTextParsersTests {
    private val germanBack = listOf(
        "Nährwerte", "Durchschnittliche Nährwerte pro 100 g",
        "Brennwert 2069 kJ / 496 kcal",
        "Fett 27 g",
        "davon gesättigte Fettsäuren 11 g",
        "Kohlenhydrate 52 g",
        "davon Zucker 51 g",
        "Ballaststoffe 6,2 g",
        "Eiweiß 7 g",
        "Salz 0,1 g",
        "Mindestens haltbar bis: siehe Prägung"
    )

    private val ukBackTwoColumns = listOf(
        "NUTRITION",
        "Typical values per 100g per 25g bar %RI*",
        "Energy 2069kJ 517kJ 6%",
        "496kcal 124kcal",
        "Fat 27g 6.8g 10%",
        "of which saturates 11g 2.8g 14%",
        "Carbohydrate 52g 13g 5%",
        "of which sugars 51g 12.8g 14%",
        "Fibre 6.2g 1.6g",
        "Protein 7g 1.8g 4%",
        "Salt 0.1g <0.1g 2%",
        "*Reference intake of an average adult (8400kJ/2000kcal)"
    )

    private val servingColumnFirst = listOf(
        "Per serving (30 g) Per 100 g",
        "Energy 150 kcal 500 kcal",
        "Fat 6 g 20 g",
        "Carbohydrate 18 g 60 g",
        "of which sugars 9 g 30 g",
        "Protein 3 g 10 g"
    )

    private val drink = listOf(
        "Nährwerte pro 100 ml",
        "Energie 180 kJ / 42 kcal",
        "Fett 0 g", "Kohlenhydrate 10,6 g", "davon Zucker 10,6 g", "Eiweiß 0 g", "Salz 0 g"
    )

    @Test
    fun germanLabelReadsEveryMacro() {
        val result = NutritionLabelParser.parseLines(germanBack)
        val facts = result.facts!!
        assertEquals(NutritionBasis.PER_100G, result.basis)
        assertEquals(52f, facts.carbsGrams, 0.001f)
        assertEquals(51f, facts.sugarsGrams!!, 0.001f)
        assertEquals(27f, facts.fatGrams!!, 0.001f)
        assertEquals(6.2f, facts.fiberGrams!!, 0.001f)
        assertEquals(7f, facts.proteinGrams!!, 0.001f)
        assertEquals(496f, facts.kcal!!, 0.001f)
        assertTrue(result.evidence["carbs"]!!.contains("Kohlenhydrate"))
        // Saturates must not be taken as the fat value.
        assertTrue(result.evidence["fat"]!!.startsWith("Fett 27"))
    }

    @Test
    fun ukLabelTakesThePer100ColumnAndIgnoresReferenceIntake() {
        val result = NutritionLabelParser.parseLines(ukBackTwoColumns)
        val facts = result.facts!!
        assertEquals(52f, facts.carbsGrams, 0.001f)
        assertEquals(51f, facts.sugarsGrams!!, 0.001f)
        assertEquals(27f, facts.fatGrams!!, 0.001f)
        assertEquals(6.2f, facts.fiberGrams!!, 0.001f)
        assertEquals(7f, facts.proteinGrams!!, 0.001f)
        assertEquals(496f, facts.kcal!!, 0.001f)
        assertEquals("25 g", result.servingText)
    }

    @Test
    fun servingColumnBeforeHundredColumnPicksTheSecondNumber() {
        val result = NutritionLabelParser.parseLines(servingColumnFirst)
        val facts = result.facts!!
        assertEquals(60f, facts.carbsGrams, 0.001f)
        assertEquals(30f, facts.sugarsGrams!!, 0.001f)
        assertEquals(20f, facts.fatGrams!!, 0.001f)
        assertEquals(10f, facts.proteinGrams!!, 0.001f)
        assertEquals(500f, facts.kcal!!, 0.001f)
        assertEquals("30 g", result.servingText)
    }

    @Test
    fun drinkIsPer100ml() {
        val result = NutritionLabelParser.parseLines(drink)
        assertEquals(NutritionBasis.PER_100ML, result.basis)
        assertEquals(10.6f, result.facts!!.carbsGrams, 0.001f)
        assertEquals(42f, result.facts!!.kcal!!, 0.001f)
    }

    @Test
    fun kilojoulesOnlyAreConvertedAndMissingRowsStayNull() {
        val result = NutritionLabelParser.parseLines(listOf("Energy 2000 kJ", "Carbohydrate 40 g"))
        assertEquals(40f, result.facts!!.carbsGrams, 0.001f)
        assertEquals(2000f / 4.184f, result.facts!!.kcal!!, 0.01f)
        assertNull(result.facts!!.proteinGrams)
        assertNull(result.facts!!.fatGrams)
    }

    @Test
    fun noCarbsMeansNotUsable() {
        val result = NutritionLabelParser.parseLines(listOf("Ingredients: sugar, cocoa butter", "Best before"))
        assertNull(result.facts)
        assertTrue(!result.isUsable)
    }

    @Test
    fun packQuantityPrefersTheLegalMarkAndMultipacks() {
        assertEquals(500f, PackQuantityParser.parse(listOf("Vollmilch Schokolade", "500 g ℮"))!!.quantity, 0.001f)
        val multi = PackQuantityParser.parse(listOf("6 x 25 g", "Riegel"))!!
        assertEquals(150f, multi.quantity, 0.001f)
        assertEquals(AmountUnit.GRAM, multi.unit)
        val litre = PackQuantityParser.parse(listOf("Orangensaft", "1 L"))!!
        assertEquals(1000f, litre.quantity, 0.001f)
        assertEquals(AmountUnit.MILLILITER, litre.unit)
        // A nutrition row is not a pack size.
        assertNull(PackQuantityParser.parse(listOf("Fett 27 g", "Kohlenhydrate 52 g", "pro 100 g")))
    }

    @Test
    fun nameCandidatesAreTheBigPrintWithoutNumbersOrTableWords() {
        val front = listOf(
            OcrLine("RITTER SPORT", top = 10, height = 90),
            OcrLine("Marzipan", top = 120, height = 70),
            OcrLine("100 g", top = 300, height = 20),
            OcrLine("Kohlenhydrate 52 g", top = 400, height = 14),
            OcrLine("Qualität im Quadrat", top = 500, height = 18)
        )
        val names = ProductNameGuesser.candidates(front)
        assertEquals(listOf("RITTER SPORT", "Marzipan", "Qualität im Quadrat"), names)
        assertNotNull(names.firstOrNull())
    }
}
