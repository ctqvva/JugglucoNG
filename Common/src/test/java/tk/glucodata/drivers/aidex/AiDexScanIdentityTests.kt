package tk.glucodata.drivers.aidex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AiDexScanIdentityTests {

    @Test
    fun normalizeSerial_acceptsPrefixedAndFamilyNames() {
        assertEquals("X-2222267V4E", AiDexScanIdentity.normalizeSerial("X-2222267V4E"))
        assertEquals("X-2222267V4E", AiDexScanIdentity.normalizeSerial("Vista-2222267V4E"))
    }

    @Test
    fun normalizeSerial_acceptsAnyGenerationPrefixTheBindingPathAccepts() {
        // The scan list must recognise every serial AiDexSerialIdentity canonicalises, or a sensor
        // of that generation is listed but not selectable.
        for (name in listOf("F-2222267V4E", "G-2222267V4E", "Q-2222267V4E")) {
            assertEquals(AiDexSerialIdentity.canonicalFromAdvertisement(name), AiDexScanIdentity.normalizeSerial(name))
            assertEquals("X-2222267V4E", AiDexScanIdentity.normalizeSerial(name))
            assertTrue(AiDexScanIdentity.canBind(AiDexScanIdentity.normalizeSerial(name), "AA:BB:CC:DD:EE:FF"))
        }
        // A widened prefix still may not bind a serial that only spells its own radio.
        assertEquals("X-1B2C3D4E5F67", AiDexScanIdentity.normalizeSerial("A-1B2C3D4E5F67"))
        assertFalse(AiDexScanIdentity.canBind("X-1B2C3D4E5F67", "1B:2C:3D:4E:5F:67"))
        assertTrue(AiDexScanIdentity.canBind("X-1B2C3D4E5F67", "AA:BB:CC:DD:EE:FF"))

        // Dash-less and brand-prefixed names keep the identity they had before this change:
        // re-reading one of them would strand an already-paired sensor under a second serial.
        assertEquals("X-2222267V4E", AiDexScanIdentity.normalizeSerial("X2222267V4E"))
        assertEquals("X-2222267V4E", AiDexScanIdentity.normalizeSerial("F-X2222267V4E"))

        // An over-long body never yields a serial bareSerial cannot strip back. Only a later X
        // re-anchors find(), so a run of digits and A-F is refused outright, while one holding an X
        // gives up its tail. Both are pinned, and so is the accepted edge of the bound: tightening
        // any of it with a lookbehind would re-identify names like LUMIX2222267V4E.
        assertNull(AiDexScanIdentity.normalizeSerial("AiDEX X-0123456789ABCDEF"))
        assertNull(AiDexScanIdentity.normalizeSerial("X-0123456789ABCDEFB22222222"))
        assertEquals("X-22222222", AiDexScanIdentity.normalizeSerial("X-0123456789ABCDEFX22222222"))
        assertEquals("X-0123456789ABCD", AiDexScanIdentity.normalizeSerial("X0123456789ABCD"))
        assertEquals("X-2222267V4E", AiDexScanIdentity.normalizeSerial("LUMIX2222267V4E"))

        // A separator the canonical parser refuses still has to resolve - brand-prefixed names
        // with '_' and '-' are what AIDEX_TITLE_PREFIX exists to strip.
        assertEquals("X-2222267V4E", AiDexScanIdentity.normalizeSerial("AiDEX_X-2222267V4E"))
        assertEquals("X-2222267V4E", AiDexScanIdentity.normalizeSerial("GlucoRx-X-2222267V4E"))

        // Widening the prefix must not swallow another driver's bare id.
        assertNull(AiDexScanIdentity.normalizeSerial("C2A1B3D4E5F6"))
    }

    @Test
    fun detectCandidate_showsTheNameThatParsesAsASerial() {
        val detected = AiDexScanIdentity.detectCandidate(
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = null,
            scanRecordName = "AiDEX",
            scanRecordBytes = null,
            advertisedServiceUuids = null,
        )
        assertEquals("AiDEX", detected.displayName)

        val withSerial = AiDexScanIdentity.detectCandidate(
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = "F-2222267V4E",
            scanRecordName = "AiDEX",
            scanRecordBytes = null,
            advertisedServiceUuids = null,
        )
        assertEquals("F-2222267V4E", withSerial.displayName)
        assertEquals("X-2222267V4E", withSerial.serial)
    }

    @Test
    fun detectCandidate_nameless181fDoesNotBindMacAsSerial() {
        val detected = AiDexScanIdentity.detectCandidate(
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = null,
            scanRecordName = null,
            scanRecordBytes = null,
            advertisedServiceUuids = listOf(
                UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
            ),
        )
        assertNull(detected.serial)
        assertTrue(detected.isLikelyAiDex)
        assertFalse(AiDexScanIdentity.canBind(detected.serial, "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun isMacFallbackSerial_onlyWhenTheSerialSpellsItsOwnAddress() {
        assertTrue(AiDexScanIdentity.isMacFallbackSerial("X-AABBCCDDEEFF", "AA:BB:CC:DD:EE:FF"))
        // Any letter, any case — a future AiDex fallback need not start with X.
        assertTrue(AiDexScanIdentity.isMacFallbackSerial("l-aabbccddeeff", "AA:BB:CC:DD:EE:FF"))
        // A real serial that happens to be 12 hex belongs to a different radio.
        assertFalse(AiDexScanIdentity.isMacFallbackSerial("X-AABBCCDDEEFF", "11:22:33:44:55:66"))
        assertFalse(AiDexScanIdentity.isMacFallbackSerial("X-2222267V4E", "AA:BB:CC:DD:EE:FF"))
        assertFalse(AiDexScanIdentity.isMacFallbackSerial("X-AABBCCDDEEFF", null))
        assertFalse(AiDexScanIdentity.isMacFallbackSerial(null, "AA:BB:CC:DD:EE:FF"))
        assertFalse(AiDexScanIdentity.canBind("X-AABBCCDDEEFF", "AA:BB:CC:DD:EE:FF"))
        assertTrue(AiDexScanIdentity.canBind("X-AABBCCDDEEFF", "11:22:33:44:55:66"))
        assertTrue(AiDexScanIdentity.canBind("X-2222267V4E", "AA:BB:CC:DD:EE:FF"))
    }

    /**
     * Regression: other drivers key their sensors by the bare 12-hex BLE MAC (Ottai always,
     * Anytime and MQ when the id came from the address). The AiDex leftover check ran on every
     * id in SensorBluetooth, so those sensors were skipped at restore and torn down on each
     * device sync. A bare MAC has no letter prefix and is never an AiDex leftover.
     */
    @Test
    fun isMacFallbackSerial_neverMatchesAnotherDriversBareMacId() {
        assertFalse(AiDexScanIdentity.isMacFallbackSerial("6CA04230E260", "6C:A0:42:30:E2:60"))
        assertFalse(AiDexScanIdentity.isMacFallbackSerial("AABBCCDDEEFF", "AA:BB:CC:DD:EE:FF"))
        assertTrue(AiDexScanIdentity.canBind("6CA04230E260", "6C:A0:42:30:E2:60"))
    }

    @Test
    fun detectCandidate_prefersAdvertisedSerialOverCachedDeviceName() {
        val advertised = "X-SENSORB12"
        val bytes = byteArrayOf(
            (1 + advertised.length).toByte(),
            0x09,
        ) + advertised.toByteArray(Charsets.UTF_8)
        val detected = AiDexScanIdentity.detectCandidate(
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = "X-SENSORA12",
            scanRecordName = null,
            scanRecordBytes = bytes,
            advertisedServiceUuids = listOf(AiDexScanIdentity.CGM_SERVICE_UUID),
        )
        assertEquals("X-SENSORB12", detected.serial)
        assertTrue(detected.serialFromAdvert)
    }

    @Test
    fun detectCandidate_cacheOnlySerialIsNotFromAdvert() {
        val detected = AiDexScanIdentity.detectCandidate(
            address = "AA:BB:CC:DD:EE:FF",
            deviceName = "X-SENSORA12",
            scanRecordName = null,
            scanRecordBytes = null,
            advertisedServiceUuids = listOf(AiDexScanIdentity.CGM_SERVICE_UUID),
        )
        assertEquals("X-SENSORA12", detected.serial)
        assertFalse(detected.serialFromAdvert)
    }

    @Test
    fun shouldReplaceScanSerial_cacheCannotOverwriteAdvert() {
        assertFalse(
            AiDexScanIdentity.shouldReplaceScanSerial(
                existingSerial = "X-SENSORB12",
                existingFromAdvert = true,
                candidateSerial = "X-SENSORA12",
                candidateFromAdvert = false,
                address = "AA:BB:CC:DD:EE:FF",
            )
        )
        assertTrue(
            AiDexScanIdentity.shouldReplaceScanSerial(
                existingSerial = "X-SENSORA12",
                existingFromAdvert = false,
                candidateSerial = "X-SENSORB12",
                candidateFromAdvert = true,
                address = "AA:BB:CC:DD:EE:FF",
            )
        )
        // An advert that only spells the radio's own MAC must not replace a real serial.
        assertFalse(
            AiDexScanIdentity.shouldReplaceScanSerial(
                existingSerial = "X-SENSORA12",
                existingFromAdvert = false,
                candidateSerial = "X-AABBCCDDEEFF",
                candidateFromAdvert = true,
                address = "AA:BB:CC:DD:EE:FF",
            )
        )
        // The same serial seen on a different radio is a real serial and is adopted.
        assertTrue(
            AiDexScanIdentity.shouldReplaceScanSerial(
                existingSerial = "X-SENSORA12",
                existingFromAdvert = false,
                candidateSerial = "X-AABBCCDDEEFF",
                candidateFromAdvert = true,
                address = "11:22:33:44:55:66",
            )
        )
    }

    @Test
    fun persistAddressOccupiedByOtherRealSerial_refusesSecondRealSn() {
        // A real serial with a 12-hex body on a different radio is still a real owner.
        assertTrue(
            AiDexScanIdentity.persistAddressOccupiedByOtherRealSerial(
                existingSerial = "X-AABBCCDDEEFF",
                existingAddress = "11:22:33:44:55:66",
                newSerial = "X-SENSORB12",
                newAddress = "11:22:33:44:55:66",
            )
        )
        assertTrue(
            AiDexScanIdentity.persistAddressOccupiedByOtherRealSerial(
                existingSerial = "X-SENSORA12",
                existingAddress = "AA:BB:CC:DD:EE:FF",
                newSerial = "X-SENSORB12",
                newAddress = "AA:BB:CC:DD:EE:FF",
            )
        )
        assertFalse(
            AiDexScanIdentity.persistAddressOccupiedByOtherRealSerial(
                existingSerial = "X-AABBCCDDEEFF",
                existingAddress = "AA:BB:CC:DD:EE:FF",
                newSerial = "X-SENSORB12",
                newAddress = "AA:BB:CC:DD:EE:FF",
            )
        )
        assertFalse(
            AiDexScanIdentity.persistAddressOccupiedByOtherRealSerial(
                existingSerial = "X-SENSORA12",
                existingAddress = "AA:BB:CC:DD:EE:FF",
                newSerial = "X-SENSORA12",
                newAddress = "AA:BB:CC:DD:EE:FF",
            )
        )
        assertFalse(
            AiDexScanIdentity.persistAddressOccupiedByOtherRealSerial(
                existingSerial = "SENSORA12",
                existingAddress = "AA:BB:CC:DD:EE:FF",
                newSerial = "X-SENSORA12",
                newAddress = "AA:BB:CC:DD:EE:FF",
            )
        )
    }
}
