package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.WearJournalSync

/**
 * Ruling 1 on #500: the watch shows journal-derived IOB/eIOB. Users will compare its numbers
 * against the phone's, so the two have to agree — and the watch reads a *different* shape from
 * the one the phone computes over: the phone has entities and presets, the watch has decoded
 * wire entries whose curves the phone already resolved.
 *
 * These go through the real encoder and the real decoder, so a change to what the bridge puts
 * on the wire is a change to what these assert. The cases the wire gets right today are pinned
 * here; the two it cannot express (an archived preset's dose, and COB) are documented in
 * [WearJournalIob] and raised on #500 rather than asserted as agreement.
 */
class WearIobAgreementTests {

    private val doseTime = 1_700_000_000_000L

    private fun minutes(min: Long) = min * 60_000L

    private fun trianglePreset(
        id: Long = 1L,
        countsTowardIob: Boolean = true
    ) = JournalInsulinPreset(
        id = id,
        displayName = "Triangle",
        onsetMinutes = 0,
        durationMinutes = 60,
        accentColor = 0,
        curveJson = "0:0;30:1;60:0",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = countsTowardIob,
        sortOrder = 0
    )

    private fun model(
        amount: Float?,
        presetId: Long? = 1L,
        timestamp: Long = doseTime,
        curveSnapshot: String? = null
    ) = JournalEntry(
        id = 0,
        timestamp = timestamp,
        sensorSerial = null,
        type = JournalEntryType.INSULIN,
        title = "",
        note = null,
        amount = amount,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = presetId,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.MANUAL,
        sourceRecordId = null,
        createdAt = timestamp,
        updatedAt = timestamp,
        insulinCurveJsonSnapshot = curveSnapshot
    )

    /** The phone's answer for the same journal. */
    private fun phone(entries: List<JournalEntry>, presets: List<JournalInsulinPreset>, atMillis: Long) =
        JournalIobCalculator.compute(
            JournalIobCalculator.dosesFromModels(entries, presets.associateBy { it.id }),
            atMillis
        )

    /** The watch's answer, over what the bridge actually puts on the wire. */
    private fun watch(
        entries: List<JournalEntry>,
        presets: List<JournalInsulinPreset>,
        atMillis: Long
    ): WearJournalSync.Journal =
        WearJournalSync.decode(WearJournalBridge.encode(entries, presets))

    private fun assertAgree(
        entries: List<JournalEntry>,
        presets: List<JournalInsulinPreset>,
        atMillis: Long,
        what: String
    ) {
        val onPhone = phone(entries, presets, atMillis)
        val onWatch = WearJournalIob.compute(watch(entries, presets, atMillis), atMillis)
        assertEquals("$what: IOB", onPhone.iobUnits, onWatch.iobUnits, 1e-4f)
        assertEquals("$what: eIOB", onPhone.eiobUnits, onWatch.eiobUnits, 1e-4f)
    }

    @Test
    fun theWireAgreesWithThePhoneOnAPlainDose() {
        val entries = listOf(model(amount = 6f))
        assertAgree(entries, listOf(trianglePreset()), doseTime + minutes(30), "plain dose")
    }

    @Test
    fun theWireAgreesAtEveryPointOfTheCurve() {
        val entries = listOf(model(amount = 6f))
        // Mid-decay and past the end are where a lost or extra vertex would show up.
        for (offset in listOf(0L, 5, 30, 59, 61, 600)) {
            assertAgree(entries, listOf(trianglePreset()), doseTime + minutes(offset), "at +$offset min")
        }
    }

    /** The per-dose snapshot wins over the preset curve, and the wire has to carry it. */
    @Test
    fun theWireAgreesWhenTheDoseHasItsOwnCurve() {
        val entries = listOf(model(amount = 6f, curveSnapshot = "0:0;20:1;120:0"))
        // The snapshot has to be load-bearing, or agreement here would be luck: the same dose
        // without one must land somewhere else, or this case proves nothing about the wire.
        val withSnapshot = phone(entries, listOf(trianglePreset()), doseTime + minutes(40))
        val withoutSnapshot = phone(
            listOf(model(amount = 6f)),
            listOf(trianglePreset()),
            doseTime + minutes(40)
        )
        assertTrue(
            "the per-dose curve must change the answer, or this test is vacuous",
            kotlin.math.abs(withSnapshot.iobUnits - withoutSnapshot.iobUnits) > 0.01f
        )
        assertAgree(entries, listOf(trianglePreset()), doseTime + minutes(40), "own curve")
    }

    /** A preset that does not count is dropped on both sides: no curve, no dose. */
    @Test
    fun theWireAgreesWhenThePresetDoesNotCountTowardIob() {
        val entries = listOf(model(amount = 6f))
        val presets = listOf(trianglePreset(countsTowardIob = false))
        assertAgree(entries, presets, doseTime + minutes(30), "non-counting preset")
        assertEquals(
            0f,
            WearJournalIob.compute(watch(entries, presets, doseTime + minutes(30)), doseTime + minutes(30)).iobUnits,
            0f
        )
    }

    @Test
    fun theWireAgreesOnSeveralDosesAtOnce() {
        val entries = listOf(
            model(amount = 4f, timestamp = doseTime - minutes(90)),
            model(amount = 6f, timestamp = doseTime - minutes(30)),
            model(amount = 3f, timestamp = doseTime - minutes(10))
        )
        assertAgree(entries, listOf(trianglePreset()), doseTime + minutes(20), "three doses")
    }

    /** Non-insulin entries must not turn into doses on either side. */
    @Test
    fun theWireAgreesWhenTheJournalAlsoHasCarbsAndNotes() {
        val entries = listOf(
            model(amount = 6f),
            model(amount = 30f, timestamp = doseTime - minutes(20)).copy(type = JournalEntryType.CARBS),
            model(amount = 0f, timestamp = doseTime - minutes(5)).copy(
                type = JournalEntryType.NOTE,
                insulinPresetId = null
            )
        )
        assertAgree(entries, listOf(trianglePreset()), doseTime + minutes(25), "mixed journal")
    }

    /** A dose whose amount never arrived contributes nothing on either side. */
    @Test
    fun theWireAgreesOnAnUnusableAmount() {
        for (amount in listOf(0f, -2f, Float.NaN, null)) {
            assertAgree(
                listOf(model(amount = amount)),
                listOf(trianglePreset()),
                doseTime + minutes(30),
                "amount=$amount"
            )
        }
    }

    /**
     * Not an agreement: the case the wire cannot express. The phone counts a dose whose preset
     * has since been archived, `serveEntries` drops archived presets from the payload, so the
     * curve never arrives and the watch cannot count it. Pinned as a *known* divergence so the
     * behaviour is visible in the suite and a future fix flips this test.
     */
    @Test
    fun archivedPresetsAreTheKnownDivergence() {
        val archived = trianglePreset().copy(isArchived = true)
        val entries = listOf(model(amount = 6f))
        val at = doseTime + minutes(30)

        assertTrue(
            "the phone counts an archived preset's dose on purpose",
            phone(entries, listOf(archived), at).iobUnits > 0f
        )
        assertEquals(
            "and the watch cannot, because the curve is not on the wire — see #500",
            0f,
            WearJournalIob.compute(watch(entries, listOf(archived), at), at).iobUnits,
            0f
        )
    }
}