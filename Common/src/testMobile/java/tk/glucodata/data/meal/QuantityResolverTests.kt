package tk.glucodata.data.meal

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The acceptance matrix from the spike (§6.4), plus the cross-dimension cases around it. */
class QuantityResolverTests {
    private val locale = Locale.GERMANY

    private fun resolved(text: String, ref: NutritionReference): QuantityResolution.Resolved {
        val result = QuantityResolver.resolve(text, ref, locale)
        assertTrue("expected Resolved for '$text' against ${ref.basis} but was $result", result is QuantityResolution.Resolved)
        return result as QuantityResolution.Resolved
    }

    private fun missing(text: String, ref: NutritionReference): MissingReference {
        val result = QuantityResolver.resolve(text, ref, locale)
        assertTrue("expected Missing for '$text' against ${ref.basis} but was $result", result is QuantityResolution.Missing)
        return (result as QuantityResolution.Missing).need
    }

    private val per100g = NutritionReference(basis = NutritionBasis.PER_100G)
    private val per100ml = NutritionReference(basis = NutritionBasis.PER_100ML)

    @Test
    fun massAgainstPer100gIsDirect() {
        val r = resolved("70g", per100g)
        assertEquals(0.7f, r.factor, 0.0001f)
        assertEquals(70f, r.grams!!, 0.0001f)
    }

    @Test
    fun cupAgainstPer100mlNeedsNoDensity() {
        val r = resolved("1 Tasse", per100ml)
        assertEquals(2.4f, r.factor, 0.0001f)
        assertEquals(240f, r.milliliters!!, 0.0001f)
        assertTrue(r.steps.joinToString().contains("240 ml"))
    }

    @Test
    fun cupAgainstPer100gGoesThroughDensityVisibly() {
        val milk = per100g.copy(densityGramsPerMl = 1.03f)
        val r = resolved("1 Tasse", milk)
        assertEquals(240f * 1.03f / 100f, r.factor, 0.0001f)
        assertEquals(240f * 1.03f, r.grams!!, 0.01f)
        assertTrue("density must show in the derivation: ${r.steps}", r.steps.any { it.contains("1,03") })
    }

    @Test
    fun cupAgainstPer100gWithoutDensityAsks() {
        assertEquals(MissingReference.DENSITY, missing("1 Tasse", per100g))
    }

    @Test
    fun threeTablespoonsAgainstPer100ml() {
        assertEquals(0.45f, resolved("drei Esslöffel", per100ml).factor, 0.0001f)
    }

    @Test
    fun thirdOfPackageNeedsNetQuantity() {
        val bar = per100g.copy(netQuantity = 100f, netUnit = AmountUnit.GRAM)
        val r = resolved("1/3", bar)
        assertEquals(1f / 3f, r.factor, 0.0001f)
        assertEquals(100f / 3f, r.grams!!, 0.001f)
        assertEquals(MissingReference.NET_QUANTITY, missing("1/3", per100g))
    }

    @Test
    fun thirtyPercentOfPackage() {
        val bar = per100g.copy(netQuantity = 250f, netUnit = AmountUnit.GRAM)
        val r = resolved("30%", bar)
        assertEquals(0.75f, r.factor, 0.0001f) // 30 % of 250 g = 75 g = 0.75 × 100 g
        assertEquals(75f, r.grams!!, 0.001f)
    }

    @Test
    fun twoServingsAgainstPerServingBasis() {
        val recipe = NutritionReference(basis = NutritionBasis.PER_SERVING)
        assertEquals(2f, resolved("2 Portionen", recipe).factor, 0.0001f)
    }

    @Test
    fun twoServingsAgainstPer100gUsesServingSize() {
        val chocolate = per100g.copy(servingQuantity = 6.52f, servingUnit = AmountUnit.GRAM, servingText = "1 Cube (6.52 g)", servingPieces = 1f, servingPieceLabel = "Cube")
        val r = resolved("2 Portionen", chocolate)
        assertEquals(2f * 6.52f / 100f, r.factor, 0.0001f)
        assertEquals(13.04f, r.grams!!, 0.001f)
        assertEquals(MissingReference.SERVING_SIZE, missing("2 Portionen", per100g))
    }

    @Test
    fun twoBarsWhenTheServingIsOneBar() {
        val bar = per100g.copy(servingText = "1 Riegel (25 g)", servingQuantity = 25f, servingUnit = AmountUnit.GRAM, servingPieces = 1f, servingPieceLabel = "Riegel")
        val r = resolved("2 Riegel", bar)
        assertEquals(50f, r.grams!!, 0.001f)
        assertEquals(0.5f, r.factor, 0.0001f) // two bars = 50 g = half of the 100 g basis
        // and against a per-serving basis the factor is plainly 2
        val perServingBar = bar.copy(basis = NutritionBasis.PER_SERVING)
        assertEquals(2f, resolved("2 Riegel", perServingBar).factor, 0.0001f)
    }

