package tk.glucodata.data.prediction

import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalIntensity
import tk.glucodata.data.journal.JournalCurvePoint
import tk.glucodata.data.journal.journalFoodTailDelayMinutes
import tk.glucodata.data.journal.journalFoodTailDurationMinutes
import tk.glucodata.data.journal.journalFoodTailEquivalentCarbs
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.GlucoseFormatter
import java.util.TimeZone
import kotlin.math.exp
import kotlin.math.sqrt

// GlucosePredictionPoint / GlucosePredictionSeries[Kind] now live beside the
// kernel in src/main so the watch can name them too.

data class PredictiveSimulationSettings(
    val enabled: Boolean,
    val trendMomentumEnabled: Boolean,
    val horizonMinutes: Int = 120,
    val stepMinutes: Int = 5,
    val carbRatioGramsPerUnit: Float = 10f,
    val insulinSensitivityMgDlPerUnit: Float = 54f,
    val carbAbsorptionGramsPerHour: Float = 35f,
    val foodMacrosEnabled: Boolean = false,
    val modelProfile: PredictionModelProfile? = null,
    val profileTimeZone: TimeZone = TimeZone.getDefault()
) {
    fun modelParametersAt(timestamp: Long): PredictionModelParameters {
        return modelProfile?.parametersAt(timestamp, profileTimeZone)
            ?: PredictionModelParameters(
                carbRatioGramsPerUnit = carbRatioGramsPerUnit,
                insulinSensitivityMgDlPerUnit = insulinSensitivityMgDlPerUnit,
                carbAbsorptionGramsPerHour = carbAbsorptionGramsPerHour
            )
    }
}

fun buildGlucosePrediction(
    history: List<GlucosePoint>,
    journalEntries: List<JournalEntry>,
    insulinPresetsById: Map<Long, JournalInsulinPreset>,
    unit: String,
    targetLow: Float,
    targetHigh: Float,
    settings: PredictiveSimulationSettings
): List<GlucosePredictionPoint> {
    if (!settings.enabled || history.size < 2) return emptyList()

    val baseline = history.asReversed().firstOrNull { it.value.isFinite() && it.value > 0.1f } ?: return emptyList()
    val baselineTime = baseline.timestamp
    if (baselineTime <= 0L) return emptyList()

    val isMmol = GlucoseFormatter.isMmol(unit)
    val horizonMinutes = settings.horizonMinutes.coerceIn(30, 360)
    val relevantEntries = journalEntries.filter { entry ->
        entry.timestamp in (baselineTime - 36L * 60L * 60L * 1000L)..(baselineTime + horizonMinutes * 60_000L)
    }

    fun journalDeltaAt(timestamp: Long): Float = relevantEntries.sumOf { entry ->
        val profile = settings.modelParametersAt(entry.timestamp)
        entry.projectedDisplayDelta(
            atMillis = timestamp,
            baselineMillis = baselineTime,
            sensitivityDisplay = GlucoseFormatter.displayFromMgDl(
                profile.insulinSensitivityMgDlPerUnit,
                isMmol
            ),
            carbRatioGramsPerUnit = profile.carbRatioGramsPerUnit.coerceAtLeast(1f),
            carbAbsorptionGramsPerHour = profile.carbAbsorptionGramsPerHour.coerceIn(
                PredictionModelProfile.CARB_ABSORPTION_MIN,
                PredictionModelProfile.CARB_ABSORPTION_MAX
            ),
            foodMacrosEnabled = settings.foodMacrosEnabled,
            insulinPresetsById = insulinPresetsById
        ).toDouble()
    }.toFloat()

    // The forward simulation is shared with the watch; only the treatment
    // model above is phone-specific, because only the phone holds the rich
    // journal it reads.
    return GlucosePredictionKernel.simulate(
        history = history.map { GlucosePredictionKernel.Sample(it.timestamp, it.value) },
        isMmol = isMmol,
        trendMomentumEnabled = settings.trendMomentumEnabled,
        horizonMinutes = settings.horizonMinutes,
        stepMinutes = settings.stepMinutes,
        targetLow = targetLow,
        targetHigh = targetHigh,
        journalDeltaAt = ::journalDeltaAt
    )
}

