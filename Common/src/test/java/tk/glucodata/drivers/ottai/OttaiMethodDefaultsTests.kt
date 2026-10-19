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
}
