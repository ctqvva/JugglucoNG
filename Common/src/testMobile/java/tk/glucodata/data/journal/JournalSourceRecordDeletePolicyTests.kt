package tk.glucodata.data.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalSourceRecordDeletePolicyTests {
    @Test
    fun nothingDeletedWakesNothing() {
        // The Nightscout follower's stale aliases match no row on almost every pass; a wake
        // for them started the next receive pass, which deleted them again.
        assertFalse(sourceRecordDeleteWakesUploads(emptyList()))
    }

    @Test
    fun importedRowsDoNotWakeTheUploaders() {
        listOf(
            JournalEntrySource.NIGHTSCOUT,
            JournalEntrySource.AAPS,
            JournalEntrySource.API,
            JournalEntrySource.CLONE,
            JournalEntrySource.CLONE_LOCAL_ICE,
            JournalEntrySource.CLONE_TURN,
        ).forEach { source ->
            assertFalse(source.name, sourceRecordDeleteWakesUploads(listOf(source)))
        }
        assertFalse(
            sourceRecordDeleteWakesUploads(listOf(JournalEntrySource.NIGHTSCOUT, JournalEntrySource.AAPS))
        )
    }

    @Test
    fun anOwnRowAmongTheDeletedStillWakesThem() {
        assertTrue(sourceRecordDeleteWakesUploads(listOf(JournalEntrySource.PEN)))
        assertTrue(
            sourceRecordDeleteWakesUploads(listOf(JournalEntrySource.NIGHTSCOUT, JournalEntrySource.MANUAL))
        )
    }
}
