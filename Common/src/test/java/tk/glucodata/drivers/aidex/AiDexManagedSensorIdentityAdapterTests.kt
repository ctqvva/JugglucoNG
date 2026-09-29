package tk.glucodata.drivers.aidex

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexManagedSensorIdentityAdapterTests {

    @Test
    fun advertisedGenerationName_usesCanonicalXIdentityRegardlessOfLetter() {
        assertEquals(
            "X-22222FZXKT",
            AiDexSerialIdentity.canonicalFromAdvertisement("AiDEX F-22222FZXKT")
        )
        assertEquals("X-22222FZXKT", AiDexSerialIdentity.canonicalFromAdvertisement("F-22222FZXKT"))
        assertEquals("X-22222GZXKT", AiDexSerialIdentity.canonicalFromAdvertisement("AiDEX G-22222GZXKT"))
        assertEquals("X-22222QZXKT", AiDexSerialIdentity.canonicalFromAdvertisement("Q-22222QZXKT"))
        assertEquals(
            "X-2222267V4E",
            AiDexSerialIdentity.canonicalFromAdvertisement("AiDEX sensor X-2222267V4E")
        )
    }

    @Test
    fun bareSerial_stripsAnyAdvertisedGenerationPrefix() {
        assertEquals("22222FZXKT", AiDexSerialIdentity.bareSerial("AiDEX F-22222FZXKT"))
        assertEquals("22222GZXKT", AiDexSerialIdentity.bareSerial("G-22222GZXKT"))
        assertEquals("22222QZXKT", AiDexSerialIdentity.bareSerial("AiDEX Q-22222QZXKT"))
        assertEquals("ABCDEFGHJK", AiDexSerialIdentity.bareSerial("ABCDEFGHJK"))
    }

    @Test
    fun macFallback_usesAdvertisedSerialForProtocolOnlyWhenIdentityMatchesAddress() {
        assertEquals(
            "22222FZXKT",
            AiDexSerialIdentity.advertisedProtocolSerialForMacFallback(
                storedSensorId = "X-6083DA152F2D",
                address = "60:83:DA:15:2F:2D",
                advertisedName = "AiDEX F-22222FZXKT",
            )
        )
        assertNull(
            AiDexSerialIdentity.advertisedProtocolSerialForMacFallback(
                storedSensorId = "X-2222267V4E",
                address = "60:83:DA:15:2F:2D",
                advertisedName = "AiDEX F-22222FZXKT",
            )
        )
        assertEquals(
            "2222267V4E",
            AiDexSerialIdentity.advertisedProtocolSerialForMacFallback(
                storedSensorId = "X-6083DA152F2D",
                address = "60:83:DA:15:2F:2D",
                advertisedName = "X2222267V4E",
            )
        )
    }

    @Test
    fun nativeAlias_stripsManagedPrefix() {
        assertEquals("222227JR7C", AiDexManagedSensorIdentityAdapter.nativeAlias("X-222227JR7C"))
    }

    @Test
    fun resolveCanonicalSensorId_normalizesManagedIdsToUppercase() {
        assertEquals(
            "X-222227JR7C",
            AiDexManagedSensorIdentityAdapter.resolveCanonicalSensorId("x-222227jr7c")
        )
    }

    @Test
    fun matchesCallbackId_acceptsNativeAliasForManagedCallback() {
        assertTrue(
            AiDexManagedSensorIdentityAdapter.matchesCallbackId("X-222227JR7C", "222227JR7C")
        )
    }

    @Test
    fun parsePersistedEntry_keepsAddressHalf() {
        val parsed = AiDexManagedSensorIdentityAdapter.parsePersistedEntry(
            "X-2222267V4E|AA:BB:CC:DD:EE:FF"
        )
        assertEquals("X-2222267V4E", parsed.serial)
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.address)
    }

    @Test
    fun parsePersistedEntry_missingAddressIsNull() {
        val parsed = AiDexManagedSensorIdentityAdapter.parsePersistedEntry("X-2222267V4E|")
        assertEquals("X-2222267V4E", parsed.serial)
        assertEquals(null, parsed.address)
    }

    @Test
    fun upsertPersistedAddress_replacesExistingRow() {
        val updated = AiDexManagedSensorIdentityAdapter.upsertPersistedAddress(
            entries = linkedSetOf("X-2222267V4E|AA:BB:CC:DD:EE:FF", "X-OTHERSN12|11:22:33:44:55:66"),
            serial = "X-2222267V4E",
            address = "01:02:03:04:05:06",
        )
        assertEquals(
            linkedSetOf("X-OTHERSN12|11:22:33:44:55:66", "X-2222267V4E|01:02:03:04:05:06"),
            updated,
        )
        assertEquals(
            "01:02:03:04:05:06",
            updated.map { AiDexManagedSensorIdentityAdapter.parsePersistedEntry(it) }
                .first { it.serial == "X-2222267V4E" }
                .address,
        )
    }

    @Test
    fun upsertPersistedAddress_matchesUnprefixedSerialBothOrders() {
        val updated = AiDexManagedSensorIdentityAdapter.upsertPersistedAddress(
            entries = linkedSetOf("2222267V4E|AA:BB:CC:DD:EE:FF"),
            serial = "X-2222267V4E",
            address = "01:02:03:04:05:06",
        )
        assertEquals(linkedSetOf("X-2222267V4E|01:02:03:04:05:06"), updated)
    }

    @Test
    fun upsertPersistedAddress_collapsesDuplicateRows() {
        val updated = AiDexManagedSensorIdentityAdapter.upsertPersistedAddress(
            entries = linkedSetOf(
                "X-2222267V4E|AA:BB:CC:DD:EE:FF",
                "X-2222267V4E|11:22:33:44:55:66",
            ),
            serial = "X-2222267V4E",
            address = "AA:BB:CC:DD:EE:FF",
        )
        assertEquals(linkedSetOf("X-2222267V4E|AA:BB:CC:DD:EE:FF"), updated)
    }

    @Test
    fun addressOccupiedByOtherRealSerial_refusesSecondRealSn() {
        val entries = linkedSetOf(
            "X-SENSORA12|AA:BB:CC:DD:EE:FF",
            "X-SENSORB12|11:22:33:44:55:66",
        )
        assertTrue(
            AiDexManagedSensorIdentityAdapter.addressOccupiedByOtherRealSerial(
                entries,
                "X-SENSORB12",
                "AA:BB:CC:DD:EE:FF",
            )
        )
        assertFalse(
            AiDexManagedSensorIdentityAdapter.addressOccupiedByOtherRealSerial(
                entries,
                "X-SENSORA12",
                "AA:BB:CC:DD:EE:FF",
            )
        )
        assertFalse(
            AiDexManagedSensorIdentityAdapter.addressOccupiedByOtherRealSerial(
                linkedSetOf("X-AABBCCDDEEFF|AA:BB:CC:DD:EE:FF"),
                "X-SENSORB12",
                "AA:BB:CC:DD:EE:FF",
            )
        )
    }

    @Test
    fun persistedLeftoverVerdict_needsTheRowsOwnAddress() {
        val entries = linkedSetOf(
            "X-AABBCCDDEEFF|AA:BB:CC:DD:EE:FF",
            "X-112233445566|AA:BB:CC:DD:EE:FF",
            "X-SENSORA12|11:22:33:44:55:66",
        )
        assertEquals(
            true,
            AiDexManagedSensorIdentityAdapter.persistedLeftoverVerdict(entries, "X-AABBCCDDEEFF"),
        )
        // Same shape, but those 12 hex digits are not this row's radio.
        assertEquals(
            false,
            AiDexManagedSensorIdentityAdapter.persistedLeftoverVerdict(entries, "X-112233445566"),
        )
        assertEquals(
            false,
            AiDexManagedSensorIdentityAdapter.persistedLeftoverVerdict(entries, "X-SENSORA12"),
        )
        // Another driver's id has no AiDex row, so the row says nothing about it.
        assertNull(AiDexManagedSensorIdentityAdapter.persistedLeftoverVerdict(entries, "6CA04230E260"))
        assertNull(AiDexManagedSensorIdentityAdapter.persistedLeftoverVerdict(entries, null))
        // Exact serial match on purpose: the shared call sites pass the canonical "X-..." id, and a
        // bare alias must not make another driver's sensor look like an AiDex row.
        assertNull(AiDexManagedSensorIdentityAdapter.persistedLeftoverVerdict(entries, "AABBCCDDEEFF"))
        // A row with no address half proves nothing, so the live callback decides instead.
        assertNull(
            AiDexManagedSensorIdentityAdapter.persistedLeftoverVerdict(
                linkedSetOf("X-AABBCCDDEEFF"),
                "X-AABBCCDDEEFF",
            )
        )
    }

    @Test
    fun persistedEntryFor_returnsTheRowOfExactlyThatSensor() {
        val entries = linkedSetOf(
            "X-AABBCCDDEEFF|AA:BB:CC:DD:EE:FF",
            "X-SENSORA12|11:22:33:44:55:66",
        )
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            AiDexManagedSensorIdentityAdapter.persistedEntryFor(entries, "x-aabbccddeeff")?.address,
        )
        assertEquals(
            "X-SENSORA12",
            AiDexManagedSensorIdentityAdapter.persistedEntryFor(entries, "X-SENSORA12")?.serial,
        )
        assertNull(AiDexManagedSensorIdentityAdapter.persistedEntryFor(entries, "6CA04230E260"))
        assertNull(AiDexManagedSensorIdentityAdapter.persistedEntryFor(entries, " "))
    }

    /**
     * A row whose serial spells its own MAC is the old MAC-as-serial leftover shape, and the
     * shared cleanup tears such a row down. A broadcast rebind able to write one would turn a live
     * sensor into a leftover, so the write is refused before anything is stored.
     */
    @Test
    fun persistAddressUpdate_refusesASerialThatSpellsItsOwnAddress() {
        assertNull(
            AiDexManagedSensorIdentityAdapter.persistAddressUpdate(
                linkedSetOf(),
                "X-AABBCCDDEEFF",
                "AA:BB:CC:DD:EE:FF",
            )
        )
        // Any letter, any case; the address keeps its separators and the serial never has them.
        assertNull(
            AiDexManagedSensorIdentityAdapter.persistAddressUpdate(
                linkedSetOf(),
                "l-aabbccddeeff",
                "AA:BB:CC:DD:EE:FF",
            )
        )
    }

    @Test
    fun persistAddressUpdate_storesARealSerialAndRefusesAnOccupiedAddress() {
        // The same 12 hex digits are a real serial on a different radio.
        assertEquals(
            linkedSetOf("X-AABBCCDDEEFF|11:22:33:44:55:66"),
            AiDexManagedSensorIdentityAdapter.persistAddressUpdate(
                linkedSetOf(),
                "X-AABBCCDDEEFF",
                "11:22:33:44:55:66",
            ),
        )
        assertNull(
            AiDexManagedSensorIdentityAdapter.persistAddressUpdate(
                linkedSetOf("X-SENSORA12|AA:BB:CC:DD:EE:FF"),
                "X-SENSORB12",
                "AA:BB:CC:DD:EE:FF",
            )
        )
        // A real serial with a 12-hex body occupies its radio like any other real serial.
        assertNull(
            AiDexManagedSensorIdentityAdapter.persistAddressUpdate(
                linkedSetOf("X-AABBCCDDEEFF|11:22:33:44:55:66"),
                "X-SENSORB12",
                "11:22:33:44:55:66",
            )
        )
    }

    @Test
    fun leftoverVerdict_letsTheRowDecideAndOnlyThenAsksTheLiveCallback() {
        var asked = 0
        val liveSaysLeftover = { asked++; true }
        val liveSaysReal = { asked++; false }
        // A row proving a real sensor wins over a live address that spells its serial.
        assertFalse(AiDexManagedSensorIdentityAdapter.leftoverVerdict(false, liveSaysLeftover))
        assertTrue(AiDexManagedSensorIdentityAdapter.leftoverVerdict(true, liveSaysReal))
        assertEquals("the live callback must not be asked when the row decides", 0, asked)
        // No verdict from the row: the live callback decides.
        assertTrue(AiDexManagedSensorIdentityAdapter.leftoverVerdict(null, liveSaysLeftover))
        assertFalse(AiDexManagedSensorIdentityAdapter.leftoverVerdict(null, liveSaysReal))
        assertEquals(2, asked)
    }

    private object OtherDriver : tk.glucodata.drivers.ManagedSensorIdentityAdapter

    /** The guard compares the ALIAS: other drivers keep bare 12-hex ids, never the X- form. */
    @Test
    fun aliasBelongsToOtherDriver_comparesTheNativeAliasWithOtherDriversIds() {
        val adapters = listOf(AiDexManagedSensorIdentityAdapter, OtherDriver)
        val otherOwns: (tk.glucodata.drivers.ManagedSensorIdentityAdapter) -> List<String> =
            { if (it === OtherDriver) listOf("6CA04230E260") else emptyList() }
        assertTrue(AiDexManagedSensorIdentityAdapter.aliasBelongsToOtherDriver("X-6CA04230E260", adapters, otherOwns))
        assertTrue(AiDexManagedSensorIdentityAdapter.aliasBelongsToOtherDriver("x-6ca04230e260", adapters, otherOwns))
        assertFalse(AiDexManagedSensorIdentityAdapter.aliasBelongsToOtherDriver("X-2222267V4E", adapters, otherOwns))
        // AiDex's own ids never count as another driver's.
        val aidexOwns: (tk.glucodata.drivers.ManagedSensorIdentityAdapter) -> List<String> =
            { if (it === AiDexManagedSensorIdentityAdapter) listOf("6CA04230E260") else emptyList() }
        assertFalse(AiDexManagedSensorIdentityAdapter.aliasBelongsToOtherDriver("X-6CA04230E260", adapters, aidexOwns))
    }

    /**
     * persistAddress writes SharedPreferences, so only the seam above can be unit-tested. What the
     * seam is worth depends on persistAddress actually going through it: a write that reaches
     * upsertPersistedAddress directly would skip the refusal and let a rebind store a row whose
     * serial spells its own MAC, which the shared leftover cleanup then tears down.
     */
    @Test
    fun persistAddress_writesOnlyThroughTheRefusingSeam() {
        val body = persistAddressBody()
        val seam = body.indexOf("persistAddressUpdate(current, serial, address) ?: return false")
        val write = body.indexOf("persistEntries(context, updated)")
        assertTrue("persistAddress no longer refuses through persistAddressUpdate", seam >= 0)
        assertTrue("persistAddress no longer writes - check this test", write >= 0)
        assertTrue("the seam must decide before the write", seam < write)
        assertEquals(
            "persistAddress must not build rows itself",
            -1,
            body.indexOf("upsertPersistedAddress("),
        )
    }

    /** persistAddress's body, comments stripped: a guard inside a comment is not a guard. */
    private fun persistAddressBody(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "src/main/java/tk/glucodata/drivers/aidex/AiDexManagedSensorIdentityAdapter.kt"
        var source: String? = null
        while (dir != null && source == null) {
            source = listOf(File(dir, "Common/$relative"), File(dir, relative))
                .firstOrNull { it.isFile }
                ?.readText()
            dir = dir.parentFile
        }
        val text = source ?: throw AssertionError("adapter source not found from ${System.getProperty("user.dir")}")
        val start = text.indexOf("fun persistAddress(context: Context, serial: String, address: String)")
        assertTrue("persistAddress not found - check this test", start >= 0)
        val end = text.indexOf("\n    }", start)
        return text.substring(start, if (end > start) end else text.length)
            .lines()
            .joinToString(" ") { it.substringBefore("//") }
            .replace(Regex("\\s+"), " ")
    }
}
