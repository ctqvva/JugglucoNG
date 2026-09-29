package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiMethodDefaultsTests {

    private val v17Coefficient =
        "0.1,0.5,18.5,0.0000,-0.0017,0.5010,0.1098,0.015,0.95,0.8,1.1,0.05,172800,1"

    @Test
    fun resolve_keepsExplicitMethod() {
        assertEquals("V0 C0 ML", OttaiMethodDefaults.resolve(" V0 C0 ML ", v17Coefficient))
    }

    @Test
    fun resolve_fillsBlankMethodForKnown14CoefficientProfile() {
        assertEquals(
            OttaiMethodDefaults.STANDARD_14_COEFF_METHOD,
            OttaiMethodDefaults.resolve("", v17Coefficient),
        )
    }

    @Test
    fun resolve_doesNotInventMethodForUnknownCoefficientProfile() {
        assertEquals("", OttaiMethodDefaults.resolve("", "1,2,3"))
    }

    @Test
    fun resolvedMethod_evaluatesWithV17CoefficientProfile() {
        val coefficients = v17Coefficient.split(',').map { it.toDouble() }
        val variables = OttaiFormula.buildVariables(
            rawCurrent = 15391,
            temperature = 30.13,
            runtimeSec = 19585 * 60,
            dataNo = 19585,
            voltage = 71,
        )
        val glucose = OttaiFormula.evaluate(
            OttaiMethodDefaults.resolve("", v17Coefficient),
            coefficients,
            variables,
            ByteArray(12),
        )

        assertTrue(glucose > 0.1 && glucose < 40.0)
    }

    @Test
    fun coefficientProfile_requiresFourteenFiniteValues() {
        assertTrue(OttaiMethodDefaults.matchesStandard14CoefficientProfile(v17Coefficient))
        assertFalse(OttaiMethodDefaults.matchesStandard14CoefficientProfile(v17Coefficient + ",2"))
        assertFalse(OttaiMethodDefaults.matchesStandard14CoefficientProfile("0.1,0.5,NaN,0,0,0,0,0,0,0,0,0,172800,1"))
    }

    /**
     * The number the user sees. Expected values come from an independent closed-form
     * implementation written from the method text (a Python script, not a port of the stack
     * machine and never calling this code), with V0 = raw, V1 = temperature, V2 = runtime s:
     *   R0 = (V1 + C0) * C1 + C2          R1 = V0 / R0^2
     *   R2 = C3*0 + C4*R1^2 + C5*R1 + C6   R3 = C7*R2^2 + C8*R2 + C9
     *   R4 = (C10 - V2/86400*C11) if V2 <= C12, else C13
     *   G  = R3 * R4 rounded half-up to one decimal (below 0.1 -> 0.0)
     *   (15391, 30.13, 1_175_100) late wear      R3 7.744526 x R4 1.0      -> 7.7
     *   (15391, 30.13,    86_400) early, day 1   R3 7.744526 x R4 1.05     -> 8.1
     *   (15391, 30.13,     3_600) early, hour 1  R3 7.744526 x R4 1.097917 -> 8.5
     *   (20443, 28.9,          0) runtime 0      R3 10.462175 x R4 1.1     -> 11.5
     *   (10410, 30.7,  2_247_900) late, low raw  R3 5.388043 x R4 1.0      -> 5.4
     * Every unrounded product is at least 0.005 from a rounding tie, so the rounding mode
     * cannot decide the result. With both age masks at 1.0 (the old port) the late-wear 7.7
     * would be about 11.0.
     */
    @Test
    fun standardMethod_goldenValues() {
        val coefficients = v17Coefficient.split(',').map { it.toDouble() }
        fun glucose(raw: Int, temperature: Double, runtimeSec: Int): Double =
            OttaiFormula.evaluate(
                OttaiMethodDefaults.STANDARD_14_COEFF_METHOD,
                coefficients,
                OttaiFormula.buildVariables(raw, temperature, runtimeSec, runtimeSec / 60, 71),
                ByteArray(12),
            )
        assertEquals(7.7, glucose(15391, 30.13, 1_175_100), 1e-9)
        assertEquals(8.1, glucose(15391, 30.13, 86_400), 1e-9)
        assertEquals(8.5, glucose(15391, 30.13, 3_600), 1e-9)
        assertEquals(11.5, glucose(20443, 28.9, 0), 1e-9)
        assertEquals(5.4, glucose(10410, 30.7, 37_465 * 60), 1e-9)
    }
}
