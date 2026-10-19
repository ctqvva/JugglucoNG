package tk.glucodata.data.prediction

import kotlin.math.exp
import kotlin.math.sqrt

data class GlucosePredictionPoint(
    val timestamp: Long,
    val value: Float,
    val confidence: Float,
    /**
     * [value] before the on-chart clamp, so it may sit below the display floor or even
     * go negative. Dose maths must read this: the drawn value saturates at the floor,
     * which made a 6 U and a 16 U dose forecast the same carb suggestion.
     */
    val unclampedValue: Float = value
)

enum class GlucosePredictionSeriesKind {
    RAW,
    AUTO,
    CALIBRATED
}

data class GlucosePredictionSeries(
    val kind: GlucosePredictionSeriesKind,
    val points: List<GlucosePredictionPoint>
)

/**
 * The forward simulation itself, with no opinion about where the treatments
 * come from.
 *
 * The engine lived in the phone's source set and took the phone's rich journal
 * model, so the watch — which has a synced, much simpler journal — could not
 * run a prediction at all. Everything here is arithmetic over a history tail
 * plus one function from a timestamp to the modelled glucose delta at it, so
 * both devices can supply that function from whatever journal they hold.
 */
object GlucosePredictionKernel {

    /** A history reading, reduced to what the simulation actually reads. */
    data class Sample(val timestamp: Long, val value: Float)

    /** Below this a value is a hole rather than a reading. */
    private const val MIN_VALID_VALUE = 0.1f

    /** How far back the momentum regression looks. */
    private const val SLOPE_WINDOW_MS = 45L * 60L * 1000L
    private const val SLOPE_MAX_SAMPLES = 10

    fun simulate(
        history: List<Sample>,
        isMmol: Boolean,
        trendMomentumEnabled: Boolean,
        horizonMinutes: Int,
        stepMinutes: Int,
        targetLow: Float,
        targetHigh: Float,
        journalDeltaAt: (Long) -> Float,
    ): List<GlucosePredictionPoint> {
        if (history.size < 2) return emptyList()
        val baseline = history.asReversed().firstOrNull {
            it.value.isFinite() && it.value > MIN_VALID_VALUE
        } ?: return emptyList()
        val baselineTime = baseline.timestamp
        if (baselineTime <= 0L) return emptyList()

        val steps = stepMinutes.coerceIn(3, 15)
        val horizon = horizonMinutes.coerceIn(30, 360)
        val targetCenter = ((targetLow + targetHigh) * 0.5f).takeIf { it.isFinite() && it > 0f }
            ?: baseline.value

        val trendSlopePerMinute = if (trendMomentumEnabled) {
            // Regress over journal-residualized samples so momentum carries only
            // the slope the journal model does not already explain; the modelled
            // part is re-added through journalDeltaAt below, and would otherwise
            // be counted twice.
            val maxSlope = if (isMmol) 0.16f else 3f
            residualSlopePerMinute(history, baselineTime, journalDeltaAt).coerceIn(-maxSlope, maxSlope)
        } else {
            0f
        }

        fun projectedDeltaAt(timestamp: Long): Float {
            val minutesFuture = ((timestamp - baselineTime) / 60_000f).coerceAtLeast(0f)
            val trend = trendSlopePerMinute * minutesFuture * exp(-minutesFuture / 70f)
            val settling = (targetCenter - baseline.value) * (1f - exp(-minutesFuture / 240f)) * 0.18f
            return trend + settling + journalDeltaAt(timestamp)
        }

        val lowClamp = if (isMmol) 1.0f else 18f
        val highClamp = if (isMmol) 30f else 540f
        return buildList {
            add(GlucosePredictionPoint(baselineTime, baseline.value, confidence = 1f))
            var minute = steps
            while (minute <= horizon) {
                val timestamp = baselineTime + minute * 60_000L
                val progress = minute.toFloat() / horizon.toFloat()
                val confidence = (0.88f - 0.62f * sqrt(progress)).coerceIn(0.18f, 0.88f)
                val projected = baseline.value + projectedDeltaAt(timestamp)
                add(
                    GlucosePredictionPoint(
                        timestamp = timestamp,
                        value = projected.coerceIn(lowClamp, highClamp),
                        confidence = confidence,
                        unclampedValue = projected
                    )
                )
                minute += steps
            }
        }
    }

