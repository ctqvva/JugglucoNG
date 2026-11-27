package tk.glucodata.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanPasswordFieldTests {

    @Test
    fun lockedWhenSessionRecoveredPasswordAndSameAccount() {
        assertTrue(
            passwordFieldLocked(
                hasSession = true,
                account = "a@b.com",
                storedAccount = "a@b.com",
                password = "secret",
            ),
        )
    }

    @Test
    fun editableWhenStoredPasswordUnreadable() {
        assertFalse(
            passwordFieldLocked(
                hasSession = true,
                account = "a@b.com",
                storedAccount = "a@b.com",
                password = "",
            ),
        )
    }

    @Test
    fun editableWithoutSession() {
        assertFalse(
            passwordFieldLocked(
                hasSession = false,
                account = "a@b.com",
                storedAccount = "a@b.com",
                password = "secret",
            ),
        )
    }

    @Test
    fun editableWhenAccountChanged() {
        assertFalse(
            passwordFieldLocked(
                hasSession = true,
                account = "other@b.com",
                storedAccount = "a@b.com",
                password = "secret",
            ),
        )
    }
}
