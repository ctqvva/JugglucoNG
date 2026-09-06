package tk.glucodata

import org.junit.Assert.*
import org.junit.Test

class GluciferFallbackClockTests {
    @Test fun `minute sensor reports keep the six minute fallback asleep`() {
        val clock = GluciferFallbackClock()
        assertEquals(360_000L, clock.remaining("libre3", 360, 0))
        for (minute in 1..60) {
            val now = minute * 60_000L
            assertEquals(300_000L, clock.remaining("libre3", 360, now))
            clock.reset("libre3", now)
            assertEquals(360_000L, clock.remaining("libre3", 360, now))
        }
        assertEquals(1L, clock.remaining("libre3", 360, 3_959_999))
        assertEquals(0L, clock.remaining("libre3", 360, 3_960_000))
    }

    @Test fun `journal sends reset inactivity and other phones retain their own deadline`() {
        val clock = GluciferFallbackClock()
        clock.remaining("first", 360, 0)
        clock.remaining("second", 120, 0)
        clock.reset("first", 30_000)
        assertEquals(330_000L, clock.remaining("first", 360, 60_000))
        assertEquals(60_000L, clock.remaining("second", 120, 60_000))
        assertEquals(0L, clock.remaining("first", 10, 60_000))
    }

    @Test fun `removing and adding a destination does not retain its previous deadline`() {
        val clock = GluciferFallbackClock()
        clock.remaining("phone", 360, 0)
        clock.retain(emptySet())
        assertEquals(360_000L, clock.remaining("phone", 360, 600_000))
    }
}
