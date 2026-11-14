package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.TrendEngine
import tk.glucodata.logic.TrendEngineNoiseProvider

class TrendNoiseProviderTests {
    private val t0 = 1_700_000_000_000L

    private fun point(minutesAgo: Long, value: Float) =
        GlucosePoint(t0 - minutesAgo * 60_000L, value, 0f)

    private val flat = listOf(
        point(20, 100f), point(15, 100f), point(10, 100f), point(5, 100f), point(0, 100f)
    )

    private val jitter = listOf(
        point(20, 100f), point(15, 120f), point(10, 100f), point(5, 120f), point(0, 100f)
    )

    @Test
    fun fewerThanFourPointsHasNoEstimate() {
        val three = listOf(point(10, 100f), point(5, 101f), point(0, 102f))
        assertNull(TrendEngineNoiseProvider.noise(three, false, false))
    }

    @Test
    fun flatTraceIsValidZeroNoise() {
        val estimate = TrendEngineNoiseProvider.noise(flat, false, false)
        assertNotNull(estimate)
        assertEquals(0f, estimate!!.varianceMgdl2, 0.001f)
        assertEquals(0f, estimate.sigmaMgdl, 0.001f)
        assertEquals(1, estimate.nightscoutLevel)
    }

    @Test
    fun jitterProducesPositiveVarianceAndSigma() {
        val estimate = TrendEngineNoiseProvider.noise(jitter, false, false)!!
        assertTrue("variance was ${estimate.varianceMgdl2}", estimate.varianceMgdl2 > 60f)
        assertEquals(kotlin.math.sqrt(estimate.varianceMgdl2), estimate.sigmaMgdl, 0.0001f)
        assertEquals(3, estimate.nightscoutLevel)
    }

    @Test
    fun publishedVarianceUsesXDripDegreesOfFreedom() {
        val engine = TrendEngine.calculateTrend(jitter, false, false)
        val estimate = TrendEngineNoiseProvider.noise(jitter, false, false)!!

        assertEquals(engine.noiseLevel * 5f / 2f, estimate.varianceMgdl2, 0.001f)
    }

    @Test
    fun mmolInputStillReportsMgdlVariance() {
        val points = listOf(
            point(20, 5.5f), point(15, 6.5f), point(10, 5.5f), point(5, 6.5f), point(0, 5.5f)
        )
        val estimate = TrendEngineNoiseProvider.noise(points, false, true)!!
        // 1 mmol/L of jitter is about 18 mg/dL; a mmol² number would be far smaller.
        assertTrue("sigma was ${estimate.sigmaMgdl}", estimate.sigmaMgdl > 6f)
    }
    @Test
    fun calibrationStepUsesOnlyTheNewestSegment() {
        val history = listOf(point(20, 100f), point(16, 100f)) +
            listOf(point(15, 180f), point(10, 180f), point(5, 180f), point(0, 180f))
        val engine = TrendEngine.calculateTrend(history, false, false)
        val estimate = TrendEngineNoiseProvider.noise(history, false, false)!!
        assertEquals(4, engine.noiseSamples)
        assertEquals(0f, estimate.varianceMgdl2, 0.001f)
    }

    @Test
    fun calibrationStepWithTooFewNewPointsOmitsNoise() {
        val history = listOf(point(20, 100f), point(15, 100f), point(11, 100f),
            point(10, 180f), point(5, 180f), point(0, 180f))
        assertNull(TrendEngineNoiseProvider.noise(history, false, false))
    }

    @Test
    fun duplicateTimesCannotProduceAQuadraticNoiseFit() {
        val history = listOf(point(5, 100f), point(5, 100f), point(0, 110f), point(0, 110f))
        assertNull(TrendEngineNoiseProvider.noise(history, false, false))
    }
}
