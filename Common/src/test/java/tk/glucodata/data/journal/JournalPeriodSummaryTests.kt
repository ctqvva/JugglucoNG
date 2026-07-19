package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalPeriodSummaryTests {
    @Test
    fun calculateUsesOnlyTheRequestedHalfOpenPeriod() {
        val start = 1_000L
        val end = 2_000L
        val entries = listOf(
            entry(1L, start - 1, JournalEntryType.INSULIN, amount = 9f),
            entry(2L, start, JournalEntryType.INSULIN, amount = 2.5f),
            entry(3L, 1_500L, JournalEntryType.CARBS, amount = 30f, protein = 4f, fat = 7f),
            entry(4L, 1_600L, JournalEntryType.ACTIVITY, duration = 45),
            entry(5L, end, JournalEntryType.CARBS, amount = 99f)
        )

        val summary = JournalPeriodSummaryCalculator.calculate(entries, start, end)

        assertEquals(3, summary.eventCount)
        assertEquals(2.5f, summary.insulinUnits, 0.001f)
        assertEquals(30f, summary.carbsGrams, 0.001f)
        assertEquals(4f, summary.proteinGrams, 0.001f)
        assertEquals(7f, summary.fatGrams, 0.001f)
        assertEquals(45, summary.activityMinutes)
    }

    @Test
    fun calculateIgnoresInvalidAmountsAndClampsNegativeDuration() {
        val entries = listOf(
            entry(1L, 10L, JournalEntryType.INSULIN, amount = Float.NaN),
            entry(2L, 20L, JournalEntryType.CARBS, amount = -4f, protein = Float.POSITIVE_INFINITY),
            entry(3L, 30L, JournalEntryType.ACTIVITY, duration = -20)
        )

        val summary = JournalPeriodSummaryCalculator.calculate(entries, 0L, 100L)

        assertEquals(3, summary.eventCount)
        assertEquals(0f, summary.insulinUnits, 0.001f)
        assertEquals(0f, summary.carbsGrams, 0.001f)
        assertEquals(0f, summary.proteinGrams, 0.001f)
        assertEquals(0, summary.activityMinutes)
    }

    private fun entry(
        id: Long,
        timestamp: Long,
        type: JournalEntryType,
        amount: Float? = null,
        protein: Float? = null,
        fat: Float? = null,
        duration: Int? = null
    ) = JournalEntry(
        id = id,
        timestamp = timestamp,
        sensorSerial = null,
        type = type,
        title = type.name,
        note = null,
        amount = amount,
        glucoseValueMgDl = null,
        durationMinutes = duration,
        intensity = null,
        insulinPresetId = null,
        foodId = null,
        proteinGrams = protein,
        fatGrams = fat,
        source = JournalEntrySource.MANUAL,
        sourceRecordId = null,
        createdAt = timestamp,
        updatedAt = timestamp
    )
}
