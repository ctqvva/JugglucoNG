package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExchangeTrendTests {
    @Test
    public void fromRate_usesStandardSevenStateThresholds() {
        assertTrend(3.0f, ExchangeTrend.DOUBLE_UP, "DoubleUp");
        assertTrend(2.0f, ExchangeTrend.SINGLE_UP, "SingleUp");
        assertTrend(1.0f, ExchangeTrend.FORTY_FIVE_UP, "FortyFiveUp");
        assertTrend(0.99f, ExchangeTrend.FLAT, "Flat");
        assertTrend(-1.0f, ExchangeTrend.FORTY_FIVE_DOWN, "FortyFiveDown");
        assertTrend(-2.0f, ExchangeTrend.SINGLE_DOWN, "SingleDown");
        assertTrend(-3.0f, ExchangeTrend.DOUBLE_DOWN, "DoubleDown");
    }

    @Test
    public void fromRate_unknownForNonFiniteRate() {
        final ExchangeTrend trend = ExchangeTrend.fromRate(Float.NaN);

        assertEquals(ExchangeTrend.UNKNOWN, trend.index);
        assertEquals("", trend.name);
        assertTrue(Float.isNaN(trend.rateMgdlPerMinute));
    }

    @Test
    public void resolve_usesCachedAidexSignedTrendByteBeforeRateFallback() {
        final String sensorId = "X-TESTTREND";
        final long timestamp = 1_700_000_000_000L;

        ExchangeTrend.cacheAiDexTrend(sensorId, timestamp, 15);
        ExchangeTrend upward = ExchangeTrend.resolve(sensorId, timestamp + 30_000L, 0.0f);
        assertEquals(ExchangeTrend.FORTY_FIVE_UP, upward.index);
        assertEquals("aidex", upward.source);
        assertEquals(1.5f, upward.rateMgdlPerMinute, 0.0f);

        ExchangeTrend.cacheAiDexTrend(sensorId, timestamp, -11);
        ExchangeTrend downward = ExchangeTrend.resolve(sensorId, timestamp, 0.0f);
        assertEquals(ExchangeTrend.FORTY_FIVE_DOWN, downward.index);
        assertEquals("aidex", downward.source);
        assertEquals(-1.1f, downward.rateMgdlPerMinute, 0.0f);
    }

    @Test
    public void resolve_doesNotReuseExpiredAidexTrendForLaterSamples() {
        final String sensorId = "X-TESTTREND-EXPIRED";
        final long timestamp = 1_700_000_000_000L;

        ExchangeTrend.cacheAiDexTrend(sensorId, timestamp, -11);
        ExchangeTrend trend = ExchangeTrend.resolve(sensorId, timestamp + 31_000L, 3.2f);

        assertEquals(ExchangeTrend.DOUBLE_UP, trend.index);
        assertEquals("rate", trend.source);
    }

    @Test
    public void adaptiveAidexCanDiscardTheStockTrendBeforePublishingItsOwnRate() {
        final String sensorId = "X-ADAPTIVE-TREND";
        final long timestamp = 1_700_000_000_000L;
        ExchangeTrend.cacheAiDexTrend(sensorId, timestamp, -20);
        assertEquals(ExchangeTrend.SINGLE_DOWN, ExchangeTrend.resolve(sensorId, timestamp, 2.0f).index);
        ExchangeTrend.clearAiDexTrend(sensorId);
        ExchangeTrend selected = ExchangeTrend.resolve(sensorId, timestamp, 2.0f);
        assertEquals(ExchangeTrend.SINGLE_UP, selected.index);
        assertEquals("rate", selected.source);
    }

    @Test
    public void nameAndArrowForIndex_areStableForExchangePayloads() {
        assertEquals("DoubleUp", ExchangeTrend.nameForIndex(ExchangeTrend.DOUBLE_UP));
        assertEquals("SingleUp", ExchangeTrend.nameForIndex(ExchangeTrend.SINGLE_UP));
        assertEquals("FortyFiveUp", ExchangeTrend.nameForIndex(ExchangeTrend.FORTY_FIVE_UP));
        assertEquals("Flat", ExchangeTrend.nameForIndex(ExchangeTrend.FLAT));
        assertEquals("FortyFiveDown", ExchangeTrend.nameForIndex(ExchangeTrend.FORTY_FIVE_DOWN));
        assertEquals("SingleDown", ExchangeTrend.nameForIndex(ExchangeTrend.SINGLE_DOWN));
        assertEquals("DoubleDown", ExchangeTrend.nameForIndex(ExchangeTrend.DOUBLE_DOWN));

        assertEquals("\u2191\u2191", ExchangeTrend.arrowForIndex(ExchangeTrend.DOUBLE_UP));
        assertEquals("\u2192", ExchangeTrend.arrowForIndex(ExchangeTrend.FLAT));
        assertEquals("\u2193\u2193", ExchangeTrend.arrowForIndex(ExchangeTrend.DOUBLE_DOWN));
    }

    @Test
    public void deriveRate_convertsSampleGapToMgdlPerMinute() {
        final long now = 1_700_000_000_000L;

        // +25 mg/dL over 5 minutes == +5 mg/dL per minute.
        assertEquals(5.0f, ExchangeTrend.deriveRate(100, now - 300_000L, 125, now), 0.001f);
        // Falling reads negative.
        assertEquals(-2.0f, ExchangeTrend.deriveRate(120, now - 300_000L, 110, now), 0.001f);
        // Unchanged glucose is genuinely flat, not unknown.
        assertEquals(0.0f, ExchangeTrend.deriveRate(120, now - 300_000L, 120, now), 0.001f);
    }

    @Test
    public void deriveRate_rejectsGapsOutsideTheUsableWindow() {
        final long now = 1_700_000_000_000L;

        // Too close together: mg/dL quantisation would dominate.
        assertTrue(Float.isNaN(ExchangeTrend.deriveRate(100, now - 60_000L, 101, now)));
        // Too far apart: the old sample no longer describes the current direction.
        assertTrue(Float.isNaN(ExchangeTrend.deriveRate(100, now - 45L * 60_000L, 140, now)));
        // Boundaries themselves are usable.
        assertTrue(Float.isFinite(
                ExchangeTrend.deriveRate(100, now - ExchangeTrend.MIN_DERIVE_GAP_MS, 110, now)));
        assertTrue(Float.isFinite(
                ExchangeTrend.deriveRate(100, now - ExchangeTrend.MAX_DERIVE_GAP_MS, 110, now)));
    }

    @Test
    public void deriveRate_rejectsMissingOrNonPositiveGlucose() {
        final long now = 1_700_000_000_000L;

        assertTrue(Float.isNaN(ExchangeTrend.deriveRate(0, now - 300_000L, 120, now)));
        assertTrue(Float.isNaN(ExchangeTrend.deriveRate(120, now - 300_000L, 0, now)));
        // Out-of-order samples must not produce a backwards rate.
        assertTrue(Float.isNaN(ExchangeTrend.deriveRate(120, now, 130, now - 300_000L)));
    }

    @Test
    public void fromSamples_yieldsSevenStateTrendForDriversWithoutARate() {
        final long now = 1_700_000_000_000L;

        // This is the issue #114 case: a driver that reports no rate at all still gets a
        // direction rather than the empty string Nightscout was receiving.
        final ExchangeTrend rising = ExchangeTrend.fromSamples(100, now - 300_000L, 112, now);
        assertEquals(ExchangeTrend.SINGLE_UP, rising.index);
        assertEquals("SingleUp", rising.name);
        assertEquals("derived", rising.source);

        final ExchangeTrend steady = ExchangeTrend.fromSamples(100, now - 300_000L, 102, now);
        assertEquals(ExchangeTrend.FLAT, steady.index);
        assertEquals("Flat", steady.name);
    }

    @Test
    public void fromSamples_staysUnknownWhenNoRateCanBeDerived() {
        final long now = 1_700_000_000_000L;
        final ExchangeTrend trend = ExchangeTrend.fromSamples(100, now - 45L * 60_000L, 140, now);

        // Better an absent arrow than a confidently wrong one.
        assertEquals(ExchangeTrend.UNKNOWN, trend.index);
        assertEquals("", trend.name);
    }

    private static void assertTrend(float rate, int expectedIndex, String expectedName) {
        final ExchangeTrend trend = ExchangeTrend.fromRate(rate);

        assertEquals(expectedIndex, trend.index);
        assertEquals(expectedName, trend.name);
        assertEquals(rate, trend.rateMgdlPerMinute, 0.0f);
    }
}
