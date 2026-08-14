package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalDosePresetPolicyTests {
    private val now = 1_700_000_000_000L

    private fun preset(
        id: Long,
        sortOrder: Int,
        useForCalculation: Boolean,
        countsTowardIob: Boolean = true,
        isArchived: Boolean = false
    ) = JournalInsulinPreset(
        id = id,
        displayName = "Preset $id",
        onsetMinutes = 0,
        durationMinutes = 60,
        accentColor = 0,
        curveJson = "0:0;30:1;60:0",
        isBuiltIn = false,
        isArchived = isArchived,
        countsTowardIob = countsTowardIob,
        sortOrder = sortOrder,
        useForCalculation = useForCalculation
    )

    private fun dose(presetId: Long, units: Float = 2f) = JournalEntry(
        id = presetId,
        timestamp = now,
        sensorSerial = null,
        type = JournalEntryType.INSULIN,
        title = "",
        note = null,
        amount = units,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = presetId,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.MANUAL,
        sourceRecordId = null,
        createdAt = now,
        updatedAt = now
    )

    @Test
    fun preferredPresetSkipsCalculationDisabledAndArchivedInsulin() {
        val disabledLong = preset(id = 1, sortOrder = 0, useForCalculation = false)
        val archivedRapid = preset(id = 2, sortOrder = 1, useForCalculation = true, isArchived = true)
        val rapid = preset(id = 3, sortOrder = 2, useForCalculation = true)

        assertEquals(
            rapid,
            JournalDosePresetPolicy.preferredPreset(listOf(disabledLong, archivedRapid, rapid))
        )
        assertNull(JournalDosePresetPolicy.preferredPreset(listOf(disabledLong, archivedRapid)))
    }

    @Test
    fun calculationActiveInsulinExcludesIneligiblePresetWithoutChangingIobFlag() {
        val rapid = preset(id = 1, sortOrder = 0, useForCalculation = true)
        val excluded = preset(id = 2, sortOrder = 1, useForCalculation = false)
        val notIob = preset(id = 3, sortOrder = 2, useForCalculation = true, countsTowardIob = false)
        val presets = listOf(rapid, excluded, notIob).associateBy { it.id }

        assertEquals(
            2f,
            JournalDosePresetPolicy.activeInsulinUnitsAt(
                entries = listOf(dose(1), dose(2), dose(3)),
                presetsById = presets,
                atMillis = now
            ),
            0.001f
        )
    }

    @Test
    fun calculationEligibilityDoesNotRemoveDoseFromDashboardIob() {
        val excludedFromSuggestions = preset(
            id = 1,
            sortOrder = 0,
            useForCalculation = false,
            countsTowardIob = true
        )
        val presets = mapOf(excludedFromSuggestions.id to excludedFromSuggestions)

        val doses = JournalIobCalculator.dosesFromModels(listOf(dose(1)), presets)
        assertEquals(2f, JournalIobCalculator.compute(doses, now).iobUnits, 0.001f)
    }
}
