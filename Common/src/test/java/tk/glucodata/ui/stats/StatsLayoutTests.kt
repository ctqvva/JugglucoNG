package tk.glucodata.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.util.AdaptiveLayoutDensity
import tk.glucodata.ui.util.AdaptiveWindowWidthClass

/**
 * The pure parts of the arrange-mode layout: reordering and what ends up visible.
 * Persistence itself is SharedPreferences and is left to the device.
 */
class StatsLayoutTests {

    @Test
    fun movingAnItemForwardsShiftsEverythingBetweenItBack() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), order.moved(0, 2))
    }

    @Test
    fun movingAnItemBackwardsShiftsEverythingBetweenItForward() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "d", "b", "c"), order.moved(3, 1))
    }

    @Test
    fun movingOutsideTheListLeavesItAlone() {
        val order = listOf("a", "b")
        assertEquals(order, order.moved(0, 5))
        assertEquals(order, order.moved(-1, 1))
        assertEquals(order, order.moved(1, 1))
    }

    @Test
    fun hiddenSectionsDropOutOfTheRenderedOrder() {
        val state = StatsLayoutState(hiddenCards = setOf(StatsCard.RISK_INDEX, StatsCard.INSIGHTS))
        assertTrue(StatsCard.RISK_INDEX !in state.visibleCards)
        assertTrue(StatsCard.INSIGHTS !in state.visibleCards)
        assertEquals(StatsCard.entries.size - 2, state.visibleCards.size)
    }

    @Test
    fun theDefaultGridFillsWholeRowsWithNothingLeftOver() {
        val visible = StatsLayoutState().visibleMetrics
        assertEquals(10, visible.size)
        assertEquals(StatsMetric.AVERAGE, visible.first())
        val rows = packMetricRows(visible, emptySet())
        assertEquals(5, rows.size)
        assertTrue(rows.all { it.second != null })
    }

    @Test
    fun metricsWithAHomeElsewhereAreOffByDefault() {
        val hidden = StatsLayoutState().hiddenMetrics
        // Each of these is already shown by another part of the screen.
        assertTrue(StatsMetric.TIME_IN_RANGE in hidden)
        assertTrue(StatsMetric.LOW_EPISODES in hidden)
        assertTrue(StatsMetric.COVERAGE in hidden)
        assertTrue(StatsMetric.GRI in hidden)
    }

    @Test
    fun theDashboardStartsWithLevelEstimateSpreadAndTimeInRange() {
        val pinned = StatsLayoutState().dashboardMetrics
        assertEquals(
            listOf(StatsMetric.TIME_IN_RANGE, StatsMetric.AVERAGE, StatsMetric.GMI, StatsMetric.CV),
            pinned
        )
        // The estimate sits beside the mean it is fitted from, and never at the end:
        // the last chip is the one a narrow row drops.
        assertEquals(pinned.indexOf(StatsMetric.AVERAGE) + 1, pinned.indexOf(StatsMetric.GMI))
    }

    @Test
    fun anUntouchedOldDefaultFollowsTheNewDefaultOnce() {
        val old = StatsMetric.LEGACY_PINNED_DEFAULT
        assertEquals(StatsMetric.PINNED_BY_DEFAULT, StatsLayoutStore.migratePinned(old, storedVersion = 1))
        // Once written at the current version the same three are a choice and stay.
        assertEquals(old, StatsLayoutStore.migratePinned(old, storedVersion = 2))
    }

    @Test
    fun aChosenPinnedListSurvivesTheDefaultChange() {
        val chosen = listOf(StatsMetric.AVERAGE, StatsMetric.CV)
        assertEquals(chosen, StatsLayoutStore.migratePinned(chosen, storedVersion = 1))
        assertEquals(emptyList<StatsMetric>(), StatsLayoutStore.migratePinned(emptyList(), storedVersion = 1))
    }

    @Test
    fun theFourthChipOnlyShowsWhenItFits() {
        assertEquals(4, pinnedStripShownCount(4) { true })
        assertEquals(3, pinnedStripShownCount(4) { it <= 3 })
    }

    @Test
    fun theRowNeverHidesMoreThanTheFourth() {
        // Below three the strip scales instead, so a row nothing fits in still shows three.
        assertEquals(3, pinnedStripShownCount(4) { false })
        assertEquals(3, pinnedStripShownCount(3) { false })
        assertEquals(2, pinnedStripShownCount(2) { false })
    }

    @Test
    fun chipWidthIsMeasuredAgainstTheWidestValueOfItsShape() {
        assertEquals("00.0%", widestValueLike("99%"))
        assertEquals("00.0%", widestValueLike("100%"))
        assertEquals("00.0", widestValueLike("5.9"))
        assertEquals("00.0", widestValueLike("180"))
    }

    @Test
    fun fourRowsShowBeforeTheDisclosure() {
        val rows = packMetricRows(StatsLayoutState().visibleMetrics, emptySet())
        val head = rows.take(StatsMetric.DEFAULT_VISIBLE_ROWS)
            .flatMap { listOfNotNull(it.first, it.second) }
        assertEquals(8, head.size)
        assertEquals(StatsMetric.AVERAGE, head.first())
        // One row left to fold away — a disclosure that reveals nothing is worse than none.
        assertTrue(rows.size > StatsMetric.DEFAULT_VISIBLE_ROWS)
    }

    @Test
    fun relatedMetricsSitOnTheSameRowByDefault() {
        // Splitting a pair across rows is what made the grid look arbitrary.
        val rows = packMetricRows(StatsLayoutState().visibleMetrics, emptySet())
        assertTrue(rows.contains(StatsMetric.AVERAGE to StatsMetric.GMI))
        assertTrue(rows.contains(StatsMetric.MEDIAN to StatsMetric.IQR))
        assertTrue(rows.contains(StatsMetric.STD_DEV to StatsMetric.CV))
        assertTrue(rows.contains(StatsMetric.GVI to StatsMetric.PSG))
    }

    @Test
    fun patternsComeBeforeEpisodes() {
        val order = StatsCard.DEFAULT_ORDER
        assertTrue(order.indexOf(StatsCard.PATTERNS) < order.indexOf(StatsCard.EPISODES))
    }

    @Test
    fun aTrailingMetricWidensInsteadOfLeavingAHole() {
        val rows = packMetricRows(listOf(StatsMetric.AVERAGE, StatsMetric.GMI, StatsMetric.CV), emptySet())
        assertEquals(2, rows.size)
        assertEquals(StatsMetric.CV to null, rows.last())
    }

    @Test
    fun aWideMetricTakesItsOwnRowAndTheRestRepack() {
        val rows = packMetricRows(
            listOf(StatsMetric.AVERAGE, StatsMetric.GMI, StatsMetric.CV, StatsMetric.MEDIAN),
            setOf(StatsMetric.GMI)
        )
        assertEquals(StatsMetric.AVERAGE to null, rows[0])
        assertEquals(StatsMetric.GMI to null, rows[1])
        assertEquals(StatsMetric.CV to StatsMetric.MEDIAN, rows[2])
    }

    @Test
    fun theRiskIndexIsNotSecondFromTheTopAnyMore() {
        // It summarises the bands above it rather than leading with a verdict.
        val order = StatsCard.DEFAULT_ORDER
        assertTrue(order.indexOf(StatsCard.RISK_INDEX) > order.indexOf(StatsCard.PATTERNS))
        assertEquals(StatsCard.OVERVIEW, order.first())
    }

    @Test
    fun theDashboardStartsFullAndTheRowShowsAtLeastThree() {
        assertEquals(StatsLayoutStore.MAX_DASHBOARD_METRICS, StatsLayoutState().dashboardMetrics.size)
        assertEquals(4, StatsLayoutStore.MAX_DASHBOARD_METRICS)
        assertEquals(3, StatsLayoutStore.MIN_DASHBOARD_METRICS_SHOWN)
    }

    @Test
    fun normalPhoneKeepsTheEstablishedFullWidthStatsStrip() {
        assertTrue(
            shouldUseEstablishedPinnedStatsPhoneLayout(
                widthClass = AdaptiveWindowWidthClass.Compact,
                layoutDensity = AdaptiveLayoutDensity.Regular
            )
        )
    }

    @Test
    fun constrainedOrWideDpLayoutsUseTheAdaptiveStatsStrip() {
        assertFalse(
            shouldUseEstablishedPinnedStatsPhoneLayout(
                widthClass = AdaptiveWindowWidthClass.Compact,
                layoutDensity = AdaptiveLayoutDensity.Compact
            )
        )
        assertFalse(
            shouldUseEstablishedPinnedStatsPhoneLayout(
                widthClass = AdaptiveWindowWidthClass.Medium,
                layoutDensity = AdaptiveLayoutDensity.Regular
            )
        )
    }

    @Test
    fun theStatsScreenOpensOnTwoWeeks() {
        // Long enough for the day-by-day grid, the weekday split and a GMI that means
        // something; short enough to still be about the present.
        assertEquals(StatsTimeRange.DAY_14, StatsUiState().selectedRange)
    }
}
