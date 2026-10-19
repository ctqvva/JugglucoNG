package tk.glucodata.drivers.anytime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnytimeTrendEstimatorTests {

    @Test
    fun fewerThanThreePointsReturnsNull() {
        assertNull(AnytimeTrendEstimator.estimate(emptyList()))
        assertNull(
            AnytimeTrendEstimator.estimate(
                listOf(AnytimeTrendEstimator.Point(0L, 6f), AnytimeTrendEstimator.Point(60_000L, 6f))
            )
        )
    }

    @Test
    fun flatSeriesHasZeroSlopeAndZeroNoise() {
        val points = (0..5).map { AnytimeTrendEstimator.Point(it * 60_000L, 6.0f) }
        val estimate = AnytimeTrendEstimator.estimate(points)!!
        assertEquals(0f, estimate.slopeMmolPerMin, 1e-4f)
        assertEquals(0f, estimate.noiseMmol, 1e-4f)
    }

    @Test
    fun risingSeriesReportsPositiveSlope() {
        // +0.1 mmol/L every minute for 10 minutes.
        val points = (0..10).map { AnytimeTrendEstimator.Point(it * 60_000L, 6f + it * 0.1f) }
        val estimate = AnytimeTrendEstimator.estimate(points)!!
        assertEquals(0.1f, estimate.slopeMmolPerMin, 1e-3f)
        assertEquals(0f, estimate.noiseMmol, 1e-3f)
    }

    @Test
    fun fallingSeriesReportsNegativeSlope() {
        val points = (0..10).map { AnytimeTrendEstimator.Point(it * 60_000L, 10f - it * 0.15f) }
        val estimate = AnytimeTrendEstimator.estimate(points)!!
        assertEquals(-0.15f, estimate.slopeMmolPerMin, 1e-3f)
    }

    @Test
    fun jitterAroundFlatLineReportsNonZeroNoise() {
        val values = listOf(6f, 6.3f, 5.7f, 6.2f, 5.8f, 6.1f)
        val points = values.mapIndexed { i, v -> AnytimeTrendEstimator.Point(i * 60_000L, v) }
        val estimate = AnytimeTrendEstimator.estimate(points)!!
        assertTrue("expected noise > 0.1, got ${estimate.noiseMmol}", estimate.noiseMmol > 0.1f)
    }
}
