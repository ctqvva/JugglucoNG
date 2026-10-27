package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.IobCurvePoint

class InsulinActivityStackTests {

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }

    private fun curve(vararg points: Pair<Int, Float>) = points.map { IobCurvePoint(it.first, it.second) }

    /** A triangle peaking at 1 after an hour and over at two. */
    private val triangle = curve(0 to 0f, 60 to 1f, 120 to 0f)

    /** From the catalogue: glargine U100's single-dose clamp, still at 72 % when it stops at 24 h. */
    private val glargineU100 = curve(
        0 to 0f, 120 to 0.27f, 240 to 0.55f, 360 to 0.82f, 480 to 1f, 600 to 0.78f,
        720 to 0.74f, 960 to 0.79f, 1200 to 0.80f, 1440 to 0.72f
    )

    /** From the catalogue: Tresiba's steady-state curve, already at 70 % when it is injected. */
    private val degludec = curve(
        0 to 0.70f, 360 to 0.96f, 720 to 1f, 1080 to 0.83f,
        1440 to 0.67f, 1800 to 0.38f, 2160 to 0.14f, 2520 to 0.05f
    )

    private fun rateAt(doses: List<InsulinActivityStack.Dose>, at: Long): Float =
        InsulinActivityStack.stack(doses, 1, longArrayOf(at)).tops[0][0]

    /** The units a dose's band adds up to, by the minute. */
    private fun unitsOf(dose: InsulinActivityStack.Dose): Double =
        (dose.timestamp..dose.endMillis step MINUTE).sumOf { InsulinActivityStack.rate(dose, it).toDouble() } / 60.0

    @Test
    fun eachDoseAddsUpToItsUnits() {
        listOf(
            InsulinActivityStack.Dose(0L, 4f, triangle, 0),
            InsulinActivityStack.Dose(0L, 10f, glargineU100, 0),
            InsulinActivityStack.Dose(0L, 20f, degludec, 0, steadyState = true)
        ).forEach { dose -> assertEquals(dose.units.toDouble(), unitsOf(dose), dose.units * 0.01) }
    }

    @Test
    fun theSmoothCurveKeepsItsPeakAndNeverGoesNegative() {
        val dose = InsulinActivityStack.Dose(0L, 4f, triangle, 0)
        val peak = InsulinActivityStack.rate(dose, HOUR)
        (0L..2 * HOUR step MINUTE).forEach { at ->
            val rate = InsulinActivityStack.rate(dose, at)
            assertTrue(rate >= 0f)
            assertTrue("no overshoot at $at", rate <= peak + 0.0001f)
        }
    }

    @Test
    fun aCurveThatStopsAboveZeroFadesOutInsteadOfDroppingOff() {
        val dose = InsulinActivityStack.Dose(0L, 10f, glargineU100, 0)
        val before = InsulinActivityStack.rate(dose, DAY - MINUTE)
        val after = InsulinActivityStack.rate(dose, DAY + MINUTE)
        assertEquals(before, after, before * 0.02f)
        // The fade-out is reported apart, as an estimate.
        assertEquals(0f, InsulinActivityStack.approximateRate(dose, DAY - MINUTE), 0f)
        assertEquals(after, InsulinActivityStack.approximateRate(dose, DAY + MINUTE), 0f)
        // It is over after a quarter of the curve again.
        assertEquals(0f, InsulinActivityStack.rate(dose, DAY + 6 * HOUR + MINUTE), 0f)
    }

    @Test
    fun dailyGlargineHasNoNotchAtEachInjection() {
        // The screenshot regression: each day's dose dropped the last one's 72 % to zero.
        val doses = (0 until 4).map { InsulinActivityStack.Dose(it * DAY, 10f, glargineU100, 0) }
        assertSmooth(doses, 2 * DAY + 12 * HOUR, 3 * DAY + 12 * HOUR)
    }

    @Test
    fun dailyTresibaIsSmoothAndAveragesItsDose() {
        val doses = (0 until 8).map { InsulinActivityStack.Dose(it * DAY, 24f, degludec, 0, steadyState = true) }
        assertSmooth(doses, 5 * DAY + 12 * HOUR, 6 * DAY + 12 * HOUR)
        // At steady state the regimen acts at its dose a day: 24 U is 1 U/h on average.
        val day = (6 * DAY until 7 * DAY step MINUTE).map { rateAt(doses, it).toDouble() }
        assertEquals(1.0, day.average(), 0.02)
    }

    @Test
    fun aMissedTresibaDoseRunsOutAlongTheMeasuredTail() {
        val one = InsulinActivityStack.Dose(0L, 20f, degludec, 0, steadyState = true)
        assertTrue(InsulinActivityStack.rate(one, 30 * HOUR) > 0f)
        assertTrue(InsulinActivityStack.rate(one, 30 * HOUR) < InsulinActivityStack.rate(one, 12 * HOUR))
    }

    @Test
    fun aDoseSplitIntoTwoInjectionsCountsBoth() {
        val whole = InsulinActivityStack.Dose(0L, 60f, degludec, 0, steadyState = true)
        val split = listOf(
            InsulinActivityStack.Dose(0L, 40f, degludec, 0, steadyState = true),
            InsulinActivityStack.Dose(0L, 20f, degludec, 0, steadyState = true)
        )
        assertEquals(InsulinActivityStack.rate(whole, 6 * HOUR), rateAt(split, 6 * HOUR), 0.0001f)
    }

    @Test
    fun aOnceWeeklyCurveIsScaledOverTheWeekAndItsSplitDosesCount() {
        val week = curve(0 to 1f, 10080 to 1f)
        val dose = InsulinActivityStack.Dose(0L, 168f, week, 0, steadyState = true)
        assertEquals(1f, InsulinActivityStack.rate(dose, 50 * HOUR), 0.0001f)
        val split = listOf(
            InsulinActivityStack.Dose(0L, 100f, week, 0, steadyState = true),
            InsulinActivityStack.Dose(0L, 68f, week, 0, steadyState = true)
        )
        assertEquals(1f, rateAt(split, 50 * HOUR), 0.0001f)
        // Last week's dose doesn't add to this week's: the curve already holds it.
        val twoWeeks = listOf(dose, InsulinActivityStack.Dose(7 * DAY, 168f, week, 0, steadyState = true))
        assertEquals(1f, rateAt(twoWeeks, 7 * DAY + 50 * HOUR), 0.0001f)
    }

    @Test
    fun overlappingDosesAdd() {
        val first = InsulinActivityStack.Dose(0L, 2f, triangle, 0)
        val second = InsulinActivityStack.Dose(HOUR / 2, 2f, triangle, 0)
        assertEquals(
            InsulinActivityStack.rate(first, HOUR) + InsulinActivityStack.rate(second, HOUR),
            rateAt(listOf(first, second), HOUR),
            0.0001f
        )
    }

    @Test
    fun layersStackOnTheOnesBelow() {
        val basal = InsulinActivityStack.Dose(0L, 1f, triangle, 0)
        val bolus = InsulinActivityStack.Dose(0L, 3f, triangle, 1)
        val tops = InsulinActivityStack.stack(listOf(basal, bolus), 2, longArrayOf(HOUR)).tops
        assertEquals(InsulinActivityStack.rate(basal, HOUR), tops[0][0], 0.0001f)
        assertEquals(4 * InsulinActivityStack.rate(basal, HOUR), tops[1][0], 0.0001f)
    }

    @Test
    fun aShortDoseBetweenCoarseSamplesStillShowsAtItsPeak() {
        // A 90-minute inhaled dose in a two-week view, sampled hours apart.
        val start = 0L
        val end = 14 * DAY
        val afrezza = InsulinActivityStack.Dose(
            5 * DAY + 7 * MINUTE,
            4f,
            curve(0 to 0f, 12 to 0.3f, 45 to 1f, 90 to 0f),
            0
        )
        val times = InsulinActivityStack.sampleTimes(listOf(afrezza), start, end, stepMillis = 3 * HOUR)
        assertEquals(start, times.first())
        assertEquals(end, times.last())
        val tops = InsulinActivityStack.stack(listOf(afrezza), 1, times).tops[0]
        assertEquals(InsulinActivityStack.rate(afrezza, afrezza.timestamp + 45 * MINUTE), tops.max(), 0.0001f)
    }

    @Test
    fun scaleRoundsUpToHalfOrWholeUnits() {
        assertEquals(0.5f, InsulinActivityStack.scaleFor(0.2f), 0f)
        assertEquals(1.5f, InsulinActivityStack.scaleFor(1.3f), 0f)
        assertEquals(4f, InsulinActivityStack.scaleFor(3.2f), 0f)
        assertEquals(1f, InsulinActivityStack.scaleFor(0f), 0f)
    }

    /** No step between neighbouring minutes bigger than 2 % of the band's height over [from, to]. */
    private fun assertSmooth(doses: List<InsulinActivityStack.Dose>, from: Long, to: Long) {
        val rates = (from..to step MINUTE).map { rateAt(doses, it) }
        val height = rates.max()
        rates.zipWithNext().forEachIndexed { index, (a, b) ->
            assertTrue("step of ${b - a} at minute $index", kotlin.math.abs(b - a) <= height * 0.02f)
        }
    }
}
