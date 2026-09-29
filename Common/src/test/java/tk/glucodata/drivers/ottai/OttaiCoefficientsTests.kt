package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C{i} in the cloud method is the i-th token of the coefficient CSV. Dropping a bad token from the
 * middle (the old mapNotNull) shifted every later coefficient: a plausible but wrong glucose with
 * no error. The owner's rule (2026-09-23): any empty or unparsable token voids the set, so there
 * are no readings until the cloud sends a valid one; only a single trailing comma is forgiven.
 */
class OttaiCoefficientsTests {

    private fun materials(coefficient: String) = OttaiRegistry.DeviceMaterials(
        keyAHex = "",
        method = "",
        coefficient = coefficient,
        activeTimeMs = 0L,
        deviceVersion = "",
        deviceId = 0,
    )

    @Test
    fun aValidSetKeepsEveryIndex() {
        assertEquals(listOf(0.1, 0.2, 0.3), materials("0.1,0.2,0.3").coefficients)
        assertEquals(listOf(0.1, 0.2), materials(" 0.1 , 0.2 ").coefficients)
    }

    @Test
    fun oneTrailingCommaIsForgiven() {
        assertEquals(listOf(0.1, 0.2), materials("0.1,0.2,").coefficients)
        assertEquals(listOf(0.1, 0.2), materials("0.1,0.2, ").coefficients)
    }

    @Test
    fun anyOtherBadTokenVoidsTheWholeSet() {
        // The old parse gave [0.1, 0.5] here: C1 read 0.5, the value meant for C2.
        assertEquals(emptyList<Double>(), materials("0.1,,0.5").coefficients)
        assertEquals(emptyList<Double>(), materials("0.1,0.2,,").coefficients)
        assertEquals(emptyList<Double>(), materials("0.1,abc,0.5").coefficients)
        assertEquals(emptyList<Double>(), materials(",0.1").coefficients)
        assertEquals(emptyList<Double>(), materials("").coefficients)
        // A number that is not finite is no coefficient either.
        assertEquals(emptyList<Double>(), materials("0.1,NaN,0.5").coefficients)
        assertEquals(emptyList<Double>(), materials("0.1,Infinity").coefficients)
        assertEquals(emptyList<Double>(), materials("1e999,0.1").coefficients)
    }

    /**
     * A reading refused for a non-finite glucose must not enter the recent-rejected veto: the veto
     * refuses the same dataNo with the same raw current again, so once valid coefficients arrive it
     * would go on refusing records whose only fault was the materials.
     */
    @Test
    fun aNonFiniteRefusalIsNotHeldAgainstTheRecord() {
        assertEquals(false, OttaiBleManager.remembersRejection(Float.NaN))
        assertEquals(false, OttaiBleManager.remembersRejection(Float.POSITIVE_INFINITY))
        assertEquals(true, OttaiBleManager.remembersRejection(5.5f))
        assertEquals(true, OttaiBleManager.remembersRejection(0f))
        // The one place a refusal is remembered goes through it.
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !java.io.File(dir, "Common/src/main/java").isDirectory) dir = dir.parentFile
        val manager = java.io.File(dir ?: error("repo root not found"),
            "Common/src/main/java/tk/glucodata/drivers/ottai/OttaiBleManager.kt").readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")
            .replace(Regex("\\s+"), " ")
        assertEquals(2, Regex("\\brememberRejectedReading\\(").findAll(manager).count())
        assertTrue(manager.contains("if (remembersRejection(mmol)) rememberRejectedReading(r, mmol)"))
    }

    private fun raw15391Record(): ByteArray {
        // The 2026-09-22 E1.2.3 notify framed as in OttaiLiveFreshnessTests: current 0x3C1F.
        val frame = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(),
            0x1F, 0x3C, 0x34, 0xE1.toByte(), 0x15, 0xBA.toByte(), 0x47, 0xC5.toByte(), 0x0B,
        )
        return OttaiParser.frameRecords(frame, "E1.2.3(V1.7.SH2542.1)").single()
    }

    @Test
    fun aVoidedSetYieldsNoReadingRatherThanAShiftedOne() {
        val record = raw15391Record()
        val current = OttaiParser.parseRecord(record).rawCurrent.toDouble()
        // Sanity: a valid set reads C1 as meant.
        val valid = OttaiParser.toReading(record, "V0 C1 ML", materials("2,3").coefficients, 0L)
        assertEquals(current * 3.0, valid.adjustGlucose, 1e-9)
        assertEquals(current * 3.0, OttaiParser.toReading(record, "V0 C1 ML", materials("2,3,").coefficients, 0L).adjustGlucose, 1e-9)
        // The middle token is empty: the old parse read C1 as 3 (the value meant for C2); now there is
        // no value, and the output filter refuses it.
        val voided = OttaiParser.toReading(record, "V0 C1 ML", materials("2,,3").coefficients, 0L)
        assertTrue("glucose=${voided.adjustGlucose}", voided.adjustGlucose.isNaN())
        assertTrue(OttaiOutputFilter.hardRejectReason(OttaiParser.parseRecord(record), voided.adjustGlucose.toFloat()) != null)
    }

    @Test
    fun aCoefficientTheSetDoesNotHoldIsNoValueNotZero() {
        val v = DoubleArray(6) { 10.0 }
        val bytes = ByteArray(12)
        assertEquals(20.0, OttaiFormula.evaluate("V0 C1 ML", listOf(1.0, 2.0), v, bytes), 1e-9)
        // Before: "C2" stayed a literal token and read as 0.0, so a short set produced a glucose.
        assertTrue(OttaiFormula.evaluate("V0 C2 ML", listOf(1.0, 2.0), v, bytes).isNaN())
        // Any group, not only the last: the set is void as a whole.
        assertTrue(OttaiFormula.evaluate("V0 C5 ML;V0 C0 ML", listOf(1.0, 2.0), v, bytes).isNaN())
        // Operators that start with C (CS) and indices that are not numbers are not coefficient names:
        // cos(-10) is about -0.84, forced to 0.0, not NaN; "CX" stays the literal it always was (0.0).
        assertEquals(0.0, OttaiFormula.evaluate("V0 NG CS", emptyList(), v, bytes), 0.0)
        assertEquals(10.0, OttaiFormula.evaluate("V0 CX AD", emptyList(), v, bytes), 1e-9)
    }
}
