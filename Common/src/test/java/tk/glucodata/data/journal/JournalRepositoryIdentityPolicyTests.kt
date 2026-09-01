package tk.glucodata.data.journal

import org.junit.Assert.assertFalse
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
}
