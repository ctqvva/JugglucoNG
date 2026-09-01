package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.isCloneJournalExportSource

class CloneJournalSnapshotPolicyTests {
    @Test
    fun routeSelectsTheJournalProvenanceShownOnTheReceiver() {
        assertEquals(
            JournalEntrySource.CLONE_LOCAL_ICE,
            OutboundApiJournalSnapshot.cloneJournalSourceForTransport(CloneTransport.LOCAL_ICE.code),
        )
        assertEquals(
            JournalEntrySource.CLONE_TURN,
            OutboundApiJournalSnapshot.cloneJournalSourceForTransport(CloneTransport.TURN.code),
        )
        assertEquals(
            JournalEntrySource.CLONE,
            OutboundApiJournalSnapshot.cloneJournalSourceForTransport(CloneTransport.UNKNOWN.code),
        )
    }

    @Test
    fun onlyAuthoritativeLocalEntriesAreRebroadcast() {
        assertTrue(isCloneJournalExportSource(JournalEntrySource.MANUAL.storageValue))
        assertTrue(isCloneJournalExportSource(JournalEntrySource.PEN.storageValue))
        assertTrue(isCloneJournalExportSource(JournalEntrySource.METER.storageValue))
        assertTrue(isCloneJournalExportSource(JournalEntrySource.HEALTH_CONNECT.storageValue))
        assertFalse(isCloneJournalExportSource(JournalEntrySource.NIGHTSCOUT.storageValue))
        assertFalse(isCloneJournalExportSource(JournalEntrySource.API.storageValue))
        assertFalse(isCloneJournalExportSource(JournalEntrySource.AAPS.storageValue))
        assertFalse(isCloneJournalExportSource(JournalEntrySource.CLONE.storageValue))
        assertFalse(isCloneJournalExportSource(JournalEntrySource.CLONE_LOCAL_ICE.storageValue))
        assertFalse(isCloneJournalExportSource(JournalEntrySource.CLONE_TURN.storageValue))
    }

    @Test
    fun cloneIdentityUsesTheImmutableLocalRowAfterOtherIdentifiersChange() {
        assertEquals(
            "journal:42",
            OutboundApiJournalSnapshot.cloneJournalTransferIdentifier(localId = 42L),
        )
    }

    @Test
    fun deletionTombstoneTargetsEveryPossibleCompoundRecordKind() {
        val recordIds = tk.glucodata.data.journal.JournalTreatmentTransfer.sourceRecordIdsForBaseId(
            sourcePrefix = "clone:test-origin",
            baseId = "journal:42",
        )

        assertEquals(5, recordIds.size)
        assertTrue(recordIds.all { it.startsWith("clone:test-origin:journal:42:") })
    }
}
