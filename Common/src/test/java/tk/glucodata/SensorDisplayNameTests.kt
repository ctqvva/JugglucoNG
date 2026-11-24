package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared sensor display-name resolution and WearPrefsSync map codec.
 *
 * Covers every source family the phone names differently from its raw record id:
 * native BLE (Dexcom, Libre, Accu-Chek, Air), managed BLE (AiDex, Anytime, iCan,
 * Medtrum, MQ, Ottai, Sibionics), cloud followers (Nightscout, API, LumiFlex), and
 * Clone fallbacks.
 */
class SensorDisplayNameTests {
    private val fullDexcom = "1111122222333331"
    private val shortDexcom = "22222333331"
    private val watchListingDexcom = "333331"

    @Test
    fun managedDisplayNameTakesPrecedenceOverCallbackAndMirrored() {
        val resolved = SensorDisplayName.resolveName(
            sensorId = "SIBI:P225043JMV",
            managedDisplayName = "Sibionics 2 JMV",
            callbackDisplayName = "AA:BB:CC:DD:EE:FF",
            mirroredNames = mapOf("SIBI:P225043JMV" to "Old Name"),
            matchesId = SensorDisplayName::nativeAliasMatches,
        )
        assertEquals("Sibionics 2 JMV", resolved)
    }

    @Test
    fun mirroredNameBeatsPreScanMacFromManagedDriver() {
        // Anytime/MQ/iCan fall back to callback.mygetDeviceName() -> MAC before first scan.
        val resolved = SensorDisplayName.resolveName(
            sensorId = "ANY-123456",
            managedDisplayName = "AA:BB:CC:DD:EE:FF",
            callbackDisplayName = "AA:BB:CC:DD:EE:FF",
            mirroredNames = mapOf("ANY-123456" to "Anytime CT3"),
            matchesId = SensorDisplayName::nativeAliasMatches,
        )
        assertEquals("Anytime CT3", resolved)

        // With no mirrored name, the managed MAC fallback is still preserved like on the phone.
        val fallback = SensorDisplayName.resolveName(
            sensorId = "ANY-123456",
            managedDisplayName = "AA:BB:CC:DD:EE:FF",
            callbackDisplayName = null,
            mirroredNames = emptyMap(),
            matchesId = SensorDisplayName::nativeAliasMatches,
        )
        assertEquals("AA:BB:CC:DD:EE:FF", fallback)
    }

    @Test
    fun liveBluetoothDeviceNameTakesPrecedenceOverMirrored() {
        val resolved = SensorDisplayName.resolveName(
            sensorId = shortDexcom,
            managedDisplayName = null,
            callbackDisplayName = "DXCMAA",
            mirroredNames = mapOf(fullDexcom to "DXCMBB"),
            matchesId = SensorDisplayName::nativeAliasMatches,
        )
        assertEquals("DXCMAA", resolved)
    }

    @Test
    fun mirroredNameBeatsPreScanMacFallbackAndMatchesAcross16And11CharNativeAliases() {
        // Phone stores the 16-char native Dexcom name; watch holds the 11-char short alias
        // and its callback only knows the MAC before the first scan.
        val resolved = SensorDisplayName.resolveName(
            sensorId = shortDexcom,
            managedDisplayName = null,
            callbackDisplayName = "AA:BB:CC:DD:EE:FF",
            mirroredNames = mapOf(fullDexcom to "DXCMAA"),
        )
        assertEquals("DXCMAA", resolved)

        // Reverse direction: watch holds full 16-char record, phone sent 11-char alias.
        val reverse = SensorDisplayName.resolveName(
            sensorId = fullDexcom,
            managedDisplayName = null,
            callbackDisplayName = null,
            mirroredNames = mapOf(shortDexcom to "DXCMAA"),
        )
        assertEquals("DXCMAA", reverse)
    }

    @Test
    fun mirroredNameMatchesUnambiguous6CharWatchNativeListing() {
        assertEquals(
            "DXCMAA",
            SensorDisplayName.resolveName(
                sensorId = watchListingDexcom,
                managedDisplayName = null,
                callbackDisplayName = null,
                mirroredNames = mapOf(fullDexcom to "DXCMAA"),
                matchesId = SensorDisplayName::nativeAliasMatches,
            ),
        )
        assertEquals(
            "DXCMAA",
            SensorDisplayName.resolveName(
                sensorId = watchListingDexcom,
                managedDisplayName = null,
                callbackDisplayName = null,
                mirroredNames = mapOf(shortDexcom to "DXCMAA"),
                matchesId = SensorDisplayName::nativeAliasMatches,
            ),
        )
        // Ambiguous 6-char suffix shared by two mirrored sensors must not pick arbitrarily.
        assertEquals(
            watchListingDexcom,
            SensorDisplayName.resolveName(
                sensorId = watchListingDexcom,
                managedDisplayName = null,
                callbackDisplayName = null,
                mirroredNames = mapOf(
                    "1111122222333331" to "DXCMAA",
                    "9999988888333331" to "DXCMBB",
                ),
                matchesId = SensorDisplayName::nativeAliasMatches,
            ),
        )
    }

