package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.TrendEngineNoiseProvider

class TrendNoiseProviderTests {
    private val t0 = 1_700_000_000_000L

    private fun point(minutesAgo: Long, value: Float) =
        GlucosePoint(t0 - minutesAgo * 60_000L, value, 0f)

    @Test
    fun fewerThanFourPointsHasNoEstimate() {
        val points = listOf(point(10, 100f), point(5, 101f), point(0, 102f))
        assertNull(TrendEngineNoiseProvider.noise(points, false, false))
    }

    @Test
    fun flatTraceIsValidZeroNoise() {
        val points = listOf(
            point(20, 100f), point(15, 100f), point(10, 100f), point(5, 100f), point(0, 100f)
        )
        val estimate = TrendEngineNoiseProvider.noise(points, false, false)
        assertNotNull(estimate)
        assertEquals(0f, estimate!!.varianceMgdl2, 0.001f)
        assertEquals(0f, estimate.sigmaMgdl, 0.001f)
        assertEquals(0, estimate.nightscoutLevel)
    }

    @Test
    fun jitterProducesPositiveVarianceAndSigma() {
        val points = listOf(
            point(20, 100f), point(15, 120f), point(10, 100f), point(5, 120f), point(0, 100f)
        )
        val estimate = TrendEngineNoiseProvider.noise(points, false, false)!!
        assertTrue("variance was ${estimate.varianceMgdl2}", estimate.varianceMgdl2 > 60f)
        assertEquals(kotlin.math.sqrt(estimate.varianceMgdl2), estimate.sigmaMgdl, 0.0001f)
        assertEquals(3, estimate.nightscoutLevel)
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
}
