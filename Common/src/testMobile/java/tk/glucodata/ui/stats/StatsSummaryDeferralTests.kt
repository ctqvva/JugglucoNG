package tk.glucodata.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The summary's expensive half must stay behind [StatsSummaryParts].
 *
 * This is the contract that keeps changing the range quick: adding a metric back as an
 * eager constructor parameter would compile, would look harmless, and would put a full
 * pass over twenty thousand readings back on the path between tapping 7D and seeing it.
 */
class StatsSummaryDeferralTests {

    /** Records what was read, and would blow up if anything read it during construction. */
    private class CountingParts : StatsSummaryParts {
        val reads = mutableListOf<String>()

        private fun <T> record(name: String, value: T): T {
            reads += name
            return value
        }

        override val gvi get() = record("gvi", GviScore())
        override val psg get() = record("psg", PsgScore())
        override val mageMgDl get() = record("mage", 1f)
        override val moddMgDl get() = record("modd", 2f)
        override val dawnRiseMgDl get() = record("dawn", 3f)
        override val bestStreakDays get() = record("streak", 4)
        override val gri get() = record("gri", GlycemiaRiskIndex())
        override val risk get() = record("risk", RiskIndices())
        override val episodes get() = record("episodes", emptyList<GlucoseEpisode>())
        override val lowEpisodes get() = record("lowEpisodes", EpisodeSummary(EpisodeKind.LOW))
        override val highEpisodes get() = record("highEpisodes", EpisodeSummary(EpisodeKind.HIGH))
        override val dayParts get() = record("dayParts", emptyList<DayPartStats>())
        override val weekdays get() = record("weekdays", emptyList<WeekdayStats>())
        override val days get() = record("days", emptyList<DayBreakdown>())
        override val comparison get() = record("comparison", null)
        override val agpByHour get() = record("agp", emptyList<AgpHourBin>())
        override val hourlyStats get() = record("hourly", emptyList<HourlyGlucoseStats>())
        override val dailyStats get() = record("daily", emptyList<DailyStats>())
        override val insights get() = record("insights", emptyList<StatsInsight>())
    }

    @Test
    fun buildingASummaryReadsNothingExpensive() {
        val parts = CountingParts()
        val summary = StatsSummary(readingCount = 20136, avgMgDl = 90f, parts = parts)

        // Scalars are free.
        assertEquals(20136, summary.readingCount)
        assertEquals(90f, summary.avgMgDl, 0f)
        assertTrue("nothing deferred should have been touched, got ${parts.reads}", parts.reads.isEmpty())
    }

    @Test
    fun eachDeferredMemberIsOnlyReachedWhenAskedFor() {
        val parts = CountingParts()
        val summary = StatsSummary(parts = parts)

        summary.agpByHour
        assertEquals(listOf("agp"), parts.reads)

        summary.days
        assertEquals(listOf("agp", "days"), parts.reads)
    }

    @Test
    fun anEmptySummaryStillAnswersEveryQuestion() {
        // The default is what the screen renders before any history has loaded, so every
        // member has to be safe to read without a parts implementation behind it.
        val summary = StatsSummary()
        assertEquals(StatsSummaryParts.Empty, summary.parts)
        assertTrue(summary.episodes.isEmpty())
        assertTrue(summary.days.isEmpty())
        assertTrue(summary.agpByHour.isEmpty())
        assertTrue(summary.insights.isEmpty())
        assertEquals(0f, summary.mageMgDl, 0f)
        assertEquals(0, summary.bestStreakDays)
        assertEquals(null, summary.comparison)
    }
}
