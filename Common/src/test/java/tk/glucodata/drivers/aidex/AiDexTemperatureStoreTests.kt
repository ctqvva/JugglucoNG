package tk.glucodata.drivers.aidex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the AiDex skin-temperature sidecar store.
 *
 * The `i2` channel of F003 live frames and 0x24 history rows carries skin
 * temperature in °C (verified against a co-worn Sibionics sensor: r ≈ 0.72
 * over ~5.5 h, identical 27–36 °C span, −0.08 °C mean bias). These tests cover
 * the persistence layer only — parsing is covered by the parser/merge tests.
 */
class AiDexTemperatureStoreTests {

    @Test
    fun encodeDecodeRoundTrip() {
        val records = listOf(
            AiDexTemperatureStore.TemperatureRecord(1789376444000L, 28.1f),
            AiDexTemperatureStore.TemperatureRecord(1789376504000L, 28.5f),
            AiDexTemperatureStore.TemperatureRecord(1789376564000L, 29.5f),
        )
        val decoded = AiDexTemperatureStore.decode(AiDexTemperatureStore.encode(records))
        assertEquals(3, decoded.size)
        assertEquals(1789376444000L, decoded[0].timestampMs)
        assertEquals(28.1f, decoded[0].temperatureC, 0.001f)
        assertEquals(28.5f, decoded[1].temperatureC, 0.001f)
        assertEquals(29.5f, decoded[2].temperatureC, 0.001f)
    }

    @Test
    fun decodeSkipsMalformedTokens() {
        val decoded = AiDexTemperatureStore.decode(
            "1789376444000,281;garbage;1789376504000;123,45,67;;,;0,285;"
        )
        assertEquals(1, decoded.size)
        assertEquals(1789376444000L, decoded[0].timestampMs)
        assertEquals(28.1f, decoded[0].temperatureC, 0.001f)
    }

    @Test
    fun decodeDropsImplausibleTemperatures() {
        // 150.0 °C and -50.0 °C are sensor faults, not skin — never surface them.
        val decoded = AiDexTemperatureStore.decode(
            "1000,1500;2000,-500;3000,330;"
        )
        assertEquals(1, decoded.size)
        assertEquals(3000L, decoded[0].timestampMs)
        assertEquals(33.0f, decoded[0].temperatureC, 0.001f)
    }

    @Test
    fun decodeDedupesAndSortsByTimestamp() {
        val decoded = AiDexTemperatureStore.decode(
            "3000,330;1000,281;3000,331;2000,285;"
        )
        assertEquals(3, decoded.size)
        assertEquals(1000L, decoded[0].timestampMs)
        assertEquals(2000L, decoded[1].timestampMs)
        assertEquals(3000L, decoded[2].timestampMs)
        // First occurrence wins, matching the Ottai store behavior.
        assertEquals(33.0f, decoded[2].temperatureC, 0.001f)
    }

    @Test
    fun decodeEmptyAndBlank() {
        assertTrue(AiDexTemperatureStore.decode("").isEmpty())
        assertTrue(AiDexTemperatureStore.decode("  ").isEmpty())
        assertTrue(AiDexTemperatureStore.decode(";;;").isEmpty())
    }

    @Test
    fun plausibilityGate() {
        assertTrue(AiDexTemperatureStore.isPlausibleSkinTemperatureC(27.2f))
        assertTrue(AiDexTemperatureStore.isPlausibleSkinTemperatureC(35.5f))
        assertTrue(AiDexTemperatureStore.isPlausibleSkinTemperatureC(-19.9f))
        assertTrue(AiDexTemperatureStore.isPlausibleSkinTemperatureC(79.9f))
        assertFalse(AiDexTemperatureStore.isPlausibleSkinTemperatureC(Float.NaN))
        assertFalse(AiDexTemperatureStore.isPlausibleSkinTemperatureC(Float.POSITIVE_INFINITY))
        assertFalse(AiDexTemperatureStore.isPlausibleSkinTemperatureC(-20f))
        assertFalse(AiDexTemperatureStore.isPlausibleSkinTemperatureC(80f))
    }

    @Test
    fun canonicalId() {
        assertEquals("X-222227KT3T", AiDexTemperatureStore.canonicalId("X-222227KT3T"))
        assertEquals("X-222227KT3T", AiDexTemperatureStore.canonicalId("x-222227kt3t"))
        assertEquals("X-222227KT3T", AiDexTemperatureStore.canonicalId("  222227KT3T  "))
        // Writer and reader must agree even across serial spellings.
        assertEquals(
            AiDexTemperatureStore.canonicalId("X-222227KT3T"),
            AiDexTemperatureStore.canonicalId("222227kt3t")
        )
    }
}
