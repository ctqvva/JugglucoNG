package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSmoothingTests {
    @Test
    fun collapsePointsForDisplaySkipsOpenBucket() {
        val points = (0..7).map { minute ->
            GlucosePoint(
                minute * 60_000L,
                (100 + minute).toFloat(),
                (90 + minute).toFloat()
            )
        }

        val collapsed = DataSmoothing.collapsePointsForDisplay(
            points = points,
            smoothingMinutes = 3,
            nowMillis = (7 * 60_000L) + 30_000L
        )

        assertEquals(listOf(2L * 60_000L, 5L * 60_000L), collapsed.map { it.timestamp })
    }

    @Test
    fun collapsePointsForDisplayFallsBackToLatestWhenOnlyOpenBucketExists() {
        val points = listOf(
            GlucosePoint(6L * 60_000L, 100f, 90f),
            GlucosePoint(7L * 60_000L, 101f, 91f)
        )

        val collapsed = DataSmoothing.collapsePointsForDisplay(
            points = points,
            smoothingMinutes = 3,
            nowMillis = (7 * 60_000L) + 30_000L
        )

        assertEquals(listOf(7L * 60_000L), collapsed.map { it.timestamp })
    }

    @Test
    fun shouldSmoothExchangeOutputsFollowsAllDataScopeByDefault() {
        assertTrue(
            DataSmoothing.shouldSmoothExchangeOutputs(
                smoothingMinutes = 5,
                graphOnly = false,
                exchangeOutputsOnly = false
            )
        )

        assertFalse(
            DataSmoothing.shouldSmoothExchangeOutputs(
                smoothingMinutes = 5,
                graphOnly = true,
                exchangeOutputsOnly = false
            )
        )

        assertTrue(
            DataSmoothing.shouldSmoothExchangeOutputs(
                smoothingMinutes = 5,
                graphOnly = true,
                exchangeOutputsOnly = true
            )
        )
    }

    @Test
    fun shouldSmoothExchangeOutputsRequiresEnabledSmoothingWindow() {
        assertFalse(
            DataSmoothing.shouldSmoothExchangeOutputs(
                smoothingMinutes = 0,
                graphOnly = false,
                exchangeOutputsOnly = true
            )
        )
    }

    @Test
    fun shouldCollapseExchangeOutputsRequiresEffectiveExchangeSmoothing() {
        assertFalse(
            DataSmoothing.shouldCollapseExchangeOutputs(
                smoothingMinutes = 5,
                graphOnly = true,
                exchangeOutputsOnly = false,
                collapseChunks = true
            )
        )

        assertTrue(
            DataSmoothing.shouldCollapseExchangeOutputs(
                smoothingMinutes = 5,
                graphOnly = false,
                exchangeOutputsOnly = false,
                collapseChunks = true
            )
        )

        assertTrue(
            DataSmoothing.shouldCollapseExchangeOutputs(
                smoothingMinutes = 5,
                graphOnly = true,
                exchangeOutputsOnly = true,
                collapseChunks = true
            )
        )

        assertFalse(
            DataSmoothing.shouldCollapseExchangeOutputs(
                smoothingMinutes = 5,
                graphOnly = false,
                exchangeOutputsOnly = false,
                collapseChunks = false
            )
        )
    }

    @Test
    fun smoothNativePointsGroupsBySensorSerial() {
        val points = listOf(
            GlucosePoint(0L, 100f, 90f, "A"),
            GlucosePoint(0L, 300f, 290f, "B"),
            GlucosePoint(60_000L, 160f, 150f, "A"),
            GlucosePoint(60_000L, 360f, 350f, "B"),
            GlucosePoint(120_000L, 100f, 90f, "A"),
            GlucosePoint(120_000L, 300f, 290f, "B")
        )

        val smoothed = DataSmoothing.smoothNativePoints(
            points = points,
            smoothingMinutes = 2,
            collapseChunks = false
        )

        val middleA = smoothed.single { it.timestamp == 60_000L && it.sensorSerial == "A" }
        val middleB = smoothed.single { it.timestamp == 60_000L && it.sensorSerial == "B" }
        assertEquals(120f, middleA.value, 0.01f)
        assertEquals(320f, middleB.value, 0.01f)
    }
}