    @Test
    fun mirroredCloudFollowerNameMatchesShortNativeAliasOnWatch() {
        val resolved = SensorDisplayName.resolveName(
            sensorId = "073E464C8CB",
            managedDisplayName = null,
            callbackDisplayName = null,
            mirroredNames = mapOf("NSF-3073E464C8CB" to "Nightscout follower"),
            matchesId = SensorDisplayName::nativeAliasMatches,
        )
        assertEquals("Nightscout follower", resolved)
    }

    @Test
    fun unusableCallbackNamesFallBackToMirroredOrPrefixStrippedSerial() {
        assertEquals(
            "Medtrum TouchCare S9 MD3660",
            SensorDisplayName.resolveName(
                sensorId = "MT-123456",
                managedDisplayName = "?",
                callbackDisplayName = "?",
                mirroredNames = mapOf("MT-123456" to "Medtrum TouchCare S9 MD3660"),
                matchesId = SensorDisplayName::nativeAliasMatches,
            ),
        )
        assertEquals(
            "P225043JMV",
            SensorDisplayName.resolveName(
                sensorId = "SIBI:P225043JMV",
                managedDisplayName = " ",
                callbackDisplayName = "?",
                mirroredNames = emptyMap(),
                matchesId = SensorDisplayName::nativeAliasMatches,
            ),
        )
    }

    @Test
    fun macFallbackIsPreservedWhenNoMirroredNameExists() {
        val resolved = SensorDisplayName.resolveName(
            sensorId = shortDexcom,
            managedDisplayName = null,
            callbackDisplayName = "AA:BB:CC:DD:EE:FF",
            mirroredNames = emptyMap(),
            matchesId = SensorDisplayName::nativeAliasMatches,
        )
        assertEquals("AA:BB:CC:DD:EE:FF", resolved)
    }

    @Test
    fun isMacAddressDistinguishesAddressesFromSensorNames() {
        assertTrue(SensorDisplayName.isMacAddress("AA:BB:CC:DD:EE:FF"))
        assertTrue(SensorDisplayName.isMacAddress("01:23:45:67:89:ab"))
        assertFalse(SensorDisplayName.isMacAddress("DXCMAA"))
        assertFalse(SensorDisplayName.isMacAddress("SIBI:P225043JMV"))
        assertFalse(SensorDisplayName.isMacAddress("?"))
        assertFalse(SensorDisplayName.isMacAddress(null))
    }

    @Test
    fun storedMapRoundTripsThroughWearPrefsLineEscaping() {
        val original = linkedMapOf(
            fullDexcom to "DXCMAA",
            "NSF-3073E464C8CB" to "Nightscout follower",
            "MT-00112233" to "Medtrum TouchCare S9 MD3660",
            "CUSTOM-1" to "Pipe | & \\ Backslash · Датчик",
        )
        val stored = SensorDisplayName.encodeStoredMap(original)
        val wire = WearPrefsSync.escapeLine(stored)
        assertFalse("wire line must stay single-line", wire.contains('\n'))
        val restored = SensorDisplayName.decodeStoredMap(WearPrefsSync.unescapeLine(wire))
        assertEquals(original, restored)
    }

    @Test
    fun encodeStoredMapDropsInvalidKeysAndValues() {
        val encoded = SensorDisplayName.encodeStoredMap(
            linkedMapOf(
                "bad|key" to "DXCMAA",
                "?" to "DXCMBB",
                "good" to "?",
                "ok" to "Pipe | Allowed",
            ),
        )
        assertEquals("ok|Pipe | Allowed", encoded)
        assertEquals(mapOf("ok" to "Pipe | Allowed"), SensorDisplayName.decodeStoredMap(encoded))
    }

    @Test
    fun decodeStoredMapIgnoresMalformedOrUnusableEntries() {
        val raw = listOf(
            "valid|DXCMAA",
            "no-separator",
            "|missing-key",
            "missing-name|",
            "sentinel|?",
            "control|bad\u0007name",
            "X-222227KT3T|X-222227KT3T",
        ).joinToString("\n")
        assertEquals(
            mapOf("valid" to "DXCMAA", "X-222227KT3T" to "X-222227KT3T"),
            SensorDisplayName.decodeStoredMap(raw),
        )
    }
}
