package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardChartRangeTests {

    @Test
    fun manualRangeUsesTotalDragFromItsStartingRange() {
        val range = manuallyAdjustedChartYRange(
            startMin = 0f,
            startMax = 13f,
            totalDragY = 50f,
            chartHeight = 500f,
            adjustsMax = true,
            minimumSpan = 6f
        )

        assertEquals(0f, range.min, 0.001f)
        assertEquals(15.6f, range.max, 0.001f)
    }

    @Test
    fun manualRangeKeepsTheOppositeEdgeAnchored() {
        val range = manuallyAdjustedChartYRange(
            startMin = 3f,
            startMax = 13f,
            totalDragY = -50f,
            chartHeight = 500f,
            adjustsMax = false,
            minimumSpan = 6f
        )

        assertEquals(1f, range.min, 0.001f)
        assertEquals(13f, range.max, 0.001f)
    }

    @Test
    fun manualRangeIgnoresInvalidGeometry() {
        val range = manuallyAdjustedChartYRange(
            startMin = 0f,
            startMax = 13f,
            totalDragY = 50f,
            chartHeight = 0f,
            adjustsMax = true,
            minimumSpan = 6f
        )

        assertEquals(0f, range.min, 0.001f)
        assertEquals(13f, range.max, 0.001f)
    }

    @Test
    fun autoRangeKeepsBaselineWhileVisibleValuesAreInsideIt() {
        val range = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 13f,
            visibleMin = 3.8f,
            visibleMax = 12.9f,
            isMmol = true
        )

        assertEquals(0f, range.min, 0.001f)
        assertEquals(13f, range.max, 0.001f)
    }

    @Test
    fun autoRangeAddsRoundedMmolHeadroomAboveBaseline() {
        val range = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 13f,
            visibleMin = 4.2f,
            visibleMax = 14.2f,
            isMmol = true
        )

        assertEquals(0f, range.min, 0.001f)
        assertEquals(15f, range.max, 0.001f)
    }

    @Test
    fun autoRangeCanExpandBelowANonZeroBaseline() {
        val range = autoExpandedChartYRange(
            baselineMin = 3f,
            baselineMax = 13f,
            visibleMin = 2.7f,
            visibleMax = 8f,
            isMmol = true
        )

        assertEquals(2f, range.min, 0.001f)
        assertEquals(13f, range.max, 0.001f)
    }

    @Test
    fun autoRangeReturnsToBaselineWhenOutlierLeavesViewport() {
        val range = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 234f,
            visibleMin = null,
            visibleMax = 220f,
            isMmol = false
        )

        assertEquals(0f, range.min, 0.001f)
        assertEquals(234f, range.max, 0.001f)
    }

    @Test
    fun autoRangeUsesMgdlSizedExpansionSteps() {
        val range = autoExpandedChartYRange(
            baselineMin = 0f,
            baselineMax = 234f,
            visibleMin = 80f,
            visibleMax = 240f,
            isMmol = false
        )

        assertEquals(252f, range.max, 0.001f)
    }

    @Test
    fun coerceChartYToDrawableRangeUsesInsetWhenThereIsRoom() {
        assertEquals(12f, coerceChartYToDrawableRange(12f, chartHeight = 100f, edgeInset = 6f), 0.001f)
        assertEquals(6f, coerceChartYToDrawableRange(-20f, chartHeight = 100f, edgeInset = 6f), 0.001f)
        assertEquals(94f, coerceChartYToDrawableRange(140f, chartHeight = 100f, edgeInset = 6f), 0.001f)
    }

    @Test
    fun coerceChartYToDrawableRangeHandlesCollapsedInsetRange() {
        val chartHeight = 1f
        val edgeInset = 19.5f

        assertEquals(1f, coerceChartYToDrawableRange(20f, chartHeight, edgeInset), 0.001f)
        assertEquals(0f, coerceChartYToDrawableRange(-20f, chartHeight, edgeInset), 0.001f)
        assertEquals(0.5f, coerceChartYToDrawableRange(Float.NaN, chartHeight, edgeInset), 0.001f)
    }

    @Test
    fun previewCenterTimeForWindowEndAnchorsPreviewAtRightEdge() {
        val windowEnd = 1_000_000_000L

        assertEquals(windowEnd, previewCenterTimeForWindowEnd(windowEnd) + 12L * 60L * 60L * 1000L)
    }

    @Test
    fun previewCenterTimeContainingViewportKeepsViewportInsidePreviewBand() {
        val hour = 60L * 60L * 1000L
        val previewCenter = 12L * hour

        assertEquals(12L * hour, previewCenterTimeContainingViewport(previewCenter, 12L * hour, 6L * hour))
        assertEquals(14L * hour, previewCenterTimeContainingViewport(previewCenter, 23L * hour, 6L * hour))
        assertEquals(10L * hour, previewCenterTimeContainingViewport(previewCenter, 1L * hour, 6L * hour))
    }
}
