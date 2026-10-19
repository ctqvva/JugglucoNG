package tk.glucodata.ui

import org.junit.Assert.*
import org.junit.Test

class LegacySensorStatusPolicyTests {
    private val start = 1_790_352_742_000L

    @Test fun waitingForBluetoothIsNotPaused() {
        assertTrue(isSensorLocallyEnabled(streaming = false, paused = false))
        assertFalse(isSensorLocallyEnabled(streaming = true, paused = true))
        assertFalse(isSensorLocallyEnabled(streaming = false, paused = null))
        assertTrue(isSensorLocallyEnabled(streaming = true, paused = null))
    }

    @Test fun libre3NfcStartShowsWarmupBeforeBluetoothStatusArrives() {
        assertEquals(60, libre3WarmupMinutes(3, start, start, 60, ""))
        assertEquals(51, libre3WarmupMinutes(3, start, start + 9 * 60_000L, 60, ""))
        assertEquals(1, libre3WarmupMinutes(3, start, start + 3_599_999, 60, "Warming Up"))
        assertNull(libre3WarmupMinutes(3, start, start + 3_600_000, 60, ""))
    }

    @Test fun respectsNegotiatedDurationAndDoesNotHideErrors() {
        assertEquals(21, libre3WarmupMinutes(3, start, start + 9 * 60_000L, 30, ""))
        for (status in listOf("Sensor Error", "Sensor Ended", "Receiving old values: 12")) {
            assertNull(libre3WarmupMinutes(3, start, start + 60_000, 60, status))
        }
        assertNull(libre3WarmupMinutes(3, start, start - 1, 60, ""))
        assertNull(libre3WarmupMinutes(3, 0, start, 60, ""))
        assertNull(libre3WarmupMinutes(3, start, start, 0, ""))
        assertNull(libre3WarmupMinutes(2, start, start, 60, ""))
        assertNull(libre3WarmupMinutes(0x40, start, start, 60, ""))
    }
}
