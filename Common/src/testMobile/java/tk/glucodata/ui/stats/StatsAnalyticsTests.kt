package tk.glucodata.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.GlucosePoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Analytics behind the statistics screen. These are the numbers people take to an
 * appointment, so the formulas are pinned to published definitions rather than to
 * whatever the current implementation happens to return.
 */
class StatsAnalyticsTests {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val targets = StatsTargets()

    private fun at(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    private fun series(
        start: LocalDateTime,
        cadenceMinutes: Long,
        values: List<Float>
    ): List<GlucosePoint> = values.mapIndexed { index, value ->
        GlucosePoint(
            value = value,
            time = "",
            timestamp = at(start.plusMinutes(index * cadenceMinutes))
        )
    }

    // ------------------------------------------------------------------- GRI

    @Test
    fun griFollowsThePublishedWeighting() {
        // 10% very low, 10% low, 10% high, 10% very high, 60% in range.
        val values = buildList {
            repeat(10) { add(40f) }   // < 54
            repeat(10) { add(60f) }   // 54..69
            repeat(60) { add(120f) }  // in range
            repeat(10) { add(200f) }  // 181..250
            repeat(10) { add(300f) }  // > 250
        }
        val gri = StatsAnalytics.glycemiaRiskIndex(values, targets)

        // 3.0*10 + 2.4*10 for the lows, 1.6*10 + 0.8*10 for the highs.
        assertEquals(54f, gri.hypoComponent, 0.01f)
        assertEquals(24f, gri.hyperComponent, 0.01f)
        assertEquals(78f, gri.value, 0.01f)
        assertEquals(GriZone.D, gri.zone)
    }

    @Test
    fun griIsZeroWhenEverythingSitsInRange() {
        val gri = StatsAnalytics.glycemiaRiskIndex(List(50) { 110f }, targets)
        assertEquals(0f, gri.value, 0.0001f)
        assertEquals(GriZone.A, gri.zone)
    }

    @Test
    fun griNeverExceedsOneHundred() {
        val gri = StatsAnalytics.glycemiaRiskIndex(List(50) { 40f }, targets)
        assertEquals(100f, gri.value, 0.0001f)
        assertEquals(GriZone.E, gri.zone)
    }

    @Test
    fun griZonesSplitAtEveryTwenty() {
        assertEquals(GriZone.A, GriZone.of(19.9f))
        assertEquals(GriZone.B, GriZone.of(20f))
        assertEquals(GriZone.C, GriZone.of(40f))
        assertEquals(GriZone.D, GriZone.of(60f))
        assertEquals(GriZone.E, GriZone.of(80f))
    }

    // ------------------------------------------------------------ risk indices

    @Test
    fun bothRiskIndicesVanishAtTheSymmetryPoint() {
        // Kovatchev's transform crosses zero at ~112.6 mg/dL.
        val risk = StatsAnalytics.riskIndices(List(20) { 112.6f })
        assertEquals(0f, risk.lbgi, 0.05f)
        assertEquals(0f, risk.hbgi, 0.05f)
    }

    @Test
    fun lowsFeedLbgiAndHighsFeedHbgi() {
        val lowRisk = StatsAnalytics.riskIndices(List(20) { 50f })
        assertTrue(lowRisk.lbgi > 5f)
        assertEquals(0f, lowRisk.hbgi, 0.0001f)

        val highRisk = StatsAnalytics.riskIndices(List(20) { 300f })
        assertTrue(highRisk.hbgi > 5f)
        assertEquals(0f, highRisk.lbgi, 0.0001f)
    }

    @Test
    fun depthCountsForMoreThanTheNumberOfLows() {
        // Same amount of time below range; the deeper excursion has to score higher.
        val deep = StatsAnalytics.riskIndices(listOf(40f) + List(9) { 110f })
        val shallow = StatsAnalytics.riskIndices(listOf(66f) + List(9) { 110f })
        assertTrue(deep.lbgi > shallow.lbgi * 3f)
    }

    // ------------------------------------------------------------ tight range

    @Test
    fun tightRangeIsDerivedFromTheUsersOwnTarget() {
        // Default target 70-180 gives a tight band of 70-125.
        val (low, high) = StatsAnalytics.tightRangeBounds(targets)
        assertEquals(70f, low, 0.01f)
        assertEquals(125f, high, 0.01f)

        val values = listOf(69f, 70f, 100f, 125f, 126f, 200f)
        assertEquals(50f, StatsAnalytics.tightRangePercent(values, targets), 0.01f)
    }

    @Test
    fun tightRangeNeverExceedsThePublishedUpperBound() {
        // A very wide target must not push the tight band above 140 mg/dL.
        val wide = StatsTargets(lowMgDl = 70f, highMgDl = 300f)
        val (low, high) = StatsAnalytics.tightRangeBounds(wide)
        assertEquals(70f, low, 0.01f)
        assertEquals(140f, high, 0.01f)
    }

    @Test
    fun griUsesTheUsersBandsNotTheFixedClinicalOnes() {
        // Target 65-162 mg/dL, the mmol/L user whose 3.6-3.9 readings the fixed
        // 70 mg/dL threshold counted as lows.
        val personal = StatsTargets(lowMgDl = 65f, highMgDl = 162f, veryLowMgDl = 58f)
        val values = List(80) { 120f } + List(20) { 68f }
        val personalGri = StatsAnalytics.glycemiaRiskIndex(values, personal)
        val defaultGri = StatsAnalytics.glycemiaRiskIndex(values, StatsTargets())
        assertEquals(0f, personalGri.value, 0.01f)
        assertTrue(defaultGri.value > 40f)
    }

    // -------------------------------------------------------------------- GMI

    @Test
    fun gmiBandsSplitOnTheTherapyTargetTheTilePrints() {
        // 7.0 is the target itself, so it still counts as meeting it.
        assertEquals(GmiBand.AT_TARGET, GmiBand.of(6.6f))
        assertEquals(GmiBand.AT_TARGET, GmiBand.of(GmiBand.TARGET_PERCENT))
        assertEquals(GmiBand.ABOVE_TARGET, GmiBand.of(7.1f))
        assertEquals(GmiBand.WELL_ABOVE_TARGET, GmiBand.of(GmiBand.WELL_ABOVE_TARGET_PERCENT))
        assertEquals(GmiBand.WELL_ABOVE_TARGET, GmiBand.of(9f))
    }

    @Test
    fun gmiDoesNotJudgeTherapyByTheDiagnosticThresholds() {
        // 5.7 and 6.5 screen an undiagnosed population; on this screen they are not a
        // scale at all, so nothing below the target may land in a warning band.
        listOf(5.6f, 5.7f, 6.4f, 6.5f, 6.9f).forEach { gmi ->
            assertEquals("GMI $gmi is within target", GmiBand.AT_TARGET, GmiBand.of(gmi))
        }
    }

    // ---------------------------------------------------------------- cadence

    @Test
    fun cadenceIsTheMedianGapBetweenReadings() {
        val points = series(LocalDateTime.of(2026, 3, 2, 8, 0), 1, List(20) { 100f })
        assertEquals(60_000L, StatsAnalytics.estimateCadenceMillis(points))

        val fivePoints = series(LocalDateTime.of(2026, 3, 2, 8, 0), 5, List(20) { 100f })
        assertEquals(300_000L, StatsAnalytics.estimateCadenceMillis(fivePoints))
    }

    @Test
    fun cadenceIgnoresLongSensorGaps() {
        val start = LocalDateTime.of(2026, 3, 2, 8, 0)
        val points = listOf(
            GlucosePoint(100f, "", at(start)),
            GlucosePoint(100f, "", at(start.plusMinutes(5))),
            GlucosePoint(100f, "", at(start.plusHours(6))),
            GlucosePoint(100f, "", at(start.plusHours(6).plusMinutes(5))),
            GlucosePoint(100f, "", at(start.plusHours(6).plusMinutes(10)))
        )
        assertEquals(300_000L, StatsAnalytics.estimateCadenceMillis(points))
    }

    // --------------------------------------------------------------- coverage

    @Test
    fun coverageComparesReadingsAgainstTheWindow() {
        val start = LocalDateTime.of(2026, 3, 2, 8, 0)
        // Half of a two-hour window, sampled every five minutes.
        val points = series(start, 5, List(12) { 100f })
        val range = StatsDateRange(
            startMillis = at(start),
            endMillis = at(start.plusHours(2))
        )
        val coverage = StatsAnalytics.sensorCoverage(points, range)
        assertEquals(50f, coverage.percent, 1f)
        assertEquals(12, coverage.readingCount)
        assertEquals(1, coverage.daysWithData)
    }

    @Test
    fun coverageNeverReportsMoreThanAFullWindow() {
        val start = LocalDateTime.of(2026, 3, 2, 8, 0)
        val points = series(start, 1, List(200) { 100f })
        val range = StatsDateRange(at(start), at(start.plusHours(1)))
        assertTrue(StatsAnalytics.sensorCoverage(points, range).percent <= 100f)
    }

    // --------------------------------------------------------------- episodes

    @Test
    fun aShortDipIsNotAnEpisode() {
        val start = LocalDateTime.of(2026, 3, 2, 8, 0)
        // Two readings below target: 5 min of readings plus one cadence = 10 min.
        val points = series(start, 5, listOf(120f, 120f, 60f, 60f, 120f, 120f, 120f))
        assertTrue(StatsAnalytics.detectEpisodes(points, targets).isEmpty())
    }

    @Test
    fun fifteenMinutesBelowTargetIsAnEpisode() {
        val start = LocalDateTime.of(2026, 3, 2, 8, 0)
        val points = series(start, 5, listOf(120f, 60f, 58f, 62f, 120f, 120f, 120f))
        val episodes = StatsAnalytics.detectEpisodes(points, targets)
        assertEquals(1, episodes.size)
        assertEquals(EpisodeKind.LOW, episodes.first().kind)
        assertEquals(15, episodes.first().durationMinutes)
        assertEquals(58f, episodes.first().extremeMgDl, 0.01f)
        assertFalse(episodes.first().severe)
    }

    @Test
    fun droppingUnderTheVeryLowLineMarksTheEpisodeSevere() {
        val start = LocalDateTime.of(2026, 3, 2, 8, 0)
        val points = series(start, 5, listOf(120f, 60f, 48f, 62f, 120f))
        val episode = StatsAnalytics.detectEpisodes(points, targets).single()
        assertTrue(episode.severe)
        assertEquals(48f, episode.extremeMgDl, 0.01f)
    }

    @Test
    fun aBriefReturnToRangeDoesNotSplitOneEpisodeInTwo() {
        val start = LocalDateTime.of(2026, 3, 2, 8, 0)
        val points = series(
            start,
            5,
            listOf(120f, 60f, 58f, 75f, 62f, 60f, 61f, 120f, 120f)
        )
        val episodes = StatsAnalytics.detectEpisodes(points, targets)
        assertEquals(1, episodes.size)
        assertEquals(30, episodes.first().durationMinutes)
    }

    @Test
    fun aReadingGapEndsTheEpisodeInsteadOfSpanningIt() {
        val start = LocalDateTime.of(2026, 3, 2, 8, 0)
        val points = listOf(
            GlucosePoint(60f, "", at(start)),
            GlucosePoint(58f, "", at(start.plusMinutes(5))),
            GlucosePoint(59f, "", at(start.plusMinutes(10))),
            // Sensor silent for two hours — nothing can be claimed about that stretch.
            GlucosePoint(61f, "", at(start.plusHours(2))),
            GlucosePoint(60f, "", at(start.plusHours(2).plusMinutes(5))),
            GlucosePoint(62f, "", at(start.plusHours(2).plusMinutes(10))),
            GlucosePoint(120f, "", at(start.plusHours(2).plusMinutes(15)))
        )
        val episodes = StatsAnalytics.detectEpisodes(points, targets)
        assertEquals(2, episodes.size)
        assertTrue(episodes.all { it.durationMinutes == 15 })
    }

    @Test
    fun highsAreDetectedWithTheSameRules() {
        val start = LocalDateTime.of(2026, 3, 2, 8, 0)
        val points = series(start, 5, listOf(120f, 200f, 260f, 220f, 120f))
        val episode = StatsAnalytics.detectEpisodes(points, targets).single()
        assertEquals(EpisodeKind.HIGH, episode.kind)
        assertTrue(episode.severe)
        assertEquals(260f, episode.extremeMgDl, 0.01f)
    }

    @Test
    fun episodeSummaryReportsSpreadAndBusiestBlock() {
        val day = LocalDate.of(2026, 3, 2)
        val episodes = listOf(
            lowEpisode(LocalDateTime.of(day, java.time.LocalTime.of(1, 0)), 20),
            lowEpisode(LocalDateTime.of(day, java.time.LocalTime.of(2, 30)), 40),
            lowEpisode(LocalDateTime.of(day, java.time.LocalTime.of(14, 0)), 60)
        )
        val summary = StatsAnalytics.summarizeEpisodes(episodes, EpisodeKind.LOW, windowDays = 3f)
        assertEquals(3, summary.count)
        assertEquals(40, summary.medianDurationMinutes)
        assertEquals(60, summary.longestDurationMinutes)
        assertEquals(120, summary.totalMinutes)
        assertEquals(1f, summary.perDay, 0.001f)
        assertEquals(0, summary.busiestBlockStartHour)
        assertEquals(2, summary.busiestBlockCount)
    }

    private fun lowEpisode(start: LocalDateTime, minutes: Int) = GlucoseEpisode(
        kind = EpisodeKind.LOW,
        startMillis = at(start),
        endMillis = at(start.plusMinutes(minutes.toLong())),
        durationMinutes = minutes,
        extremeMgDl = 60f,
        severe = false
    )

    // ------------------------------------------------------------- day splits

    @Test
    fun dayPartsBucketBySixHourBlocks() {
        val day = LocalDate.of(2026, 3, 2)
        val points = listOf(
            GlucosePoint(80f, "", at(LocalDateTime.of(day, java.time.LocalTime.of(2, 0)))),
            GlucosePoint(90f, "", at(LocalDateTime.of(day, java.time.LocalTime.of(5, 0)))),
            GlucosePoint(200f, "", at(LocalDateTime.of(day, java.time.LocalTime.of(9, 0)))),
            GlucosePoint(150f, "", at(LocalDateTime.of(day, java.time.LocalTime.of(13, 0)))),
            GlucosePoint(130f, "", at(LocalDateTime.of(day, java.time.LocalTime.of(21, 0))))
        )
        val parts = StatsAnalytics.dayPartStats(points, targets)
        assertEquals(4, parts.size)
        assertEquals(85f, parts[0].averageMgDl, 0.01f)
        assertEquals(2, parts[0].readingCount)
        assertEquals(200f, parts[1].averageMgDl, 0.01f)
        assertEquals(150f, parts[2].averageMgDl, 0.01f)
        assertEquals(130f, parts[3].averageMgDl, 0.01f)
    }

    @Test
    fun weekdayStatsCountDistinctDates() {
        // 2 March 2026 is a Monday.
        val monday = LocalDate.of(2026, 3, 2)
        val points = listOf(
            GlucosePoint(100f, "", at(LocalDateTime.of(monday, java.time.LocalTime.of(8, 0)))),
            GlucosePoint(120f, "", at(LocalDateTime.of(monday, java.time.LocalTime.of(9, 0)))),
            GlucosePoint(160f, "", at(LocalDateTime.of(monday.plusWeeks(1), java.time.LocalTime.of(8, 0))))
        )
        val monday1 = StatsAnalytics.weekdayStats(points, targets).first()
        assertEquals(java.time.DayOfWeek.MONDAY, monday1.dayOfWeek)
        assertEquals(3, monday1.readingCount)
        assertEquals(2, monday1.dayCount)
    }

    @Test
    fun dayBreakdownsSplitOnLocalDates() {
        val day = LocalDate.of(2026, 3, 2)
        val points = listOf(
            GlucosePoint(90f, "", at(LocalDateTime.of(day, java.time.LocalTime.of(23, 0)))),
            GlucosePoint(200f, "", at(LocalDateTime.of(day.plusDays(1), java.time.LocalTime.of(1, 0)))),
            GlucosePoint(100f, "", at(LocalDateTime.of(day.plusDays(1), java.time.LocalTime.of(3, 0))))
        )
        val days = StatsAnalytics.dayBreakdowns(points, targets)
        assertEquals(2, days.size)
        assertEquals(day, days[0].date)
        assertEquals(1, days[0].readingCount)
        assertEquals(150f, days[1].averageMgDl, 0.01f)
        assertEquals(100f, days[1].minMgDl, 0.01f)
        assertEquals(200f, days[1].maxMgDl, 0.01f)
    }

    // ------------------------------------------------------------- comparison

    @Test
    fun comparisonNeedsBothPeriodsToHoldReadings() {
        val current = StatsAnalytics.periodScalars(List(20) { 120f }, targets)
        assertNull(StatsAnalytics.compare(current, PeriodScalars()))
        assertNull(StatsAnalytics.compare(PeriodScalars(), current))
    }

    @Test
    fun comparisonReportsSignedDeltas() {
        val previous = StatsAnalytics.periodScalars(List(10) { 250f } + List(10) { 120f }, targets)
        val current = StatsAnalytics.periodScalars(List(20) { 120f }, targets)
        val comparison = StatsAnalytics.compare(current, previous)
        assertNotNull(comparison)
        assertEquals(50f, comparison!!.inRangeDelta, 0.01f)
        assertTrue(comparison.averageDelta < 0f)
        assertTrue(comparison.griDelta < 0f)
    }

    // --------------------------------------------------------------- findings

    @Test
    fun sparseCoverageIsCalledOutBeforeAnythingElse() {
        val findings = StatsAnalytics.findings(
            findingInput(coverage = SensorCoverage(percent = 42f, readingCount = 100))
        )
        assertEquals(FindingKind.SPARSE_COVERAGE, findings.first().kind)
    }

    @Test
    fun clusteredLowsAreReportedWithTheirBlock() {
        val findings = StatsAnalytics.findings(
            findingInput(
                lows = EpisodeSummary(
                    kind = EpisodeKind.LOW,
                    count = 6,
                    busiestBlockStartHour = 0,
                    busiestBlockCount = 4
                )
            )
        )
        val cluster = findings.single { it.kind == FindingKind.LOWS_CLUSTER }
        assertEquals(0, cluster.hour)
        assertEquals(4f, cluster.primary, 0.01f)
        assertEquals(6f, cluster.secondary, 0.01f)
    }

    @Test
    fun scatteredLowsAreNotCalledACluster() {
        val findings = StatsAnalytics.findings(
            findingInput(
                lows = EpisodeSummary(
                    kind = EpisodeKind.LOW,
                    count = 8,
                    busiestBlockStartHour = 12,
                    busiestBlockCount = 2
                )
            )
        )
        assertTrue(findings.none { it.kind == FindingKind.LOWS_CLUSTER })
    }

    @Test
    fun anOutlyingDayPartIsNamedWithItsDirection() {
        val findings = StatsAnalytics.findings(
            findingInput(
                averageMgDl = 140f,
                dayParts = listOf(
                    DayPartStats(DayPart.NIGHT, 135f, TimeInRangeBreakdown(), 100),
                    DayPartStats(DayPart.MORNING, 185f, TimeInRangeBreakdown(), 100),
                    DayPartStats(DayPart.AFTERNOON, 138f, TimeInRangeBreakdown(), 100),
                    DayPartStats(DayPart.EVENING, 142f, TimeInRangeBreakdown(), 100)
                )
            )
        )
        val outlier = findings.single { it.kind == FindingKind.DAYPART_HIGH }
        assertEquals(DayPart.MORNING, outlier.dayPart)
        assertEquals(45f, outlier.secondary, 0.01f)
    }

    @Test
    fun aQuietWindowStillSaysSomething() {
        val findings = StatsAnalytics.findings(
            findingInput(
                tir = TimeInRangeBreakdown(inRangePercent = 82f),
                cvPercent = 28f,
                coverage = SensorCoverage(percent = 96f, readingCount = 4000)
            )
        )
        assertEquals(1, findings.size)
        assertEquals(FindingKind.ON_TARGET, findings.first().kind)
    }

    private fun findingInput(
        tir: TimeInRangeBreakdown = TimeInRangeBreakdown(inRangePercent = 65f),
        averageMgDl: Float = 150f,
        cvPercent: Float = 30f,
        coverage: SensorCoverage = SensorCoverage(percent = 95f, readingCount = 2000),
        lows: EpisodeSummary = EpisodeSummary(EpisodeKind.LOW),
        highs: EpisodeSummary = EpisodeSummary(EpisodeKind.HIGH),
        dayParts: List<DayPartStats> = emptyList(),
        days: List<DayBreakdown> = emptyList(),
        comparison: StatsComparison? = null
    ) = FindingInput(
        tir = tir,
        averageMgDl = averageMgDl,
        cvPercent = cvPercent,
        coverage = coverage,
        lowEpisodes = lows,
        highEpisodes = highs,
        dayParts = dayParts,
        days = days,
        comparison = comparison
    )

    // ------------------------------------------------- less common measures

    @Test
    fun maeIgnoresARigidlyFlatSeries() {
        val points = series(LocalDateTime.of(2026, 3, 2, 8, 0), 5, List(40) { 110f })
        assertEquals(0f, StatsAnalytics.mage(points), 0.01f)
    }

    @Test
    fun mageMeasuresTheSizeOfTheSwings() {
        // Four clean 60 mg/dL swings, well past one standard deviation.
        val values = (0 until 40).map { index -> if ((index / 5) % 2 == 0) 90f else 150f }
        val points = series(LocalDateTime.of(2026, 3, 2, 8, 0), 5, values)
        assertTrue(StatsAnalytics.mage(points) > 40f)
    }

    @Test
    fun moddIsZeroWhenEveryDayRepeatsItself() {
        val start = LocalDateTime.of(2026, 3, 2, 0, 0)
        val oneDay = (0 until 288).map { 100f + (it % 24) }
        val points = series(start, 5, oneDay + oneDay)
        assertEquals(0f, StatsAnalytics.modd(points), 0.01f)
    }

    @Test
    fun moddGrowsWhenTheSecondDayDiffers() {
        val start = LocalDateTime.of(2026, 3, 2, 0, 0)
        val dayOne = List(288) { 100f }
        val dayTwo = List(288) { 130f }
        val points = series(start, 5, dayOne + dayTwo)
        assertEquals(30f, StatsAnalytics.modd(points), 0.5f)
    }

    @Test
    fun dawnRiseMeasuresTheClimbFromTheOvernightLow() {
        val start = LocalDateTime.of(2026, 3, 2, 0, 0)
        // Flat at 90 until 04:00, then climbing to 150 by 08:00.
        val values = (0 until 108).map { index ->
            val hour = index / 12
            if (hour < 4) 90f else 90f + (index - 48) * 1.25f
        }
        val points = series(start, 5, values)
        assertTrue(StatsAnalytics.dawnRise(points) > 40f)
    }

    @Test
    fun dawnRiseIsZeroWithoutAnOvernightWindow() {
        val points = series(LocalDateTime.of(2026, 3, 2, 12, 0), 5, List(60) { 120f })
        assertEquals(0f, StatsAnalytics.dawnRise(points), 0.01f)
    }

    @Test
    fun theStreakCountsOnlyConsecutiveOnTargetDays() {
        val start = LocalDate.of(2026, 3, 2)
        fun day(offset: Long, inRange: Float) = DayBreakdown(
            date = start.plusDays(offset),
            averageMgDl = 120f,
            minMgDl = 80f,
            maxMgDl = 180f,
            tir = TimeInRangeBreakdown(inRangePercent = inRange),
            readingCount = 288,
            coveragePercent = 100f
        )
        val days = listOf(
            day(0, 80f), day(1, 75f), day(2, 40f), day(3, 90f), day(4, 90f), day(5, 90f)
        )
        assertEquals(3, StatsAnalytics.bestInRangeStreak(days))
    }

    @Test
    fun aGapInTheDatesBreaksTheStreak() {
        val start = LocalDate.of(2026, 3, 2)
        fun day(offset: Long) = DayBreakdown(
            date = start.plusDays(offset),
            averageMgDl = 120f,
            minMgDl = 80f,
            maxMgDl = 180f,
            tir = TimeInRangeBreakdown(inRangePercent = 90f),
            readingCount = 288,
            coveragePercent = 100f
        )
        assertEquals(2, StatsAnalytics.bestInRangeStreak(listOf(day(0), day(1), day(5), day(9))))
    }
}
