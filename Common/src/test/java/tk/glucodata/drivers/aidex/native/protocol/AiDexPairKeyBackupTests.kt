package tk.glucodata.drivers.aidex.native.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
