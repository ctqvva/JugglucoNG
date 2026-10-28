package tk.glucodata

import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomManualPairingTests {
    @Test
    fun `pairing code is exactly four ascii digits`() {
        assertTrue(DexcomManualPairing.isValidPairingCode("1234"))
        assertTrue(DexcomManualPairing.isValidPairingCode(" 0012 "))

        listOf(null, "", "123", "12345", "12 34", "12-34", "12A4", "１２３４").forEach { code ->
            assertFalse(code.orEmpty(), DexcomManualPairing.isValidPairingCode(code))
            assertNull(DexcomManualPairing.createScanPayload(code, "M00000000001"))
        }
    }

    @Test
    fun `manual payload matches native dexcom scan contract`() {
        val payload = DexcomManualPairing.createScanPayload("0012", "M00000KF12OI")

        requireNotNull(payload)
        assertEquals(55, payload.length)
        assertEquals("JUGGLUCO-MANUAL-G7:", payload.take(19))
        assertEquals("M00000KF12OI", payload.substring(19, 31))
        assertEquals("2400012", payload.takeLast(7))

        // makeDexComSensorindex() uses bytes 19..30 plus the PIN as its 16-byte identity.
        assertEquals("M00000KF12OI0012", payload.substring(19, 31) + payload.takeLast(4))
    }

    @Test
    fun `lifetime selection preserves manual identity and legacy default`() {
        val old = DexcomManualPairing.createScanPayload("0012", "M00000KF12OI")!!
        val ten = DexcomManualPairing.createScanPayload("0012", "M00000KF12OI", 10)!!
        val fifteen = DexcomManualPairing.createScanPayload("0012", "M00000KF12OI", 15)!!
        assertEquals(old, ten)
        assertEquals("00000000000000000", ten.substring(31, 48))
        assertEquals("15000000000000000", fifteen.substring(31, 48))
        assertEquals(55, fifteen.length)
        assertEquals(ten.substring(19, 31) + ten.takeLast(4),
            fifteen.substring(19, 31) + fifteen.takeLast(4))
        listOf(0, 12, 16).forEach {
            assertNull(DexcomManualPairing.createScanPayload("0012", "M00000KF12OI", it))
        }
    }

    @Test
    fun `new sensor identities use random input rather than wall clock time`() {
        val first = DexcomManualPairing.createScanPayload(
            "1234",
            DexcomManualPairing.createSensorId(Random(10L)),
        )
        val second = DexcomManualPairing.createScanPayload(
            "1234",
            DexcomManualPairing.createSensorId(Random(11L)),
        )

        requireNotNull(first)
        requireNotNull(second)
        assertNotEquals(first.substring(19, 31), second.substring(19, 31))
        assertEquals(first.takeLast(7), second.takeLast(7))
    }

    @Test
    fun `manual retry lookup never reuses an established same pin sensor`() {
        val nativeSource = File(projectRoot(), "Common/src/main/cpp/sensoren.hpp").readText()
        val lookup = nativeSource.substring(
            nativeSource.indexOf("sensor *findUnboundManualDexcom"),
            nativeSource.indexOf("makeDexComSensorindex", nativeSource.indexOf("sensor *findUnboundManualDexcom")),
        )
        val normalizedLookup = lookup.replace(Regex("\\s+"), " ")

        assertTrue(lookup.contains("candidate->finished"))
        assertTrue(lookup.contains("data->isDexcom()"))
        assertTrue(normalizedLookup.contains("info->pollcount || info->scancount || info->endhistory"))
        assertTrue(lookup.contains("info->DexDeviceName[0]"))
        assertTrue(lookup.contains("info->sharedKey != std::array<uint8_t, 16>{}"))
        assertTrue(lookup.contains("manualDexcomPrefix"))
        assertTrue(lookup.contains("data->getDexPin()"))
        assertTrue(lookup.contains("memcmp(storedPin.data(), pin, storedPin.size())"))
    }

    private fun projectRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "Common/src/main/cpp/sensoren.hpp").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("project root not found")
    }
}
