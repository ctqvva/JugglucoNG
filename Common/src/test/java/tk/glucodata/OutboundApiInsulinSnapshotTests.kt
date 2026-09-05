package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalInsulinPreset

class OutboundApiInsulinSnapshotTests {
    private val now = 1_700_000_000_000L
    private val preset = JournalInsulinPreset(
        id = 1L,
        displayName = "Basal",
        onsetMinutes = 0,
        durationMinutes = 60,
        accentColor = 0,
        curveJson = "0:0;30:1;60:0",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = false,
        sortOrder = 0
    )

    private fun entry(snapshot: String?) = JournalEntryEntity(
        id = 1L,
        timestamp = now - 60 * 60_000L,
        sensorSerial = null,
        entryType = "insulin",
        title = "Basal",
        note = null,
        amount = 4f,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = 1L,
        source = "manual",
        sourceRecordId = null,
        createdAt = now,
        updatedAt = now,
        insulinCurveJsonSnapshot = snapshot
    )

    @Test
    fun namedTypeUsesFrozenDoseCurveEvenWhenExcludedFromAggregateIob() {
        val type = OutboundApiJournalSnapshot.insulinTypesJson(
            listOf(preset), listOf(entry("0:0;60:1;120:0")), now
        ).getJSONObject(0)
        assertEquals(2.0, type.getDouble("iob"), 0.0001)
        assertEquals(4.0, type.getDouble("last_units"), 0.0001)
    }

    @Test
    fun entryWithoutSnapshotFallsBackToItsPresetCurve() {
        val type = OutboundApiJournalSnapshot.insulinTypesJson(
            listOf(preset), listOf(entry(null)), now
        ).getJSONObject(0)
        assertEquals(0.0, type.getDouble("iob"), 0.0001)
        assertEquals(4.0, type.getDouble("last_units"), 0.0001)
    }
}
