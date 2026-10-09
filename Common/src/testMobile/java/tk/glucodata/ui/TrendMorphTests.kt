package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.logic.TrendEngine

class TrendMorphTests {
    private val now = 1_700_000_000_000L

    private fun corners(radii: TrendCornerRadii) = listOf(
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
        val radii = navigationCornerRadii(0f)
        assertEquals(32f, radii.topStart, 0f)
        assertEquals(radii.topStart, radii.topEnd, 0f)
        assertEquals(20f, radii.bottomStart, 0f)
        assertEquals(radii.bottomStart, radii.bottomEnd, 0f)
    }

    @Test
    fun flatReadingsWithNonzeroMeasuredVelocityKeepTheRestingCorners() {
        for (slope in listOf(-0.4f, 0.4f)) {
            val points = history().map { point ->
                val minutesAgo = (now - point.timestamp) / 60_000f
                point.copy(value = 120f - minutesAgo * slope)
            }
            val trend = dashboardTrend(points, latestDashboardPoint(points), null, 0, false)
            assertEquals(TrendEngine.TrendState.Flat, trend.state)
            assertEquals(slope, trend.velocity, 0.01f)
            assertEquals(navigationCornerRadii(0f), navigationCornerRadiiForTrend(trend))
        }
    }

    @Test
    fun unknownTrendWithRetainedVelocityKeepsTheRestingCorners() {
        val trend = TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, 1.5f, 0f, 0f)
        assertEquals(navigationCornerRadii(0f), navigationCornerRadiiForTrend(trend))
    }

    @Test
    fun classificationChangesAtTheSameVelocityChangeTheTargetShape() {
        val rising = TrendEngine.TrendResult(TrendEngine.TrendState.FortyFiveUp, 0.4f, 0f, 1f)
        val flat = rising.copy(state = TrendEngine.TrendState.Flat)
        assertTrue(navigationCornerRadiiForTrend(rising) != navigationCornerRadiiForTrend(flat))
        assertEquals(navigationCornerRadii(0f), navigationCornerRadiiForTrend(flat))
    }

    @Test
    fun risingAndFallingReverseTheVerticalAsymmetry() {
        val rising = navigationCornerRadii(1.5f)
        val falling = navigationCornerRadii(-1.5f)
        assertTrue(rising.topEnd - 32f < rising.bottomEnd - 20f)
        assertTrue(falling.topEnd - 32f > falling.bottomEnd - 20f)
        assertEquals(rising.topStart - 32f, falling.bottomStart - 20f, 0.001f)
        assertEquals(rising.topEnd - 32f, falling.bottomEnd - 20f, 0.001f)
        assertEquals(rising.bottomEnd - 20f, falling.topEnd - 32f, 0.001f)
        assertEquals(rising.bottomStart - 20f, falling.topStart - 32f, 0.001f)
    }

    @Test
    fun ordinaryRisingAndFallingTrendsProduceVisibleCornerChanges() {
        for (velocity in listOf(-0.8f, 0.8f)) {
            val weights = trendCornerWeightsFromVelocity(velocity)
            val radii = navigationCornerRadii(velocity)
            val topChange = radii.topEnd - 32f
            val bottomChange = radii.bottomEnd - 20f
            // The previous 8x scale only changed these corners by about 1–2dp.
            assertTrue(kotlin.math.abs(topChange - bottomChange) >= 16f)
            assertTrue((topChange - bottomChange) * (weights.topEnd - weights.bottomEnd) > 0f)
        }
    }

    @Test
    fun springOvershootCannotProduceInvalidNavigationCorners() {
        val radii = navigationCornerRadiiFromWeights(TrendCornerWeights(-0.2f, 1.2f, -0.2f, 1.2f))
        listOf(radii.topStart, radii.topEnd).forEach { assertTrue(it in 16f..48f) }
        listOf(radii.bottomStart, radii.bottomEnd).forEach { assertTrue(it in 4f..36f) }
    }

    @Test
    fun extremeTrendsKeepEveryCornerWithinTheNavigationRange() {
        listOf(-Float.MAX_VALUE, -100f, -3.6f, -1f, 0f, 1f, 3.6f, 100f, Float.MAX_VALUE)
            .forEach { velocity ->
                val radii = navigationCornerRadii(velocity)
                listOf(radii.topStart, radii.topEnd).forEach { assertTrue(it in 16f..48f) }
                listOf(radii.bottomStart, radii.bottomEnd).forEach { assertTrue(it in 4f..36f) }
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
