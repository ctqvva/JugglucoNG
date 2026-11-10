package tk.glucodata.data.journal

import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the shapes the LibreView measurement log expects.
 *
 * These entries are spliced into a document the native writer assembles, so a malformed
 * one does not fail on its own — it takes the whole upload down, glucose data included.
 */
class LibreviewJournalTransferTests {

    private val utc = ZoneId.of("UTC")

    // 2026-03-14T09:26:53Z
    private val noon = 1_773_480_413_000L

    private fun entry(
        id: Long = 1L,
        type: JournalEntryType,
        amount: Float? = null,
        title: String = "",
        note: String? = null,
        source: JournalEntrySource = JournalEntrySource.MANUAL,
        presetId: Long? = null,
        timestamp: Long = noon
    ) = JournalEntryEntity(
        id = id,
        timestamp = timestamp,
        sensorSerial = null,
        entryType = type.storageValue,
        title = title,
        note = note,
        amount = amount,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = presetId,
        source = source.storageValue,
        sourceRecordId = null,
        createdAt = timestamp,
        updatedAt = timestamp
    )

    private fun preset(id: Long, countsTowardIob: Boolean) = JournalInsulinPresetEntity(
        id = id,
        displayName = "preset",
        onsetMinutes = 15,
        durationMinutes = 300,
        accentColor = 0,
        curveJson = "{}",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = countsTowardIob,
        sortOrder = 0
    )

    private fun build(
        entries: List<JournalEntryEntity>,
        presets: Map<Long, JournalInsulinPresetEntity> = emptyMap(),
        libre3: Boolean = true
    ) = LibreviewJournalTransfer.build(entries, presets, libre3, utc)

    @Test
    fun carbsBecomeAFoodEntryWithWholeGrams() {
        val built = build(listOf(entry(type = JournalEntryType.CARBS, amount = 42.4f)))
        assertEquals(1, built.food.size)
        val json = JSONObject(built.food.single())
        assertEquals("Snack", json.getString("foodType"))
        // gramsCarbs is written without decimals, exactly as writefood does.
        assertEquals(42, json.getInt("gramsCarbs"))
        assertTrue(built.insulin.isEmpty())
        assertTrue(built.generic.isEmpty())
    }

    @Test
    fun aRealMealIsNamedAfterTheTimeOfDay() {
        // getmealtype only names a meal above 50g; anything smaller stays a snack.
        assertEquals("Snack", LibreviewJournalTransfer.mealType(50f, noon, utc))
        assertEquals("Breakfast", LibreviewJournalTransfer.mealType(60f, noon, utc))
    }

    @Test
    fun insulinSplitsRapidFromLongByTheIobFlag() {
        val entries = listOf(
            entry(id = 1, type = JournalEntryType.INSULIN, amount = 4.5f, presetId = 7L),
            entry(id = 2, type = JournalEntryType.INSULIN, amount = 12f, presetId = 8L)
        )
        val presets = mapOf(
            7L to preset(7L, countsTowardIob = true),
            8L to preset(8L, countsTowardIob = false)
        )
        val built = build(entries, presets)
        assertEquals(2, built.insulin.size)
        val rapid = JSONObject(built.insulin[0])
        assertEquals("RapidActing", rapid.getString("insulinType"))
        assertEquals(4.5, rapid.getDouble("units"), 0.001)
        assertEquals("LongActing", JSONObject(built.insulin[1]).getString("insulinType"))
    }

    @Test
    fun anUnknownPresetStillGoesOutAsRapidRatherThanBeingDropped() {
        val built = build(listOf(entry(type = JournalEntryType.INSULIN, amount = 3f, presetId = 99L)))
        assertEquals("RapidActing", JSONObject(built.insulin.single()).getString("insulinType"))
    }

    @Test
    fun notesCarryTheirTextAndNoValueField() {
        val built = build(listOf(entry(type = JournalEntryType.NOTE, title = "Walk", note = "30 min")))
        val json = JSONObject(built.generic.single())
        assertEquals("com.abbottdiabetescare.informatics.customnote", json.getString("type"))
        assertEquals("Walk: 30 min", json.getJSONObject("extendedProperties").getString("text"))
        assertFalse(json.has("units"))
        assertFalse(json.has("gramsCarbs"))
    }

    @Test
    fun recordNumbersCannotCollideWithTheLegacyNumdataCounter() {
        // Numdata hands out small sequential ids into the same recordNumber namespace, so a
        // row id of 1 must not produce the record number its counter would.
        val journal = LibreviewJournalTransfer.recordNumber(1L, 4)
        assertTrue("journal record numbers must sit far above the native counter", journal > (1L shl 27))
        assertEquals(4L, journal and 0xFFL)
        assertTrue(
            LibreviewJournalTransfer.recordNumber(2L, 4) > LibreviewJournalTransfer.recordNumber(1L, 4)
        )
    }

    @Test
    fun theTwoDocumentFlavoursSpellTimestampsDifferently() {
        assertEquals("2026-03-14T09:26:00Z", LibreviewJournalTransfer.factoryTimestamp(noon, libre3 = true))
        assertEquals("2026-03-14T09:26:00.000Z", LibreviewJournalTransfer.factoryTimestamp(noon, libre3 = false))
        assertEquals(
            "2026-03-14T09:26:00+00:00",
            LibreviewJournalTransfer.localTimestamp(noon, libre3 = true, zone = utc)
        )
    }

    @Test
    fun timestampsCarryTheZoneOffsetTheyWereRecordedIn() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals(
            "2026-03-14T14:56:00+05:30",
            LibreviewJournalTransfer.localTimestamp(noon, libre3 = true, zone = kolkata)
        )
    }

    @Test
    fun nonAsciiIsEscapedSoJniCannotCorruptTheDocument() {
        // JNI hands strings over in modified UTF-8, which spells non-BMP characters as a
        // surrogate pair rather than as UTF-8 bytes. Escaping keeps the payload valid.
        val built = build(listOf(entry(type = JournalEntryType.NOTE, title = "café 🍎")))
        val raw = built.generic.single()
        assertTrue("entry must be pure ASCII, was: $raw", raw.all { it.code in 0x20..0x7E })
        assertEquals("café 🍎", JSONObject(raw).getJSONObject("extendedProperties").getString("text"))
    }

    @Test
    fun entriesMirroredInFromElsewhereAreNotForwardedOn() {
        val entries = listOf(
            entry(id = 1, type = JournalEntryType.CARBS, amount = 20f, source = JournalEntrySource.NIGHTSCOUT),
            entry(id = 2, type = JournalEntryType.CARBS, amount = 20f, source = JournalEntrySource.AAPS),
            entry(id = 3, type = JournalEntryType.CARBS, amount = 20f, source = JournalEntrySource.API),
            entry(id = 4, type = JournalEntryType.CARBS, amount = 20f, source = JournalEntrySource.MANUAL)
        )
        assertEquals(1, build(entries).food.size)
    }

    @Test
    fun entriesWithoutAValueAreLeftOut() {
        val entries = listOf(
            entry(id = 1, type = JournalEntryType.CARBS, amount = 0f),
            entry(id = 2, type = JournalEntryType.INSULIN, amount = null),
            entry(id = 3, type = JournalEntryType.NOTE, title = "", note = null),
            // A fingerstick belongs in bloodGlucoseEntries, which this payload does not carry.
            entry(id = 4, type = JournalEntryType.FINGERSTICK, amount = 5.5f)
        )
        assertTrue(build(entries).isEmpty)
        entries.forEach { assertFalse(LibreviewJournalTransfer.isSendable(it)) }
    }
}
