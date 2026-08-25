package tk.glucodata.data.prediction

import tk.glucodata.GlucoseDelta
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.GlucoseFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class StateDoseHintKind {
    CARBS,
    INSULIN
}

/**
 * @param amount grams for [StateDoseHintKind.CARBS], units for [StateDoseHintKind.INSULIN].
 * @param targetMgDl the dose target the hint is stated against, so the display can name it.
 * @param minutesAhead when the state it describes is still ahead — "under 90 in ~40 min".
 *   Null when the hint is about the state right now, which is the insulin case: a
 *   correction is computed on the current value, never on an extrapolated peak.
 */
data class StateDoseHint(
    val kind: StateDoseHintKind,
    val amount: Float,
    val targetMgDl: Float,
    val minutesAhead: Int? = null
)

/**
 * A complete evaluation can legitimately contain no hint. [Incomplete] is narrower: a
 * fresh reading exists, but the current history snapshot cannot yet form the trend needed
 * to decide. Keeping those states distinct prevents a transient Room refresh from looking
 * like a treatment decision changed.
 */
sealed interface StateDoseHintEvaluation {
    data class Complete(
        val hint: StateDoseHint?,
        val latestTimestamp: Long,
        val sensorSerial: String?
    ) : StateDoseHintEvaluation

    data class Incomplete(
        val latestTimestamp: Long?,
        val sensorSerial: String?
    ) : StateDoseHintEvaluation
}

/** The last complete hint and the reading identity that produced it. */
data class StateDoseHintDisplaySnapshot(
    val hint: StateDoseHint,
    val latestTimestamp: Long,
    val sensorSerial: String?
)

object StateDoseHintContinuity {
    /** Long enough to bridge a Room update burst, short enough not to mask missing data. */
    const val INCOMPLETE_HOLD_MILLIS = 5_000L

    fun canRetain(
        previous: StateDoseHintDisplaySnapshot?,
        incomplete: StateDoseHintEvaluation.Incomplete,
        nowMillis: Long,
        maxReadingAgeMillis: Long
    ): Boolean {
        val retained = previous ?: return false
        val latestTimestamp = incomplete.latestTimestamp ?: return false
        if (retained.sensorSerial != incomplete.sensorSerial) return false
        if (latestTimestamp < retained.latestTimestamp) return false
        return nowMillis - latestTimestamp in 0..maxReadingAgeMillis
    }
}

/**
 * A dose hint read off the **current** state rather than off the far end of the forecast
 * curve.
 *
 * Its predecessor compared the last point of the prediction against the dose target, which
 * put the decision on the least certain part of the curve: with the endpoint sitting near
 * the target, every new reading nudged it across and the suggestion changed sign — "carbs:
 * 2 g" and, twenty seconds later, "insulin: 0.2 U", neither of them an amount anyone can
 * act on.
 *
 * Two questions are answerable from the state itself:
 *
 * - falling, and on the current rate below the target within the horizon → how many carbs
 *   raise the measured projection back to the target;
 * - high, not coming down although insulin is already acting → how much more insulin the
 *   gap needs beyond what is already on board; and, when asked for, the same question for a
 *   value that is still inside the range but sitting well above the dose target with nothing
 *   left in flight to move it.
 *
 * The **error directions are not symmetric**, and the arithmetic follows that. Too many
 * carbs show up on the curve within the hour and are corrected; too few end in a hypo, so
 * that side rounds up. Its projection already measures the fall insulin is producing, so
 * adding the modelled IOB effect again would count the same action twice. Too much insulin
 * is noticed hours later and can only be eaten back, so that side computes on the current
 * value (a curve can turn before its peak, and insulin given for a peak that never arrives
 * cannot be taken back), subtracts **all** insulin on board rather than the acting part,
 * and rounds down to the pen's step.
 *
 * Known gap, and the reason for the conservatism above: without carb entries a rise from
 * an untreated meal and a rise from too little insulin look identical. That does not make
 * the arithmetic on the current value wrong, but it does mean the insulin side must never
 * be generous.
 */
object StateDoseHintCalculator {
    /** How far ahead the falling case is allowed to look. */
    const val HORIZON_MINUTES_MIN = 30
    const val HORIZON_MINUTES_MAX = 60
    const val HORIZON_MINUTES_DEFAULT = 45

    /** Readings older than this contribute nothing to the trend. */
    private const val TREND_WINDOW_MINUTES = 30f

