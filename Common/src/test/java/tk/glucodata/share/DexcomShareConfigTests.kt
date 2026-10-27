package tk.glucodata.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a Test's verdict still describes the saved settings: the on/off
 * switch is irrelevant, the login target is not. It exists so that saving unchanged settings while
 * a Test is running cannot discard a rejected password and let the next flush retry it.
 */
class DexcomShareConfigTests {

    private val base = DexcomShareSettings.Config(
        enabled = true,
        account = "someone@example.com",
        password = "secret",
        region = DexcomShareRegion.OUS,
        serial = "SM00000000"
    )

    @Test
    fun theOnOffSwitchDoesNotChangeTheLoginTarget() {
        assertTrue(base.copy(enabled = false).sameLoginTarget(base))
        assertTrue(base.sameLoginTarget(base.copy(enabled = false)))
    }

    @Test
    fun aChangedAccountOrPasswordIsAnotherLoginTarget() {
        assertFalse(base.copy(account = "other@example.com").sameLoginTarget(base))
        assertFalse(base.copy(password = "new-secret").sameLoginTarget(base))
    }

    @Test
    fun aChangedRegionOrReceiverIsAnotherLoginTarget() {
        assertFalse(base.copy(region = DexcomShareRegion.US).sameLoginTarget(base))
        assertFalse(base.copy(serial = "SM11111111").sameLoginTarget(base))
    }

    @Test
    fun anUnchangedConfigurationIsTheSameLoginTarget() {
        assertTrue(base.copy().sameLoginTarget(base))
    }

    @Test
    fun theAccountLosesStraySpaces() {
        // A keyboard suggestion leaves a trailing space; Dexcom answers it with "invalid password".
        assertEquals("kid_username", DexcomShareSettings.normalizeAccount(" kid_username \n"))
        assertEquals("someone@example.com", DexcomShareSettings.normalizeAccount("someone@example.com"))
    }
}
