package tk.glucodata.share.ican

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordHashTests {

    @Test
    fun argon2idHexMatchesVendorVectors() {
        assertEquals(
            "0c8e25317e780ddaa07ae4cb46d395c56681fb8e9ead9e39252e558ac59486b8",
            ICanPasswordHash.hash("password123"),
        )
        assertEquals(
            "c3e136c13f2b9edb079d7224cd1dae424480176dbe53023ea486a1f10c26a387",
            ICanPasswordHash.hash("TestPass1!"),
        )
    }

    @Test
    fun hashIsLowercaseHexOf32Bytes() {
        val value = ICanPasswordHash.hash("anotherPassword1")
        assertEquals(64, value.length)
        assertEquals(value, value.lowercase())
        assertEquals(true, value.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
