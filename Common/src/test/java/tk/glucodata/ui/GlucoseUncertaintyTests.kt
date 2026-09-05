package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucoseUncertainty
import tk.glucodata.ui.util.inDisplayUnit

/**
 * The generic uncertainty representation and the unit handling around it.
 *
 * Backward compatibility is the load-bearing property: every reading that
 * predates this feature, and every reading from a sensor whose algorithm does
 * not model uncertainty, must keep working and render as a plain line.
 */
class GlucoseUncertaintyTests {

    @Test
    fun aPointWithoutUncertaintyIsTheDefault() {
        val point = GlucosePoint(value = 100f, time = "", timestamp = 1_000L)
        assertNull(point.uncertainty)
    }

    @Test
    fun existingConstructionSitesStillCompileAndCarryNoUncertainty() {
        // The positional form used across the app predates the new field.
        val point = GlucosePoint(120f, "10:00", 5_000L, 118f, 0.3f, "SIBI:X")
        assertNull(point.uncertainty)
        assertEquals(120f, point.value, 0f)
        assertEquals("SIBI:X", point.sensorSerial)
    }

    @Test
    fun boundsAreValidatedRatherThanTrusted() {
        assertTrue(GlucoseUncertainty(90f, 110f).isUsable)
        assertFalse("upper below lower", GlucoseUncertainty(110f, 90f).isUsable)
        assertFalse("non-finite", GlucoseUncertainty(Float.NaN, 110f).isUsable)
        assertFalse("non-positive", GlucoseUncertainty(0f, 110f).isUsable)
    }

    @Test
    fun intervalsMayBeAsymmetricAroundTheValue() {
        // The whole point: a posterior torn between "real low" and "artifact"
        // is not symmetric, and the representation must not force it to be.
        val uncertainty = GlucoseUncertainty(lower = 55f, upper = 105f)
        val value = 98f
        assertTrue(value - uncertainty.lower > uncertainty.upper - value)
        assertTrue(uncertainty.isUsable)
    }

    @Test
    fun convertingAPointToMmolConvertsItsIntervalWithIt() {
        val point = GlucosePoint(
            value = 108f,
            time = "",
            timestamp = 1_000L,
            rawValue = 106f,
            uncertainty = GlucoseUncertainty(lower = 90f, upper = 126f, confidence = 0.7f),
        )

        val converted = point.inDisplayUnit(isMmol = true)

        assertEquals(6.0f, converted.value, 0.02f)
        val uncertainty = requireNotNull(converted.uncertainty)
        assertEquals(5.0f, uncertainty.lower, 0.02f)
        assertEquals(7.0f, uncertainty.upper, 0.02f)
        // Metadata is not a measurement and must not be rescaled.
        assertEquals(0.7f, uncertainty.confidence)
        assertEquals(0.9f, uncertainty.intervalMass, 0f)
    }

    @Test
    fun convertingToMgdlLeavesEverythingAlone() {
        val point = GlucosePoint(
            value = 108f,
            time = "",
            uncertainty = GlucoseUncertainty(lower = 90f, upper = 126f),
        )
        val converted = point.inDisplayUnit(isMmol = false)
        assertEquals(108f, converted.value, 0f)
        assertEquals(90f, requireNotNull(converted.uncertainty).lower, 0f)
    }

    @Test
    fun convertingAPointWithoutUncertaintyKeepsItNull() {
        val converted = GlucosePoint(108f, "", 1_000L, 106f).inDisplayUnit(isMmol = true)
        assertEquals(6.0f, converted.value, 0.02f)
        assertNull(converted.uncertainty)
    }

    @Test
    fun aListConvertsEveryPointsIntervalConsistently() {
        val points = listOf(
            GlucosePoint(90f, "", 1L, uncertainty = GlucoseUncertainty(72f, 108f)),
            GlucosePoint(180f, "", 2L),
            GlucosePoint(126f, "", 3L, uncertainty = GlucoseUncertainty(108f, 144f)),
        )

        val converted = points.inDisplayUnit(isMmol = true)

        assertEquals(3, converted.size)
        assertEquals(4.0f, requireNotNull(converted[0].uncertainty).lower, 0.02f)
        assertNull(converted[1].uncertainty)
        assertEquals(8.0f, requireNotNull(converted[2].uncertainty).upper, 0.02f)
    }

    @Test
    fun shiftingMovesTheBandWithoutChangingItsWidth() {
        val uncertainty = GlucoseUncertainty(lower = 4.0f, upper = 6.4f, confidence = 0.8f)
        val shifted = uncertainty.shifted(0.6f)

        assertEquals(4.6f, shifted.lower, 0.0001f)
        assertEquals(7.0f, shifted.upper, 0.0001f)
        // Displacing the value it describes does not make the sensor more or
        // less certain, so the width is preserved exactly.
        assertEquals(uncertainty.halfWidth, shifted.halfWidth, 0.0001f)
        assertEquals(0.8f, shifted.confidence)
    }

    @Test
    fun shiftingByZeroOrNonFiniteIsANoOp() {
        val uncertainty = GlucoseUncertainty(lower = 4.0f, upper = 6.4f)
        assertEquals(uncertainty, uncertainty.shifted(0f))
        assertEquals(uncertainty, uncertainty.shifted(Float.NaN))
    }

    @Test
    fun halfWidthDescribesTheBandNotASigma() {
        val uncertainty = GlucoseUncertainty(lower = 4f, upper = 7f)
        assertEquals(1.5f, uncertainty.halfWidth, 0.0001f)
    }

    @Test
    fun segmentSplittingIsUnaffectedByTheNewField() {
        // Uncertainty must not participate in gap or sensor-change detection.
        val points = listOf(
            GlucosePoint(100f, "", 0L, sensorSerial = "A"),
            GlucosePoint(101f, "", 60_000L, sensorSerial = "A", uncertainty = GlucoseUncertainty(90f, 110f)),
            GlucosePoint(102f, "", 120_000L, sensorSerial = "A"),
        )
        assertEquals(1, GlucosePointSegments.split(points).size)
    }
}
