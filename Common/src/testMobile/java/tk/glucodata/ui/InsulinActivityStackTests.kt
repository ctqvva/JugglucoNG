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

    /** Tresiba's steady-state curve from the catalogue: already at 70 % when it is injected. */
    private val degludec = listOf(
        IobCurvePoint(0, 0.70f), IobCurvePoint(360, 0.96f), IobCurvePoint(720, 1f), IobCurvePoint(1080, 0.83f),
        IobCurvePoint(1440, 0.67f), IobCurvePoint(1800, 0.38f), IobCurvePoint(2160, 0.14f), IobCurvePoint(2520, 0.05f)
    )

    @Test
    fun aDailySteadyStateDoseAveragesItsUnitsOverTheDay() {
        val dose = InsulinActivityStack.Dose(0L, 24f, degludec, 0, steadyState = true)
        // One day of the curve adds up to one dose: 24 U over 24 h is 1 U/h on average.
        val times = LongArray(24 * 60 + 1) { it * 60_000L }
        val total = InsulinActivityStack.stack(listOf(dose), 1, times)[0]
        val average = (0 until 24 * 60).sumOf { ((total[it] + total[it + 1]) / 2f).toDouble() } / (24 * 60)
        assertEquals(1.0, average, 0.01)
    }

    @Test
    fun steadyStateDosesDoNotAddUp() {
        // Yesterday's dose is already in today's curve, so the band follows the latest dose
        // instead of adding yesterday's 42 h tail on top of it.
        val yesterday = InsulinActivityStack.Dose(0L, 24f, degludec, 0, steadyState = true)
        val today = InsulinActivityStack.Dose(24 * HOUR, 24f, degludec, 0, steadyState = true)
        val at = longArrayOf(30 * HOUR)
        val both = InsulinActivityStack.stack(listOf(yesterday, today), 1, at)[0][0]
        assertEquals(InsulinActivityStack.rate(today, 30 * HOUR), both, 0.0001f)
        // With no dose today, yesterday's runs on along its measured tail.
        assertEquals(InsulinActivityStack.rate(yesterday, 30 * HOUR), InsulinActivityStack.stack(listOf(yesterday), 1, at)[0][0], 0.0001f)
    }

    @Test
    fun aSteadyStateDoseSplitIntoTwoInjectionsCountsBoth() {
        // 40 U and 20 U logged at the same minute act as 60 U, not as whichever came first.
        val first = InsulinActivityStack.Dose(0L, 40f, degludec, 0, steadyState = true)
        val second = InsulinActivityStack.Dose(0L, 20f, degludec, 0, steadyState = true)
        val whole = InsulinActivityStack.Dose(0L, 60f, degludec, 0, steadyState = true)
        val at = longArrayOf(6 * HOUR)
        assertEquals(
            InsulinActivityStack.rate(whole, 6 * HOUR),
            InsulinActivityStack.stack(listOf(first, second), 1, at)[0][0],
            0.0001f
        )
    }

    @Test
    fun aOnceWeeklyCurveIsScaledOverTheWeek() {
        val week = listOf(IobCurvePoint(0, 1f), IobCurvePoint(10080, 1f))
        val dose = InsulinActivityStack.Dose(0L, 168f, week, 0, steadyState = true)
        assertEquals(1f, InsulinActivityStack.rate(dose, 50 * HOUR), 0.0001f)
    }

    @Test
    fun aShortDoseBetweenCoarseSamplesStillShowsAtItsPeak() {
        // A 90-minute inhaled dose two weeks into a chart: any fixed grid wide enough for two
        // weeks on a phone steps right over it.
        val start = 0L
        val end = 14 * 24 * HOUR
        val afrezza = InsulinActivityStack.Dose(
            5 * 24 * HOUR + 7 * 60_000L,
            4f,
            listOf(IobCurvePoint(0, 0f), IobCurvePoint(12, 0.3f), IobCurvePoint(45, 1f), IobCurvePoint(90, 0f)),
            0
        )
        val times = InsulinActivityStack.sampleTimes(listOf(afrezza), start, end)
        assertEquals(start, times.first())
        assertEquals(end, times.last())
        val tops = InsulinActivityStack.stack(listOf(afrezza), 1, times)[0]
        assertEquals(InsulinActivityStack.rate(afrezza, afrezza.timestamp + 45 * 60_000L), tops.max(), 0.0001f)
    }

    @Test
    fun scaleRoundsUpToHalfOrWholeUnits() {
        assertEquals(0.5f, InsulinActivityStack.scaleFor(0.2f), 0f)
        assertEquals(1.5f, InsulinActivityStack.scaleFor(1.3f), 0f)
        assertEquals(4f, InsulinActivityStack.scaleFor(3.2f), 0f)
        assertEquals(1f, InsulinActivityStack.scaleFor(0f), 0f)
    }
}
