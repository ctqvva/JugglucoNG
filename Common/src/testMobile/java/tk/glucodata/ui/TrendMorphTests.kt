package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.CurrentDisplaySource

class TrendMorphTests {
    private val now = 1_700_000_000_000L

    private fun corners(radii: NavigationCornerRadii) = listOf(
        radii.topStart, radii.topEnd, radii.bottomEnd, radii.bottomStart
    )

    private fun history(scale: Float = 1f) = (15 downTo 0).map { minutesAgo ->
        GlucosePoint(
            value = (120f - minutesAgo * 1.5f) / scale,
            rawValue = (140f + minutesAgo) / scale,
            time = "",
            timestamp = now - minutesAgo * 60_000L
        )
    }

    @Test
    fun restingNavigationShapeIsSymmetric() {
        corners(navigationCornerRadii(0f)).forEach { assertEquals(32f, it, 0f) }
    }

    @Test
    fun risingAndFallingReverseTheVerticalAsymmetry() {
        val rising = navigationCornerRadii(1.5f)
        val falling = navigationCornerRadii(-1.5f)
        assertTrue(rising.topEnd < rising.bottomEnd)
        assertTrue(falling.topEnd > falling.bottomEnd)
        assertEquals(rising.topStart, falling.bottomStart, 0.001f)
        assertEquals(rising.topEnd, falling.bottomEnd, 0.001f)
        assertEquals(rising.bottomEnd, falling.topEnd, 0.001f)
        assertEquals(rising.bottomStart, falling.topStart, 0.001f)
    }

    @Test
    fun extremeTrendsKeepEveryCornerWithinTheNavigationRange() {
        listOf(-Float.MAX_VALUE, -100f, -3.6f, -1f, 0f, 1f, 3.6f, 100f, Float.MAX_VALUE)
            .forEach { velocity ->
                corners(navigationCornerRadii(velocity)).forEach {
                    assertTrue("$velocity produced radius $it", it in 24f..40f)
                }
            }
    }

    @Test
    fun nonFiniteTrendsReturnToTheRestingShape() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(navigationCornerRadii(0f), navigationCornerRadii(it))
        }
    }

    @Test
    fun rawAndAutoModesFollowTheirOwnMeasuredSlopes() {
        val points = history()
        listOf(0, 2).forEach { mode ->
            val result = dashboardTrend(points, latestDashboardPoint(points), null, mode, false)
            assertEquals(1.5f, result.velocity, 0.01f)
        }
        listOf(1, 3).forEach { mode ->
            val result = dashboardTrend(points, latestDashboardPoint(points), null, mode, false)
            assertEquals(-1f, result.velocity, 0.01f)
        }
    }

    @Test
    fun switchingUnitsKeepsTheSameNavigationShape() {
        val mgdlPoints = history()
        val mmolPoints = history(18.016f)
        for (mode in 0..3) {
            val mgdl = dashboardTrend(mgdlPoints, latestDashboardPoint(mgdlPoints), null, mode, false)
            val mmol = dashboardTrend(mmolPoints, latestDashboardPoint(mmolPoints), null, mode, true)
            corners(navigationCornerRadii(mgdl.velocity)).zip(corners(navigationCornerRadii(mmol.velocity)))
                .forEach { (a, b) -> assertEquals(a, b, 0.02f) }
        }
    }

    @Test
    fun liveTailExtendsTheMeasuredTrendRatherThanUsingItsReportedRate() {
        val points = history()
        val snapshot = CurrentDisplaySource.Snapshot(
            timeMillis = now + 60_000L,
            rate = -5f,
            sensorId = "sensor",
            sensorGen = 0,
            index = 0,
            viewMode = 0,
            source = "sensor",
            autoValue = 121.5f,
            rawValue = 139f,
            sharedDisplayValue = 121.5f,
            sharedMgdl = 122,
            isMmol = false,
            displayValues = DisplayValueResolver.resolve(121.5f, 139f, 0, false)
        )
        val result = dashboardTrend(points, latestDashboardPoint(points), snapshot, 0, false)
        assertEquals(1.5f, result.velocity, 0.01f)
    }

    @Test
    fun noHistoryProducesANeutralNavigationShape() {
        val result = dashboardTrend(emptyList(), null, null, 0, false)
        assertEquals(navigationCornerRadii(0f), navigationCornerRadii(result.velocity))
    }

    @Test
    fun anOlderAppendedPointDoesNotReplaceTheNewestDashboardPoint() {
        val points = history()
        val withOlderTail = points + points.first()
        assertEquals(points.last(), latestDashboardPoint(withOlderTail))
    }
}
