package tk.glucodata.data.journal

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
}
