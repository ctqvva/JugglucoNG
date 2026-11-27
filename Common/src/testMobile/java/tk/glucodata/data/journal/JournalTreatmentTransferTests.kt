package tk.glucodata.data.journal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTreatmentTransferTests {
    private fun journalEntry(type: JournalEntryType) = JournalEntryEntity(
        timestamp = 1_786_794_604_000L,
        sensorSerial = null,
        entryType = type.storageValue,
        title = "Journal entry",
        note = null,
        amount = null,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = null,
        source = JournalEntrySource.MANUAL.storageValue,
        sourceRecordId = null,
        createdAt = 1_786_794_604_000L,
        updatedAt = 1_786_794_604_000L
    )

    private fun parseNightscout(
        document: JSONObject,
        presets: List<JournalInsulinPreset> = emptyList()
    ) = JournalTreatmentTransfer.parseTreatment(
        treatment = document,
        source = JournalEntrySource.NIGHTSCOUT,
        sourcePrefix = "nightscout:test",
        insulinPresets = presets,
        stringResource = { "Journal entry" }
    )!!.inputs.single()

    private fun insulinPreset(
        id: Long,
        name: String,
        profile: JournalBuiltInCurveProfile,
        countsTowardIob: Boolean,
        archived: Boolean = false
    ) = JournalInsulinPreset(
        id = id,
        displayName = name,
        onsetMinutes = 15,
        durationMinutes = 360,
        accentColor = 0,
        curveJson = "",
        isBuiltIn = true,
        isArchived = archived,
        countsTowardIob = countsTowardIob,
        sortOrder = id.toInt(),
        curveProfileId = profile.storageValue
    )

    @Test
    fun nightscoutFingersticksUseDisplayPrecisionAndKeepCanonicalGlucose() {
        val entry = journalEntry(JournalEntryType.FINGERSTICK).copy(glucoseValueMgDl = 165.76744f)

        for ((useMmol, expected) in listOf(false to 166.0, true to 9.2)) {
            val document = JournalTreatmentTransfer.buildNightscoutTreatmentJson(entry, null, null, useMmol)!!

            assertEquals("BG Check", document.getString("eventType"))
            assertEquals("Finger", document.getString("glucoseType"))
            assertEquals(if (useMmol) "mmol" else "mg/dl", document.getString("units"))
            assertEquals(expected, document.getDouble("glucose"), 0.0)
            assertEquals(165.76744, document.getDouble("glucoseValueMgDl"), 0.0)
            assertEquals(entry.glucoseValueMgDl!!, parseNightscout(document).glucoseValueMgDl!!, 0f)

            document.remove("glucoseValueMgDl")
            assertEquals(166f, parseNightscout(document).glucoseValueMgDl!!, 0.3f)
        }

        val clone = JournalTreatmentTransfer.buildTreatmentJson(entry, "journal:1", null, null, useV3 = true)!!
        assertEquals("mg/dl", clone.getString("units"))
        assertEquals(entry.glucoseValueMgDl!!.toDouble(), clone.getDouble("glucose"), 0.0)
    }

    @Test
    fun aNightscoutFingerstickCorrectionSupersedesStaleCanonicalMetadata() {
        val entry = journalEntry(JournalEntryType.FINGERSTICK).copy(glucoseValueMgDl = 165.76744f)
        val mmol = JournalTreatmentTransfer.buildNightscoutTreatmentJson(entry, null, null, useMmol = true)!!
        val mgdl = JournalTreatmentTransfer.buildNightscoutTreatmentJson(entry, null, null, useMmol = false)!!

        assertEquals(144.1456f, parseNightscout(mmol.put("glucose", "8.0")).glucoseValueMgDl!!, 0.001f)
        assertEquals(145f, parseNightscout(mgdl.put("glucose", 145)).glucoseValueMgDl!!, 0f)
    }

    @Test
    fun aNightscoutUnitLabelChangeAloneKeepsTheOriginalCanonicalValue() {
        val canonicalMgdl = 165.76744f
        val entry = journalEntry(JournalEntryType.FINGERSTICK).copy(glucoseValueMgDl = canonicalMgdl)
        val mmol = JournalTreatmentTransfer.buildNightscoutTreatmentJson(entry, null, null, useMmol = true)!!
        val mgdl = JournalTreatmentTransfer.buildNightscoutTreatmentJson(entry, null, null, useMmol = false)!!

        assertEquals(canonicalMgdl, parseNightscout(mmol.put("units", "mg/dl")).glucoseValueMgDl!!, 0f)
        assertEquals(canonicalMgdl, parseNightscout(mgdl.put("units", "mmol")).glucoseValueMgDl!!, 0f)
    }

    @Test
    fun basalNoteCarriesDoseAndRestoresTheNamedPreset() {
        val entry = journalEntry(JournalEntryType.INSULIN).copy(
            title = "Tresiba",
            amount = 5.2f,
            note = "Evening dose",
            insulinCurveProfileId = JournalBuiltInCurveProfile.DEGLUDEC.storageValue
        )
        val document = JournalTreatmentTransfer.buildNightscoutTreatmentJson(entry, null, null, useMmol = false)!!
        assertEquals("Note", document.getString("eventType"))
        assertEquals("insulin", document.getString("type"))
        assertFalse(document.has("insulin"))
        assertEquals(5.2, document.getDouble("amount"), 0.0)
        assertEquals("Tresiba", document.getString("insulinType"))
        assertEquals("Basal injection: Tresiba 5.2 U | Evening dose", document.getString("notes"))

        val generic = insulinPreset(1, "Long acting", JournalBuiltInCurveProfile.LONG_BASAL_GENERIC, false)
        val tresiba = insulinPreset(2, "TRESIBA", JournalBuiltInCurveProfile.DEGLUDEC, false, archived = true)
        val imported = parseNightscout(document, listOf(generic, tresiba))
        assertEquals(JournalEntryType.INSULIN, imported.type)
        assertEquals(5.2f, imported.amount!!, 0f)
        assertEquals(tresiba.id, imported.insulinPresetId)
        assertEquals(null, parseNightscout(document, listOf(generic)).insulinPresetId)
    }

    @Test
    fun aNamedBolusUsesItsOwnPresetAndABasalMarkerCannotSelectARapidPreset() {
        val generic = insulinPreset(1, "Rapid acting", JournalBuiltInCurveProfile.RAPID_GENERIC, true)
        val fiasp = insulinPreset(2, "Fiasp", JournalBuiltInCurveProfile.FIASP, true)
        val document = JSONObject()
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Correction Bolus")
            .put("insulin", 3)
            .put("insulinType", " fiasp ")
        assertEquals(fiasp.id, parseNightscout(document, listOf(generic, fiasp)).insulinPresetId)
        assertEquals(
            null,
            parseNightscout(document.put("isBasalInsulin", true), listOf(generic, fiasp)).insulinPresetId
        )
    }

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

    private fun parseAaps(document: JSONObject) = JournalTreatmentTransfer.parseTreatment(
        treatment = document,
        source = JournalEntrySource.AAPS,
        sourcePrefix = "aaps",
        insulinPresets = emptyList(),
        stringResource = { "Entry" },
    )

    /**
     * AAPS signs every treatment; a temp basal becomes a chart segment, never a note
     * made of the sender stamp.
     */
    @Test
    fun anAapsTempBasalIsASegmentNotANote() {
        val tempBasal = JSONObject()
            .put("_id", "aaps-temp-basal")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Temp Basal")
            .put("enteredBy", "openaps://AndroidAPS")
            .put("app", "AAPS")
            .put("duration", 30)
            .put("absolute", 0.4)

        val entry = parseAaps(tempBasal)!!.inputs.single()
        assertEquals(JournalEntryType.TEMP_BASAL, entry.type)
        assertEquals(0.4f, entry.amount!!, 0.0001f)
        assertEquals(30, entry.durationMinutes)
        assertEquals("aaps:aaps-temp-basal:temp_basal", entry.sourceRecordId)
    }

    /** A temp basal with no rate carries nothing to draw and takes away what it brought. */
    @Test
    fun anAapsTempBasalWithoutARateIsDeleteOnly() {
        val tempBasal = JSONObject()
            .put("_id", "aaps-temp-basal")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Temp Basal")
            .put("enteredBy", "openaps://AndroidAPS")
            .put("duration", 30)

        val parsed = parseAaps(tempBasal)!!
        assertTrue(parsed.inputs.isEmpty())
        assertTrue(parsed.deleteOnly)
    }

    @Test
    fun aNoteSayingOnlyWhoSentItIsNotANote() {
        val document = JSONObject()
            .put("_id", "aaps-note")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Note")
            .put("notes", "AAPS")
            .put("enteredBy", "AndroidAPS")

        assertTrue(parseAaps(document)!!.inputs.isEmpty())
    }

    /** The changed treatment must still reach the importer, or the old text would stay. */
    @Test
    fun aNoteWhoseTextWasClearedTakesAwayWhatItImportedBefore() {
        val document = JSONObject()
            .put("_id", "aaps-cleared-note")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Note")
            .put("notes", "")
            .put("enteredBy", "AndroidAPS")

        val parsed = parseAaps(document)!!
        assertTrue(parsed.deleteOnly)
        assertTrue("aaps:aaps-cleared-note:note" in parsed.candidateSourceRecordIds)
    }

    @Test
    fun theSenderStaysInsideTheEntryTheTreatmentCarries() {
        val document = JSONObject()
            .put("_id", "aaps-bolus")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Correction Bolus")
            .put("insulin", 1.5)
            .put("enteredBy", "AndroidAPS")

        val entry = parseAaps(document)!!.inputs.single()
        assertEquals(JournalEntryType.INSULIN, entry.type)
        assertEquals("AndroidAPS", entry.note)
    }

    @Test
    fun aWrittenNoteFromAapsIsStillANoteWithItsSenderInside() {
        val document = JSONObject()
            .put("_id", "aaps-written-note")
            .put("date", 1_786_794_604_000L)
            .put("eventType", "Note")
            .put("notes", "Pizza, slow carbs")
            .put("enteredBy", "AndroidAPS")

        val entry = parseAaps(document)!!.inputs.single()
        assertEquals(JournalEntryType.NOTE, entry.type)
        assertEquals("Pizza, slow carbs | AndroidAPS", entry.note)
    }

    @Test
    fun onlyNotesNamingNothingButAapsAreSweptAway() {
        assertTrue(JournalTreatmentTransfer.isAapsSenderOnlyNote("AndroidAPS"))
        assertTrue(JournalTreatmentTransfer.isAapsSenderOnlyNote("openaps://AndroidAPS"))
        assertTrue(JournalTreatmentTransfer.isAapsSenderOnlyNote("AAPS | AndroidAPS"))
        assertFalse(JournalTreatmentTransfer.isAapsSenderOnlyNote("Pizza | AndroidAPS"))
        assertFalse(JournalTreatmentTransfer.isAapsSenderOnlyNote("xDrip"))
        assertFalse(JournalTreatmentTransfer.isAapsSenderOnlyNote(null))
    }
}
