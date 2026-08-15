package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalTreatmentUploader.MAX_DELETE_ATTEMPTS
import tk.glucodata.data.journal.JournalTreatmentUploader.TombstoneAction
import tk.glucodata.data.journal.JournalTreatmentUploader.tombstoneAction

class JournalTreatmentUploaderTests {
    private fun preset(countsTowardIob: Boolean) = JournalInsulinPresetEntity(
        id = 1,
        displayName = "Test insulin",
        onsetMinutes = 30,
        durationMinutes = 720,
        accentColor = 0,
        curveJson = "",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = countsTowardIob,
        sortOrder = 0
    )

    @Test
    fun longInsulinCanBeExcludedWithoutSuppressingOtherTreatments() {
        assertFalse(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.INSULIN.storageValue,
                preset(countsTowardIob = false),
                sendLongInsulin = false
            )
        )
        assertTrue(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.INSULIN.storageValue,
                preset(countsTowardIob = true),
                sendLongInsulin = false
            )
        )
        assertTrue(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.CARBS.storageValue,
                null,
                sendLongInsulin = false
            )
        )
    }

    @Test
    fun longInsulinRemainsEnabledByTheUploadPolicyWhenRequested() {
        assertTrue(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.INSULIN.storageValue,
                preset(countsTowardIob = false),
                sendLongInsulin = true
            )
        )
    }

    @Test
    fun acceptedDeleteClearsTheTombstone() {
        assertEquals(TombstoneAction.CLEAR, tombstoneAction(code = 200, attemptsSoFar = 0))
        assertEquals(TombstoneAction.CLEAR, tombstoneAction(code = 204, attemptsSoFar = 3))
    }

    @Test
    fun aDocumentTheServerNoLongerHasCountsAsDeleted() {
        // The old boolean path retried these forever: the delete can never succeed,
        // and there is nothing left on the server to delete either.
        assertEquals(TombstoneAction.CLEAR, tombstoneAction(code = 404, attemptsSoFar = 0))
        assertEquals(TombstoneAction.CLEAR, tombstoneAction(code = 410, attemptsSoFar = 0))
    }

    @Test
    fun aRefusedDeleteIsRetriedUntilTheAttemptCapIsReached() {
        // 403 is the reported case: a role with api:treatments:create but not :delete.
        assertEquals(TombstoneAction.RETRY, tombstoneAction(code = 403, attemptsSoFar = 0))
        assertEquals(
            TombstoneAction.RETRY,
            tombstoneAction(code = 403, attemptsSoFar = MAX_DELETE_ATTEMPTS - 2)
        )
        assertEquals(
            TombstoneAction.GIVE_UP,
            tombstoneAction(code = 403, attemptsSoFar = MAX_DELETE_ATTEMPTS - 1)
        )
        assertEquals(
            TombstoneAction.GIVE_UP,
            tombstoneAction(code = 403, attemptsSoFar = MAX_DELETE_ATTEMPTS)
        )
    }

    @Test
    fun aDeleteThatNeverGotAnAnswerIsRetriedToo() {
        assertEquals(TombstoneAction.RETRY, tombstoneAction(code = -1, attemptsSoFar = 0))
        assertEquals(TombstoneAction.RETRY, tombstoneAction(code = 500, attemptsSoFar = 0))
    }
}
