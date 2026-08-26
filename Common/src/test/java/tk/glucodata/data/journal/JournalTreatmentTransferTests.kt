package tk.glucodata.data.journal

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
