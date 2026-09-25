package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the NFC-settings routing: the system NFC page must only open when NFC is relevant.
 *
 * Context: MainActivity.setnfc() used to open ACTION_NFC_SETTINGS on every cold start with NFC
 * off, for every user. BLE-only users (iCan, Sibionics, …) were yanked out of the app to a
 * settings page for hardware they never use (discussion #370). The routing decision is pure so a
 * JVM test can pin it; the sensor probing behind `nfcNeeded` stays on MainActivity.
 */
class NfcSettingsRoutingTests {

    @Test
    fun `primary with active Libre sensor opens settings`() {
        assertTrue(NfcSettingsRouting.shouldOpen(false, true, 0))
    }

    @Test
    fun `explicit NFC flow opens settings even without Libre sensor`() {
        assertTrue(NfcSettingsRouting.shouldOpen(true, false, 0))
    }

    @Test
    fun `BLE-only cold start never opens settings`() {
        assertFalse(NfcSettingsRouting.shouldOpen(false, false, 0))
    }

    @Test
    fun `follower never opens settings even for Libre`() {
        assertFalse(NfcSettingsRouting.shouldOpen(true, true, 1))
        assertFalse(NfcSettingsRouting.shouldOpen(false, true, 2))
    }
}
