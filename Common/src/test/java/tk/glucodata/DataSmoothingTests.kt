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
    fun shouldCollapseExchangeOutputsPullsExchangeSmoothingBackInUnderGraphOnly() {
        // "Collapse" is defined as keeping one *smoothed* reading per interval, so turning
        // it on for exchange overrides "graph only" instead of leaving it a no-op: there is
        // no smoothed reading to collapse to otherwise, which used to mean xDrip/WearInt/
        // Gadgetbridge/the outbound API kept sending an unthrottled raw reading every minute
        // whenever "graph only" was set alongside "collapse".
        assertTrue(
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

        // Without "collapse" itself on, "graph only" still switches exchange smoothing off.
        assertFalse(
            DataSmoothing.shouldCollapseExchangeOutputs(
                smoothingMinutes = 5,
                graphOnly = true,
                exchangeOutputsOnly = false,
                collapseChunks = false
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
    fun causalSmoothingFiltersBothLanesAndKeepsTimestamps() {
        val minute = 60_000L
        val points = (0 until 30).map { i ->
            GlucosePoint(i * minute, 100f + (i % 2) * 6f, 90f + (i % 2) * 6f)
        }
        val out = DataSmoothing.smoothNativePointsCausal(points, smoothingMinutes = 5,
            collapseChunks = false, mgdlPerUnit = 1f, nowMillis = Long.MAX_VALUE / 2)
        assertEquals(points.map { it.timestamp }, out.map { it.timestamp })
        assertTrue(out.last().value in 100f..106f)
        assertTrue(out.last().rawValue in 90f..96f)
        assertTrue(kotlin.math.abs(out.last().value - out[out.size - 2].value) < 3f)
    }

    @Test
    fun causalSmoothingOfTheNewestPointIgnoresNothingButThePast() {
        val minute = 60_000L
        val base = (0 until 30).map { i -> GlucosePoint(i * minute, 100f + (i % 3) * 5f, 0f) }
        val a = DataSmoothing.smoothNativePointsCausal(base.take(20), 5, false, 1f, Long.MAX_VALUE / 2)
        val b = DataSmoothing.smoothNativePointsCausal(base, 5, false, 1f, Long.MAX_VALUE / 2)
        assertEquals(a.last().value, b[19].value, 0f)
    }

    @Test
    fun causalSmoothingIsOffWhenTheWindowIsOff() {
        val points = listOf(GlucosePoint(0L, 100f, 0f), GlucosePoint(60_000L, 200f, 0f))
        assertEquals(points, DataSmoothing.smoothNativePointsCausal(points, 0, false, 1f))
    }

    @Test
    fun importedReadingsDoNotShareTheSelectedSensorsFilterState() {
        val selected = GlucosePoint(0L, 100f, 90f).also { it.sensorSerial = "30211DF2J38" }
        val alias = GlucosePoint(60_000L, 100f, 90f).also { it.sensorSerial = "30211df2j38" }
        val imported = GlucosePoint(60_000L, 200f, 190f).also { it.sensorSerial = "__imported_csv__" }
        val live = GlucosePoint(120_000L, 100f, 90f)

        assertTrue(DataSmoothing.sameCausalSource(selected, alias))
        assertTrue(DataSmoothing.sameCausalSource(selected, live))
        assertFalse(DataSmoothing.sameCausalSource(selected, imported))
    }

    @Test
    fun causalLiveSettingIsRegisteredMirroredAndOffByDefault() {
        val definition = tk.glucodata.settings.SettingsRegistry.SMOOTHING_CAUSAL_LIVE
        assertEquals("dashboard_data_smoothing_causal_live", definition.key)
        assertEquals(false, definition.defaultValue)
        assertTrue(tk.glucodata.settings.SettingsRegistry.definitions.contains(definition))
    }

    @Test
    fun exchangeSmoothingMinutesIsPulledBackInByCollapseUnderGraphOnly() {
        assertEquals(
            0,
            DataSmoothing.exchangeSmoothingMinutes(
                smoothingMinutes = 5,
                graphOnly = true,
                exchangeOutputsOnly = false
            )
        )

        assertEquals(
            5,
            DataSmoothing.exchangeSmoothingMinutes(
                smoothingMinutes = 5,
                graphOnly = true,
                exchangeOutputsOnly = false,
                collapseChunks = true
            )
        )
    }

    @Test
    fun collapsePointsForDisplayKeepsASparseLaneTheLastReadingLacksAtItsOwnTime() {
        // Libre's History lane is one value per 15 minutes.
        val points = (0..5).map { minute ->
            GlucosePoint(minute * 60_000L, (100 + minute).toFloat(), if (minute == 3) 130f else 0f)
        }

        val collapsed = DataSmoothing.collapsePointsForDisplay(
            points = points,
            smoothingMinutes = 3,
            nowMillis = 60L * 60_000L
        )

        assertEquals(listOf(2L * 60_000L, 3L * 60_000L, 5L * 60_000L), collapsed.map { it.timestamp })
        assertEquals(0f, collapsed[0].rawValue, 1e-6f)
        assertEquals(130f, collapsed[1].rawValue, 1e-6f)
        assertEquals(105f, collapsed[2].value, 1e-6f)
        assertEquals(0f, collapsed[2].rawValue, 1e-6f)
    }
}
