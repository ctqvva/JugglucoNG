package tk.glucodata.data.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityParserTests {
    private fun parsed(text: String): Quantity {
        val result = QuantityParser.parse(text)
        assertTrue("expected Parsed for '$text' but was $result", result is QuantityParse.Parsed)
        return (result as QuantityParse.Parsed).quantity
    }

    private fun mass(text: String): Float = (parsed(text) as Quantity.Mass).grams
    private fun volume(text: String): Float = (parsed(text) as Quantity.Volume).milliliters
    private fun fraction(text: String): Float = (parsed(text) as Quantity.Fraction).value

    @Test
    fun massInGramsKilogramsAndOunces() {
        assertEquals(70f, mass("70g"), 0.001f)
        assertEquals(70f, mass("70 g"), 0.001f)
        assertEquals(200f, mass("0,2 kg"), 0.001f)
        assertEquals(200f, mass("0.2kg"), 0.001f)
        assertEquals(2 * QuantityParser.OUNCE_GRAMS, mass("2 oz"), 0.01f)
        assertEquals(500f, mass("1 Pfund"), 0.001f)
    }

    @Test
    fun volumeInMillilitersCupsAndSpoons() {
        assertEquals(250f, volume("250ml"), 0.001f)
        assertEquals(240f, volume("1 Tasse"), 0.001f)
        assertEquals(480f, volume("2 cups"), 0.001f)
        assertEquals(45f, volume("drei Esslöffel"), 0.001f)
        assertEquals(30f, volume("2 EL"), 0.001f)
        assertEquals(5f, volume("1 TL"), 0.001f)
        assertEquals(15f, volume("1 tbsp"), 0.001f)
        assertEquals(1000f, volume("1 l"), 0.001f)
        assertEquals(330f, volume("33 cl"), 0.001f)
    }

    @Test
    fun sharesAsFractionsWordsAndPercent() {
        assertEquals(1f / 3f, fraction("1/3"), 0.0001f)
        assertEquals(1f / 3f, fraction("ein Drittel"), 0.0001f)
        assertEquals(0.5f, fraction("die Hälfte"), 0.0001f)
        assertEquals(0.5f, fraction("half"), 0.0001f)
        assertEquals(0.5f, fraction("½"), 0.0001f)
        assertEquals(0.75f, fraction("¾"), 0.0001f)
        assertEquals(0.30f, fraction("30%"), 0.0001f)
        assertEquals(0.30f, fraction("30 Prozent"), 0.0001f)
        assertEquals(0.25f, fraction("a quarter"), 0.0001f)
        assertEquals(1f, fraction("alles"), 0.0001f)
    }

    @Test
    fun packagesAreMultiplesOfTheWhole() {
        assertEquals(1.5f, fraction("eineinhalb Packungen"), 0.0001f)
        assertEquals(0.5f, fraction("halbe Packung"), 0.0001f)
        assertEquals(2f, fraction("2 Dosen"), 0.0001f)
        assertEquals(1f, fraction("1 pack"), 0.0001f)
    }

    @Test
    fun servings() {
        assertEquals(2f, (parsed("2 Portionen") as Quantity.Servings).count, 0.0001f)
        assertEquals(1f, (parsed("1 serving") as Quantity.Servings).count, 0.0001f)
        assertEquals(1.5f, (parsed("1,5 Portionen") as Quantity.Servings).count, 0.0001f)
        assertEquals(0.5f, (parsed("halbe Portion") as Quantity.Servings).count, 0.0001f)
    }

    @Test
    fun servingAndPackageWordsOfEveryMaintainedLocaleParse() {
        for (word in listOf("portion", "Portionen", "porzioni", "porties", "porcje", "porções", "порции", "порції", "порцыі", "porsiyon", "qaybood", "份")) {
            val parsed = QuantityParser.parse("2 $word")
            assertTrue("'2 $word' should be servings but was $parsed", parsed is QuantityParse.Parsed && (parsed.quantity as? Quantity.Servings)?.count == 2f)
        }
        for (word in listOf("Packung", "pack", "paquet", "confezione", "verpakking", "opakowanie", "embalagem", "упаковка", "упакоўка", "förpackning", "paket", "baakad", "包")) {
            val parsed = QuantityParser.parse("½ $word")
            assertTrue("'½ $word' should be a share but was $parsed", parsed is QuantityParse.Parsed && (parsed.quantity as? Quantity.Fraction)?.value == 0.5f)
        }
    }

    @Test
    fun piecesKeepTheirWord() {
        val riegel = parsed("2 Riegel") as Quantity.Pieces
        assertEquals(2f, riegel.count, 0.0001f)
        assertEquals("riegel", riegel.label)
        val scheibe = parsed("1 Scheibe") as Quantity.Pieces
        assertEquals(1f, scheibe.count, 0.0001f)
        assertEquals(3f, (parsed("3 Stück") as Quantity.Pieces).count, 0.0001f)
        assertEquals(4f, (parsed("four cubes") as Quantity.Pieces).count, 0.0001f)
    }

    @Test
    fun mixedNumbersAndNumberWords() {
        assertEquals(360f, volume("1 1/2 Tassen"), 0.001f)
        assertEquals(360f, volume("1½ Tassen"), 0.001f)
        assertEquals(360f, volume("eineinhalb Tassen"), 0.001f)
        assertEquals(360f, volume("one and a half cups"), 0.001f)
        assertEquals(1.5f, mass("1,5 g"), 0.0001f)
        assertEquals(1.5f, mass("1.5 g"), 0.0001f)
        assertEquals(120f, volume("halbe Tasse"), 0.001f)
        assertEquals(80f, volume("1/3 Tasse"), 0.01f)
    }

    @Test
    fun bareNumbersAreAmbiguousNotGuessed() {
        val result = QuantityParser.parse("2")
        assertTrue(result is QuantityParse.Ambiguous)
        val candidates = (result as QuantityParse.Ambiguous).candidates
        assertTrue(candidates.any { it is Quantity.Servings && it.count == 2f })
        assertTrue(candidates.any { it is Quantity.Pieces && it.count == 2f })
        assertTrue(candidates.any { it is Quantity.Mass && it.grams == 2f })
        assertTrue(candidates.none { it is Quantity.Fraction })
    }

    @Test
    fun nonsenseIsUnparsed() {
        assertEquals(QuantityParse.Unparsed, QuantityParser.parse(""))
        assertEquals(QuantityParse.Unparsed, QuantityParser.parse("   "))
        assertEquals(QuantityParse.Unparsed, QuantityParser.parse("viel"))
        assertEquals(QuantityParse.Unparsed, QuantityParser.parse("g 70"))
    }
}
