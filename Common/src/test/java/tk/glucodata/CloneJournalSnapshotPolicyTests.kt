package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalEntrySource

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
        OutboundApiJournalSnapshot.run {
            assertTrue(JournalEntrySource.MANUAL.isCloneJournalExportSource())
            assertTrue(JournalEntrySource.PEN.isCloneJournalExportSource())
            assertTrue(JournalEntrySource.METER.isCloneJournalExportSource())
            assertTrue(JournalEntrySource.HEALTH_CONNECT.isCloneJournalExportSource())
            assertFalse(JournalEntrySource.NIGHTSCOUT.isCloneJournalExportSource())
            assertFalse(JournalEntrySource.API.isCloneJournalExportSource())
            assertFalse(JournalEntrySource.AAPS.isCloneJournalExportSource())
            assertFalse(JournalEntrySource.CLONE.isCloneJournalExportSource())
            assertFalse(JournalEntrySource.CLONE_LOCAL_ICE.isCloneJournalExportSource())
            assertFalse(JournalEntrySource.CLONE_TURN.isCloneJournalExportSource())
        }
    }
}
