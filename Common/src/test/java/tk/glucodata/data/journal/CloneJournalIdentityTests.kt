package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloneJournalIdentityTests {
    @Test
    fun locallyAuthoredEntryUsesTheSameIdentityAsLiveClone() {
        val entry = entry(
            id = 42L,
            type = JournalEntryType.INSULIN,
            source = JournalEntrySource.PEN,
            sourceRecordId = "test-pen-record",
        )

        assertEquals(
            "clone:test-origin-alpha:journal:42:insulin",
            CloneJournalIdentity.stableEntryId(entry, "test-origin-alpha"),
        )
    }

    @Test
    fun mirroredEntryKeepsItsOriginalCrossDeviceIdentity() {
        val entry = entry(
            id = 77L,
            type = JournalEntryType.CARBS,
            source = JournalEntrySource.CLONE_TURN,
            sourceRecordId = "clone:test-origin-sender:journal:19:carbs",
        )

        assertEquals(
            "clone:test-origin-sender:journal:19:carbs",
            CloneJournalIdentity.stableEntryId(entry, "test-origin-receiver"),
        )
    }

    @Test
    fun ownStableIdentityCanResolveAnExistingLocalRow() {
        val stableId = CloneJournalIdentity.localEntryId(
            origin = "test-origin-alpha",
            localId = 123L,
            type = JournalEntryType.NOTE,
        )

        assertEquals(
            123L,
            CloneJournalIdentity.localRowId(
                stableEntryId = stableId,
                localOrigin = "test-origin-alpha",
                type = JournalEntryType.NOTE,
            ),
        )
        assertNull(
            CloneJournalIdentity.localRowId(
                stableEntryId = stableId,
                localOrigin = "test-origin-beta",
                type = JournalEntryType.NOTE,
            ),
        )
        assertNull(
            CloneJournalIdentity.localRowId(
                stableEntryId = stableId,
                localOrigin = "test-origin-alpha",
                type = JournalEntryType.ACTIVITY,
            ),
        )
    }

    @Test
    fun tombstoneBaseExpandsToEveryPossibleJournalKind() {
        val base = CloneJournalIdentity.localTombstoneBaseId(
            origin = "test-origin-alpha",
            localId = 91L,
        )

        assertEquals(
            listOf(
                "clone:test-origin-alpha:journal:91:carbs",
                "clone:test-origin-alpha:journal:91:insulin",
                "clone:test-origin-alpha:journal:91:fingerstick",
                "clone:test-origin-alpha:journal:91:activity",
                "clone:test-origin-alpha:journal:91:note",
            ),
            CloneJournalIdentity.entryIdsForTombstoneBase(base),
        )
    }

    private fun entry(
        id: Long,
        type: JournalEntryType,
        source: JournalEntrySource,
        sourceRecordId: String?,
    ) = JournalEntryEntity(
        id = id,
        timestamp = 1_000_000L,
        sensorSerial = "test-sensor-journal",
        entryType = type.storageValue,
        title = "Test entry",
        note = null,
        amount = 1f,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = null,
        source = source.storageValue,
        originSource = source.storageValue,
        sourceRecordId = sourceRecordId,
        createdAt = 1_000_000L,
        updatedAt = 1_000_000L,
    )
}
