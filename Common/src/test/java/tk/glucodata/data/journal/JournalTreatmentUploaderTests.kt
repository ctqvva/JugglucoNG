package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    // -- what a refusal reports --------------------------------------------

    @Test
    fun aRefusedWriteReportsThePermissionTheServerNamed() {
        // The field case: a role that may create and delete treatments but not update them.
        // The sentence is the actionable part; "403" alone sends people to the wrong setting.
        val body = """{"status":403,"message":"Missing permission api:treatments:update"}"""
        assertEquals("Missing permission api:treatments:update", JournalTreatmentUploader.serverMessage(body))
        assertEquals(
            "403: Missing permission api:treatments:update",
            JournalTreatmentUploader.failureText(403, JournalTreatmentUploader.serverMessage(body))
        )
    }

    @Test
    fun aBodyWithoutAMessageStillReportsSomething() {
        assertEquals("Unauthorized", JournalTreatmentUploader.serverMessage("Unauthorized"))
        assertEquals("""{"status":401}""", JournalTreatmentUploader.serverMessage("""{"status":401}"""))
        // No answer at all: the code stands alone rather than trailing an empty colon.
        assertEquals("-1", JournalTreatmentUploader.failureText(-1, ""))
    }

    // -- a re-upload never costs the server data -----------------------------

    @Test
    fun theOldCopyIsOnlyDeletedOnceTheServerHoldsADifferentNewDocument() {
        // v3: the re-upload carries the old identifier and updates that document in place.
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.KEEP,
            JournalTreatmentUploader.oldCopyAction(oldRemoteId = "jng-j-fe", acceptedRemoteId = "jng-j-fe")
        )
        // v1 upsert: the server stored the new document under the old _id.
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.KEEP,
            JournalTreatmentUploader.oldCopyAction(oldRemoteId = "65a1", acceptedRemoteId = "65a1")
        )
        // v1 create: a second document now exists, so the first one is surplus.
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.DELETE,
            JournalTreatmentUploader.oldCopyAction(oldRemoteId = "65a1", acceptedRemoteId = "65b2")
        )
    }

    @Test
    fun aFirstUploadHasNoOldCopyToDelete() {
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.KEEP,
            JournalTreatmentUploader.oldCopyAction(oldRemoteId = null, acceptedRemoteId = "65b2")
        )
    }

    // -- a refused entry is not knocked on every few seconds ------------------

    @Test
    fun aFailedEntryIsHeldAndTheHoldGrowsWhileItKeepsFailing() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 30 * 60_000L)
        val start = 1_000_000L
        assertFalse(backoff.shouldHold(254L, start))
        backoff.recordFailure(254L, start)
        // The trace: three identical attempts in twelve seconds.
        assertTrue(backoff.shouldHold(254L, start + 4_000L))
        assertTrue(backoff.shouldHold(254L, start + 12_000L))
        assertFalse(backoff.shouldHold(254L, start + 60_000L))
        backoff.recordFailure(254L, start + 60_000L)
        assertTrue(backoff.shouldHold(254L, start + 60_000L + 90_000L))
        assertFalse(backoff.shouldHold(254L, start + 60_000L + 120_000L))
    }

    @Test
    fun theHoldIsCappedAndNeverOutlastsItsCap() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 5 * 60_000L)
        var now = 0L
        repeat(10) {
            backoff.recordFailure(7L, now)
            now += 5 * 60_000L
            assertFalse(backoff.shouldHold(7L, now))
        }
    }

    @Test
    fun anotherEntryIsNotHeldForTheFailingOne() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 30 * 60_000L)
        backoff.recordFailure(254L, 1_000_000L)
        assertFalse(backoff.shouldHold(255L, 1_001_000L))
    }

    @Test
    fun anAcceptedWriteEndsTheHold() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 30 * 60_000L)
        backoff.recordFailure(254L, 1_000_000L)
        backoff.reset()
        assertFalse(backoff.shouldHold(254L, 1_001_000L))
    }

    @Test
    fun aClockThatMovedBackwardsDoesNotHoldTheEntryForLonger() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 30 * 60_000L)
        backoff.recordFailure(254L, 1_000_000L)
        assertFalse(backoff.shouldHold(254L, 1_000_000L - 5 * 60_000L))
    }
}
