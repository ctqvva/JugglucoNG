package tk.glucodata.data.journal

data class JournalPeriodSummary(
    val eventCount: Int = 0,
    val insulinUnits: Float = 0f,
    val carbsGrams: Float = 0f,
    val proteinGrams: Float = 0f,
    val fatGrams: Float = 0f,
    val activityMinutes: Int = 0
)

object JournalPeriodSummaryCalculator {
    fun calculate(
        entries: List<JournalEntry>,
        startMillisInclusive: Long,
        endMillisExclusive: Long
    ): JournalPeriodSummary {
        if (endMillisExclusive <= startMillisInclusive) return JournalPeriodSummary()

        var eventCount = 0
        var insulinUnits = 0.0
        var carbsGrams = 0.0
        var proteinGrams = 0.0
        var fatGrams = 0.0
        var activityMinutes = 0

        entries.forEach { entry ->
            if (entry.timestamp !in startMillisInclusive until endMillisExclusive) return@forEach
            eventCount++
            when (entry.type) {
                JournalEntryType.INSULIN -> insulinUnits += entry.amount.validPositiveAmount()
                JournalEntryType.CARBS -> {
                    carbsGrams += entry.amount.validPositiveAmount()
                    proteinGrams += entry.proteinGrams.validPositiveAmount()
                    fatGrams += entry.fatGrams.validPositiveAmount()
                }
                JournalEntryType.ACTIVITY -> activityMinutes += entry.durationMinutes?.coerceAtLeast(0) ?: 0
                JournalEntryType.FINGERSTICK,
                JournalEntryType.NOTE -> Unit
            }
        }

        return JournalPeriodSummary(
            eventCount = eventCount,
            insulinUnits = insulinUnits.toFloat(),
            carbsGrams = carbsGrams.toFloat(),
            proteinGrams = proteinGrams.toFloat(),
            fatGrams = fatGrams.toFloat(),
            activityMinutes = activityMinutes
        )
    }

    private fun Float?.validPositiveAmount(): Double {
        return this?.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 0.0
    }
}
