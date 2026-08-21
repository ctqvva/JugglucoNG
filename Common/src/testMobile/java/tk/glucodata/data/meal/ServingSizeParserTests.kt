package tk.glucodata.data.meal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServingSizeParserTests {
    @Test
    fun pieceWithBracketedWeight() {
        val spec = ServingSizeParser.parse("1 Riegel (25 g)")!!
        assertEquals(1f, spec.pieces!!, 0.0001f)
        assertEquals("Riegel", spec.pieceLabel)
        assertEquals(25f, spec.quantity!!, 0.0001f)
        assertEquals(AmountUnit.GRAM, spec.unit)
    }

    @Test
    fun openFoodFactsCubeAndPortion() {
        val cube = ServingSizeParser.parse("1 Cube (6.52 g)")!!
        assertEquals(1f, cube.pieces!!, 0.0001f)
        assertEquals("Cube", cube.pieceLabel)
        assertEquals(6.52f, cube.quantity!!, 0.0001f)

        val can = ServingSizeParser.parse("1 portion (330 ml)")!!
        assertEquals(330f, can.quantity!!, 0.0001f)
        assertEquals(AmountUnit.MILLILITER, can.unit)
        assertEquals("portion", can.pieceLabel)
    }

    @Test
    fun plainWeightHasNoPiece() {
        val spec = ServingSizeParser.parse("30 g")!!
        assertNull(spec.pieces)
        assertNull(spec.pieceLabel)
        assertEquals(30f, spec.quantity!!, 0.0001f)
        assertEquals(30f, ServingSizeParser.parse("30g")!!.quantity!!, 0.0001f)
    }

    @Test
    fun severalSlicesWithCommaDecimal() {
        val spec = ServingSizeParser.parse("2 Scheiben (62,5 g)")!!
        assertEquals(2f, spec.pieces!!, 0.0001f)
        assertEquals("Scheiben", spec.pieceLabel)
        assertEquals(62.5f, spec.quantity!!, 0.0001f)
    }

    @Test
    fun unitsOtherThanGramsAreNormalized() {
        assertEquals(250f, ServingSizeParser.parse("25 cl")!!.quantity!!, 0.0001f)
        assertEquals(1000f, ServingSizeParser.parse("1 l")!!.quantity!!, 0.0001f)
        assertEquals(500f, ServingSizeParser.parse("0.5 kg")!!.quantity!!, 0.0001f)
    }

    @Test
    fun emptyOrUselessTextIsNull() {
        assertNull(ServingSizeParser.parse(null))
        assertNull(ServingSizeParser.parse(""))
        assertNull(ServingSizeParser.parse("varies"))
    }
}
