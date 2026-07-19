package tk.glucodata

import org.junit.Assert.assertEquals
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
    fun graphWindowTracksViewportScaleAndSelectedStrength() {
        val oneHour = 60L * 60L * 1000L
        val oneDay = 24L * oneHour

        assertEquals(0, DataSmoothing.graphWindowMinutes(0, oneDay))
        assertTrue(DataSmoothing.graphWindowMinutes(2, oneHour) <= 2)
        assertTrue(DataSmoothing.graphWindowMinutes(2, oneDay) >= 7)
        assertTrue(
            DataSmoothing.graphWindowMinutes(3, oneDay) >=
                DataSmoothing.graphWindowMinutes(1, oneDay)
        )
    }

    @Test
    fun smoothingPreservesLatestActualValue() {
        val points = listOf(
            GlucosePoint(0L, 100f, 100f),
            GlucosePoint(60_000L, 100f, 100f),
            GlucosePoint(120_000L, 160f, 160f)
        )

        val smoothed = DataSmoothing.smoothNativePoints(points, 3, false, preserveLatestEndpoint = true)

        assertEquals(160f, smoothed.last().value, 0.001f)
        assertEquals(160f, smoothed.last().rawValue, 0.001f)
    }
}
