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

    private val utc = java.util.TimeZone.getTimeZone("UTC")

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
    fun aCurveIsNeverExtendedPastItsObservation() {
        // Lantus's clamp ended at 24 h while still acting; nothing is invented past it.
        val dose = InsulinActivityStack.Dose(0L, 10f, glargineU100, 0)
        assertTrue(InsulinActivityStack.rate(dose, DAY) > 0f)
        assertEquals(0f, InsulinActivityStack.rate(dose, DAY + MINUTE), 0f)
    }

    @Test
    fun whereTheDataStopsATrailEasesOutOverAFifthOfTheObservation() {
        // Lantus was observed for 24 h, so its trail runs 4.8 h.
        val dose = InsulinActivityStack.Dose(0L, 10f, glargineU100, 0)
        val stopped = InsulinActivityStack.rate(dose, DAY)
        val times = longArrayOf(DAY + MINUTE, DAY + 144 * MINUTE, DAY + 288 * MINUTE)
        val band = InsulinActivityStack.stack(listOf(dose), 1, times)
        // Not a total: the band itself ends with the data.
        assertEquals(0f, band.tops[0][0], 0f)
        assertEquals(stopped, band.trailing[0][0], stopped * 0.01f)
        assertEquals(stopped / 2f, band.trailing[0][1], stopped * 0.01f)
        assertEquals(0f, band.trailing[0][2], 0f)
        // A curve that ends at zero leaves no trail.
        val bolus = InsulinActivityStack.Dose(0L, 4f, triangle, 0)
        assertEquals(0f, InsulinActivityStack.stack(listOf(bolus), 1, longArrayOf(2 * HOUR + MINUTE)).trailing[0][0], 0f)
    }

    @Test
    fun aOnceWeeklyTrailIsCappedAndGivesWayToTheNextDose() {
        val week = curve(0 to 1f, 10080 to 1f)
        val dose = InsulinActivityStack.Dose(0L, 168f, week, 0, steadyState = true)
        assertEquals(12 * HOUR, dose.trailMillis)
        assertTrue(InsulinActivityStack.stack(listOf(dose), 1, longArrayOf(7 * DAY + HOUR)).trailing[0][0] > 0f)
        // The next week's dose already holds what this one still does.
        val next = InsulinActivityStack.Dose(7 * DAY, 168f, week, 0, steadyState = true)
        assertEquals(0f, InsulinActivityStack.stack(listOf(dose, next), 1, longArrayOf(7 * DAY + HOUR)).trailing[0][0], 0f)
    }

    @Test
    fun dailyGlargineIsDrawnWithoutANotchAtEachInjection() {
        // The screenshot regression: each day's dose cut the last one's 72 % off at a hard edge.
        // The data still stops there, but the drawn band trails off instead of notching.
        val doses = (0 until 4).map { InsulinActivityStack.Dose(it * DAY, 10f, glargineU100, 0) }
        assertSmoothlyDrawn(doses, 2 * DAY + 12 * HOUR, 3 * DAY + 12 * HOUR)
    }

    @Test
    fun dailyTresibaIsSmoothAndAveragesItsDose() {
        val doses = (0 until 8).map { InsulinActivityStack.Dose(it * DAY, 24f, degludec, 0, steadyState = true) }
        assertSmoothlyDrawn(doses, 5 * DAY + 12 * HOUR, 6 * DAY + 12 * HOUR)
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
    fun eachDoseOnItsOwnIsWhatItAddsToTheStack() {
        val doses = listOf(
            InsulinActivityStack.Dose(0L, 10f, glargineU100, 0),
            InsulinActivityStack.Dose(2 * HOUR, 3f, triangle, 1),
            InsulinActivityStack.Dose(2 * HOUR + 20 * MINUTE, 2f, triangle, 1)
        )
        val times = (0L..6 * HOUR step 5 * MINUTE).toList().toLongArray()
        val tops = InsulinActivityStack.stack(doses, 2, times).tops
        times.forEachIndexed { i, at ->
            val own = doses.sumOf { dose ->
                if (at <= InsulinActivityStack.countsUntil(dose, doses)) InsulinActivityStack.rate(dose, at).toDouble() else 0.0
            }
            assertEquals(tops[1][i], own.toFloat(), 0.0001f)
        }
    }

    @Test
    fun aOnceWeeklyDoseCountsUntilTheNext() {
        val week = curve(0 to 1f, 10080 to 1f)
        val first = InsulinActivityStack.Dose(0L, 168f, week, 0, steadyState = true)
        val next = InsulinActivityStack.Dose(6 * DAY, 168f, week, 0, steadyState = true)
        assertEquals(6 * DAY - 1, InsulinActivityStack.countsUntil(first, listOf(first, next)))
        assertEquals(next.endMillis, InsulinActivityStack.countsUntil(next, listOf(first, next)))
    }

    @Test
    fun aCurveEnteredAsRunningForYearsIsCheapToPrepare() {
        // The curve editor takes any minute; the area is worked out per point, not per minute.
        val forever = curve(0 to 0f, 60 to 1f, Int.MAX_VALUE to 0f)
        val started = System.nanoTime()
        val dose = InsulinActivityStack.Dose(0L, 4f, forever, 0)
        assertTrue(dose.areaHours > 0f)
        assertTrue(InsulinActivityStack.typicalDailyPeak(listOf(dose), utc) > 0f)
        assertTrue((System.nanoTime() - started) < 2_000_000_000L)
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
    fun scaleHasHeadroomAndRoundsUpToHalfOrWholeUnits() {
        assertEquals(0.5f, InsulinActivityStack.scaleFor(0.2f), 0f)
        assertEquals(1.5f, InsulinActivityStack.scaleFor(1.3f), 0f)
        assertEquals(4f, InsulinActivityStack.scaleFor(3.2f), 0f)
        // 1.9 U/h would touch a 2 U/h scale; with headroom it gets 3.
        assertEquals(3f, InsulinActivityStack.scaleFor(1.9f), 0f)
        assertEquals(1f, InsulinActivityStack.scaleFor(0f), 0f)
    }

    @Test
    fun aTypicalDayIsFoundAcrossAllDosesAndAnOutlierDoesntSetIt() {
        // A day of Lantus with two boluses overlapping it and each other: its peak, found among
        // the other doses' curve points and steps, is what a brute-force scan finds.
        val day = listOf(
            InsulinActivityStack.Dose(2 * HOUR, 10f, glargineU100, 0),
            InsulinActivityStack.Dose(8 * HOUR, 6f, triangle, 1),
            InsulinActivityStack.Dose(8 * HOUR + 30 * MINUTE, 4f, triangle, 1)
        )
        val times = (0L until DAY step MINUTE).toList().toLongArray()
        val bruteForce = InsulinActivityStack.stack(day, 2, times).tops[1].max()
        assertEquals(bruteForce, InsulinActivityStack.typicalDailyPeak(day, utc), bruteForce * 0.02f)
        assertEquals(0f, InsulinActivityStack.typicalDailyPeak(emptyList(), utc), 0f)
        // Three days of 2 U and one of 20: the typical day is a 2 U one.
        val days = (0 until 4).map { InsulinActivityStack.Dose(it * DAY + 8 * HOUR, if (it == 3) 20f else 2f, triangle, 0) }
        val typical = InsulinActivityStack.rate(days[0], days[0].timestamp + HOUR)
        assertEquals(typical, InsulinActivityStack.typicalDailyPeak(days, utc), typical * 0.01f)
        assertEquals(
            InsulinActivityStack.typicalDailyPeak(days, utc),
            InsulinActivityStack.typicalDailyPeak(days.reversed(), utc),
            0f
        )
    }

    @Test
    fun aTypicalDayCountsOnlyTheLatestOnceWeeklyDose() {
        val week = curve(0 to 1f, 10080 to 1f)
        val doses = (0 until 3).map { InsulinActivityStack.Dose(it * 7 * DAY, 168f, week, 0, steadyState = true) }
        assertEquals(1f, InsulinActivityStack.typicalDailyPeak(doses, utc), 0.0001f)
    }

    /**
     * No step between neighbouring minutes bigger than 2 % of the band's height over [from, to],
     * in the band's outline as drawn: the totals with the trail where data stops.
     */
    private fun assertSmoothlyDrawn(doses: List<InsulinActivityStack.Dose>, from: Long, to: Long) {
        val times = (from..to step MINUTE).toList().toLongArray()
        val band = InsulinActivityStack.stack(doses, 1, times)
        val rates = times.indices.map { band.tops[0][it] + band.trailing[0][it] }
        val height = rates.max()
        rates.zipWithNext().forEachIndexed { index, (a, b) ->
            assertTrue("step of ${b - a} at minute $index", kotlin.math.abs(b - a) <= height * 0.02f)
        }
    }
}
