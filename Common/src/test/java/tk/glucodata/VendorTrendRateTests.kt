package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.DisplayValues

class VendorTrendRateTests {
    @After
    fun clear() = VendorTrendRate.clearForTests()

    @Test
    fun resolvesOnlyTheMatchingSensorAndSample() {
        VendorTrendRate.publish("ANYTIME-123", 1_700_000_000_000L, -0.75f)

        assertEquals(-0.75f, VendorTrendRate.resolve("anytime-123", 1_700_000_004_000L)!!, 0f)
        assertNull(VendorTrendRate.resolve("another-sensor", 1_700_000_000_000L))
        assertNull(VendorTrendRate.resolve("ANYTIME-123", 1_700_000_006_000L))
    }

    @Test
    fun vendorRateTakesPriorityOverUsableHistoryForThatSample() {
        val timestamp = 1_700_000_000_000L
        VendorTrendRate.publish("ANYTIME-123", timestamp, 1.5f)
        val current = CurrentDisplaySource.Snapshot(
            timeMillis = timestamp,
            rate = 1.5f,
            sensorId = "ANYTIME-123",
            sensorGen = 0,
            index = 0,
            viewMode = 0,
            source = "test",
            autoValue = 100f,
            rawValue = 100f,
            sharedDisplayValue = 100f,
            sharedMgdl = 100,
            isMmol = false,
            displayValues = DisplayValues(
                primaryValue = 100f,
                primaryStr = "100",
                fullFormatted = "100",
            ),
        )
        val history = listOf(
            GlucosePoint(timestamp - 60_000L, 140f, 140f),
            GlucosePoint(timestamp, 100f, 100f),
        )

        val rate = DisplayTrendSource.resolveArrowRate(history, current, 0, false)
        assertEquals(1.5f, rate, 0f)
        assertEquals(TrendEngine.TrendState.SingleUp, TrendEngine.stateForVelocity(rate))
    }

    @Test
    fun explicitVendorDirectionSurvivesExchangeRateThresholdDifferences() {
        val timestamp = 1_700_000_000_000L
        VendorTrendRate.publish(
            "ANYTIME-123",
            timestamp,
            -0.75f,
            ExchangeTrend.FORTY_FIVE_DOWN,
        )

        val trend = ExchangeTrend.resolve("ANYTIME-123", timestamp, -0.75f)
        assertEquals(ExchangeTrend.FORTY_FIVE_DOWN, trend.index)
        assertEquals("FortyFiveDown", trend.name)
        assertEquals("vendor", trend.source)
    }
}
