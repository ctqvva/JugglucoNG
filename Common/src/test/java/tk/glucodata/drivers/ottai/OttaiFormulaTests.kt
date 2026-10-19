package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests the RPN evaluator port (a1.a.e stack machine). */
class OttaiFormulaTests {

    private val noBytes = ByteArray(12)
    private fun v(vararg d: Double) = d

    @Test
    fun multiply_currentByCoefficient() {
        // "V0 C0 ML" -> current * coeff0
        val r = OttaiFormula.evaluate("V0 C0 ML", listOf(0.05), v(100.0, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes)
        assertEquals(5.0, r, 1e-9)
    }

    @Test
    fun subtract_isStackTopMinusNext() {
        // SB: stack[sp] - stack[sp+1]  => 10 - 3
        val r = OttaiFormula.evaluate("V0 V1 SB", emptyList(), v(10.0, 3.0, 0.0, 0.0, 0.0, 0.0), noBytes)
        assertEquals(7.0, r, 1e-9)
    }

    @Test
    fun divide_order() {
        // DV: stack[sp] / stack[sp+1] => 10 / 4
        val r = OttaiFormula.evaluate("V0 V1 DV", emptyList(), v(10.0, 4.0, 0.0, 0.0, 0.0, 0.0), noBytes)
        assertEquals(2.5, r, 1e-9)
    }

    @Test
    fun multiGroup_resultReference() {
        // group0 = V0*C0 = 3*2 = 6 ; group1 = R0 + C1 = 6 + 1 = 7
        val r = OttaiFormula.evaluate("V0 C0 ML;R0 C1 AD", listOf(2.0, 1.0), v(3.0, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes)
        assertEquals(7.0, r, 1e-9)
    }

    @Test
    fun belowFloor_forcedToZero() {
        val r = OttaiFormula.evaluate("V0", emptyList(), v(0.05, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes)
        assertEquals(0.0, r, 1e-12)
    }

    @Test
    fun round_toDecimals() {
        // "V0 C0 RD" round 3.14159 to 2 decimals
        val r = OttaiFormula.evaluate("V0 C0 RD", listOf(2.0), v(3.14159, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes)
        assertEquals(3.14, r, 1e-9)
    }

    @Test
    fun byteVariable_isSigned() {
        val rec = ByteArray(12).also { it[4] = 10 }
        // "B4 C0 AD" => 10 + 0.5 = 10.5
        val r = OttaiFormula.evaluate("B4 C0 AD", listOf(0.5), v(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), rec)
        assertEquals(10.5, r, 1e-9)
    }

    @Test
    fun comparisons_returnMaskValueOrZero() {
        assertEquals(7.0, OttaiFormula.evaluate("V0 C0 C1 GT", listOf(5.0, 7.0), v(6.0, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes), 1e-9)
        assertEquals(0.0, OttaiFormula.evaluate("V0 C0 C1 GT", listOf(5.0, 7.0), v(4.0, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes), 1e-9)
        assertEquals(3.0, OttaiFormula.evaluate("V0 C0 C1 LE", listOf(5.0, 3.0), v(5.0, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes), 1e-9)
        assertEquals(0.0, OttaiFormula.evaluate("V0 C0 C1 LE", listOf(5.0, 3.0), v(6.0, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes), 1e-9)
    }

    @Test
    fun ageCorrectionMasks_areMutuallyExclusive() {
        // This mirrors the Ottai age-correction pattern:
        //   V2 C12 1 LE
        //   V2 C12 1 GT
        // Only one side may contribute; the old decompile-shaped port returned 1.0
        // for the false side too, doubling the correction factor.
        val early = OttaiFormula.evaluate(
            "V2 C0 1 LE;V2 C0 1 GT;R0 R1 AD",
            listOf(86_400.0),
            v(0.0, 0.0, 25.0, 0.0, 0.0, 0.0),
            noBytes,
        )
        val late = OttaiFormula.evaluate(
            "V2 C0 1 LE;V2 C0 1 GT;R0 R1 AD",
            listOf(86_400.0),
            v(0.0, 0.0, 172_800.0, 0.0, 0.0, 0.0),
            noBytes,
        )
        assertEquals(1.0, early, 1e-9)
        assertEquals(1.0, late, 1e-9)
    }

    @Test
    fun buildVariables_v4IsIntegerHours() {
        val vv = OttaiFormula.buildVariables(rawCurrent = 120, temperature = 35.0, runtimeSec = 7300, dataNo = 42, voltage = 6)
        assertEquals(120.0, vv[0], 1e-9)
        assertEquals(35.0, vv[1], 1e-9)
        assertEquals(7300.0, vv[2], 1e-9)
        assertEquals(42.0, vv[3], 1e-9)
        assertEquals(2.0, vv[4], 1e-9) // 7300/3600 integer = 2
        assertEquals(6.0, vv[5], 1e-9)
    }
}
