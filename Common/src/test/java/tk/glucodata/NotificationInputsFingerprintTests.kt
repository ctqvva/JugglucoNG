package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.chart.ChartLook
import tk.glucodata.chart.ChartPointModel
import tk.glucodata.chart.ChartRun
import tk.glucodata.chart.ChartSeriesModel
import tk.glucodata.chart.HistoryChartModel

class NotificationInputsFingerprintTests {
    private val minute = 60_000L

    private fun hash(vararg points: GlucosePoint) =
        NotificationFingerprint().addPoints(points.toList(), true).value()

    @Test fun liveReadingAndItsStoredRowAreTheSameMinute() {
        val earlier = GlucosePoint(10 * minute, 5.1f, 0f)
        val live = GlucosePoint(11 * minute + 8_000L, 5.4f, 0f)
        val stored = GlucosePoint(11 * minute + 8_400L, 5.4f, 0f)
        assertEquals(hash(earlier, live), hash(earlier, live, stored))
        assertEquals(hash(earlier, live), hash(earlier, stored))
    }

    @Test fun newMinuteOrVisibleChangeIsDifferent() {
        val a = GlucosePoint(10 * minute, 5.1f, 0f)
        assertNotEquals(hash(a), hash(a, GlucosePoint(11 * minute, 5.1f, 0f)))
        assertNotEquals(hash(a), hash(GlucosePoint(10 * minute, 5.2f, 0f)))
        assertNotEquals(hash(a), hash(GlucosePoint(10 * minute, 5.1f, 4.0f)))
        assertNotEquals(hash(a), hash(GlucosePoint(10 * minute, 5.1f, 0f).also { it.sealedDisplayValue = 5.6f }))
    }

    @Test fun calibrationMovingTheLineIsDifferent() {
        fun model(value: Float) = HistoryChartModel(listOf(ChartSeriesModel(
            sensorId = "s", isPrimary = true, viewMode = 0, colorArgb = 1,
            runs = listOf(ChartRun(ChartLook.MAIN, listOf(ChartPointModel(10 * minute, value)))),
        )))
        fun of(model: HistoryChartModel?) = NotificationFingerprint().addModel(model, true).value()
        assertEquals(of(model(5f)), of(model(5f)))
        assertNotEquals(of(model(5f)), of(model(5.5f)))
        assertNotEquals(of(null), of(model(5f)))
    }

    @Test fun reuseNeedsKnownEqualInputsOfTheSameOwnerDrawnRecently() {
        val owner = Any()
        assertTrue(NotificationRefreshPolicy.mayReusePublication(7L, 7L, owner, owner, 30_000L))
        assertFalse("unknown", NotificationRefreshPolicy.mayReusePublication(0L, 0L, owner, owner, 0L))
        assertFalse("changed", NotificationRefreshPolicy.mayReusePublication(7L, 8L, owner, owner, 0L))
        assertFalse("new service", NotificationRefreshPolicy.mayReusePublication(7L, 7L, owner, Any(), 0L))
        assertFalse("axis moved on", NotificationRefreshPolicy.mayReusePublication(7L, 7L, owner, owner, 60_000L))
    }
}
