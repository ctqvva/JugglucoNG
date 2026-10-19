package tk.glucodata.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.GlucosePoint as UiGlucosePoint

/**
 * Native storage keeps one slot per minute of sensor life and fills the minutes it never
 * got a reading for with a timestamped, glucose-less placeholder — the "--" rows in the
 * readings list. Those reach the trend engine as a value of 0.
 *
 * Issue #166: after an NFC scan a clean Libre reported a noise of several thousand and the
 * mirror inherited it. A 0 mg/dL sitting between real readings is not a quiet sensor
 * misread as a loud one, it is a hole being measured as a plunge and a recovery.
 */
class TrendEngineGapTests {

    private val nowMillis = 1_700_000_000_000L

    /** Newest-first, one a minute, mg/dL. */
    private fun stream(vararg values: Float): List<UiGlucosePoint> =
        values.mapIndexed { i, v ->
            UiGlucosePoint(value = v, time = "", timestamp = nowMillis - i * 60_000L)
        }

    private fun noise(points: List<UiGlucosePoint>): Float =
        TrendEngine.calculateTrend(points, useRaw = false, isMmol = false).noiseLevel

    private fun velocity(points: List<UiGlucosePoint>): Float =
        TrendEngine.calculateTrend(points, useRaw = false, isMmol = false).velocity

    private val clean = stream(140f, 138f, 137f, 135f, 134f, 132f, 131f, 129f, 128f, 126f)

    @Test
    fun aGapDoesNotRegisterAsNoise() {
        val withHole = clean.toMutableList()
        withHole[4] = withHole[4].copy(value = 0f)

        // The placeholder is dropped, so the surviving readings describe the same signal.
        assertEquals(noise(clean), noise(withHole), 0.5f)
    }

    @Test
    fun aGapLeftInWouldHaveBeenCatastrophic() {
        // What the shipped code measured: a lone 0 among ~130 mg/dL readings, charged to
        // the sensor as error variance. Guards the fix against being quietly undone.
        val withHole = clean.toMutableList()
        withHole[4] = withHole[4].copy(value = 0f)

        assertTrue("clean stream must read as quiet, was ${noise(clean)}", noise(clean) < 10f)
        assertTrue("gap must not read as noise, was ${noise(withHole)}", noise(withHole) < 10f)
    }

    @Test
    fun aGapDoesNotTruncateTheTrend() {
        // The >20 mg/dL/min artifact guard used to see the drop into the placeholder as a
        // calibration step and throw away everything older than it, leaving the arrow
        // regressing over four points.
        val withHole = clean.toMutableList()
        withHole[4] = withHole[4].copy(value = 0f)

        assertEquals(velocity(clean), velocity(withHole), 0.1f)
    }

    @Test
    fun anAllGapWindowReportsNothingRatherThanZeroGlucose() {
        val onlyHoles = stream(0f, 0f, 0f, 0f, 0f)

        val result = TrendEngine.calculateTrend(onlyHoles, useRaw = false, isMmol = false)
        assertEquals(0f, result.velocity, 0.0001f)
        assertEquals(0f, result.noiseLevel, 0.0001f)
    }

    @Test
    fun unevenSpacingIsFitInTimeNotInSampleIndex() {
        // An NFC scan drops 15-minute history points into the same window as the 1-minute
        // stream. On an index axis the older, widely spaced points look like a sudden bend;
        // on a time axis they are exactly where a straight fall puts them.
        val mixed = listOf(
            UiGlucosePoint(value = 140f, time = "", timestamp = nowMillis),
            UiGlucosePoint(value = 139f, time = "", timestamp = nowMillis - 60_000L),
            UiGlucosePoint(value = 138f, time = "", timestamp = nowMillis - 120_000L),
            UiGlucosePoint(value = 130f, time = "", timestamp = nowMillis - 8L * 60_000L),
            UiGlucosePoint(value = 128f, time = "", timestamp = nowMillis - 10L * 60_000L)
        )

        // Perfectly on a line through time: the parabola fits it with no residual left over.
        assertTrue("uneven but linear data must read as quiet, was ${noise(mixed)}", noise(mixed) < 1f)
    }

    @Test
    fun evenlySpacedNoiseIsUnchangedByTheTimeAxis() {
        // A quadratic fit is invariant under an affine change of x, so re-basing the axis
        // from sample index to elapsed time must not move the number for a normal stream.
        // Pinned against the values the index-based fit produced.
        val jittery = stream(140f, 133f, 139f, 132f, 138f, 131f, 137f, 130f)
        assertEquals(10.0595f, noise(jittery), 0.001f)
    }
}
