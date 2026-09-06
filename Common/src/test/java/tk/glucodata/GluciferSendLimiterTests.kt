package tk.glucodata

import org.junit.Assert.*
import org.junit.Test

class GluciferSendLimiterTests {
    @Test fun `all intervals bound repeated live history test and retry attempts`() {
        assertEquals(listOf(1, 5, 10, 30, 60, 120, 360), GluciferSendLimiter.intervals)
        for (seconds in GluciferSendLimiter.intervals) {
            val limiter = GluciferSendLimiter()
            assertTrue(limiter.acquire("phone", seconds, 100))
            for (now in listOf(100L, 101L, 100L + seconds * 1000L - 1)) {
                assertFalse(limiter.acquire("phone", seconds, now))
            }
            assertTrue(limiter.acquire("phone", seconds, 100L + seconds * 1000L))
        }
    }

    @Test fun `destinations have independent budgets and interval changes use the last request`() {
        val limiter = GluciferSendLimiter()
        assertTrue(limiter.acquire("first", 1, 100))
        assertTrue(limiter.acquire("second", 360, 100))
        assertFalse(limiter.acquire("first", 360, 1100))
        assertTrue(limiter.acquire("first", 1, 1100))
        assertFalse(limiter.acquire("second", 360, 1100))
    }

    @Test fun `blocked requests expose the remaining wait without extending it`() {
        val limiter = GluciferSendLimiter()
        assertTrue(limiter.acquire("phone", 5, 1000))
        assertEquals(4700L, limiter.remaining("phone", 5, 1300))
        assertFalse(limiter.acquire("phone", 5, 1500))
        assertEquals(4500L, limiter.remaining("phone", 5, 1500))
        assertTrue(limiter.ready("phone", 5, 6000))
    }

    @Test fun `new destinations default to one second limit and 360 second inactivity fallback`() {
        val destination = OutboundApiSettings.createDestination(OutboundApiSettings.PRESET_GLUCIFER)
        assertEquals(1, destination.gluciferMinIntervalSeconds)
        assertEquals(360, destination.gluciferFallbackSeconds)
        assertEquals(360, GluciferSendLimiter.interval(0))
        assertEquals(360, GluciferSendLimiter.interval(9999))
    }

    @Test fun `delivery preferences round trip and old saved destinations get the defaults`() {
        val encode = OutboundApiSettings::class.java.getDeclaredMethod("encodeDestinations", List::class.java).apply { isAccessible = true }
        val decode = OutboundApiSettings::class.java.getDeclaredMethod("decodeDestinations", String::class.java).apply { isAccessible = true }
        val destination = OutboundApiSettings.createDestination(OutboundApiSettings.PRESET_GLUCIFER)
            .copy(gluciferMinIntervalSeconds = 5, gluciferFallbackSeconds = 30)
        val raw = encode.invoke(OutboundApiSettings, listOf(destination)).toString()
        @Suppress("UNCHECKED_CAST")
        val restored = (decode.invoke(OutboundApiSettings, raw) as List<OutboundApiSettings.Destination>).single()
        assertEquals(5, restored.gluciferMinIntervalSeconds)
        assertEquals(30, restored.gluciferFallbackSeconds)
        val old = org.json.JSONArray(raw)
        old.getJSONObject(0).remove("gluciferMinIntervalSeconds")
        old.getJSONObject(0).remove("gluciferFallbackSeconds")
        @Suppress("UNCHECKED_CAST")
        val migrated = (decode.invoke(OutboundApiSettings, old.toString()) as List<OutboundApiSettings.Destination>).single()
        assertEquals(1, migrated.gluciferMinIntervalSeconds)
        assertEquals(360, migrated.gluciferFallbackSeconds)
    }
}