    private fun residualSlopePerMinute(
        history: List<Sample>,
        baselineTime: Long,
        modeledDeltaAt: (Long) -> Float
    ): Float {
        val recent = history
            .asReversed()
            .asSequence()
            .filter { it.timestamp <= baselineTime && baselineTime - it.timestamp <= SLOPE_WINDOW_MS }
            .filter { it.value.isFinite() && it.value > MIN_VALID_VALUE }
            .take(SLOPE_MAX_SAMPLES)
            .toList()
            .asReversed()
        if (recent.size < 2) return 0f

        val firstTime = recent.first().timestamp
        val xs = recent.map { (it.timestamp - firstTime) / 60_000f }
        val ys = recent.map { it.value - modeledDeltaAt(it.timestamp) }
        val xMean = xs.average().toFloat()
        val yMean = ys.average().toFloat()
        var numerator = 0f
        var denominator = 0f
        for (index in recent.indices) {
            val dx = xs[index] - xMean
            numerator += dx * (ys[index] - yMean)
            denominator += dx * dx
        }
        return if (denominator > 0.001f) numerator / denominator else 0f
    }
}

/**
 * The treatment curves both devices model with. Kept beside the kernel so a
 * carb rise or an insulin drop is shaped identically wherever it is computed.
 */
object GlucoseTreatmentCurves {

    fun linearProgress(startMillis: Long, durationMinutes: Float, atMillis: Long): Float {
        if (atMillis <= startMillis) return 0f
        val elapsedMinutes = (atMillis - startMillis) / 60_000f
        return (elapsedMinutes / durationMinutes.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    /** Smoothstep over the absorption window: slow start, slow finish. */
    fun mealProgress(startMillis: Long, durationMinutes: Float, atMillis: Long): Float {
        val x = linearProgress(startMillis, durationMinutes, atMillis)
        return x * x * (3f - 2f * x)
    }

    /** The default absorption window for a carb amount, in minutes. */
    fun carbAbsorptionMinutes(grams: Float, absorptionGramsPerHour: Float): Float =
        (grams / absorptionGramsPerHour.coerceAtLeast(5f) * 60f).coerceIn(30f, 360f)

    /**
     * Display-unit rise contributed by [grams] between the baseline and [atMillis].
     */
    fun carbRise(
        grams: Float,
        startMillis: Long,
        baselineMillis: Long,
        atMillis: Long,
        absorptionMinutes: Float,
        carbRatioGramsPerUnit: Float,
        sensitivityDisplay: Float,
    ): Float {
        if (grams <= 0f) return 0f
        val totalRise = (grams / carbRatioGramsPerUnit.coerceAtLeast(1f)) * sensitivityDisplay
        return totalRise * (
            mealProgress(startMillis, absorptionMinutes, atMillis) -
                mealProgress(startMillis, absorptionMinutes, baselineMillis)
            )
    }

    /**
     * Fraction of an insulin dose's total action delivered by [atMillis], from a
     * piecewise-linear activity curve given as (minute, activity) pairs.
     */
    fun cumulativeCurveFraction(
        curveMinutes: IntArray,
        curveActivity: FloatArray,
        doseTimestamp: Long,
        atMillis: Long,
    ): Float {
        val size = minOf(curveMinutes.size, curveActivity.size)
        if (size < 2 || atMillis <= doseTimestamp) return 0f
        val elapsedMinutes = ((atMillis - doseTimestamp) / 60_000f).coerceAtLeast(0f)
        val total = integrate(curveMinutes, curveActivity, size, curveMinutes[size - 1].toFloat())
        if (total <= 0.0001f) return 0f
        return (integrate(curveMinutes, curveActivity, size, elapsedMinutes) / total).coerceIn(0f, 1f)
    }

    private fun integrate(
        minutes: IntArray,
        activity: FloatArray,
        size: Int,
        upToMinute: Float,
    ): Float {
        if (size < 2 || upToMinute <= minutes[0]) return 0f
        var area = 0f
        for (index in 0 until size - 1) {
            val startMinute = minutes[index]
            val endMinute = minutes[index + 1]
            if (upToMinute <= startMinute) break
            val segmentEndMinute = minOf(upToMinute, endMinute.toFloat())
            val segmentWidth = segmentEndMinute - startMinute
            if (segmentWidth <= 0f) continue
            val fullWidth = (endMinute - startMinute).coerceAtLeast(1).toFloat()
            val endFraction = ((segmentEndMinute - startMinute) / fullWidth).coerceIn(0f, 1f)
            val segmentEndActivity = activity[index] + ((activity[index + 1] - activity[index]) * endFraction)
            area += ((activity[index] + segmentEndActivity) * 0.5f) * segmentWidth
            if (upToMinute <= endMinute) break
        }
        return area
    }
}
