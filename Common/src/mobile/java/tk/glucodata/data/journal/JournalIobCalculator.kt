package tk.glucodata.data.journal

import kotlin.math.roundToInt

/**
 * Single source of truth for insulin-on-board math, shared by the journal UI,
 * the outbound API snapshot and the glucodata.Minute broadcast. The arithmetic
 * itself is [JournalIobMath] in `src/main` -- the watch computes IOB/COB from
 * its synced journal (plan §4 category W, D1) and cannot see Room, so this
 * object keeps the phone's entry points and delegates.
 *
 * Two distinct quantities are computed from the same dose list:
 *  - [Result.iobUnits]: classic IOB — remaining future action,
 *    `sum(amount * (1 - deliveredCurveArea/totalCurveArea))`. Counts the full
 *    amount from injection time (t=0 -> 100%), monotonically falling. This is
 *    the semantic external consumers (GDH/AAPS-style) expect.
 *  - [Result.eiobUnits]: activity-based "effective" IOB —
 *    `sum(amount * remainingFraction(t) * activityLevel(t))`, i.e. how much of
 *    the remaining insulin is actively working right now. Zero until the
 *    action curve's onset, equals IOB at the activity peak, never exceeds IOB.
 */
object JournalIobCalculator {

    data class Dose(
        val timestampMillis: Long,
        val amountUnits: Float,
        val preset: JournalInsulinPreset,
        val curvePoints: List<JournalCurvePoint>
    )

    data class Result(
        val iobUnits: Float,
        val eiobUnits: Float
    )

    fun dosesFromEntities(
        entries: List<JournalEntryEntity>,
        presetsById: Map<Long, JournalInsulinPreset>
    ): List<Dose> = entries.mapNotNull { entry ->
        if (JournalEntryType.fromStorage(entry.entryType) != JournalEntryType.INSULIN) return@mapNotNull null
        toDose(
            entry.timestamp,
            entry.amount,
            entry.insulinPresetId,
            entry.insulinCurveJsonSnapshot,
            presetsById
        )
    }

    fun dosesFromModels(
        entries: List<JournalEntry>,
        presetsById: Map<Long, JournalInsulinPreset>
    ): List<Dose> = entries.mapNotNull { entry ->
        if (entry.type != JournalEntryType.INSULIN) return@mapNotNull null
        toDose(
            entry.timestamp,
            entry.amount,
            entry.insulinPresetId,
            entry.insulinCurveJsonSnapshot,
            presetsById
        )
    }

    private fun toDose(
        timestamp: Long,
        amount: Float?,
        presetId: Long?,
        curveJsonSnapshot: String?,
        presetsById: Map<Long, JournalInsulinPreset>
    ): Dose? {
        // Archived presets intentionally still count: insulin injected with a
        // since-disabled preset keeps acting.
        val preset = presetId?.let(presetsById::get) ?: return null
        if (!preset.countsTowardIob) return null
        val units = amount?.takeIf { it.isFinite() && it > 0f } ?: return null
        val snapshotPoints = parseJournalCurve(curveJsonSnapshot)
        val points = if (snapshotPoints.size >= 2) snapshotPoints else preset.curvePoints
        return Dose(timestamp, units, preset, points)
    }

    fun compute(doses: List<Dose>, atMillis: Long): Result {
        val result = JournalIobMath.compute(
            doses.map { IobDose(it.timestampMillis, it.amountUnits, it.curvePoints) },
            atMillis,
        )
        return Result(result.iobUnits, result.eiobUnits)
    }

    fun remainingCurveFraction(
        points: List<JournalCurvePoint>,
        doseTimestampMillis: Long,
        atMillis: Long
    ): Float = JournalIobMath.remainingCurveFraction(points, doseTimestampMillis, atMillis)

    fun activityFractionAt(
        points: List<JournalCurvePoint>,
        doseTimestampMillis: Long,
        atMillis: Long
    ): Float = JournalIobMath.activityFractionAt(points, doseTimestampMillis, atMillis)

    fun buildActiveInsulinSummary(
        entries: List<JournalEntry>,
        presetsById: Map<Long, JournalInsulinPreset>,
        atMillis: Long
    ): JournalActiveInsulinSummary? {
        val active = entries.mapNotNull { entry ->
            if (entry.type != JournalEntryType.INSULIN) return@mapNotNull null
            val dose = toDose(
                entry.timestamp,
                entry.amount,
                entry.insulinPresetId,
                entry.insulinCurveJsonSnapshot,
                presetsById
            )
                ?: return@mapNotNull null
            val activity = activityFractionAt(dose.curvePoints, dose.timestampMillis, atMillis)
            val remaining = remainingCurveFraction(dose.curvePoints, dose.timestampMillis, atMillis)
            // A dose is on board from injection until its curve is fully spent —
            // include the pre-onset window where activity is still ~0.
            if (activity <= 0.01f && remaining <= 0.001f) return@mapNotNull null
            Triple(dose, activity, remaining)
        }
        if (active.isEmpty()) return null

        val totalUnits = active.sumOf { it.first.amountUnits.toDouble() }.toFloat()
        val weightedActivity = active.sumOf { (it.first.amountUnits * it.second).toDouble() }.toFloat()
        return JournalActiveInsulinSummary(
            activeEntryCount = active.size,
            totalUnits = totalUnits,
            weightedActivityPercent = ((weightedActivity / totalUnits) * 100f).roundToInt().coerceIn(0, 100),
            activeUntil = active.maxOfOrNull { activeDose ->
                activeDose.first.timestampMillis +
                    ((activeDose.first.curvePoints.lastOrNull()?.minute ?: 0).coerceAtLeast(0) * 60_000L)
            },
            iobUnits = active.sumOf { (it.first.amountUnits * it.third).toDouble() }.toFloat(),
            eiobUnits = active.sumOf { (it.first.amountUnits * it.third * it.second).toDouble() }.toFloat()
        )
    }
}
