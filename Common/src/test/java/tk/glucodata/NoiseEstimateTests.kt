package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class NoiseEstimateTests {
    @Test
    fun nightscoutLevelMatchesXDripThresholds() {
        assertEquals(1, NoiseScale.nightscoutLevel(0f))
        assertEquals(1, NoiseScale.nightscoutLevel(10f))
        assertEquals(2, NoiseScale.nightscoutLevel(10.1f))
        assertEquals(2, NoiseScale.nightscoutLevel(60f))
        assertEquals(3, NoiseScale.nightscoutLevel(60.1f))
        assertEquals(3, NoiseScale.nightscoutLevel(200f))
        assertEquals(4, NoiseScale.nightscoutLevel(200.1f))
    }

    @Test
    fun nonFiniteOrNegativeVarianceIsUnknown() {
        assertEquals(0, NoiseScale.nightscoutLevel(Float.NaN))
        assertEquals(0, NoiseScale.nightscoutLevel(-1f))
    }

    @Test
    fun sigmaIsTheSquareRootOfVariance() {
        assertEquals(8f, NoiseEstimate(64f).sigmaMgdl, 0.0001f)
        assertEquals(3, NoiseEstimate(64f).nightscoutLevel)
    }
}
