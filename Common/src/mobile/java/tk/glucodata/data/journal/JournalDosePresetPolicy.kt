package tk.glucodata.data.journal

/** Keeps dose-assist eligibility separate from dashboard IOB participation. */
object JournalDosePresetPolicy {
    fun preferredPreset(presets: List<JournalInsulinPreset>): JournalInsulinPreset? {
        return presets
            .asSequence()
            .filterNot { it.isArchived }
            .filter { it.useForCalculation }
            .minByOrNull { it.sortOrder }
    }

    fun activeInsulinUnitsAt(
        entries: List<JournalEntry>,
        presetsById: Map<Long, JournalInsulinPreset>,
        atMillis: Long
    ): Float {
        return entries.sumOf { entry ->
            if (entry.type != JournalEntryType.INSULIN) return@sumOf 0.0
            val preset = entry.insulinPresetId?.let(presetsById::get) ?: return@sumOf 0.0
            if (!preset.countsTowardIob || !preset.useForCalculation) return@sumOf 0.0
            val amount = entry.amount?.takeIf { it.isFinite() && it > 0f } ?: return@sumOf 0.0
            val snapshot = parseJournalCurve(entry.insulinCurveJsonSnapshot)
            val points = if (snapshot.size >= 2) snapshot else preset.curvePoints
            val remaining = JournalIobCalculator.remainingCurveFraction(
                points = points,
                doseTimestampMillis = entry.timestamp,
                atMillis = atMillis
            )
            (amount * remaining).toDouble()
        }.toFloat()
    }
}
