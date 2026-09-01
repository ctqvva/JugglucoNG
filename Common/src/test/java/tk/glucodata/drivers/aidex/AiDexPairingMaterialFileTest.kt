package tk.glucodata.drivers.aidex

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.R
import tk.glucodata.drivers.aidex.native.ble.aiDexPairingKeyProblemStatusRes

class AiDexPairingMaterialFileTest {
    @Test
    fun portableFileRoundTripsAnyGenerationPrefix() {
        val secret = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 16).toByte() }
        val encoded = AiDexPairingMaterialFile.encode("AiDEX G-22222GZXKT", secret, iv)
        val parsed = AiDexPairingMaterialFile.decode(encoded.orEmpty())

        assertEquals("22222GZXKT", parsed?.serial)
        assertArrayEquals(secret, parsed!!.secret)
        assertArrayEquals(iv, parsed.iv)
    }

    @Test
    fun importAcceptsVendorFieldNamesWithoutAFormatMarker() {
        val secret = ByteArray(16) { 0x31 }
        val iv = ByteArray(16) { 0x42 }
        val json = JSONObject()
            .put("deviceSn", "Q-22222QZXKT")
            .put("publicKey", Base64.getEncoder().encodeToString(secret))
            .put("communicationKey", Base64.getEncoder().encodeToString(iv))
            .toString()

        val parsed = AiDexPairingMaterialFile.decode(json)
        assertEquals("22222QZXKT", parsed?.serial)
        assertArrayEquals(secret, parsed!!.secret)
        assertArrayEquals(iv, parsed.iv)
    }

    @Test
    fun importRejectsIncompleteOrForeignFiles() {
        assertNull(AiDexPairingMaterialFile.decode("{}"))
        assertNull(
            AiDexPairingMaterialFile.decode(
                JSONObject()
                    .put("format", "something-else")
                    .put("sensorSerial", "22222GZXKT")
                    .put("publicKey", Base64.getEncoder().encodeToString(ByteArray(16)))
                    .put("communicationKey", Base64.getEncoder().encodeToString(ByteArray(16)))
                    .toString(),
            ),
        )
    }

    @Test
    fun missingKeyStatusDependsOnAttemptedMaterialNotAdvertisedName() {
        assertEquals(R.string.aidex_key_missing, aiDexPairingKeyProblemStatusRes(false))
        assertEquals(R.string.aidex_key_rejected, aiDexPairingKeyProblemStatusRes(true))
    }
}