    @Test
    fun severalPiecesOnlyShareAServingWeightWhenTheTextCarriesThatWeight() {
        val bare = per100g.copy(
            servingText = "6 sausages",
            servingQuantity = 100f,
            servingUnit = AmountUnit.GRAM,
            servingPieces = 6f,
            servingPieceLabel = "sausages"
        )
        assertNull(bare.effectiveServingPieces)
        assertEquals(MissingReference.PIECE_WEIGHT, missing("1 sausage", bare))

        val repeatedReferenceAmount = bare.copy(servingText = "6 sausages (100 g)")
        assertNull(repeatedReferenceAmount.effectiveServingPieces)
        assertEquals(MissingReference.PIECE_WEIGHT, missing("1 sausage", repeatedReferenceAmount))

        val explicit = bare.copy(servingText = "6 sausages (400 g)", servingQuantity = 400f)
        val one = resolved("1 sausage", explicit)
        assertEquals(400f / 6f, one.grams!!, 0.001f)
        assertEquals((400f / 6f) / 100f, one.factor, 0.0001f)
    }

    @Test
    fun packagePiecesDivideTheNetContentsNotTheServingSize() {
        val sausages = per100g.copy(
            netQuantity = 400f,
            netUnit = AmountUnit.GRAM,
            packagePieces = 6f,
            packagePieceLabel = "sausages",
            servingQuantity = 59f,
            servingUnit = AmountUnit.GRAM
        )
        val one = resolved("1 sausage", sausages)
        assertEquals(400f / 6f, one.grams!!, 0.001f)
        assertEquals((400f / 6f) / 100f, one.factor, 0.0001f)

        val explicitWeight = sausages.copy(pieceGrams = 62.5f)
        assertEquals(400f / 6f, resolved("1 sausage", explicitWeight).grams!!, 0.001f)
        assertEquals(62.5f, resolved("1 sausage", explicitWeight.copy(packagePieces = null)).grams!!, 0.001f)
    }

    @Test
    fun piecesWithoutAnyWeightAsk() {
        assertEquals(MissingReference.PIECE_WEIGHT, missing("2 Riegel", per100g))
        val learned = per100g.copy(pieceGrams = 25f)
        assertEquals(0.5f, resolved("2 Riegel", learned).factor, 0.0001f)
    }

    @Test
    fun oneAndAHalfCupsAgainstPer100ml() {
        val r = resolved("1 1/2 Tassen", per100ml)
        assertEquals(360f, r.milliliters!!, 0.001f)
        assertEquals(3.6f, r.factor, 0.0001f)
    }

    @Test
    fun oneAndAHalfPackagesWithNetKnown() {
        val pack = per100g.copy(netQuantity = 200f, netUnit = AmountUnit.GRAM)
        val r = resolved("eineinhalb Packungen", pack)
        assertEquals(3f, r.factor, 0.0001f) // 1.5 × 200 g = 300 g = 3 × 100 g
        assertEquals(300f, r.grams!!, 0.001f)
    }

    @Test
    fun gramsAgainstPer100mlNeedDensity() {
        assertEquals(MissingReference.DENSITY, missing("100 g", per100ml))
        val r = resolved("103 g", per100ml.copy(densityGramsPerMl = 1.03f))
        assertEquals(1f, r.factor, 0.0001f)
    }

    @Test
    fun mealBatchPortionsAndFractions() {
        val meal = NutritionReference.forMeal(servings = 4f, cookedWeightGrams = null)
        assertEquals(0.5f, resolved("2 Portionen", meal).factor, 0.0001f)
        assertEquals(1f / 3f, resolved("1/3", meal).factor, 0.0001f)
        assertEquals(1f, resolved("alles", meal).factor, 0.0001f)
        assertEquals(MissingReference.BATCH_WEIGHT, missing("250 g", meal))
        val weighed = NutritionReference.forMeal(servings = 4f, cookedWeightGrams = 1000f)
        assertEquals(0.25f, resolved("250 g", weighed).factor, 0.0001f)
        val unportioned = NutritionReference.forMeal(servings = null, cookedWeightGrams = null)
        assertEquals(MissingReference.SERVINGS_PER_BATCH, missing("1 Portion", unportioned))
    }

    @Test
    fun bareNumberOffersOnlyCandidatesTheReferenceCanResolve() {
        val bar = per100g.copy(servingQuantity = 25f, servingUnit = AmountUnit.GRAM, servingPieces = 1f, servingPieceLabel = "Riegel")
        val result = QuantityResolver.resolve("2", bar, locale)
        assertTrue(result is QuantityResolution.Ambiguous)
        val candidates = (result as QuantityResolution.Ambiguous).candidates
        assertTrue(candidates.any { it is Quantity.Mass })
        assertTrue(candidates.any { it is Quantity.Servings })
        assertTrue(candidates.any { it is Quantity.Pieces })
        // Without serving or piece info, only mass survives.
        val bare = QuantityResolver.resolve("2", per100g, locale) as QuantityResolution.Ambiguous
        assertEquals(listOf<Quantity>(Quantity.Mass(2f)), bare.candidates)
    }

    @Test
    fun derivationShowsTheChain() {
        val r = resolved("70g", per100g)
        assertEquals(2, r.steps.size)
        assertEquals("70 g", r.steps[0])
        assertTrue(r.steps[1].contains("÷ 100 g"))
        assertTrue(r.steps[1].contains("× 0,7"))
    }

    @Test
    fun nothingResolvesFromNonsense() {
        assertEquals(QuantityResolution.Unparsed, QuantityResolver.resolve("viel", per100g, locale))
        assertNull((QuantityResolver.resolve("1/3", per100g, locale) as? QuantityResolution.Resolved))
        assertNotNull(QuantityResolver.resolve("1/3", per100g, locale) as? QuantityResolution.Missing)
    }
}
