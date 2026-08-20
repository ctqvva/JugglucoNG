package tk.glucodata.data.journal

import org.json.JSONArray
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

    // -- receive endpoint --------------------------------------------------

    @Test
    fun receivingFollowsTheConfiguredApiVersion() {
        val v1 = JournalTreatmentUploader.treatmentFetchUrl("https://ns.example.com", useV3 = false, count = 240)
        assertEquals("https://ns.example.com/api/v1/treatments.json?count=240", v1)

        val v3 = JournalTreatmentUploader.treatmentFetchUrl("https://ns.example.com", useV3 = true, count = 240)
        assertTrue(v3, v3.startsWith("https://ns.example.com/api/v3/treatments?"))
        // v3 counts with limit and has no .json suffix; sending the v1 form is what answered 401.
        assertTrue(v3, v3.contains("limit=240"))
        assertFalse(v3, v3.contains(".json"))
        assertFalse(v3, v3.contains("count="))
    }

    @Test
    fun v3ResultEnvelopeIsUnwrappedForTheImporter() {
        val body = """{"status":200,"result":[{"identifier":"a"},{"identifier":"b"}]}"""
        val array = JSONArray(JournalTreatmentUploader.treatmentsArrayBody(body, useV3 = true))
        assertEquals(2, array.length())
        assertEquals("a", array.getJSONObject(0).getString("identifier"))
    }

    @Test
    fun v1ArraysAndV3HostsAnsweringArraysBothPassThrough() {
        val array = """[{"identifier":"a"}]"""
        assertEquals(array, JournalTreatmentUploader.treatmentsArrayBody(array, useV3 = false))
        assertEquals(array, JournalTreatmentUploader.treatmentsArrayBody(array, useV3 = true))
    }

    @Test
    fun anEnvelopeWithoutResultYieldsAnEmptyArrayRatherThanAParseFailure() {
        assertEquals("[]", JournalTreatmentUploader.treatmentsArrayBody("""{"status":401}""", useV3 = true))
    }

    // -- what a refusal reports --------------------------------------------

    @Test
    fun aRefusedReadReportsThePermissionTheServerNamed() {
        // An upload-only token answers this; the sentence is the actionable part, and
        // it is what distinguishes a missing permission from wrong credentials.
        val body = """{"status":403,"message":"Missing permission api:treatments:read"}"""
        assertEquals("Missing permission api:treatments:read", JournalTreatmentUploader.serverMessage(body))
    }

    @Test
    fun aBodyWithoutAMessageStillReportsSomething() {
        assertEquals("Unauthorized", JournalTreatmentUploader.serverMessage("Unauthorized"))
        assertEquals("""{"status":401}""", JournalTreatmentUploader.serverMessage("""{"status":401}"""))
    }

    @Test
    fun theFailureNamesTheEndpointPathWithoutRepeatingTheHost() {
        assertEquals(
            "/api/v3/treatments",
            JournalTreatmentUploader.endpointPath("https://ns.example.com/api/v3/treatments?limit=240")
        )
    }

    // -- repeating failure logging -----------------------------------------

    @Test
    fun anUnchangedFailureIsLoggedOncePerInterval() {
        val log = JournalTreatmentUploader.RepeatedErrorLog(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 401", start))
        assertEquals(-1, log.suppressedSince("HTTP 401", start + 1_000))
        assertEquals(-1, log.suppressedSince("HTTP 401", start + 2_000))
        // The line that finally speaks says how many it stood in for.
        assertEquals(2, log.suppressedSince("HTTP 401", start + 5 * 60_000L))
    }

    @Test
    fun aDifferentFailureIsNeverHiddenBehindTheOldOnesInterval() {
        val log = JournalTreatmentUploader.RepeatedErrorLog(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 401", start))
        assertEquals(0, log.suppressedSince("HTTP 500", start + 1_000))
    }

    @Test
    fun aSuccessEndsTheEpisodeSoTheNextFailureReportsAtOnce() {
        val log = JournalTreatmentUploader.RepeatedErrorLog(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 401", start))
        assertEquals(-1, log.suppressedSince("HTTP 401", start + 1_000))
        log.reset()
        assertEquals(0, log.suppressedSince("HTTP 401", start + 2_000))
    }

    @Test
    fun aClockThatMovedBackwardsDoesNotSilenceTheLogForever() {
        val log = JournalTreatmentUploader.RepeatedErrorLog(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 401", start))
        assertEquals(0, log.suppressedSince("HTTP 401", start - 60_000L))
    }
}
