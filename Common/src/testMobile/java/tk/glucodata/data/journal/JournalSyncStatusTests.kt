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

    /**
     * "Failing since 17:22" reads as something being tried and refused over and over. It was
     * also what the app said after a single refusal that nothing had followed for hours,
     * which is a different state and the one worth knowing about: the line now carries when
     * the path was last exercised at all.
     */
    @Test
    fun everyCycleRecordsThatItWasTried() {
        val failed = applySyncFailure(JournalSyncState(), now = 1_000L, code = 0, failure = JournalSyncFailure.UPLOAD)
        assertEquals(1_000L, failed.lastAttemptAt)
        assertEquals(1_000L, failed.failingSince)

        // Refused again much later: still one run of failures, but it was tried just now.
        val again = applySyncFailure(failed, now = 9_000L, code = 400, failure = JournalSyncFailure.UPLOAD)
        assertEquals(1_000L, again.failingSince)
        assertEquals(9_000L, again.lastAttemptAt)
    }

    @Test
    fun aCycleWithNothingToSendCountsAsHavingTried() {
        val failed = applySyncFailure(JournalSyncState(), now = 1_000L, code = 0, failure = JournalSyncFailure.UPLOAD)

        val quiet = applySyncSuccess(failed, now = 5_000L, acceptedDocument = false)

        // Nothing was sent, so "last sent" stands still, but the path was exercised.
        assertEquals(0L, quiet.lastSuccessAt)
        assertEquals(5_000L, quiet.lastAttemptAt)
        assertFalse(quiet.isFailing)
    }
}