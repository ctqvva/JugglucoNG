package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSmoothingTests {
    private val minute = 60_000L

    @Test
    fun nativePointsUseTheSharedBoundaryRegressionForBothLanes() {
        val points = (0..12).map { index ->
            GlucosePoint(
                index * minute,
                160f - 2f * index,
                120f - index,
            )
        }

        for (window in listOf(5, 10, 13)) {
            val smoothed = DataSmoothing.smoothNativePoints(points, window, collapseChunks = false)
            points.indices.forEach { index ->
                assertEquals(points[index].value, smoothed[index].value, 1e-3f)
                assertEquals(points[index].rawValue, smoothed[index].rawValue, 1e-3f)
            }
        }
    }

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

    /**
     * The three destinations, from the three switches, in one place. Each used to be
     * recombined at its call site, which is how the reading behind a row's Δ drifted away
     * from the reading behind the notification and the delta alarms.
     */
    @Test
    fun eachDestinationResolvesItsOwnWindowFromTheSameSwitches() {
        // Plain "smoothing on": every destination gets it.
        assertEquals(5, DataSmoothing.graphSmoothingMinutes(5, exchangeOutputsOnly = false))
        assertEquals(5, DataSmoothing.localSmoothingMinutes(5, graphOnly = false, exchangeOutputsOnly = false))
        assertEquals(5, DataSmoothing.exchangeSmoothingMinutes(5, graphOnly = false, exchangeOutputsOnly = false))

        // "Smooth only graph": the escape hatch. The drawn line keeps it, nothing the app
        // reasons with does, and nothing that leaves the phone does.
        assertEquals(5, DataSmoothing.graphSmoothingMinutes(5, exchangeOutputsOnly = false))
        assertEquals(0, DataSmoothing.localSmoothingMinutes(5, graphOnly = true, exchangeOutputsOnly = false))
        assertEquals(0, DataSmoothing.exchangeSmoothingMinutes(5, graphOnly = true, exchangeOutputsOnly = false))

        // "Smooth only exchange outputs": the mirror image.
        assertEquals(0, DataSmoothing.graphSmoothingMinutes(5, exchangeOutputsOnly = true))
        assertEquals(0, DataSmoothing.localSmoothingMinutes(5, graphOnly = false, exchangeOutputsOnly = true))
        assertEquals(5, DataSmoothing.exchangeSmoothingMinutes(5, graphOnly = false, exchangeOutputsOnly = true))
    }

    @Test
    fun aWindowOfZeroLeavesEveryDestinationMeasured() {
        assertEquals(0, DataSmoothing.graphSmoothingMinutes(0, exchangeOutputsOnly = false))
        assertEquals(0, DataSmoothing.localSmoothingMinutes(0, graphOnly = false, exchangeOutputsOnly = false))
        assertEquals(0, DataSmoothing.exchangeSmoothingMinutes(0, graphOnly = false, exchangeOutputsOnly = false))
    }

    @Test
    fun aWindowOffTheScaleIsNotHonouredAnywhere() {
        assertEquals(0, DataSmoothing.graphSmoothingMinutes(6, exchangeOutputsOnly = false))
        assertEquals(0, DataSmoothing.localSmoothingMinutes(6, graphOnly = false, exchangeOutputsOnly = false))
        assertEquals(0, DataSmoothing.exchangeSmoothingMinutes(6, graphOnly = false, exchangeOutputsOnly = false))
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
    fun exchangeSmoothingMinutesFeedsTheNativeUploaderInTheDefaultState() {
        // Default state: smoothing on, neither scope switch flipped. The Nightscout
        // uploader has to see the same window every Java-side output already applies.
        assertEquals(
            5,
            DataSmoothing.exchangeSmoothingMinutes(
                smoothingMinutes = 5,
                graphOnly = false,
                exchangeOutputsOnly = false
            )
        )

        assertEquals(
            7,
            DataSmoothing.exchangeSmoothingMinutes(
                smoothingMinutes = 7,
                graphOnly = true,
                exchangeOutputsOnly = true
            )
        )
    }

    @Test
    fun exchangeSmoothingMinutesIsZeroWhenExchangeOutputsStayUnsmoothed() {
        assertEquals(
            0,
            DataSmoothing.exchangeSmoothingMinutes(
                smoothingMinutes = 5,
                graphOnly = true,
                exchangeOutputsOnly = false
            )
        )

        assertEquals(
            0,
            DataSmoothing.exchangeSmoothingMinutes(
                smoothingMinutes = 0,
                graphOnly = false,
                exchangeOutputsOnly = false
            )
        )
    }

    @Test
    fun exchangeSmoothingMinutesRejectsAWindowThatIsNotOnTheScale() {
        assertEquals(
            0,
            DataSmoothing.exchangeSmoothingMinutes(
                smoothingMinutes = 6,
                graphOnly = false,
                exchangeOutputsOnly = false
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
}
