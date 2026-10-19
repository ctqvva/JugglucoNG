package tk.glucodata.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucosePoint

class TrendEngineVelocityTests {

    private val nowMillis = 1_700_000_000_000L

    // Newest-first series: value(i) = base + slopePerMin * i means the series
    // FALLS by slopePerMin per minute toward now.
    private fun series(count: Int, base: Float, risePerMinuteGoingBack: Float): MutableList<GlucosePoint> {
        val points = mutableListOf<GlucosePoint>()
        for (i in 0 until count) {
            points.add(GlucosePoint(nowMillis - i * 60_000L, base + risePerMinuteGoingBack * i, 0f))
        }
        return points
    }

    @Test
    fun cleanLinearFallRecoversExactSlope() {
        val points = series(20, base = 100f, risePerMinuteGoingBack = 1.5f)
        val result = TrendEngine.calculateTrend(points, useRaw = false, isMmol = false)
        assertEquals(-1.5f, result.velocity, 0.01f)
        assertEquals(TrendEngine.TrendState.SingleDown, result.state)
    }

    @Test
    fun singleNoisyReadingDoesNotDominateTheTrend() {
        // Steady fall of 0.5 mg/dL/min, but the newest reading sits 3 mg/dL
        // above the line — ordinary sensor jitter. One noisy minute must not
        // flip a falling trend into a rising arrow.
        val points = series(20, base = 100f, risePerMinuteGoingBack = 0.5f)
        points[0] = GlucosePoint(points[0].timestamp, points[0].value + 3f, 0f)
        val result = TrendEngine.calculateTrend(points, useRaw = false, isMmol = false)
        assertTrue("velocity ${result.velocity} should stay negative", result.velocity < -0.2f)
        assertTrue("velocity ${result.velocity} drifted too far", result.velocity > -0.7f)
    }

    @Test
    fun mmolSeriesYieldsMgdlVelocity() {
        // Falling 0.1 mmol/L per minute = about 1.8 mg/dL per minute.
        val points = series(20, base = 5.5f, risePerMinuteGoingBack = 0.1f)
        val result = TrendEngine.calculateTrend(points, useRaw = false, isMmol = true)
        assertEquals(-1.8f, result.velocity, 0.1f)
    }

    @Test
    fun calibrationStepEndsTheRegressionWindow() {
        // Newest 6 points fall 1 mg/dL/min; before them a +40 step from a
        // calibration. Only the segment newer than the artifact describes the
        // current trend.
        val points = mutableListOf<GlucosePoint>()
        for (i in 0 until 6) {
            points.add(GlucosePoint(nowMillis - i * 60_000L, 100f + i * 1.0f, 0f))
        }
        for (i in 6 until 15) {
            points.add(GlucosePoint(nowMillis - i * 60_000L, 146f, 0f))
        }
        val result = TrendEngine.calculateTrend(points, useRaw = false, isMmol = false)
        assertEquals(-1.0f, result.velocity, 0.05f)
    }

    @Test
    fun tooFewPointsAreFlatZero() {
        val single = series(1, base = 100f, risePerMinuteGoingBack = 0f)
        val result = TrendEngine.calculateTrend(single, useRaw = false, isMmol = false)
        assertEquals(0f, result.velocity, 0.0001f)
        assertEquals(TrendEngine.TrendState.Flat, result.state)
    }
}
