package tk.glucodata.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tk.glucodata.chart.HistoryChartModel
import tk.glucodata.chart.MainSensorOwnership

class ChartResolutionCacheTests {
    private fun inputs(
        data: List<GlucosePoint> = emptyList(),
        calibrationRevision: Long = 0L,
        smoothing: Int = 0,
        viewMode: Int = 0,
        ownership: MainSensorOwnership = MainSensorOwnership.NONE,
    ) = ChartResolutionInputs(
        data, MultiSensorDisplayData.EMPTY, smoothing, false, ownership,
        calibrationRevision, viewMode, false, false, "A", 0,
    )

    private fun resolution() = ChartResolution(emptyList(), emptyList(), HistoryChartModel.EMPTY)

    @Test fun returningWithSameInputsReusesTheCompletedChart() {
        val cache = ChartResolutionCache()
        val result = resolution()
        cache.put(inputs(), result)
        assertSame(result, cache.get(inputs()))
    }

    @Test fun changedRenderInputsCannotReuseAnOldChart() {
        val cache = ChartResolutionCache()
        cache.put(inputs(), resolution())
        assertNull(cache.get(inputs(calibrationRevision = 1)))
        assertNull(cache.get(inputs(smoothing = 5)))
        assertNull(cache.get(inputs(viewMode = 1)))
        assertNull(cache.get(inputs(ownership = MainSensorOwnership(mapOf(0L to "B"), 100_000_000L))))
        // A fresh data snapshot is distinct even if it compares equal.
        assertNull(cache.get(inputs(data = ArrayList())))
    }

    @Test fun cacheRetainsOnlyTwoRecentCharts() {
        val cache = ChartResolutionCache()
        val first = resolution()
        val second = resolution()
        cache.put(inputs(viewMode = 0), first)
        cache.put(inputs(viewMode = 1), second)
        assertSame(first, cache.get(inputs(viewMode = 0)))
        cache.put(inputs(viewMode = 2), resolution())
        assertNull(cache.get(inputs(viewMode = 0)))
        assertSame(second, cache.get(inputs(viewMode = 1)))
    }
}