    /** Below three readings, or a span this short, a slope is noise. */
    private const val TREND_MIN_SAMPLES = 3
    private const val TREND_MIN_SPAN_MINUTES = 10f

    /**
     * A fall shallower than this is drift, not a fall. Roughly 0.3 mg/dL per minute is
     * 4.5 mg/dL over a quarter of an hour — inside sensor noise.
     */
    private const val FALL_FLOOR_MGDL_PER_MINUTE = 0.3f

    /**
     * "Not coming down": at or above this slope the value is rising or holding. A slight
     * negative slope still counts, because a value drifting down 0.1 mg/dL per minute from
     * 260 is not on its way anywhere useful.
     */
    private const val STAGNANT_MGDL_PER_MINUTE = -0.2f

    /** How long the rise must already have held before it says anything. */
    private const val RISE_MIN_SPAN_MINUTES = 20f

    /**
     * Silence around the target. Inside this band the state has no direction worth naming,
     * and a hint would alternate between carbs and insulin on sensor noise alone.
     */
    private const val HYSTERESIS_MGDL = 20f

    /**
     * How far above the dose target a still-in-range value has to sit before the correction
     * case looks at it. A little wider than the hysteresis band, so that the stretch just
     * above the target stays quiet rather than collecting suggestions.
     */
    private const val IN_RANGE_CORRECTION_MARGIN_MGDL = 25f

    /** Below these, showing an amount is worse than showing nothing. */
    private const val MIN_CARBS_GRAMS = 5f
    private const val PEN_STEP_UNITS = 0.5f

    /**
     * @param history the drawn readings, oldest first, in display units.
     * @param unit the display unit, for the mg/dL conversion.
     * @param targetHighDisplay upper bound of the in-range band, which picks which of the
     *   two correction cases a reading belongs to. Never what a correction aims at: the band
     *   is a display range, far too wide to dose against, but exactly right for telling
     *   "high" from "still in range".
     * @param doseTargetMgDl what a correction aims at.
     * @param iobUnits insulin on board — remaining future action, the whole of it.
     * @param eiobUnits the acting part of it. Read only above the band, to tell "the insulin
     *   is not enough" from an ordinary uncorrected high, which is not this hint's business.
     * @param parameters sensitivity and carb ratio for now, from the model profile.
     * @param horizonMinutes how far ahead the falling case looks.
     * @param correctInRange whether a value that is still inside the range but sitting well
     *   above the dose target, with nothing left in flight to move it, gets a correction too.
     * @param maxReadingAgeMillis a reading older than this says nothing about now.
     */
    fun calculate(
        history: List<GlucosePoint>,
        unit: String,
        targetHighDisplay: Float,
        doseTargetMgDl: Float,
        iobUnits: Float,
        eiobUnits: Float,
        parameters: PredictionModelParameters,
        horizonMinutes: Int,
        correctInRange: Boolean,
        nowMillis: Long,
        maxReadingAgeMillis: Long
    ): StateDoseHint? = when (val evaluation = evaluate(
        history = history,
        unit = unit,
        targetHighDisplay = targetHighDisplay,
        doseTargetMgDl = doseTargetMgDl,
        iobUnits = iobUnits,
        eiobUnits = eiobUnits,
        parameters = parameters,
        horizonMinutes = horizonMinutes,
        correctInRange = correctInRange,
        nowMillis = nowMillis,
        maxReadingAgeMillis = maxReadingAgeMillis
    )) {
        is StateDoseHintEvaluation.Complete -> evaluation.hint
        is StateDoseHintEvaluation.Incomplete -> null
    }

