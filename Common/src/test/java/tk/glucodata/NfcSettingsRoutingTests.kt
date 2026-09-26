package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the NFC-settings routing: a passive check never launches another app.
 *
 * Context: MainActivity.setnfc() used to open ACTION_NFC_SETTINGS on every cold start with NFC
 * off, for every user. BLE-only users (iCan, Sibionics, …) were yanked out of the app to a
 * settings page for hardware they never use (discussion #370). Only an explicit NFC flow on a
 * primary may open the page; the one-shot nag state lives in NfcPromptState.
 */
class NfcSettingsRoutingTests {

    @Test
    fun `explicit NFC flow on primary opens settings`() {
        assertTrue(NfcSettingsRouting.shouldOpen(true, 0))
    }

    @Test
    fun `passive check never opens settings`() {
        assertFalse(NfcSettingsRouting.shouldOpen(false, 0))
    }

    @Test
    fun `follower never opens settings`() {
        assertFalse(NfcSettingsRouting.shouldOpen(true, 1))
        assertFalse(NfcSettingsRouting.shouldOpen(false, 2))
    }

    @Test
    fun `NFC stack is touched for explicit flow, Libre sensor or pens`() {
        assertTrue(NfcSettingsRouting.needsNfcStack(true, false, false))
        assertTrue(NfcSettingsRouting.needsNfcStack(false, true, false))
        assertTrue(NfcSettingsRouting.needsNfcStack(false, false, true))
    }

    @Test
    fun `NFC stack stays untouched with no NFC consumer`() {
        assertFalse(NfcSettingsRouting.needsNfcStack(false, false, false))
    }
}
