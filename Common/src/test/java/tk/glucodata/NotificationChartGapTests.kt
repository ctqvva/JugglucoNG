package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification chart drew a straight line across however long the sensor had been silent:
 * drawSeries walked the list and joined consecutive points with no reference to elapsed time.
 * On a 2026-08-30 trace that turned fourteen missing minutes into an unremarkable stretch of
 * rising glucose, while the dashboard — reading the same rows — broke the line.
 *
 * It now uses the chart's own constant, so a hole reads as a hole on every surface.
 */
class NotificationChartGapTests {
    private companion object {
        const val MINUTE_MS = 60L * 1000L
    }

    @Test
    fun adjacentHistorySlotsStillConnect() {
        // 15-minute Libre history plus write drift is what the threshold is derived from;
        // joining those is the behaviour the rule must not break.
        assertTrue(NotificationChartDrawer.joinsAcross(0L, 15 * MINUTE_MS))
        assertTrue(NotificationChartDrawer.joinsAcross(0L, 16 * MINUTE_MS))
        assertTrue(NotificationChartDrawer.joinsAcross(0L, GlucoseChartGap.THRESHOLD_MS))
    }

    @Test
    fun aGenuineHoleBreaksTheLine() {
        assertFalse(NotificationChartDrawer.joinsAcross(0L, GlucoseChartGap.THRESHOLD_MS + 1))
        assertFalse(NotificationChartDrawer.joinsAcross(0L, 30 * MINUTE_MS))
    }

    @Test
    fun theRuleIsTheChartsOwnConstant() {
        // Not a second opinion about the same question — the same number the Compose charts use.
        assertTrue(GlucoseChartGap.THRESHOLD_MS == 17 * MINUTE_MS)
    }
}