    fun evaluate(
        history: List<GlucosePoint>,
        unit: String,
        targetHighDisplay: Float,
        doseTargetMgDl: Float,
        iobUnits: Float,
        eiobUnits: Float,
        parameters: PredictionModelParameters,
        horizonMinutes: Int,
        correctInRange: Boolean,
        nowMillis: Long,
        maxReadingAgeMillis: Long
    ): StateDoseHintEvaluation {
        val latest = history.lastOrNull { it.timestamp > 0L && it.value.isFinite() && it.value > 0.1f }
            ?: return StateDoseHintEvaluation.Incomplete(null, null)
        fun complete(hint: StateDoseHint?) = StateDoseHintEvaluation.Complete(
            hint = hint,
            latestTimestamp = latest.timestamp,
            sensorSerial = latest.sensorSerial
        )
        if (nowMillis - latest.timestamp !in 0..maxReadingAgeMillis) return complete(null)

        val isMmol = GlucoseFormatter.isMmol(unit)
        fun mgDl(value: Float) = if (isMmol) GlucoseFormatter.mmolToMg(value) else value

        val target = doseTargetMgDl.takeIf { it.isFinite() && it > 0f } ?: return complete(null)
        val sensitivity = parameters.insulinSensitivityMgDlPerUnit
            .takeIf { it.isFinite() && it > 0f }
            ?: return complete(null)
        val currentMgDl = mgDl(latest.value)

        // Hysteresis: around the target the state has no direction worth acting on, and
        // this is where the old suggestion flipped its sign between two readings.
        if (abs(currentMgDl - target) <= HYSTERESIS_MGDL) return complete(null)

        // Nonsense in, nothing out. How much insulin on board each case needs is the
        // case's own question, and they do not answer it the same way.
        val iob = iobUnits.takeIf { it.isFinite() && it >= 0f } ?: return complete(null)

        val trend = trend(history, latest.timestamp, ::mgDl)
            ?: return StateDoseHintEvaluation.Incomplete(latest.timestamp, latest.sensorSerial)

        if (trend.slopeMgDlPerMinute <= -FALL_FLOOR_MGDL_PER_MINUTE) {
            // The long fit deliberately smooths noise, but it must never overrule a measured
            // five-minute rise and recommend carbs in the opposite direction.
            if (risingOverFiveMinutes(history, latest)) return complete(null)
            return complete(carbs(currentMgDl, target, sensitivity, iob, trend, parameters, horizonMinutes))
        }
        return complete(insulin(
            currentMgDl = currentMgDl,
            highMgDl = mgDl(targetHighDisplay),
            target = target,
            sensitivity = sensitivity,
            iob = iob,
            eiobUnits = eiobUnits,
            trend = trend,
            correctInRange = correctInRange
        ))
    }

    /**
     * Falling toward the target. The amount covers the gap from the measured projection to
     * the target and rounds up. The projection already contains the insulin effect visible
     * in the readings; adding IOB to it would count that effect twice.
     */
    private fun carbs(
        currentMgDl: Float,
        target: Float,
        sensitivity: Float,
        iob: Float,
        trend: Trend,
        parameters: PredictionModelParameters,
        horizonMinutes: Int
    ): StateDoseHint? {
        // A fall with nothing on board is not being driven downward by insulin, and this
        // case is about what the insulin is still going to add to it.
        if (iob <= 0f) return null
        val carbRatio = parameters.carbRatioGramsPerUnit.takeIf { it.isFinite() && it > 0f }
            ?: return null
        val horizon = horizonMinutes.coerceIn(HORIZON_MINUTES_MIN, HORIZON_MINUTES_MAX).toFloat()

        // Already under it counts as "now"; otherwise the crossing has to fall inside the
        // horizon, or this is a fall from somewhere high with nothing to say yet.
        val minutesAhead = if (currentMgDl <= target) {
            0f
        } else {
            (target - currentMgDl) / trend.slopeMgDlPerMinute
        }
        if (!minutesAhead.isFinite() || minutesAhead < 0f || minutesAhead > horizon) return null

        val expectedLowMgDl = currentMgDl + trend.slopeMgDlPerMinute * horizon
        val riseNeededMgDl = target - expectedLowMgDl
        if (riseNeededMgDl <= 0f) return null

        val grams = ceil((riseNeededMgDl / sensitivity) * carbRatio)
        if (!grams.isFinite() || grams < MIN_CARBS_GRAMS) return null
        return StateDoseHint(StateDoseHintKind.CARBS, grams, target, minutesAhead.roundToInt())
    }

