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
    fun aSnapshotWithinTheCapCarriesEveryTombstone() {
        val tombstones = (1..256).toList()

        assertEquals(tombstones, OutboundApiJournalSnapshot.cloneJournalTombstonesForSnapshot(tombstones, 0))
        assertEquals(tombstones, OutboundApiJournalSnapshot.cloneJournalTombstonesForSnapshot(tombstones, 7))
    }

    @Test
    fun snapshotsOfAClearedJournalStayWithinTheCapAndKeepTheNewestDeletions() {
        // 700 deletions at once, as after clearing a long journal; larger numbers are newer.
        val tombstones = (1..700).toList()

        repeat(12) { rotation ->
            val sent = OutboundApiJournalSnapshot.cloneJournalTombstonesForSnapshot(tombstones, rotation)

            assertTrue("a receiver rejects more than 256", sent.size <= 256)
            assertEquals("oldest first, like the list it came from", sent.sorted(), sent)
            assertTrue(
                "the newest deletions go in every snapshot",
                sent.containsAll(((700 - 191)..700).toList()),
            )
        }
    }

    @Test
    fun everyTombstoneOfAClearedJournalIsSentWithinAFewSnapshots() {
        val tombstones = (1..700).toList()

        val seen = HashSet<Int>()
        var snapshots = 0
        while (seen.size < tombstones.size && snapshots < 100) {
            seen += OutboundApiJournalSnapshot.cloneJournalTombstonesForSnapshot(tombstones, snapshots)
            snapshots++
        }

        assertEquals(tombstones.toSet(), seen)
        // 508 older ones in slices of 64.
        assertTrue("took $snapshots snapshots", snapshots <= 8)
    }

    @Test
    fun tombstoneRotationHandlesAnyCounterValue() {
        val tombstones = (1..300).toList()

        listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE).forEach { rotation ->
            val sent = OutboundApiJournalSnapshot.cloneJournalTombstonesForSnapshot(tombstones, rotation)
            assertTrue(sent.size in 192..256)
        }
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

        assertEquals(6, recordIds.size)
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
