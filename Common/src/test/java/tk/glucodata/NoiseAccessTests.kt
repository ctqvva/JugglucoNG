package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoiseAccessTests {
    private val t0 = 1_700_000_000_000L

    private fun point(minutesAgo: Long, value: Float) =
        GlucosePoint(t0 - minutesAgo * 60_000L, value, 0f)

    private val points = listOf(
        point(20, 100f), point(15, 100f), point(10, 100f), point(5, 100f), point(0, 100f)
    )

    @Test
    fun noProviderMeansNoExtras() {
        assertNull(NoiseAccess.resolve(null, points, false, false))
    }

    @Test
    fun providerEstimateIsReturned() {
        val expected = NoiseEstimate(73f)
        val actual = NoiseAccess.resolve({ _, _, _ -> expected }, points, false, false)
        assertEquals(expected, actual)
    }

    @Test
    fun providerFailureIsSwallowed() {
        val actual = NoiseAccess.resolve({ _, _, _ -> error("boom") }, points, false, false)
        assertNull(actual)
    }

    @Test
    fun nonFiniteEstimateIsRejected() {
        val actual = NoiseAccess.resolve({ _, _, _ -> NoiseEstimate(Float.NaN) }, points, false, false)
        assertNull(actual)
    }

    @Test
    fun exchangeNoiseUsesCalibratedSeries() {
        var seenRaw: Boolean? = null
        val provider = TrendNoiseProvider { _, useRaw, _ ->
            seenRaw = useRaw
            NoiseEstimate(1f)
        }

        NoiseAccess.resolveForExchange(provider, points, isMmol = false)

        assertEquals(false, seenRaw)
    }
}
