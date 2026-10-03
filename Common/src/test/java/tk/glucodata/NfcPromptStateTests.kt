package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the NFC-disabled prompt state machine (NfcPromptState), in particular the bug found in
 * review of #441: a passive check used to consume the one-shot flag, so a later explicit Libre
 * scan silently did nothing. The flag now controls only the automatic nag.
 */
class NfcPromptStateTests {

    @Test
    fun `BLE-only passive check is silent and preserves the nag`() {
        val state = NfcPromptState()
        val passive = state.decide(false, false, 0)
        assertFalse(passive.toast)
        assertFalse(passive.openSettings)
        // The nag was not consumed: a later explicit action still fires fully.
        val explicit = state.decide(true, false, 0)
        assertTrue(explicit.toast)
        assertTrue(explicit.openSettings)
    }

    @Test
    fun `Libre passive check toasts once and never opens settings`() {
        val state = NfcPromptState()
        val first = state.decide(false, true, 0)
        assertTrue(first.toast)
        assertFalse(first.openSettings)
        val second = state.decide(false, true, 0)
        assertFalse(second.toast)
        assertFalse(second.openSettings)
    }

    @Test
    fun `explicit action on primary always toasts and opens`() {
        val state = NfcPromptState()
        repeat(2) {
            val decision = state.decide(true, false, 0)
            assertTrue(decision.toast)
            assertTrue(decision.openSettings)
        }
    }

    @Test
    fun `follower never opens settings`() {
        val state = NfcPromptState()
        val explicit = state.decide(true, true, 1)
        assertTrue(explicit.toast)
        assertFalse(explicit.openSettings)
    }
}
