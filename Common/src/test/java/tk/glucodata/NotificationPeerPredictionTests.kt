package tk.glucodata

import android.app.Application
import android.content.Context
import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.chart.HistoryChartModelBuilder
import tk.glucodata.chart.MainSensorOwnership

/**
 * A calibrated secondary sensor lost its forecast on the notification and AOD charts
 * (2026-11-18 trace: an Ottai peer calibrated from a stock 12.1 to 8.5 mmol/L). The
 * dashboard forecast it; the bitmap charts asked for every peer's forecast as if it
 * were uncalibrated, and the CALIBRATED series — the only one continuing the drawn
 * line once the initial value is hidden — was dropped. As the main sensor the same
 * Ottai forecast fine, and an uncalibrated AiDex peer did too, so it looked like a
 * driver problem. It was the peer's calibration state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationPeerPredictionTests {
    private data class Request(val sensorId: String?, val viewMode: Int, val hasCalibration: Boolean)

    private class RecordingBridge : NotificationPredictionBridge {
        val requests = ArrayList<Request>()
        override fun buildPredictionSeries(
            context: Context,
            data: List<GlucosePoint>?,
            isMmol: Boolean,
            viewMode: Int,
            hasCalibration: Boolean,
            calibrationSensorId: String?,
            targetLow: Float,
            targetHigh: Float,
        ): List<NotificationPredictionSeries> {
            requests += Request(calibrationSensorId, viewMode, hasCalibration)
            return emptyList()
        }
    }

    @After
    fun tearDown() {
        NotificationPredictionAccess.unregisterForTests()
        CalibrationAccess.unregisterForTests()
    }

    private val minute = 60_000L

    private fun points(sensor: String, newestMs: Long, value: Float, count: Int = 30) =
        (0 until count).map { i ->
            GlucosePoint(newestMs - (count - 1 - i) * minute, value, value).also { it.sensorSerial = sensor }
        }

    @Test
    fun aCalibratedPeerIsForecastAsCalibrated() {
        val bridge = RecordingBridge().also(NotificationPredictionAccess::register)
        val context = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val peers = listOf(
            NotificationChartDrawer.PeerSeries("OTTAI-PEER", 0, Color.CYAN, points("OTTAI-PEER", now - minute, 12.1f), true),
            NotificationChartDrawer.PeerSeries("X-AIDEX", 0, Color.GREEN, points("X-AIDEX", now - minute, 9.5f), false),
        )
        NotificationChartDrawer.drawChartWithPrediction(context, points("X-MAIN", now - minute, 9f),
            480, 256, true, 0, true, false, false, "X-MAIN", peers)

        val bySensor = bridge.requests.associateBy { it.sensorId }
        assertEquals(Request("OTTAI-PEER", 0, true), bySensor["OTTAI-PEER"])
        assertEquals(Request("X-AIDEX", 0, false), bySensor["X-AIDEX"])
    }

    @Test
    fun aPeerThatStoppedReportingIsNotExtrapolated() {
        val bridge = RecordingBridge().also(NotificationPredictionAccess::register)
        val context = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val stale = NotificationChartDrawer.PeerSeries("STALE", 0, Color.CYAN,
            points("STALE", now - GlucoseChartGap.THRESHOLD_MS - minute, 7f), false)
        NotificationChartDrawer.drawChartWithPrediction(context, points("X-MAIN", now - minute, 9f),
            480, 256, true, 0, true, false, false, "X-MAIN", listOf(stale))

        assertFalse(bridge.requests.any { it.sensorId == "STALE" })
        assertTrue("the main sensor is still forecast", bridge.requests.any { it.sensorId == "X-MAIN" })
    }

    @Test
    fun peerEligibilityFollowsTheChartGap() {
        val now = 1_800_000_000_000L
        fun peer(newest: Long) = NotificationChartDrawer.PeerSeries("P", 0, Color.CYAN, points("P", newest, 6f, 3), false)
        assertTrue(NotificationChartDrawer.peerPredictionEligible(peer(now - GlucoseChartGap.THRESHOLD_MS), now))
        assertFalse(NotificationChartDrawer.peerPredictionEligible(peer(now - GlucoseChartGap.THRESHOLD_MS - 1), now))
        assertFalse(NotificationChartDrawer.peerPredictionEligible(
            NotificationChartDrawer.PeerSeries("P", 0, Color.CYAN, points("P", now, 6f, 1), false), now))
        assertFalse(NotificationChartDrawer.peerPredictionEligible(null, now))
    }

    /**
     * The model draws a calibrated peer at its calibrated value; the stored value
     * behind it is the uncalibrated source and must not size the axis. The trace's
     * chart reached up to ~12 mmol/L with every line below 10.
     */
    @Test
    fun aCalibratedPeersSourceValueDoesNotStretchTheAxis() {
        val context = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis()
        val main = points("X-MAIN", now - minute, 9f)

        fun draw(storedPeerValue: Float): android.graphics.Bitmap {
            val peerPoints = points("OTTAI-PEER", now - minute, storedPeerValue)
            val peer = NotificationChartDrawer.PeerSeries("OTTAI-PEER", 0, Color.CYAN, peerPoints, true)
            val model = HistoryChartModelBuilder.build(
                listOf(
                    HistoryChartModelBuilder.SeriesInput("X-MAIN", true, 0, Color.WHITE, main),
                    HistoryChartModelBuilder.SeriesInput("OTTAI-PEER", false, 0, Color.CYAN, peerPoints,
                        hasCalibration = true),
                ),
                MainSensorOwnership.NONE,
                HistoryChartModelBuilder.Calibration { _, _, _, sensorId -> if (sensorId == "OTTAI-PEER") 8.5f else null },
                hideInitialWhenCalibrated = true,
            )
            return NotificationChartDrawer.drawChartWithPrediction(context, main, 480, 256,
                true, 0, true, false, false, "X-MAIN", listOf(peer), model)
        }

        // The drawer reads the wall clock, so two renders can differ by a sub-pixel
        // horizontal shift near vertical gridlines. The y-range is vertical: with the
        // same axis, nearly every column (target band, grid rows, flat lines) is
        // identical; with a different axis, almost none is.
        val a = draw(12.1f)
        val b = draw(18f)
        val identical = (0 until a.width).count { x ->
            (0 until a.height).all { y -> a.getPixel(x, y) == b.getPixel(x, y) }
        }
        assertTrue("only $identical of ${a.width} columns agree", identical >= a.width * 9 / 10)
    }
}
