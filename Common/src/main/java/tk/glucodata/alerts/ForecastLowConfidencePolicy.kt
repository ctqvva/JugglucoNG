package tk.glucodata.alerts

/**
 * Pre-entry confirmation for PRE_LOW. These are evidence rules, not probabilities.
 * Call after the existing direction and COB gates, before updating alert episodes.
 * An admitted episode keeps its existing rearm, dismissal and retry semantics.
 */
internal class ForecastLowConfidencePolicy {
    private companion object {
        const val MIN_CONFIRMATION_MS = 60_000L
        // Accept normal five-minute sensors; longer gaps start a new run.
        const val MAX_READING_GAP_MS = 6 * 60_000L
        const val NEAR_THRESHOLD_MGDL = 15f
        const val URGENT_MINUTES = 10f
        const val MIN_INSULIN_EFFECT_MGDL = 10f
    }

    enum class Decision(val eligible: Boolean) {
        NO_CANDIDATE(false), CONFIRMING(false), EXISTING_EPISODE(true),
        NEAR_THRESHOLD(true), IMMINENT(true), INSULIN_SUPPORTED(true), CONFIRMED(true), RETURNING_TO_BASELINE(false), BELOW_BASELINE(true)
    }

    private data class Context(
        val sensorId: String?, val sensorGen: Int, val threshold: Float,
        val horizon: Int, val isMmol: Boolean
    )

    private var context: Context? = null
    private var lastReadingTimeMs = 0L
    private var previousValue = Float.NaN
    private var firstCandidateTimeMs = 0L
    private var candidates = 0

    /** Keep the timestamp watermark so a scheduler tick cannot become a new sample. */
    fun reset() {
        previousValue = Float.NaN
        firstCandidateTimeMs = 0L
        candidates = 0
    }

    fun evaluate(
        condition: StandardGlucoseAlertCondition?,
        rate: Float,
        readingTimeMs: Long,
        nowMs: Long,
        sensorId: String?,
        sensorGen: Int,
        forecastMinutes: Int?,
        isMmol: Boolean,
        episodeActive: Boolean,
        iobNext30: Float = Float.NaN,
        insulinSensitivity: Float = Float.NaN,
        classicIob: Float = Float.NaN,
        recentRiseBaselineMgdl: Float? = null
    ): Decision {
        if (condition == null) {
            reset()
            return Decision.NO_CANDIDATE
        }
        if (episodeActive) {
            reset()
            return Decision.EXISTING_EPISODE
        }
        val horizon = AlertGlucoseMath.normalizedForecastMinutes(forecastMinutes)
        val nextContext = Context(sensorId, sensorGen, condition.threshold, horizon, isMmol)
        if (context != nextContext) {
            reset()
            context = nextContext
        }
        val scale = if (isMmol) 18.0182f else 1f
        val current = condition.glucoseValue * scale
        val threshold = condition.threshold * scale
        val projected = condition.evaluatedValue * scale
        if (!current.isFinite() || !threshold.isFinite() || threshold <= 0f ||
            !projected.isFinite() || current < threshold || projected >= threshold ||
            !ForecastLowSuppression.hasDownwardArrow(rate) || readingTimeMs <= 0L ||
            nowMs < readingTimeMs || nowMs - readingTimeMs > MAX_READING_GAP_MS
        ) {
            reset()
            return Decision.NO_CANDIDATE
        }
        if (readingTimeMs < lastReadingTimeMs) {
            reset()
            return Decision.NO_CANDIDATE
        }
        if (readingTimeMs > lastReadingTimeMs) {
            if (readingTimeMs - lastReadingTimeMs > MAX_READING_GAP_MS ||
                (previousValue.isFinite() && current > previousValue)
            ) reset()
            lastReadingTimeMs = readingTimeMs
            previousValue = current
            if (candidates == 0) firstCandidateTimeMs = readingTimeMs
            candidates++
        }

        val distance = current - threshold
        if (distance <= NEAR_THRESHOLD_MGDL) return Decision.NEAR_THRESHOLD
        if (distance / -rate <= URGENT_MINUTES) return Decision.IMMINENT
        // Insulin only accelerates eligibility. Missing/invalid quantities never
        // disable glucose-only confirmation. The caller supplies a configured ISF;
        // a built-in sensitivity alone is not evidence of individual insulin effect.
        val insulinEffect = iobNext30.toDouble() * insulinSensitivity
        if (iobNext30.isFinite() && iobNext30 > 0f &&
            insulinSensitivity.isFinite() && insulinSensitivity > 0f &&
            insulinEffect >= maxOf(MIN_INSULIN_EFFECT_MGDL.toDouble(), distance * 0.5)
        ) return Decision.INSULIN_SUPPORTED
        // Only explicitly absent insulin supports interpreting a fall as a return
        // from a recent rise. Unknown journal data and insulin waiting to act do
        // not qualify. A continued fall below baseline releases immediately.
        val noInsulin = classicIob.isFinite() && classicIob in 0f..0.0001f &&
            iobNext30.isFinite() && iobNext30 in 0f..0.0001f
        val baseline = recentRiseBaselineMgdl?.takeIf { it.isFinite() && it > threshold + NEAR_THRESHOLD_MGDL }
        if (noInsulin && baseline != null) {
            return if (current >= baseline - 3f) Decision.RETURNING_TO_BASELINE else Decision.BELOW_BASELINE
        }
        return if (candidates >= 2 && readingTimeMs - firstCandidateTimeMs >= MIN_CONFIRMATION_MS) {
            Decision.CONFIRMED
        } else {
            Decision.CONFIRMING
        }
    }
}
