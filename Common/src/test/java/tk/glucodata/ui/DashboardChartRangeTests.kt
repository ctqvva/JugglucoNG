package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardChartRangeTests {

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
}
