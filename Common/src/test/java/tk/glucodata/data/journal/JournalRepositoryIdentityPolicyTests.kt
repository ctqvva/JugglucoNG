package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalRepositoryIdentityPolicyTests {
    @Test
    fun matchingRemoteIdentityOnlyCollapsesTheSameTreatmentKind() {
        assertTrue(
            isSameNightscoutJournalKind(
                existingNsRemoteId = "remote-treatment-id",
                incomingNsRemoteId = "remote-treatment-id",
                existingEntryType = JournalEntryType.INSULIN.storageValue,
                incomingEntryType = JournalEntryType.INSULIN.storageValue,
            )
        )
        assertFalse(
            isSameNightscoutJournalKind(
                existingNsRemoteId = "remote-treatment-id",
                incomingNsRemoteId = "remote-treatment-id",
                existingEntryType = JournalEntryType.CARBS.storageValue,
                incomingEntryType = JournalEntryType.INSULIN.storageValue,
            )
        )
    }

    @Test
    fun absentRemoteIdentityNeverCollapsesLocalRows() {
        assertFalse(
            isSameNightscoutJournalKind(
                existingNsRemoteId = null,
                incomingNsRemoteId = null,
                existingEntryType = JournalEntryType.NOTE.storageValue,
                incomingEntryType = JournalEntryType.NOTE.storageValue,
            )
        )
    }

    @Test
    fun existingCloneIngressRouteIsStableForTheSameRecord() {
        assertTrue(
            isSameCloneJournalRecord(
                existingSource = JournalEntrySource.CLONE_LOCAL_ICE.storageValue,
                incomingSource = JournalEntrySource.CLONE_TURN,
                existingSourceRecordId = "clone:origin:journal:42:insulin",
                incomingSourceRecordId = "clone:origin:journal:42:insulin",
            )
        )
        assertFalse(
            isSameCloneJournalRecord(
                existingSource = JournalEntrySource.NIGHTSCOUT.storageValue,
                incomingSource = JournalEntrySource.CLONE_TURN,
                existingSourceRecordId = "nightscout:server:42:insulin",
                incomingSourceRecordId = "clone:origin:journal:42:insulin",
            )
        )
    }

    @Test
    fun cloneAndNightscoutRowsAreTheOnlyCrossSourceOverlapPair() {
        assertTrue(
            isCloneNightscoutPair(
                JournalEntrySource.CLONE_TURN.storageValue,
                JournalEntrySource.NIGHTSCOUT.storageValue,
            )
        )
        assertTrue(
            isCloneNightscoutPair(
                JournalEntrySource.NIGHTSCOUT.storageValue,
                JournalEntrySource.CLONE_LOCAL_ICE.storageValue,
            )
        )
        assertFalse(
            isCloneNightscoutPair(
                JournalEntrySource.CLONE_TURN.storageValue,
                JournalEntrySource.CLONE_LOCAL_ICE.storageValue,
            )
        )
        assertFalse(
            isCloneNightscoutPair(
                JournalEntrySource.NIGHTSCOUT.storageValue,
                JournalEntrySource.API.storageValue,
            )
        )
    }

    @Test
    fun overlapKeepsTheFirstObservedLocalRow() {
        assertTrue(isEarlierJournalRow(100L, 9L, 101L, 1L))
        assertTrue(isEarlierJournalRow(100L, 9L, 100L, 10L))
        assertFalse(isEarlierJournalRow(101L, 1L, 100L, 9L))
    }

    @Test
    fun delayedNightscoutIdentityReconcilesTheEarlierCloneRow() {
        val clone = journalEntity(
            id = 7L,
            createdAt = 100L,
            source = JournalEntrySource.CLONE_TURN,
            sourceRecordId = "clone:sender:journal:42:insulin",
            nsRemoteId = null,
        )
        val nightscout = journalEntity(
            id = 8L,
            createdAt = 101L,
            source = JournalEntrySource.NIGHTSCOUT,
            sourceRecordId = "nightscout:server:remote-treatment-id:insulin",
            nsRemoteId = "remote-treatment-id",
        )

        val overlap = cloneNightscoutOverlap(
            sourceMatch = clone,
            remoteMatches = listOf(nightscout),
            incomingNsRemoteId = "remote-treatment-id",
            incomingEntryType = JournalEntryType.INSULIN.storageValue,
        )

        assertEquals(clone.id, overlap?.keeper?.id)
        assertEquals(listOf(nightscout.id), overlap?.redundantRows?.map { it.id })
    }

    @Test
    fun mirroredRowsNeverCreateNightscoutDeleteTombstones() {
        val externalSources = listOf(
            JournalEntrySource.AAPS,
            JournalEntrySource.NIGHTSCOUT,
            JournalEntrySource.API,
            JournalEntrySource.CLONE,
            JournalEntrySource.CLONE_LOCAL_ICE,
            JournalEntrySource.CLONE_TURN,
        )
        externalSources.forEach { source ->
            assertNull(nightscoutDeleteRemoteId(source.storageValue, "remote-treatment-id"))
        }
        assertEquals(
            "remote-treatment-id",
            nightscoutDeleteRemoteId(JournalEntrySource.MANUAL.storageValue, "remote-treatment-id"),
        )
    }

    private fun journalEntity(
        id: Long,
        createdAt: Long,
        source: JournalEntrySource,
        sourceRecordId: String,
        nsRemoteId: String?,
    ) = JournalEntryEntity(
        id = id,
        timestamp = 1L,
        sensorSerial = null,
        entryType = JournalEntryType.INSULIN.storageValue,
        title = "Insulin",
        note = null,
        amount = 1f,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = null,
        source = source.storageValue,
        sourceRecordId = sourceRecordId,
        createdAt = createdAt,
        updatedAt = createdAt,
        nsRemoteId = nsRemoteId,
    )
}
