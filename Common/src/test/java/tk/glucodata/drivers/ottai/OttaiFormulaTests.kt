package tk.glucodata.drivers.ottai

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    /**
     * The method is cloud input. An operator short of operands used to read below the stack
     * and throw ArrayIndexOutOfBoundsException on every live notify and history page; an RD
     * with a negative precision and an MD by zero threw the same way. Each now voids the whole
     * evaluation (NaN), which the output gate refuses.
     */
    @Test
    fun malformedMethod_doesNotThrow() {
        val record = OttaiRecord(
            dataNo = 100, voltage = 0, runtimeSec = 6_000, rawCurrent = 12_000, temperatureC = 32.0,
            recordBytes = ByteArray(OttaiParser.PARSER_RECORD_SIZE),
        )
        val methods = listOf(
            "V0 AD", "AD", "NG", "V0 V1 BW", "V0 V1 V2 BE", "V0 C0 GT",
            "V0 -1 RD", "V0 0 MD", "AD;R0 C0 AD",
        )
        for (m in methods) {
            val result = runCatching {
                OttaiFormula.evaluate(m, listOf(5.0), v(100.0, 3.0, 50.0, 0.0, 0.0, 0.0), noBytes)
            }
            assertTrue("$m threw ${result.exceptionOrNull()}", result.isSuccess)
            val g = result.getOrThrow()
            assertTrue("$m gave $g", g.isNaN())
            assertNotNull(m, OttaiOutputFilter.hardRejectReason(record, g.toFloat()))
        }
    }

    /**
     * A broken group read by a later one: a comparison or bitwise operator would turn its NaN into
     * a plausible glucose (BE with NaN takes its 1.0 branch, NaN.toInt() is 0), which the output
     * gate accepts. The whole evaluation is void instead.
     */
    @Test
    fun aMalformedGroupVoidsTheWholeEvaluation() {
        val values = v(100.0, 3.0, 50.0, 0.0, 0.0, 0.0)
        // Before, each broken group read on as NaN and came out finite: 1.0 * 5.5 = 5.5 (BE),
        // 0 or 5 = 5.0 (BO), (NaN > 0 is false) 0 + 7 = 7.0 (GT), (NaN and 1) 0 + 2 = 2.0 (BA).
        assertTrue(OttaiFormula.evaluate("V0 AD;R0 0 1 0 BE C0 ML", listOf(5.5), values, noBytes).isNaN())
        assertTrue(OttaiFormula.evaluate("V0 AD;R0 5 BO", emptyList(), values, noBytes).isNaN())
        assertTrue(OttaiFormula.evaluate("V0 0 MD;R0 0 7 GT 7 AD", emptyList(), values, noBytes).isNaN())
        assertTrue(OttaiFormula.evaluate("V0 -1 RD;R0 1 BA 2 AD", emptyList(), values, noBytes).isNaN())
        // A well-formed group read the same way still evaluates.
        assertEquals(5.5, OttaiFormula.evaluate("V0 V1 AD;R0 0 200 1 BE C0 ML", listOf(5.5), values, noBytes), 1e-9)
    }

    @Test
    fun operatorWithExactlyItsOperands_stillEvaluates() {
        // The arity guard must not be off by one: four operands are enough for BW and BE.
        val inside = v(5.0, 1.0, 10.0, 0.0, 0.0, 0.0) // value 5 inside (1, 10), mask 0 != 1
        assertEquals(5.0, OttaiFormula.evaluate("V0 V1 V2 V3 BW", emptyList(), inside, noBytes), 1e-9)
        assertEquals(5.0, OttaiFormula.evaluate("V0 V1 V2 V3 BE", emptyList(), inside, noBytes), 1e-9)
        assertEquals(3.0, OttaiFormula.evaluate("V0 AB", emptyList(), v(-3.0, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes), 1e-9)
        assertEquals(1.0, OttaiFormula.evaluate("V0 C0 MD", listOf(3.0), v(10.0, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes), 1e-9)
        // RD precision above the cap is clamped, and the clamp does not move the value.
        assertEquals(3.14159, OttaiFormula.evaluate("V0 C0 RD", listOf(1e9), v(3.14159, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes), 0.0)
    }

    /**
     * The user's phone formats numbers with a decimal comma. RD formats with Locale.ROOT and
     * parses back with toDouble(); a default-locale format would write "3,14" and throw on
     * every notify. Operators are matched after uppercase(Locale.ROOT); under tr_TR a
     * default-locale uppercase turns "mi" into "Mİ", which is no operator.
     */
    @Test
    fun evaluate_isLocaleIndependent() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            assertEquals(3.14, OttaiFormula.evaluate("V0 C0 RD", listOf(2.0), v(3.14159, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes), 1e-9)
            val coefficients =
                "0.1,0.5,18.5,0.0000,-0.0017,0.5010,0.1098,0.015,0.95,0.8,1.1,0.05,172800,1"
                    .split(',').map { it.toDouble() }
            val variables = OttaiFormula.buildVariables(
                rawCurrent = 15391, temperature = 30.13, runtimeSec = 1_175_100, dataNo = 19585, voltage = 71,
            )
            // Same vector and value as OttaiMethodDefaultsTests.standardMethod_goldenValues.
            assertEquals(
                7.7,
                OttaiFormula.evaluate(OttaiMethodDefaults.STANDARD_14_COEFF_METHOD, coefficients, variables, noBytes),
                1e-9,
            )

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // min(3, 2) = 2; an unmatched "mi" would be a literal and leave stack[0] = 3.
            assertEquals(2.0, OttaiFormula.evaluate("C0 C1 mi", listOf(3.0, 2.0), v(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), noBytes), 1e-9)
        } finally {
            Locale.setDefault(saved)
        }
    }
}
