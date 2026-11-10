package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** The snapshot must not resurrect a dose the phone drops for a non-counting preset. */
    @Test
    fun aNonCountingPresetDoseWithASnapshotIsZeroOnBothSides() {
        val entries = listOf(model(amount = 6f, curveSnapshot = "0:0;20:1;120:0"))
        val presets = listOf(trianglePreset(countsTowardIob = false))
        for (offset in listOf(10L, 30, 90)) {
            val at = doseTime + minutes(offset)
            assertEquals(0f, phone(entries, presets, at).iobUnits, 0f)
            assertAgree(entries, presets, at, "non-counting with snapshot +$offset")
            assertEquals(0f, WearJournalIob.compute(watch(entries, presets, at), at).iobUnits, 0f)
        }
    }

    private fun longSnapshot(points: Int, stepMinutes: Int): String =
        (0 until points).joinToString(";") { i ->
            val activity = Math.sin(Math.PI * i / (points - 1)).toFloat()
            "${i * stepMinutes}:$activity"
        }

    @Test
    fun aCurveLongerThanTheWireCapKeepsItsTrueEnd() {
        val entries = listOf(model(amount = 6f, curveSnapshot = longSnapshot(40, 6)))
        val presets = listOf(trianglePreset())
        val journal = watch(entries, presets, doseTime)
        assertEquals(24, journal.entries.single().curveMinutes.size)
        assertEquals(234, journal.entries.single().curveMinutes.last())
        // Stated tolerance: thinning a smooth 40-point curve to 24 moves IOB by < 0.1 U of 6 U.
        for (offset in listOf(0L, 30, 90, 150, 200, 225, 233, 240)) {
            val at = doseTime + minutes(offset)
            val onPhone = phone(entries, presets, at)
            val onWatch = WearJournalIob.compute(watch(entries, presets, at), at)
            assertEquals("IOB +$offset", onPhone.iobUnits, onWatch.iobUnits, 0.1f)
            assertEquals("eIOB +$offset", onPhone.eiobUnits, onWatch.eiobUnits, 0.1f)
        }
        val tail = doseTime + minutes(200)
        assertTrue(
            "the tail must still count on the watch",
            WearJournalIob.compute(watch(entries, presets, tail), tail).iobUnits > 0.01f
        )
    }

    @Test
    fun aLongPresetCurveWithoutASnapshotIsThinnedTheSameWay() {
        val preset = trianglePreset().copy(curveJson = longSnapshot(40, 6))
        val entries = listOf(model(amount = 6f))
        val at = doseTime + minutes(200)
        assertEquals(
            phone(entries, listOf(preset), at).iobUnits,
            WearJournalIob.compute(watch(entries, listOf(preset), at), at).iobUnits,
            0.1f
        )
    }

    @Test
    fun aCurveWithinTheCapIsUnchangedOnTheWire() {
        val points = (0 until 24).map { JournalCurvePoint(it * 5, it / 23f) }
        org.junit.Assert.assertSame(points, WearJournalBridge.thinCurve(points, 24))
        val snapshot = points.joinToString(";") { "${it.minute}:${it.activity}" }
        val journal = watch(listOf(model(amount = 6f, curveSnapshot = snapshot)), listOf(trianglePreset()), doseTime)
        assertEquals(points.map { it.minute }, journal.entries.single().curveMinutes.toList())
        assertEquals(points.map { it.activity }, journal.entries.single().curveActivity.toList())
    }

    @Test
    fun thinningKeepsEndpointsAndIsDeterministic() {
        val points = (0 until 100).map { JournalCurvePoint(it, 0.5f) }
        val a = WearJournalBridge.thinCurve(points, 24)
        assertEquals(24, a.size)
        assertEquals(points.first(), a.first())
        assertEquals(points.last(), a.last())
        assertEquals(a, WearJournalBridge.thinCurve(points, 24))
        assertEquals(a.map { it.minute }.sorted(), a.map { it.minute })
        assertEquals(24, a.map { it.minute }.toSet().size)
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

    // ---- COB ----------------------------------------------------------------------------------

    private fun carb(
        grams: Float?,
        durationMinutes: Int? = null,
        timestamp: Long = doseTime
    ) = model(amount = grams, presetId = null, timestamp = timestamp).copy(
        type = JournalEntryType.CARBS,
        durationMinutes = durationMinutes
    )

    private val rateGramsPerHour = 35f

    /**
     * The phone's COB for the same entries: `OutboundApiJournalSnapshot.activeCarbsGrams` is private
     * and reads prefs, so this repeats its (three-line) mapping over the same shared function, with
     * the entry's own `durationMinutes` and the per-timestamp rate. The watch must match this, not a
     * value recomputed from the wire.
     */
    private fun phoneCob(entries: List<JournalEntry>, atMillis: Long, rate: Float = rateGramsPerHour): Float =
        JournalIobMath.activeCarbsGrams(
            entries.mapNotNull { entry ->
                if (entry.type != JournalEntryType.CARBS) return@mapNotNull null
                val grams = entry.amount?.takeIf { it.isFinite() && it > 0f } ?: return@mapNotNull null
                CarbEntry(entry.timestamp, grams, entry.durationMinutes?.toFloat())
            },
            atMillis
        ) { rate }

    private fun watchCob(entries: List<JournalEntry>, atMillis: Long, rate: Float = rateGramsPerHour): Float? =
        WearJournalIob.cobGrams(
            WearJournalSync.decode(WearJournalBridge.encode(entries, emptyList()) { rate }),
            atMillis
        )

    /**
     * Resolved minutes are rounded to whole minutes on the wire, so an entry with no explicit
     * duration can differ from the phone by up to grams * 0.5 / duration (largest as the carb
     * runs out): 40 g over 68.6 min is 0.25 g at +68 min. An explicit duration is exact.
     */
    private val cobTolerance = 0.3f

    @Test
    fun cobAgreesWithThePhoneForAnExplicitDuration() {
        val entries = listOf(carb(40f, durationMinutes = 90))
        for (offset in listOf(0L, 10, 45, 89, 90, 200)) {
            val at = doseTime + minutes(offset)
            assertEquals("+$offset min", phoneCob(entries, at), watchCob(entries, at)!!, 1e-4f)
        }
        assertTrue(watchCob(entries, doseTime + minutes(45))!! > 0f)
    }

    @Test
    fun cobAgreesWhenThePhoneHadToResolveTheDuration() {
        // 40 g at 35 g/h is 68.57 min: the wire carries 69, so the answers differ slightly.
        val entries = listOf(carb(40f))
        for (offset in listOf(0L, 20, 34, 68, 69, 120)) {
            val at = doseTime + minutes(offset)
            assertEquals("+$offset min", phoneCob(entries, at), watchCob(entries, at)!!, cobTolerance)
        }
        assertTrue(watchCob(entries, doseTime + minutes(20))!! > 0f)
    }

    @Test
    fun cobAgreesOnTwoCarbs() {
        val entries = listOf(
            carb(40f, durationMinutes = 120, timestamp = doseTime - minutes(60)),
            carb(25f, timestamp = doseTime - minutes(15))
        )
        assertEquals(phoneCob(entries, doseTime), watchCob(entries, doseTime)!!, cobTolerance)
    }

    @Test
    fun aFullyAbsorbedCarbIsZeroOnBothSides() {
        val entries = listOf(carb(40f, durationMinutes = 60))
        val at = doseTime + minutes(600)
        assertEquals(0f, phoneCob(entries, at), 0f)
        assertEquals(0f, watchCob(entries, at)!!, 0f)
    }

    @Test
    fun unusableCarbAmountsContributeNothingAndDoNotMakeCobUnavailable() {
        val at = doseTime + minutes(10)
        for (grams in listOf(0f, -5f, Float.NaN, Float.POSITIVE_INFINITY, null)) {
            val entries = listOf(carb(grams, durationMinutes = 90), carb(30f, durationMinutes = 90))
            assertEquals("grams=$grams", phoneCob(entries, at), watchCob(entries, at)!!, 1e-4f)
        }
    }

    @Test
    fun cobIsUnavailableWhenACarbEntryHasNoMinutes() {
        // An old phone's payload: the encoder minus the absorption section. A number built from the
        // entries that do have minutes would disagree with the phone, so COB is withheld.
        val full = WearJournalBridge.encode(listOf(carb(40f, durationMinutes = 90)), emptyList()) { rateGramsPerHour }
        val oldPhone = full.copyOf(full.size - 4) // drops u16 count + one u16 minutes
        val journal = WearJournalSync.decode(oldPhone)
        assertEquals(1, journal.entries.size)
        assertNull(journal.entries.single().absorptionMinutes)
        assertNull(WearJournalIob.cobGrams(journal, doseTime + minutes(10)))
    }

    @Test
    fun aJournalWithNoCarbsHasZeroCobNotUnavailable() {
        val entries = listOf(model(amount = 6f))
        assertEquals(0f, watchCob(entries, doseTime)!!, 0f)
    }

    @Test
    fun aPendingWatchCarbIsNotCountedAndDoesNotWithholdCob() {
        val confirmed = WearJournalSync.decode(
            WearJournalBridge.encode(listOf(carb(40f, durationMinutes = 90)), emptyList()) { rateGramsPerHour }
        )
        val pending = WearJournalSync.Entry(
            timestampMs = doseTime, id = 0L, type = WearJournalSync.TYPE_CARBS,
            amount = 50f, title = "", pending = true
        )
        val at = doseTime + minutes(10)
        assertEquals(
            WearJournalIob.cobGrams(confirmed, at)!!,
            WearJournalIob.cobGrams(confirmed.copy(entries = confirmed.entries + pending), at)!!,
            0f
        )
    }
}
