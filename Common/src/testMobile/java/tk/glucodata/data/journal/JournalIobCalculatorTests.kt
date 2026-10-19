package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalIobCalculatorTests {

    private val doseTime = 1_700_000_000_000L

    // Triangle curve: activity ramps 0 -> 1 over 30 min, back to 0 at 60 min.
    // Trapezoidal integral: 0..30 = 15, total = 30.
    private fun trianglePreset(
        id: Long = 1L,
        countsTowardIob: Boolean = true,
        isArchived: Boolean = false
    ) = JournalInsulinPreset(
        id = id,
        displayName = "Triangle",
        onsetMinutes = 0,
        durationMinutes = 60,
        accentColor = 0,
        curveJson = "0:0;30:1;60:0",
        isBuiltIn = false,
        isArchived = isArchived,
        countsTowardIob = countsTowardIob,
        sortOrder = 0
    )

    // Fiasp-like curve with a flat zero-activity onset segment until minute 19.
    private fun onsetPreset(id: Long = 2L) = JournalInsulinPreset(
        id = id,
        displayName = "Onset",
        onsetMinutes = 19,
        durationMinutes = 360,
        accentColor = 0,
        curveJson = "0:0;19:0;55:1;360:0",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = true,
        sortOrder = 0
    )

    private fun entity(
        amount: Float?,
        presetId: Long? = 1L,
        entryType: String = "insulin",
        timestamp: Long = doseTime,
        curveSnapshot: String? = null
    ) = JournalEntryEntity(
        id = 0,
        timestamp = timestamp,
        sensorSerial = null,
        entryType = entryType,
        title = "",
        note = null,
        amount = amount,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = presetId,
        source = "manual",
        sourceRecordId = null,
        createdAt = timestamp,
        updatedAt = timestamp,
        insulinCurveJsonSnapshot = curveSnapshot
    )

    private fun model(
        amount: Float?,
        presetId: Long? = 1L,
        type: JournalEntryType = JournalEntryType.INSULIN,
        timestamp: Long = doseTime,
        curveSnapshot: String? = null
    ) = JournalEntry(
        id = 0,
        timestamp = timestamp,
        sensorSerial = null,
        type = type,
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

    private fun minutes(min: Long) = min * 60_000L

    @Test
    fun classicIobCountsFullAmountAtInjectionTime() {
        val presets = mapOf(1L to trianglePreset())
        val doses = JournalIobCalculator.dosesFromEntities(listOf(entity(10f)), presets)
        val result = JournalIobCalculator.compute(doses, doseTime)
        assertEquals(10f, result.iobUnits, 0.01f)
        assertEquals(0f, result.eiobUnits, 0.01f)
    }

    @Test
    fun iobDecaysWhileEiobFollowsActivityPeak() {
        val presets = mapOf(1L to trianglePreset())
        val doses = JournalIobCalculator.dosesFromEntities(listOf(entity(10f)), presets)
        val result = JournalIobCalculator.compute(doses, doseTime + minutes(30))
        // Half of the curve area delivered at the peak.
        assertEquals(5f, result.iobUnits, 0.05f)
        // At the 1.0 activity peak the whole remaining insulin is acting:
        // eIOB = amount * remaining(0.5) * activity(1.0) = IOB.
        assertEquals(5f, result.eiobUnits, 0.05f)
    }

    @Test
    fun windowedDeliveryIsIobDecayAcrossTheWindow() {
        // The notification risk warning projects with iob(t) - iob(t+30min):
        // triangle curve delivers exactly half its 10U in the first 30 minutes.
        val presets = mapOf(1L to trianglePreset())
        val doses = JournalIobCalculator.dosesFromEntities(listOf(entity(10f)), presets)
        val now = JournalIobCalculator.compute(doses, doseTime).iobUnits
        val afterWindow = JournalIobCalculator.compute(doses, doseTime + minutes(30)).iobUnits
        assertEquals(5f, now - afterWindow, 0.05f)
        // Decay is monotonic: the window quantity can never be negative.
        for (elapsed in 0..90 step 10) {
            val a = JournalIobCalculator.compute(doses, doseTime + minutes(elapsed.toLong())).iobUnits
            val b = JournalIobCalculator.compute(doses, doseTime + minutes(elapsed.toLong() + 30)).iobUnits
            assertTrue("delivery negative at +${elapsed}min", a - b >= -0.0001f)
        }
    }

    @Test
    fun eiobNeverExceedsIob() {
        val presets = mapOf(1L to trianglePreset(), 2L to onsetPreset())
        val entries = listOf(
            entity(10f),
            entity(4f, presetId = 2L, timestamp = doseTime + minutes(20))
        )
        val doses = JournalIobCalculator.dosesFromEntities(entries, presets)
        for (elapsed in 0..400 step 5) {
            val result = JournalIobCalculator.compute(doses, doseTime + minutes(elapsed.toLong()))
            assertTrue(
                "eIOB ${result.eiobUnits} > IOB ${result.iobUnits} at +${elapsed}min",
                result.eiobUnits <= result.iobUnits + 0.0001f
            )
        }
    }

    @Test
    fun bothZeroAfterCurveEnd() {
        val presets = mapOf(1L to trianglePreset())
        val doses = JournalIobCalculator.dosesFromEntities(listOf(entity(10f)), presets)
        val result = JournalIobCalculator.compute(doses, doseTime + minutes(90))
        assertEquals(0f, result.iobUnits, 0.001f)
        assertEquals(0f, result.eiobUnits, 0.001f)
    }

    @Test
    fun futureDosesContributeNothing() {
        val presets = mapOf(1L to trianglePreset())
        val doses = JournalIobCalculator.dosesFromEntities(listOf(entity(10f)), presets)
        val result = JournalIobCalculator.compute(doses, doseTime - minutes(5))
        assertEquals(0f, result.iobUnits, 0.001f)
        assertEquals(0f, result.eiobUnits, 0.001f)
    }

    @Test
    fun presetNotCountingTowardIobIsExcluded() {
        val presets = mapOf(1L to trianglePreset(countsTowardIob = false))
        val doses = JournalIobCalculator.dosesFromEntities(listOf(entity(10f)), presets)
        assertTrue(doses.isEmpty())
    }

    @Test
    fun nonInsulinUnknownPresetAndInvalidAmountsAreExcluded() {
        val presets = mapOf(1L to trianglePreset())
        val entries = listOf(
            entity(40f, entryType = "carbs"),
            entity(10f, presetId = null),
            entity(10f, presetId = 99L),
            entity(null),
            entity(0f),
            entity(-2f)
        )
        val doses = JournalIobCalculator.dosesFromEntities(entries, presets)
        assertTrue(doses.isEmpty())
    }

    @Test
    fun archivedPresetStillCounts() {
        // Archiving hides a preset from the picker; insulin already injected with
        // it keeps acting, so it must keep counting toward IOB (underreporting
        // IOB invites insulin stacking).
        val presets = mapOf(1L to trianglePreset(isArchived = true))
        val doses = JournalIobCalculator.dosesFromEntities(listOf(entity(10f)), presets)
        val result = JournalIobCalculator.compute(doses, doseTime)
        assertEquals(10f, result.iobUnits, 0.01f)
    }

    @Test
    fun perDoseSnapshotWinsAfterPresetCurveChanges() {
        val presets = mapOf(1L to trianglePreset())
        val dose = entity(
            amount = 10f,
            curveSnapshot = "0:0;60:1;120:0"
        )

        val result = JournalIobCalculator.compute(
            JournalIobCalculator.dosesFromEntities(listOf(dose), presets),
            doseTime + minutes(60)
        )

        // The mutable preset ended at 60 minutes. The stored 120-minute dose
        // curve is halfway through its area and must remain authoritative.
        assertEquals(5f, result.iobUnits, 0.05f)
        assertEquals(5f, result.eiobUnits, 0.05f)
    }

    @Test
    fun entityAndModelPathsAgree() {
        val presets = mapOf(1L to trianglePreset())
        val at = doseTime + minutes(20)
        val fromEntities = JournalIobCalculator.compute(
            JournalIobCalculator.dosesFromEntities(listOf(entity(7.5f)), presets), at
        )
        val fromModels = JournalIobCalculator.compute(
            JournalIobCalculator.dosesFromModels(listOf(model(7.5f)), presets), at
        )
        assertEquals(fromEntities.iobUnits, fromModels.iobUnits, 0.0001f)
        assertEquals(fromEntities.eiobUnits, fromModels.eiobUnits, 0.0001f)
    }

    @Test
    fun multipleDosesAreSummed() {
        val presets = mapOf(1L to trianglePreset())
        val entries = listOf(
            entity(10f),
            entity(4f, timestamp = doseTime + minutes(30))
        )
        val doses = JournalIobCalculator.dosesFromEntities(entries, presets)
        val result = JournalIobCalculator.compute(doses, doseTime + minutes(30))
        // First dose half delivered (5U) + second dose fully on board (4U).
        assertEquals(9f, result.iobUnits, 0.05f)
        // First dose: remaining 0.5 * peak activity 1.0 * 10U = 5U;
        // second dose still at zero activity.
        assertEquals(5f, result.eiobUnits, 0.05f)
    }

    @Test
    fun summaryIncludesFreshBolusBeforeActivityOnset() {
        val presets = mapOf(2L to onsetPreset())
        val at = doseTime + minutes(10)
        val summary = JournalIobCalculator.buildActiveInsulinSummary(
            listOf(model(10f, presetId = 2L)), presets, at
        )
        assertNotNull(summary)
        summary!!
        assertEquals(1, summary.activeEntryCount)
        assertEquals(10f, summary.iobUnits, 0.05f)
        assertEquals(0f, summary.eiobUnits, 0.05f)
        assertEquals(0, summary.weightedActivityPercent)
    }

    @Test
    fun summaryRemainsActiveUntilLatestDoseEnds() {
        val preset = trianglePreset()
        val presets = mapOf(preset.id to preset)
        val laterDoseTime = doseTime + minutes(30)
        val summary = JournalIobCalculator.buildActiveInsulinSummary(
            listOf(
                model(2f),
                model(4.5f, timestamp = laterDoseTime)
            ),
            presets,
            doseTime + minutes(45)
        )

        assertNotNull(summary)
        assertEquals(preset.activeEndAt(laterDoseTime), summary!!.activeUntil)
    }

    @Test
    fun summaryNullWhenAllDosesExpired() {
        val presets = mapOf(1L to trianglePreset())
        val summary = JournalIobCalculator.buildActiveInsulinSummary(
            listOf(model(10f)), presets, doseTime + minutes(120)
        )
        assertNull(summary)
    }

    @Test
    fun summaryMatchesComputeMidCurve() {
        val presets = mapOf(1L to trianglePreset())
        val at = doseTime + minutes(15)
        val entries = listOf(model(10f))
        val summary = JournalIobCalculator.buildActiveInsulinSummary(entries, presets, at)
        val result = JournalIobCalculator.compute(
            JournalIobCalculator.dosesFromModels(entries, presets), at
        )
        assertNotNull(summary)
        summary!!
        assertEquals(result.iobUnits, summary.iobUnits, 0.0001f)
        assertEquals(result.eiobUnits, summary.eiobUnits, 0.0001f)
        assertEquals(10f, summary.totalUnits, 0.001f)
        assertEquals(50, summary.weightedActivityPercent)
    }
}