    /**
     * Not coming down, in either of two shapes.
     *
     * Above the range: high although insulin is already acting, so what is on board is not
     * enough. Inside it: sitting well above the dose target, flat, with nothing left in
     * flight to move it -- 171 with 0.3 U on board of which 0.1 is still acting will be 171
     * in an hour. The therapeutic target is the neighbourhood of the dose target, not the
     * upper edge of a display band, and that stretch is where a long-term average is won.
     *
     * The arithmetic is the same either way: computed on the current value, never on a peak
     * the curve may never reach, with every unit on board subtracted -- all of it will still
     * land, so subtracting only the acting part would systematically overshoot -- and the
     * result rounded down to the pen's step.
     */
    private fun insulin(
        currentMgDl: Float,
        highMgDl: Float,
        target: Float,
        sensitivity: Float,
        iob: Float,
        eiobUnits: Float,
        trend: Trend,
        correctInRange: Boolean
    ): StateDoseHint? {
        if (!highMgDl.isFinite()) return null
        if (trend.spanMinutes < RISE_MIN_SPAN_MINUTES) return null
        if (trend.slopeMgDlPerMinute < STAGNANT_MGDL_PER_MINUTE) return null

        if (currentMgDl > highMgDl) {
            // Nothing acting yet is an ordinary correction case, not a "the insulin is not
            // enough" case, and this hint has no business in the former.
            if (!eiobUnits.isFinite() || eiobUnits <= 0f) return null
        } else {
            if (!correctInRange) return null
            // Just above the target is not worth a dose; the case starts further out.
            if (currentMgDl <= target + IN_RANGE_CORRECTION_MARGIN_MGDL) return null
        }

        val neededUnits = (currentMgDl - target) / sensitivity - iob
        if (!neededUnits.isFinite() || neededUnits <= 0f) return null
        // Down to the step, not to the nearest one: rounding up here hands out insulin the
        // arithmetic did not ask for.
        val units = floor(neededUnits / PEN_STEP_UNITS + 1e-4f) * PEN_STEP_UNITS
        if (units < PEN_STEP_UNITS) return null
        return StateDoseHint(StateDoseHintKind.INSULIN, units, target)
    }

    /**
     * Whether the one-time "check your model profile" notice belongs on screen.
     *
     * The amounts are computed from the sensitivity and carb ratio in the model profile, and
     * an untouched profile still holds the built-in defaults. The answer to that is to say
     * so once, not to withhold the hint: this one adds information the reader then acts on,
     * where the insulin-coverage check it inherited the worry from suppresses a warning the
     * reader never sees. It also stops for good once acknowledged, so a suggestion every
     * five minutes does not drag a notice along with it.
     */
    fun profileNoticeDue(
        hintPresent: Boolean,
        modelProfileSaved: Boolean,
        noticeAcknowledged: Boolean
    ): Boolean = hintPresent && !modelProfileSaved && !noticeAcknowledged

    private class Trend(val slopeMgDlPerMinute: Float, val spanMinutes: Float)

    /** Recency-weighted slope over the readings inside the trend window, in mg/dL per minute. */
    private fun trend(
        history: List<GlucosePoint>,
        latestTimestamp: Long,
        mgDl: (Float) -> Float
    ): Trend? {
        // Walked backwards and cut at the window: the drawn history can hold a whole day,
        // and the last half hour is all this reads.
        val windowStart = latestTimestamp - (TREND_WINDOW_MINUTES * 60_000f).toLong()
        val samples = ArrayList<GlucosePoint>()
        for (index in history.indices.reversed()) {
            val point = history[index]
            if (point.timestamp < windowStart) break
            if (point.timestamp > latestTimestamp) continue
            if (point.value.isFinite() && point.value > 0.1f) samples.add(point)
        }
        samples.reverse()
        if (samples.size < TREND_MIN_SAMPLES) return null
        val spanMinutes = (samples.last().timestamp - samples.first().timestamp) / 60_000f
        if (spanMinutes < TREND_MIN_SPAN_MINUTES) return null

        // TrendEngine owns the weighting term so the arrow and this hint cannot drift into
        // subtly different definitions of "recent". This caller deliberately keeps its
        // own 30-minute window and minimum-span gate.
        val slope = TrendEngine.recencyWeightedSlope(samples.asReversed()) {
            mgDl(it.value).toDouble()
        } ?: return null
        return Trend(slope, spanMinutes)
    }

    /** Same five-minute walk-back as the dashboard delta, used only as a carb veto. */
    private fun risingOverFiveMinutes(
        history: List<GlucosePoint>,
        latest: GlucosePoint
    ): Boolean {
        val previous = history.asReversed().firstOrNull { point ->
            point.timestamp > 0L &&
                point.timestamp < latest.timestamp &&
                latest.timestamp - point.timestamp >= GlucoseDelta.MIN_GAP_MILLIS &&
                point.value.isFinite() && point.value > 0.1f
        } ?: return false
        val delta = GlucoseDelta.fiveMinuteDelta(
            latest.timestamp,
            latest.value,
            previous.timestamp,
            previous.value
        )
        return delta.isFinite() && delta > 0f
    }
}
