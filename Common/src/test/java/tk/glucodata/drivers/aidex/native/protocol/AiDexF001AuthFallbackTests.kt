package tk.glucodata.drivers.aidex.native.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.aidex.native.protocol.AiDexParser.compactHex

class AiDexF001AuthFallbackTests {
    private val address = "60:83:DA:15:2F:2D"
    private val advertisedName = "AiDEX F-22222FZXKT"

    @Test
    fun explicitZeroRejection_selectsGenerationQualifiedSerialOnce() {
        val fallback = AiDexF001AuthFallback()

        assertEquals(
            "F22222FZXKT",
            fallback.selectAlternative(
                response = byteArrayOf(0),
                storedSensorId = "X-22222FZXKT",
                address = address,
                advertisedName = advertisedName,
                currentProtocolSerial = "22222FZXKT",
            )
        )
        assertTrue(fallback.attempted)
        assertNull(
            fallback.selectAlternative(
                response = byteArrayOf(0),
                storedSensorId = "X-22222FZXKT",
                address = address,
                advertisedName = advertisedName,
                currentProtocolSerial = "F22222FZXKT",
            )
        )
    }

    @Test
    fun otherResponsesAndUnrelatedAdvertisements_remainFailClosed() {
        val fallback = AiDexF001AuthFallback()

        assertNull(
            fallback.selectAlternative(
                response = byteArrayOf(1),
                storedSensorId = "X-22222FZXKT",
                address = address,
                advertisedName = advertisedName,
                currentProtocolSerial = "22222FZXKT",
            )
        )
        assertNull(
            fallback.selectAlternative(
                response = byteArrayOf(0),
                storedSensorId = "X-2222267V4E",
                address = address,
                advertisedName = advertisedName,
                currentProtocolSerial = "2222267V4E",
            )
        )
        assertFalse(fallback.attempted)
    }

    @Test
    fun selectedAlternative_derivesExpectedDeterministicChallenge() {
        val keyExchange = AiDexKeyExchange.fromExactProtocolSerial("F22222FZXKT")
        assertEquals("F22222FZXKT", keyExchange.bareSerial)
        assertEquals(
            "E5D8665C9DB2D6B389FDC8E53515CD67",
            compactHex(keyExchange.getChallenge()),
        )
    }
}