private fun JournalEntry.projectedDisplayDelta(
    atMillis: Long,
    baselineMillis: Long,
    sensitivityDisplay: Float,
    carbRatioGramsPerUnit: Float,
    carbAbsorptionGramsPerHour: Float,
    foodMacrosEnabled: Boolean,
    insulinPresetsById: Map<Long, JournalInsulinPreset>
): Float {
    return when (type) {
        JournalEntryType.CARBS -> {
            val grams = amount?.takeIf { it > 0f } ?: return 0f
            val absorptionMinutes = durationMinutes
                ?.coerceIn(15, 480)
                ?.toFloat()
                ?: (grams / carbAbsorptionGramsPerHour * 60f).coerceIn(30f, 360f)
            val totalRise = (grams / carbRatioGramsPerUnit) * sensitivityDisplay
            val carbRise = totalRise * (
                mealProgress(timestamp, absorptionMinutes, atMillis) -
                    mealProgress(timestamp, absorptionMinutes, baselineMillis)
                )
            if (!foodMacrosEnabled) return carbRise

            val tailGrams = journalFoodTailEquivalentCarbs(proteinGrams, fatGrams)
            if (tailGrams <= 0f) return carbRise
            val tailStart = timestamp + (journalFoodTailDelayMinutes(proteinGrams, fatGrams) * 60_000f).toLong()
            val tailDuration = journalFoodTailDurationMinutes(proteinGrams, fatGrams)
            val tailRise = (tailGrams / carbRatioGramsPerUnit) * sensitivityDisplay
            carbRise + tailRise * (
                mealProgress(tailStart, tailDuration, atMillis) -
                    mealProgress(tailStart, tailDuration, baselineMillis)
                )
        }
        JournalEntryType.INSULIN -> {
            val units = amount?.takeIf { it > 0f } ?: return 0f
            val preset = insulinPresetId?.let(insulinPresetsById::get) ?: return 0f
            if (!preset.countsTowardIob) return 0f
            val snapshot = tk.glucodata.data.journal.parseJournalCurve(insulinCurveJsonSnapshot)
            val points = if (snapshot.size >= 2) snapshot else preset.curvePoints
            val futureAction = cumulativeCurveFraction(points, timestamp, atMillis)
            val baselineAction = cumulativeCurveFraction(points, timestamp, baselineMillis)
            -(units * sensitivityDisplay * (futureAction - baselineAction))
        }
        JournalEntryType.ACTIVITY -> {
            val duration = (durationMinutes ?: 30).coerceIn(5, 240).toFloat()
            val intensityFactor = when (intensity) {
                JournalIntensity.LIGHT -> 0.35f
                JournalIntensity.MODERATE -> 0.65f
                JournalIntensity.INTENSE -> 1f
                null -> 0.5f
            }
            val oneHourDrop = sensitivityDisplay * 0.22f * intensityFactor
            -oneHourDrop * (duration / 60f) * (
                linearProgress(timestamp, duration, atMillis) -
                    linearProgress(timestamp, duration, baselineMillis)
                )
        }
        JournalEntryType.FINGERSTICK,
        JournalEntryType.NOTE -> 0f
    }
}

private fun linearProgress(startMillis: Long, durationMinutes: Float, atMillis: Long): Float {
    if (atMillis <= startMillis) return 0f
    val elapsedMinutes = (atMillis - startMillis) / 60_000f
    return (elapsedMinutes / durationMinutes.coerceAtLeast(1f)).coerceIn(0f, 1f)
}

private fun mealProgress(startMillis: Long, durationMinutes: Float, atMillis: Long): Float {
    val x = linearProgress(startMillis, durationMinutes, atMillis)
    return x * x * (3f - 2f * x)
}

private fun cumulativeCurveFraction(points: List<JournalCurvePoint>, doseTimestamp: Long, atMillis: Long): Float {
    if (points.size < 2 || atMillis <= doseTimestamp) return 0f
    val elapsedMinutes = ((atMillis - doseTimestamp) / 60_000f).coerceAtLeast(0f)
    val total = integrateCurve(points, points.last().minute.toFloat())
    if (total <= 0.0001f) return 0f
    return (integrateCurve(points, elapsedMinutes) / total).coerceIn(0f, 1f)
}

private fun integrateCurve(points: List<JournalCurvePoint>, upToMinute: Float): Float {
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
