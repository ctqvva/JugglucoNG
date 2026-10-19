package tk.glucodata.ui

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnServerInputPolicyTests {
    @Test
    fun asciiCredentialsReserveTheNativeTerminator() {
        for (limit in listOf(
            TurnServerInputPolicy.HOST_BYTES,
            TurnServerInputPolicy.USERNAME_BYTES,
            TurnServerInputPolicy.PASSWORD_BYTES,
        )) {
            assertTrue(TurnServerInputPolicy.fitsNativeBuffer("a".repeat(limit), limit))
            assertFalse(TurnServerInputPolicy.fitsNativeBuffer("a".repeat(limit + 1), limit))
        }
    }

    @Test
    fun unicodeLimitsMatchJniModifiedUtf8RatherThanCharacterCount() {
        for (value in listOf("", "a\u0000b", "имя", "密码", "🔐", "\uD800", "я".repeat(48))) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { it.writeUTF(value) }
            val encodedLength = bytes.size() - 2 // writeUTF prefixes the MUTF-8 byte count.
            for (limit in 0..encodedLength + 1) {
                assertEquals(
                    "Unexpected native buffer fit for length $encodedLength, limit $limit",
                    encodedLength <= limit,
                    TurnServerInputPolicy.fitsNativeBuffer(value, limit),
                )
            }
        }
    }
}
