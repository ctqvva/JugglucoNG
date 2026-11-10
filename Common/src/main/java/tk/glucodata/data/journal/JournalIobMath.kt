package tk.glucodata.data.journal

/**
 * Insulin-on-board and carbohydrate-on-board arithmetic, over inputs both
 * devices can produce. The watch shows IOB/COB (plan §4 category W, D1) and it
 * gets its journal as a synced payload rather than a Room table, so the maths
 * cannot live next to the phone's entities. It lives here instead, in `main`,
 * and the phone keeps its own entry points as thin delegates — the arithmetic
 * is unchanged, only where it is compiled.
 *
 * Two distinct insulin quantities come out of one dose list:
 *  - [IobResult.iobUnits]: classic IOB — remaining future action,
 *    `sum(amount * (1 - deliveredCurveArea/totalCurveArea))`. Counts the full
 *    amount from injection time (t=0 -> 100%), monotonically falling. This is
 *    the semantic external consumers (GDH/AAPS-style) expect.
 *  - [IobResult.eiobUnits]: activity-based "effective" IOB —
 *    `sum(amount * remainingFraction(t) * activityLevel(t))`, i.e. how much of
 *    the remaining insulin is actively working right now. Zero until the
 *    action curve's onset, equals IOB at the activity peak, never exceeds IOB.
 *
 * What the caller supplies is the dose list with each dose's curve already
 * resolved, and for carbs the absorption window the entry states or the profile
 * falls back to. Nothing here reads settings, a database or a clock, so both
 * sides get the same number for the same journal.
 */
data class IobCurvePoint(
    val minute: Int,
    val activity: Float
)

/** One dose of insulin, with the action curve that applies to it. */
data class IobDose(
    val timestampMillis: Long,
    val amountUnits: Float,
    val curvePoints: List<IobCurvePoint>
)

data class IobResult(
    val iobUnits: Float,
    val eiobUnits: Float
)

/**
 * A carb entry. [absorptionMinutes] is the entry's own stated absorption time
 * when it has one; null means "use the profile", which is why the profile is
 * asked per entry rather than once — `PredictionModelProfileStore` is
 * time-dependent.
 */
data class CarbEntry(
    val timestampMillis: Long,
    val grams: Float,
    val absorptionMinutes: Float? = null
)

object JournalIobMath {

    fun compute(doses: List<IobDose>, atMillis: Long): IobResult {
        var iob = 0.0
        var eiob = 0.0
        doses.forEach { dose ->
            val remaining = remainingCurveFraction(dose.curvePoints, dose.timestampMillis, atMillis)
            val activity = activityFractionAt(dose.curvePoints, dose.timestampMillis, atMillis)
            iob += (dose.amountUnits * remaining).toDouble()
            eiob += (dose.amountUnits * remaining * activity).toDouble()
        }
        return IobResult(iob.toFloat(), eiob.toFloat())
    }

    fun remainingCurveFraction(
        points: List<IobCurvePoint>,
        doseTimestampMillis: Long,
        atMillis: Long
    ): Float {
        if (points.size < 2 || atMillis < doseTimestampMillis) return 0f
        val elapsedMinutes = ((atMillis - doseTimestampMillis) / 60_000f).coerceAtLeast(0f)
        val total = integrateCurve(points, points.last().minute.toFloat())
        if (total <= 0.0001f) return 0f
        val delivered = (integrateCurve(points, elapsedMinutes) / total).coerceIn(0f, 1f)
        return (1f - delivered).coerceIn(0f, 1f)
    }

    fun activityFractionAt(
        points: List<IobCurvePoint>,
        doseTimestampMillis: Long,
        atMillis: Long
    ): Float {
        val elapsedMinutes = ((atMillis - doseTimestampMillis) / 60_000f).coerceAtLeast(0f)
        return interpolate(points, elapsedMinutes)
    }

    fun interpolate(points: List<IobCurvePoint>, minute: Float): Float {
        if (points.isEmpty()) return 0f
        if (minute <= points.first().minute.toFloat()) return points.first().activity
        if (minute >= points.last().minute.toFloat()) return points.last().activity

        val upperIndex = points.indexOfFirst { it.minute >= minute }.takeIf { it >= 0 } ?: return 0f
        val upper = points[upperIndex]
        val lower = points.getOrNull(upperIndex - 1) ?: return upper.activity
        val span = (upper.minute - lower.minute).toFloat().coerceAtLeast(1f)
        val progress = ((minute - lower.minute) / span).coerceIn(0f, 1f)
        return lower.activity + ((upper.activity - lower.activity) * progress)
    }

    private fun integrateCurve(
        points: List<IobCurvePoint>,
        upToMinute: Float
    ): Float {
        if (points.size < 2 || upToMinute <= points.first().minute) return 0f
        var area = 0f
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            if (upToMinute <= start.minute) break
            val segmentEndMinute = minOf(upToMinute, end.minute.toFloat())
            val segmentWidth = segmentEndMinute - start.minute
            if (segmentWidth <= 0f) continue
            val fullWidth = (end.minute - start.minute).coerceAtLeast(1).toFloat()
            val endFraction = ((segmentEndMinute - start.minute) / fullWidth).coerceIn(0f, 1f)
            val segmentEndActivity = start.activity + ((end.activity - start.activity) * endFraction)
            area += ((start.activity + segmentEndActivity) * 0.5f) * segmentWidth
            if (upToMinute <= end.minute) break
        }
        return area
    }

    /**
     * Grams of carbohydrate still absorbing at [atMillis].
     *
     * Absorption is linear over the entry's own window, and the window is the
     * entry's `durationMinutes` where it has one and otherwise the profile's
     * rate for that entry's timestamp, clamped to the 30–360 minute band the
     * phone has always used.
     */
    fun activeCarbsGrams(
        entries: List<CarbEntry>,
        atMillis: Long,
        absorptionGramsPerHourAt: (timestampMillis: Long) -> Float
    ): Float = entries.sumOf { entry ->
        val grams = entry.grams.takeIf { it.isFinite() && it > 0f } ?: return@sumOf 0.0
        val absorptionMinutes = entry.absorptionMinutes
            ?: (grams / absorptionGramsPerHourAt(entry.timestampMillis) * 60f)
                .coerceIn(30f, 360f)
        val progress = linearProgress(entry.timestampMillis, absorptionMinutes, atMillis)
        (grams * (1f - progress)).coerceAtLeast(0f).toDouble()
    }.toFloat()

    private fun linearProgress(startMillis: Long, durationMinutes: Float, atMillis: Long): Float {
        if (atMillis <= startMillis) return 0f
        val elapsedMinutes = (atMillis - startMillis) / 60_000f
        return (elapsedMinutes / durationMinutes.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }
}
