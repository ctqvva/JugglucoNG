package tk.glucodata.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucosePoint
import tk.glucodata.ui.GlucosePoint as UiGlucosePoint

/**
 * The regression window scales with the sensor's measured cadence so the slope carries
 * the same confidence everywhere, instead of a fixed 20 minutes meaning ~2x the smoothing
 * on a one-a-minute sensor that it does on a five-minute one.
 */
class TrendWindowCadenceTests {

    private val nowMillis = 1_700_000_000_000L

    private fun uiPoint(timestamp: Long) =
        UiGlucosePoint(value = 100f, time = "", timestamp = timestamp)

    /** Newest-first, evenly spaced — the order calculateTrend normalises to. */
    private fun cadence(minutesApart: Double, count: Int = 30): List<UiGlucosePoint> =
        (0 until count).map { i -> uiPoint(nowMillis - (i * minutesApart * 60_000L).toLong()) }

    private fun windowMinutes(points: List<UiGlucosePoint>): Double =
        TrendEngine.trendWindowMillis(points) / 60_000.0

    @Test
    fun fiveMinuteCadenceKeepsTheEstablishedTwentyMinuteWindow() {
        // The reference point: Dexcom/Libre behaviour must not move at all.
        assertEquals(20.0, windowMinutes(cadence(5.0)), 0.01)
    }

    @Test
    fun oneMinuteCadenceShortensTowardEqualConfidence() {
        // window ~ cadence^(1/3): 20 * cbrt(1/5) = 11.7 min. Same slope standard error
        // as a 5-minute sensor gets at 20 minutes, roughly a third less lag.
        assertEquals(11.7, windowMinutes(cadence(1.0)), 0.1)
    }

    @Test
    fun windowGrowsMonotonicallyWithCadence() {
        val windows = listOf(1.0, 2.0, 3.0, 5.0).map { windowMinutes(cadence(it)) }
        windows.zipWithNext { shorter, longer ->
            assertTrue("window must not shrink as cadence slows: $windows", longer > shorter)
        }
    }

    @Test
    fun clampedAtBothEnds() {
        // Sub-minute cadence must not drive the window into jitter territory...
        assertTrue(windowMinutes(cadence(0.2)) >= 8.0)
        // ...and a very slow sensor must not regress over a window that outlives the trend.
        assertEquals(25.0, windowMinutes(cadence(15.0)), 0.01)
    }

    @Test
    fun medianIgnoresADisconnectGap() {
        // One 40 minute hole in an otherwise 1-a-minute stream is a dropout, not a cadence
        // change. A mean would stretch the window; the median must not notice.
        val points = mutableListOf<UiGlucosePoint>()
        points.add(uiPoint(nowMillis))
        points.add(uiPoint(nowMillis - 40 * 60_000L))
        for (i in 1..20) {
            points.add(uiPoint(nowMillis - (40 + i) * 60_000L))
        }
        assertEquals(11.7, windowMinutes(points), 0.1)
    }

    @Test
    fun duplicateTimestampsDoNotCollapseTheWindow() {
        // Zero gaps say nothing about cadence and must be skipped, not treated as
        // an infinitely fast sensor.
        val points = listOf(
            uiPoint(nowMillis),
            uiPoint(nowMillis),
            uiPoint(nowMillis - 5 * 60_000L),
            uiPoint(nowMillis - 10 * 60_000L),
            uiPoint(nowMillis - 15 * 60_000L)
        )
        assertEquals(20.0, windowMinutes(points), 0.01)
    }

    @Test
    fun noUsableGapsFallsBackToTheReferenceWindow() {
        val points = listOf(uiPoint(nowMillis), uiPoint(nowMillis))
        assertEquals(20.0, windowMinutes(points), 0.01)
    }

    @Test
    fun fastSensorReactsSoonerToARealTurn() {
        // The point of the whole change: a 1-minute sensor that turned 8 minutes ago.
        // A 20 minute window still averages in the old direction; the cadence-scaled
        // window has mostly moved on.
        val points = mutableListOf<GlucosePoint>()
        for (minutesAgo in 0..7) {
            // last 8 min: rising 2 mg/dL/min off the bottom.
            points.add(GlucosePoint(nowMillis - minutesAgo * 60_000L, 100f + (7 - minutesAgo) * 2f, 0f))
        }
        for (minutesAgo in 8..24) {
            // before that: falling 2 mg/dL/min into it.
            points.add(GlucosePoint(nowMillis - minutesAgo * 60_000L, 100f + (minutesAgo - 7) * 2f, 0f))
        }
        val result = TrendEngine.calculateTrend(points, useRaw = false, isMmol = false)
        assertTrue("a sensor that turned 8 min ago should read as rising, was ${result.velocity}", result.velocity > 0.5f)
        // Recency weighting carries this past the 1.0 SingleUp threshold; the unweighted fit
        // over the same window only reached FortyFiveUp.
        assertEquals(TrendEngine.TrendState.SingleUp, result.state)

        // The fixed 20 minute window would still have called this a fall. The two windows
        // disagree on direction outright, which is the whole responsiveness gain — and if
        // this ever stops holding, the fixture has stopped exercising the change.
        val fixedTwenty = slopeOver(points, windowMinutes = 20)
        assertTrue("fixed 20 min should still read falling here, was $fixedTwenty", fixedTwenty < -0.5f)
    }

    /** Plain least-squares slope over an explicit window, for comparing against the adaptive one. */
    private fun slopeOver(points: List<GlucosePoint>, windowMinutes: Int): Double {
        val newest = points.maxOf { it.timestamp }
        val window = points.filter { newest - it.timestamp <= windowMinutes * 60_000L }
        val n = window.size
        val xs = window.map { (it.timestamp - newest) / 60000.0 }
        val ys = window.map { it.value.toDouble() }
        val sx = xs.sum()
        val sy = ys.sum()
        val sxy = xs.zip(ys).sumOf { (x, y) -> x * y }
        val sxx = xs.sumOf { it * it }
        return (n * sxy - sx * sy) / (n * sxx - sx * sx)
    }

    @Test
    fun stillFarCalmerThanTheEstimatorTheRegressionReplaced() {
        // Recency weighting costs some noise rejection on purpose — that is the trade that
        // buys the responsiveness. What must not come back is the pre-regression behaviour,
        // where one bad reading owned the answer.
        //
        // On a steady -0.8/min fall, an 8 mg/dL blip on the newest reading moves this fit
        // from -0.80 to -1.38, a drift of 0.58. The `0.6^i` adjacent-delta estimator moved
        // to -4.00 on the identical input, a drift of 3.20 — it turned an ordinary fall into
        // a full double-down. Holding the drift under 1.0 keeps one bad reading from
        // promoting the arrow across two trend states.
        val clean = (0 until 20).map { i ->
            GlucosePoint(nowMillis - i * 60_000L, 100f + i * 0.8f, 0f)
        }
        val jittered = clean.toMutableList()
        jittered[0] = GlucosePoint(clean[0].timestamp, clean[0].value - 8f, 0f)

        val a = TrendEngine.calculateTrend(clean, useRaw = false, isMmol = false).velocity
        val b = TrendEngine.calculateTrend(jittered, useRaw = false, isMmol = false).velocity
        val drift = Math.abs(b - a)
        assertTrue("8 mg/dL of jitter moved the slope by $drift", drift < 1.0f)
        assertTrue("the fall must survive the blip, read $b against a true -0.8", b < 0f)
    }
}
