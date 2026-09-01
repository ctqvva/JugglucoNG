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
}
