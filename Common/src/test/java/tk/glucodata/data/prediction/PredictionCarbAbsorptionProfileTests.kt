package tk.glucodata.data.prediction

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.GlucosePoint

class PredictionCarbAbsorptionProfileTests {

    private val profile = PredictionModelProfile.single(
        carbRatioGramsPerUnit = 10f,
        insulinSensitivityMgDlPerUnit = 50f,
        carbAbsorptionGramsPerHour = 20f
    )
        .addBlock(8 * 60)
        .updateBlock(8 * 60, carbAbsorptionGramsPerHour = 60f)

    @Test
    fun undatedMealCurveUsesAbsorptionFromEntryTimePeriod() {
        val slowMorning = predictionForEntryAt(hour = 7)
        val fastDaytime = predictionForEntryAt(hour = 9)

        assertTrue(
            "60 g/h daytime profile should raise the 30-minute forecast more than 20 g/h morning profile",
            fastDaytime > slowMorning
        )
    }

    @Test
    fun explicitMealDurationOverridesProfileAbsorption() {
        val slowMorning = predictionForEntryAt(hour = 7, durationMinutes = 90)
        val fastDaytime = predictionForEntryAt(hour = 9, durationMinutes = 90)

        assertEquals(slowMorning, fastDaytime, 0.001f)
    }

    private fun predictionForEntryAt(hour: Int, durationMinutes: Int? = null): Float {
        val timestamp = hour * 60L * 60L * 1000L
        val history = (0..2).map { index ->
            GlucosePoint(
                value = 100f,
                time = "",
                timestamp = timestamp - (2 - index) * 5L * 60_000L
            )
        }
        val entry = JournalEntry(
            id = 1L,
            timestamp = timestamp,
            sensorSerial = null,
            type = JournalEntryType.CARBS,
            title = "Meal",
            note = null,
            amount = 30f,
            glucoseValueMgDl = null,
            durationMinutes = durationMinutes,
            intensity = null,
            insulinPresetId = null,
            foodId = null,
            proteinGrams = null,
            fatGrams = null,
            source = JournalEntrySource.MANUAL,
            sourceRecordId = null,
            createdAt = timestamp,
            updatedAt = timestamp
        )
        val prediction = buildGlucosePrediction(
            history = history,
            journalEntries = listOf(entry),
            insulinPresetsById = emptyMap(),
            unit = "mg/dL",
            targetLow = 80f,
            targetHigh = 120f,
            settings = PredictiveSimulationSettings(
                enabled = true,
                trendMomentumEnabled = false,
                horizonMinutes = 30,
                stepMinutes = 5,
                modelProfile = profile,
                profileTimeZone = TimeZone.getTimeZone("UTC")
            )
        )
        return prediction.last().unclampedValue
    }
}
