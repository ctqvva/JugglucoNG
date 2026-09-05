package tk.glucodata.data.journal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class JournalTreatmentTransferTests {
    @Test
    fun xdripMbgEntryBecomesNightscoutFingerstickJournalEntry() {
        val document = JSONObject()
            .put("_id", "xdrip-mbg-id")
            .put("device", "xDrip-DexcomG5")
            .put("type", "mbg")
            .put("date", 1_718_928_000_000L)
            .put("dateString", "2024-06-21T00:00:00.000Z")
            .put("mbg", 95)

        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = "nightscout:NSF-TEST",
            insulinPresets = emptyList(),
            stringResource = { "Fingerstick" },
        )

        assertNotNull(parsed)
        val entry = parsed!!.inputs.single()
        assertEquals(JournalEntryType.FINGERSTICK, entry.type)
        assertEquals(95f, entry.glucoseValueMgDl!!, 0f)
        assertEquals(1_718_928_000_000L, entry.timestamp)
        assertEquals(JournalEntrySource.NIGHTSCOUT, entry.source)
        assertEquals("nightscout:NSF-TEST:xdrip-mbg-id:fingerstick", entry.sourceRecordId)
        assertEquals("xdrip-mbg-id", entry.nsRemoteId)
    }

    /**
     * What an update may carry. v3 answers a document that still names its own time with
     * 400 "Field date cannot be modified by the client" and stops at the first such field,
     * so they are removed together; what is left is what an update is for.
     */
    @Test
    fun anUpdateCarriesNoFieldTheServerOwns() {
        val json = org.json.JSONObject()
            .put("date", 1_700_000_000_000L)
            .put("created_at", "2023-11-14T22:13:20.000Z")
            .put("utcOffset", 0)
            .put("_id", "jng-j-1a7-18bd0a4b800")
            .put("identifier", "jng-j-1a7-18bd0a4b800")
            .put("eventType", "Correction Bolus")
            .put("insulin", 4.0)
            .put("notes", "kept")

        JournalTreatmentTransfer.stripImmutableForUpdate(json)

        assertFalse(json.has("date"))
        assertFalse(json.has("created_at"))
        assertFalse(json.has("utcOffset"))
        assertFalse(json.has("_id"))
        // The endpoint names the document; the body only says what changes.
        assertFalse(json.has("identifier"))
        assertEquals("Correction Bolus", json.optString("eventType"))
        assertEquals(4.0, json.optDouble("insulin"), 0.001)
        assertEquals("kept", json.optString("notes"))
    }

    /**
     * Shape taken from a live v3 /api/v3/treatments response. v3 answers with `identifier`
     * and the server-side srvCreated/srvModified; there is no `_id` at all, so nothing in the
     * import may depend on one being present.
     */
    @Test
    fun aV3TreatmentIsIdentifiedWithoutAnyUnderscoreId() {
        val document = JSONObject()
            .put("date", 1_786_794_604_000L)
            .put("created_at", "2026-08-15T11:50:04.000Z")
            .put("utcOffset", 0)
            .put("isValid", true)
            .put("app", "JugglucoNG")
            .put("enteredBy", "JugglucoNG")
            .put("type", "insulin")
            .put("eventType", "Correction Bolus")
            .put("insulin", 3)
            .put("isBasalInsulin", false)
            .put("insulinType", "Fiasp")
            .put("identifier", "jng-j-101")
            .put("srvModified", 1_787_225_341_651L)
            .put("srvCreated", 1_787_225_341_651L)

        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = "nightscout:NSF-TEST",
            insulinPresets = emptyList(),
            stringResource = { "Insulin" },
        )

        assertNotNull(parsed)
        val entry = parsed!!.inputs.single()
        assertEquals(JournalEntryType.INSULIN, entry.type)
        assertEquals(3f, entry.amount!!, 0f)
        assertEquals(1_786_794_604_000L, entry.timestamp)
        // Our own upload identifier survives the round trip, which is what keeps a
        // device from re-importing its own treatments.
        assertEquals("jng-j-101", entry.nsRemoteId)
    }

    @Test
    fun theV3IdentifierIsPreferredOverALegacyUnderscoreId() {
        val document = JSONObject()
            .put("identifier", "jng-j-101")
            .put("_id", "legacy-mongo-id")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Correction Bolus")
            .put("insulin", 3)

        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = "nightscout:NSF-TEST",
            insulinPresets = emptyList(),
            stringResource = { "Insulin" },
        )

        assertEquals("jng-j-101", parsed!!.inputs.single().nsRemoteId)
    }

    @Test
    fun cloneTransferKeepsExplicitNightscoutIdentityForCrossSourceDeduplication() {
        val document = JSONObject()
            .put("identifier", "journal:42")
            .put("nsRemoteId", "remote-treatment-id")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Correction Bolus")
            .put("insulin", 2.5)

        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.CLONE_TURN,
            sourcePrefix = "clone:test-origin",
            insulinPresets = emptyList(),
            stringResource = { "Insulin" },
        )

        val entry = parsed!!.inputs.single()
        assertEquals("clone:test-origin:journal:42:insulin", entry.sourceRecordId)
        assertEquals("remote-treatment-id", entry.nsRemoteId)
    }

    @Test
    fun cloneTransferCarriesDurableRecoveryIdentity() {
        val recoveryId = "0123456789abcdef0123456789abcdef"
        val document = JSONObject()
            .put("identifier", "journal:42")
            .put("recoveryId", recoveryId)
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Correction Bolus")
            .put("insulin", 2.5)

        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.CLONE_TURN,
            sourcePrefix = "clone:test-origin",
            insulinPresets = emptyList(),
            stringResource = { "Insulin" },
        )

        assertEquals(recoveryId, parsed!!.inputs.single().recoveryId)
    }

    @Test
    fun malformedCloneRecoveryIdentityRejectsTheTreatment() {
        val document = JSONObject()
            .put("identifier", "journal:42")
            .put("recoveryId", "not-a-recovery-id")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Correction Bolus")
            .put("insulin", 2.5)

        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.CLONE_TURN,
            sourcePrefix = "clone:test-origin",
            insulinPresets = emptyList(),
            stringResource = { "Insulin" },
        )

        assertEquals(null, parsed)
    }

    @Test
    fun cloneTransferCarriesTheSendersUpdatedPenOrigin() {
        val document = JSONObject()
            .put("identifier", "journal:42")
            .put("journalSource", "pen")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Correction Bolus")
            .put("insulin", 2.5)

        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.CLONE_TURN,
            sourcePrefix = "clone:test-origin",
            insulinPresets = emptyList(),
            stringResource = { "Insulin" },
        )

        val entry = parsed!!.inputs.single()
        assertEquals(JournalEntrySource.CLONE_TURN, entry.source)
        assertEquals(JournalEntrySource.PEN, entry.originSource)
    }
}
