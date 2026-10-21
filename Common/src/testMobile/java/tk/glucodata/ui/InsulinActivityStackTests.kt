package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.data.journal.IobCurvePoint

class InsulinActivityStackTests {

    private companion object {
        const val HOUR = 3_600_000L
    }

    /** A triangle peaking at 1 after an hour and over at two: an area of one activity-hour. */
    private val triangle = listOf(IobCurvePoint(0, 0f), IobCurvePoint(60, 1f), IobCurvePoint(120, 0f))

    @Test
    fun rateIsDoseOverArea() {
        val dose = InsulinActivityStack.Dose(0L, 4f, triangle, 0)
        assertEquals(1f, dose.areaHours, 0.0001f)
        assertEquals(4f, InsulinActivityStack.rate(dose, HOUR), 0.0001f)
        assertEquals(2f, InsulinActivityStack.rate(dose, HOUR / 2), 0.0001f)
    }

    @Test
    fun aCurveThatEndsAboveZeroStopsAtItsEnd() {
        // Steady-state basal curves don't return to zero; the dose still ends with its curve.
        val basal = listOf(IobCurvePoint(0, 0.7f), IobCurvePoint(2520, 0.05f))
        val dose = InsulinActivityStack.Dose(0L, 20f, basal, 0)
        assertEquals(0f, InsulinActivityStack.rate(dose, 43 * HOUR), 0f)
        assertEquals(0f, InsulinActivityStack.rate(dose, -1L), 0f)
    }

    @Test
    fun overlappingDosesAdd() {
        val first = InsulinActivityStack.Dose(0L, 2f, triangle, 0)
        val second = InsulinActivityStack.Dose(HOUR / 2, 2f, triangle, 0)
        val tops = InsulinActivityStack.stack(listOf(first, second), 1, longArrayOf(HOUR))
        // 2 U at its peak (2 U/h) plus 2 U half way up (1 U/h).
        assertEquals(3f, tops[0][0], 0.0001f)
    }

    @Test
    fun layersStackOnTheOnesBelow() {
        val basal = InsulinActivityStack.Dose(0L, 1f, triangle, 0)
        val bolus = InsulinActivityStack.Dose(0L, 3f, triangle, 1)
        val tops = InsulinActivityStack.stack(listOf(basal, bolus), 2, longArrayOf(HOUR))
        assertEquals(1f, tops[0][0], 0.0001f)
        assertEquals(4f, tops[1][0], 0.0001f)
    }

    @Test
    fun scaleRoundsUpToHalfOrWholeUnits() {
        assertEquals(0.5f, InsulinActivityStack.scaleFor(0.2f), 0f)
        assertEquals(1.5f, InsulinActivityStack.scaleFor(1.3f), 0f)
        assertEquals(4f, InsulinActivityStack.scaleFor(3.2f), 0f)
        assertEquals(1f, InsulinActivityStack.scaleFor(0f), 0f)
    }
}
