package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine behind the treatment line on the Nightscout settings screen (issue #191).
 * What matters is that a failure run keeps its *first* timestamp, so a path that has been dead
 * for days says so instead of resetting to "just now" on every cycle.
 */
class JournalSyncStatusTests {
    private val firstFailure = 1_000_000L
    private val laterFailure = firstFailure + 3 * 24 * 60 * 60 * 1000L

    @Test
    fun aHealthyStateReportsNoFailure() {
        assertFalse(JournalSyncState().isFailing)
    }

    @Test
    fun theStartOfAFailureRunSurvivesLaterFailures() {
        val first = applySyncFailure(
            JournalSyncState(lastSuccessAt = 500L),
            now = firstFailure,
            code = 403,
            failure = JournalSyncFailure.DELETE
        )
        val later = applySyncFailure(first, laterFailure, code = 401, failure = JournalSyncFailure.UPLOAD)

        assertEquals(firstFailure, later.failingSince)
        assertEquals(401, later.failureCode)
        assertEquals(JournalSyncFailure.UPLOAD, later.failure)
        // A stale success time is worth keeping: it is the "and it last worked then" half.
        assertEquals(500L, later.lastSuccessAt)
        assertTrue(later.isFailing)
    }

    @Test
    fun anAcceptedDocumentEndsTheFailureRun() {
        val failing = applySyncFailure(
            JournalSyncState(),
            now = firstFailure,
            code = 403,
            failure = JournalSyncFailure.DELETE
        )
        val healthy = applySyncSuccess(failing, now = laterFailure, acceptedDocument = true)

        assertFalse(healthy.isFailing)
        assertEquals(0L, healthy.failingSince)
        assertEquals(0, healthy.failureCode)
        assertEquals(laterFailure, healthy.lastSuccessAt)
    }

    @Test
    fun anIdleCycleClearsTheFailureWithoutFakingASend() {
        val failing = applySyncFailure(
            JournalSyncState(lastSuccessAt = 500L),
            now = firstFailure,
            code = 403,
            failure = JournalSyncFailure.DELETE
        )
        val idle = applySyncSuccess(failing, now = laterFailure, acceptedDocument = false)

        assertFalse(idle.isFailing)
        assertEquals(500L, idle.lastSuccessAt)
    }

    @Test
    fun failureKindsRoundTripThroughStorage() {
        for (failure in JournalSyncFailure.entries) {
            assertEquals(failure, JournalSyncFailure.fromStorage(failure.storageValue))
        }
        assertEquals(JournalSyncFailure.NONE, JournalSyncFailure.fromStorage(99))
    }
}
