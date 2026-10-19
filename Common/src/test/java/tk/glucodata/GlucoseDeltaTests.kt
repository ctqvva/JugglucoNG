package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GlucoseDeltaTests {

    private val t0 = 1_700_000_000_000L

    @Test
    fun fiveMinutePairGivesRawChange() {
        val d = GlucoseDelta.fiveMinuteDelta(t0, 215f, t0 - 5 * 60_000L, 219f)
        assertEquals(-4f, d, 0.001f)
    }

    @Test
    fun widerGapsNormalizeToFiveMinutes() {
        // 10 minutes apart, +10 -> +5 per five minutes.
        val d = GlucoseDelta.fiveMinuteDelta(t0, 130f, t0 - 10 * 60_000L, 120f)
        assertEquals(5f, d, 0.001f)
    }

    @Test
    fun tooClosePairsGiveNaN() {
        // A single-minute pair says almost nothing (readings repeat on noise)
        // and extrapolating it to five minutes would amplify that noise 5x.
        assertTrue(GlucoseDelta.fiveMinuteDelta(t0, 119f, t0 - 60_000L, 120f).isNaN())
        assertTrue(GlucoseDelta.fiveMinuteDelta(t0, 119f, t0, 120f).isNaN())
    }

    @Test
    fun staleOrInvalidPairsGiveNaN() {
        // Gap beyond 20 minutes says nothing about the current change.
        assertTrue(GlucoseDelta.fiveMinuteDelta(t0, 119f, t0 - 21 * 60_000L, 140f).isNaN())
        // Wrong order.
        assertTrue(GlucoseDelta.fiveMinuteDelta(t0, 119f, t0 + 60_000L, 120f).isNaN())
        assertTrue(GlucoseDelta.fiveMinuteDelta(t0, Float.NaN, t0 - 5 * 60_000L, 120f).isNaN())
    }

    @Test
    fun oneMinuteIntervalGivesRawChangeForAdjacentReadings() {
        // 1-minute interval, 1-minute gap -> raw change, no scaling.
        val d = GlucoseDelta.delta(t0, 219f, t0 - 60_000L, 215f, 1)
        assertEquals(4f, d, 0.001f)
    }

    @Test
    fun oneMinuteIntervalNormalizesWiderGaps() {
        // 1-minute interval over a 5-minute gap: +10 -> +2 per minute.
        val d = GlucoseDelta.delta(t0, 130f, t0 - 5 * 60_000L, 120f, 1)
        assertEquals(2f, d, 0.001f)
    }

    @Test
    fun oneMinuteIntervalRejectsSubHalfWindowPairs() {
        // Closer than half of a 1-minute window (< 30 s) says nothing.
        assertTrue(GlucoseDelta.delta(t0, 119f, t0 - 20_000L, 120f, 1).isNaN())
    }

    @Test
    fun minGapScalesWithInterval() {
        // 5-minute interval keeps the historical walk-back constant.
        assertEquals(GlucoseDelta.MIN_GAP_MILLIS, GlucoseDelta.minGapMillis(5))
        // 1-minute interval walks back only ~54 s.
        assertEquals(54_000L, GlucoseDelta.minGapMillis(1))
    }

    @Test
    fun fiveMinuteDeltaStillMatchesGeneralInterval() {
        assertEquals(
            GlucoseDelta.delta(t0, 130f, t0 - 10 * 60_000L, 120f, 5),
            GlucoseDelta.fiveMinuteDelta(t0, 130f, t0 - 10 * 60_000L, 120f),
            0.001f
        )
    }

    @Test
    fun formatsMgdlCompactlyWithSign() {
        withLocale(Locale.US) {
            assertEquals("+2", GlucoseDelta.format(2f, false))
            assertEquals("−1", GlucoseDelta.format(-1f, false))
            assertEquals("−1.5", GlucoseDelta.format(-1.5f, false))
            assertEquals("0", GlucoseDelta.format(0f, false))
        }
    }

    @Test
    fun formatsMmolWithOneDecimal() {
        withLocale(Locale.US) {
            assertEquals("−0.1", GlucoseDelta.format(-0.1f, true))
            assertEquals("+0.2", GlucoseDelta.format(0.15f, true))
        }
    }

    @Test
    fun formatsDecimalSeparatorForLocale() {
        withLocale(Locale.forLanguageTag("ru-RU")) {
            assertEquals("−0,1", GlucoseDelta.format(-0.1f, true))
            assertEquals("+0,2", GlucoseDelta.format(0.15f, true))
        }
    }

    private inline fun withLocale(locale: Locale, block: () -> Unit) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(original)
        }
    }
}
