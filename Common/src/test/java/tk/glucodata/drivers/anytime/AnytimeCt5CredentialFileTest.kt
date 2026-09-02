package tk.glucodata.drivers.anytime

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnytimeCt5CredentialFileTest {
    @Test
    fun completeCt5SessionRoundTrips() {
        val encoded = AnytimeCt5CredentialFile.encode(
            sensorId = "D76C7BB368A3",
            address = "d7:6c:7b:b3:68:a3",
            deviceName = "Anytime5252037585",
            cipherKey = 0x48,
            randomB = intArrayOf(0x5F, 0xFB, 0x67, 0x3E),
            temporaryId = "9787",
        )
        val parsed = AnytimeCt5CredentialFile.decode(encoded.orEmpty())

        assertEquals("D76C7BB368A3", parsed?.sensorId)
        assertEquals("D7:6C:7B:B3:68:A3", parsed?.address)
        assertEquals("Anytime5252037585", parsed?.deviceName)
        assertEquals(0x48, parsed?.cipherKey)
        assertArrayEquals(intArrayOf(0x5F, 0xFB, 0x67, 0x3E), parsed?.randomB)
        assertEquals("9787", parsed?.temporaryId)
    }

    @Test
    fun importRejectsForeignIncompleteOrMismatchedIdentity() {
        assertNull(AnytimeCt5CredentialFile.decode("{}"))
        assertNull(
            AnytimeCt5CredentialFile.decode(
                JSONObject()
                    .put("format", "juggluco-anytime-ct5-credentials")
                    .put("version", 1)
                    .put("sensorId", "D76C7BB368A3")
                    .put("address", "D7:6C:7B:B3:68:A4")
                    .put("deviceName", "Anytime5252037585")
                    .put("cipherKey", 0x48)
                    .put("randomB", "5FFB673E")
                    .put("temporaryId", "9787")
                    .toString(),
            ),
        )
        assertNull(
            AnytimeCt5CredentialFile.encode(
                sensorId = "D76C7BB368A3",
                address = "D7:6C:7B:B3:68:A3",
                deviceName = "SN16-not-ct5",
                cipherKey = 0x48,
                randomB = intArrayOf(0x5F, 0xFB, 0x67, 0x3E),
                temporaryId = "9787",
            ),
        )
    }

    @Test
    fun importRejectsPartialAuthenticationMaterial() {
        assertNull(
            AnytimeCt5CredentialFile.encode(
                sensorId = "D76C7BB368A3",
                address = "D7:6C:7B:B3:68:A3",
                deviceName = "Anytime5252037585",
                cipherKey = -1,
                randomB = intArrayOf(0x5F, 0xFB, 0x67, 0x3E),
                temporaryId = "9787",
            ),
        )
        assertNull(
            AnytimeCt5CredentialFile.encode(
                sensorId = "D76C7BB368A3",
                address = "D7:6C:7B:B3:68:A3",
                deviceName = "Anytime5252037585",
                cipherKey = 0x48,
                randomB = intArrayOf(0x5F, 0xFB, 0x67),
                temporaryId = "9787",
            ),
        )
        assertNull(
            AnytimeCt5CredentialFile.encode(
                sensorId = "D76C7BB368A3",
                address = "D7:6C:7B:B3:68:A3",
                deviceName = "Anytime5252037585",
                cipherKey = 0x48,
                randomB = intArrayOf(0x5F, 0xFB, 0x67, 0x3E),
                temporaryId = "79286",
            ),
        )
    }
}
