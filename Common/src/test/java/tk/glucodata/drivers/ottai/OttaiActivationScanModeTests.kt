package tk.glucodata.drivers.ottai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared scan runs at full duty only while an Ottai waits for its fresh-activation
 * advertisement. Full duty left on for hours would drain the phone, so the gate is pinned here,
 * including against an awaiting flag a teardown path forgot to clear.
 */
class OttaiActivationScanModeTests {
    // Any arming instant; only the offsets from it matter.
    private val armedAt = 1_700_000_183_000L

    @Test
    fun lowLatencyIsAskedForOnlyInsideTheAdvertisementWindow() {
        assertTrue(OttaiBleManager.wantsLowLatencyActivationScan(true, armedAt, armedAt))
        assertTrue(OttaiBleManager.wantsLowLatencyActivationScan(true, armedAt, armedAt + 119_999L))
        assertFalse(OttaiBleManager.wantsLowLatencyActivationScan(true, armedAt, armedAt + 120_000L))
        // A flag left set by resetActivationNegotiation, which drops the timeout without clearing it.
        assertFalse(OttaiBleManager.wantsLowLatencyActivationScan(true, armedAt, armedAt + 6L * 3_600_000L))
    }

    @Test
    fun noWaitNoLowLatency() {
        assertFalse(OttaiBleManager.wantsLowLatencyActivationScan(false, armedAt, armedAt + 1_000L))
        assertFalse(OttaiBleManager.wantsLowLatencyActivationScan(true, 0L, armedAt))
        assertFalse(OttaiBleManager.wantsLowLatencyActivationScan(true, armedAt, armedAt - 1_000L))
    }
}
