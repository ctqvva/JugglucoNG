package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.BLE_ERROR_CARD_WINDOW_MS

class BleErrorDetailTests {

    @Test
    fun `missing event time keeps the status as a non-historical detail`() {
        assertNull(bleErrorEventTimeForDisplay(eventAtMs = 0L, nowMs = 2_000L))
    }

    @Test
    fun `future event time caused by a clock change is clamped to now`() {
        assertEquals(2_000L, bleErrorEventTimeForDisplay(eventAtMs = 3_000L, nowMs = 2_000L))
    }

    @Test
    fun `error at the one hour boundary remains visible`() {
        val now = 10_000_000L
        assertEquals(
            now - BLE_ERROR_CARD_WINDOW_MS,
            bleErrorEventTimeForDisplay(now - BLE_ERROR_CARD_WINDOW_MS, now),
        )
    }

    @Test
    fun `error older than one hour leaves the sensor card`() {
        val now = 10_000_000L
        assertNull(bleErrorEventTimeForDisplay(now - BLE_ERROR_CARD_WINDOW_MS - 1L, now))
    }

    @Test
    fun `historical error includes its localized relative age`() {
        assertEquals("Disconnected (147) · 1 day ago", bleErrorValue("Disconnected (147)", "1 day ago"))
    }
}
