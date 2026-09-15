package tk.glucodata.drivers.aidex.native.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class AiDexPairKeyBackupTests {
    private val pairKey = ByteArray(AiDexPairKeyBackup.PAIR_KEY_BYTES) { index ->
        (index * 11 + 3).toByte()
    }

    @Test
    fun roundTripCanonicalizesSerialAndPreservesKey() {
        val payload = AiDexPairKeyBackup.encode("AiDEX X-2222267v4e", pairKey)
        val restored = AiDexPairKeyBackup.decode(payload!!)

        assertEquals("2222267V4E", restored?.bareSerial)
        assertArrayEquals(pairKey, restored?.pairKey)
        assertTrue(payload.contains("pair_key="))
    }

    @Test
    fun tamperedCredentialIsRejected() {
        val payload = AiDexPairKeyBackup.encode("X-2222267V4E", pairKey)!!
        val tampered = payload.replace("pair_key=03", "pair_key=04")

        assertNull(AiDexPairKeyBackup.decode(tampered))
    }

    @Test
    fun malformedOrWrongLengthCredentialIsRejected() {
        assertNull(AiDexPairKeyBackup.encode("X-2222267V4E", ByteArray(15)))
        assertNull(AiDexPairKeyBackup.decode("not an AiDex key backup"))
    }

    @Test
    fun backupWithoutASensorIsRejectedEvenWhenItsChecksumMatches() {
        // A hand-built file with an empty sensor field and a checksum computed over that
        // empty field. The vault would otherwise file this key under a blank serial.
        val keyHex = pairKey.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("JUGGLUCONG_AIDEX_PAIR_KEY\n1\n\n$keyHex".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val serialless = "JUGGLUCONG_AIDEX_PAIR_KEY\nversion=1\nsensor=\npair_key=$keyHex\nsha256=$digest\n"

        assertNull(AiDexPairKeyBackup.decode(serialless))
    }
}
