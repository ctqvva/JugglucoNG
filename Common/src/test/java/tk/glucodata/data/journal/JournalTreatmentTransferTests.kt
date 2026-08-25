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
}
