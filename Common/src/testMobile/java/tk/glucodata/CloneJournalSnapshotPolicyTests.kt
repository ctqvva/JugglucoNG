package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
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

    @Test
    fun legacyDeletionWithoutRecoveryIdentityKeepsTheSnapshotValid() {
        val deleted = JSONArray()
            .put(JSONObject().put("id", 42L).put("recoveryId", JSONObject.NULL))
            .put(JSONObject().put("id", 43L))
        val envelope = OutboundApiJournalSnapshot.parseCloneJournalEnvelope(
            JSONObject()
                .put("schema", "tk.glucodata.clone.journal.v1")
                .put("origin", "test-origin")
                .put("events", JSONArray())
                .put("deleted", deleted)
                .toString()
        )

        assertEquals(2, envelope.deletedEntries.size)
        assertNull(envelope.deletedEntries[0].recoveryId)
        assertNull(envelope.deletedEntries[1].recoveryId)
    }

    @Test
    fun malformedDeletionRecoveryIdentityStillRejectsTheSnapshot() {
        val raw = JSONObject()
            .put("schema", "tk.glucodata.clone.journal.v1")
            .put("origin", "test-origin")
            .put("events", JSONArray())
            .put(
                "deleted",
                JSONArray().put(
                    JSONObject().put("id", 42L).put("recoveryId", "not-a-recovery-id")
                )
            )
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            OutboundApiJournalSnapshot.parseCloneJournalEnvelope(raw)
        }
    }
}
