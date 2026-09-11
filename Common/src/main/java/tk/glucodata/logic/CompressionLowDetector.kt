package tk.glucodata.logic

import kotlin.math.min

/**
 * Retrospective detector for pressure-induced sensor attenuation (PISA, "compression lows"):
 * pressure on the sensor throttles interstitial flow and the trace shows a sudden,
 * non-physiological plunge with a V-shaped rebound once the pressure lifts — most often
 * from lying on the sensor in sleep, but the mechanism is posture, not time of day, so
 * nothing here looks at the clock.
 *
 * An episode is only reported when ALL of these hold, on a fully recorded V:
 *  1. the fall is steeper than a physiological drop, as the MEAN rate of the whole
 *     falling segment, not a single sample pair — one noisy pair on an ordinary decline
 *     must not qualify a real hypoglycemia as an artifact,
 *  2. the insulin known to the journal cannot account for the depth (IOB × ISF, the same
 *     arithmetic the PRE_HIGH coverage gate uses, applied in the opposite direction),
 *  3. the last dose's activity peak has already passed — derivable from the configured
 *     activity curve via [dosePeakPassed], never from a fixed minute count,
 *  4. the curve held a quiet baseline through the pre-onset window: enough recorded
 *     minutes, no holes, no sample dipping below the onset level. A recent treated low
 *     recovering into a second fall, or one minute of "baseline" after an outage, is not
 *     quiet. This is deliberately tighter than "flat or rising": a steep rise into a
 *     plunge is oscillation, not rest, and rest is what makes a plunge suspicious,
 *  5. the value rebounds to near the pre-drop baseline within minutes, never dipping
 *     below the vetted nadir on the way (a W is not a V — the second dip was never
 *     checked against insulin, so the episode is refused),
 *  6. no carbohydrates in the journal explain that rebound — including carbs logged
 *     BEFORE the fall registered, because glucose eaten at onset minus a few minutes is
 *     exactly what a treated real low looks like.
 *
 * What this cannot separate is an UNLOGGED journal entry, in either direction: a
 * forgotten bolus produces the unexplained fall, and unlogged rescue carbs (the juice
 * nobody records at 4 a.m.) produce the clean rebound. In both cases the low is real and
 * the signature is identical, because the discriminating information never reached the
 * journal. That is why this object only classifies history after the rebound — it never
 * touches the alarm path, and every episode it emits still needs the user's confirmation
 * before statistics may exclude it. Sensor age is recorded on the episode as a weight
 * for the caller (fresh sensors are more prone to PISA); it is never a condition.
 *
 * Pure on purpose: samples and journal facts come in as values and lambdas so the same
 * detector runs against recorded traces under JUnit and against Room/native data on
 * device. Zero-valued samples are the native minute-slot placeholders ("--" rows) and are
 * dropped before analysis, for the reason TrendEngineGapTests pins: a hole must not be
 * measured as a plunge and a recovery.
 */
object CompressionLowDetector {

    /**
     * Every detection threshold, fully user-tunable. The defaults are the conservative
     * literature-informed values; tuning them against one's own recorded episodes —
     * with the episode log as the scorecard — is the calibration path. [sanitized]
     * guards against nonsense, not against opinion: recommended ranges are the UI's
     * job to signal, with warnings, never to enforce. The hard floor and the maximum
     * hold time are not in here — those live with the hold, not with detection.
     */
    data class Tuning(
        /** The falling segment's mean rate must be steeper than this (mg/dL per minute). */
        val suspectDropMgdlPerMinute: Float = 2.0f,
        /** A fall shallower than this is noise territory, whatever its rate. */
        val minDropDepthMgdl: Float = 25f,
        /** The pre-onset window the baseline is judged over. */
        val flatWindowMinutes: Long = 15L,
        /** At least this much of the window must actually be recorded before onset. */
        val minFlatSpanMinutes: Long = 10L,
        /** Mean pre-onset slope at or above this counts as quiet (mg/dL per minute). */
        val flatMinRateMgdlPerMinute: Float = -0.5f,
        /** No pre-onset sample may sit further than this below the onset value. */
        val flatDipToleranceMgdl: Float = 10f,
        /** The rebound must arrive within this window after the nadir, or the low is real. */
        val recoveryWindowMinutes: Long = 45L,
        /** Recovered means back within this band under the pre-drop baseline. */
        val recoveryBandMgdl: Float = 15f,
        /** Observed depth must exceed IOB × ISF by this factor to count as unexplained. */
        val unexplainedFactor: Float = 1.25f,
        /** Journal carbs below this many grams do not explain a rebound. */
        val negligibleCarbGrams: Float = 5f,
        /** Carbs are looked up from this long before onset: eating just ahead of the
         *  fall registering is what a treated real low looks like. */
        val carbLookbackMinutes: Long = 30L,
        /** A recording hole wider than this inside the episode or the pre-onset window
         *  makes the shape assumed rather than observed, and is never classified. */
        val maxGapMinutes: Long = 10L
    ) {
        /**
         * Guards against nonsense only — non-finite values, zero or negative rates,
         * a recorded-span floor above the window it spans. The user owns every value
         * beyond that: the RECOMMENDED ranges are UI guidance with warning styling,
         * never enforcement. That is a deliberate product decision — an opted-in user
         * keeps full authority, and the settings screen owes them friction and plain
         * words, not a locked door.
         */
        fun sanitized(): Tuning = Tuning(
            suspectDropMgdlPerMinute = suspectDropMgdlPerMinute.finiteIn(0.5f, 20f, DEFAULT.suspectDropMgdlPerMinute),
            minDropDepthMgdl = minDropDepthMgdl.finiteIn(5f, 200f, DEFAULT.minDropDepthMgdl),
            flatWindowMinutes = flatWindowMinutes.coerceIn(5L, 60L),
            minFlatSpanMinutes = minFlatSpanMinutes.coerceIn(1L, flatWindowMinutes.coerceIn(5L, 60L)),
            flatMinRateMgdlPerMinute = flatMinRateMgdlPerMinute.finiteIn(-5f, 0f, DEFAULT.flatMinRateMgdlPerMinute),
            flatDipToleranceMgdl = flatDipToleranceMgdl.finiteIn(1f, 50f, DEFAULT.flatDipToleranceMgdl),
            recoveryWindowMinutes = recoveryWindowMinutes.coerceIn(10L, 120L),
            recoveryBandMgdl = recoveryBandMgdl.finiteIn(5f, 50f, DEFAULT.recoveryBandMgdl),
            unexplainedFactor = unexplainedFactor.finiteIn(0.5f, 3f, DEFAULT.unexplainedFactor),
            negligibleCarbGrams = negligibleCarbGrams.finiteIn(0f, 30f, DEFAULT.negligibleCarbGrams),
            carbLookbackMinutes = carbLookbackMinutes.coerceIn(0L, 120L),
            maxGapMinutes = maxGapMinutes.coerceIn(2L, 30L)
        )

        private fun Float.finiteIn(lo: Float, hi: Float, default: Float): Float =
            if (isFinite()) coerceIn(lo, hi) else default

        internal val maxGapMs: Long get() = maxGapMinutes * MINUTE_MS

        companion object {
            val DEFAULT = Tuning()
        }
    }

    private const val MINUTE_MS = 60_000L
    private const val MIN_RATE_SPACING_MS = 30_000L

    /** A reading older than this cannot support a live suspicion — the fall is history, not ongoing. */
    const val STALE_READING_MINUTES = 6L

    data class Sample(val timestampMillis: Long, val mgdl: Float)

    /**
     * A live compression suspicion: the fall is still in progress, so only the
     * pre-rebound conditions are knowable. The rebound itself is what a hold window
     * exists to observe.
     */
    data class OngoingSuspect(
        val onsetMillis: Long,
        val baselineMgdl: Float,
        val currentMgdl: Float,
        /** Mean fall over the segment, negative, mg/dL per minute. */
        val meanDropMgdlPerMinute: Float,
        val depthMgdl: Float,
        /** How much drop the journal's insulin could account for: IOB × ISF. */
        val explainableDropMgdl: Float
    )

    data class Episode(
        val onsetMillis: Long,
        val nadirMillis: Long,
        val recoveryMillis: Long,
        val baselineMgdl: Float,
        val nadirMgdl: Float,
        /** Steepest fall inside the segment, negative, mg/dL per minute. */
        val steepestDropMgdlPerMinute: Float,
        val iobUnitsAtOnset: Float,
        /** How much drop the journal's insulin could account for: IOB × ISF. */
        val explainableDropMgdl: Float,
        /** Recorded as a weight for the caller, never a detection condition. */
        val sensorAgeHoursAtOnset: Float?
    )

    /**
     * The minute of maximum activity in a piecewise-linear insulin activity curve given
     * as (minute, activity) pairs — the shape `JournalInsulinPreset.curvePoints` carries.
     */
    fun peakMinuteOf(curve: List<Pair<Int, Float>>): Int? =
        curve.maxByOrNull { it.second }?.first

    /**
     * Whether a dose's activity peak lies behind [atMillis], derived from its configured
     * curve. No curve means no answer, and no answer must read as "not passed": a fall
     * that cannot be cleared against the insulin timeline stays a real low.
     */
    fun dosePeakPassed(doseTimestampMillis: Long, curve: List<Pair<Int, Float>>, atMillis: Long): Boolean {
        val peakMinute = peakMinuteOf(curve) ?: return false
        return atMillis >= doseTimestampMillis + peakMinute * MINUTE_MS
    }

    /**
     * Scans a trace, oldest or newest first, and returns every confirmed compression
     * suspect in chronological order. The lambdas answer journal questions at a given
     * instant so the caller decides where that truth comes from (Room, native, a fixture).
     * A non-positive or non-finite [isfMgdlPerUnit] disables detection entirely: without a
     * sensitivity there is no way to tell an unexplained drop from an explained one.
     * Samples sharing a timestamp resolve to the lowest reading, so the result does not
     * depend on input order.
     */
    fun detect(
        samples: List<Sample>,
        isfMgdlPerUnit: Float,
        iobUnitsAt: (timestampMillis: Long) -> Float,
        dosePeakPassedAt: (timestampMillis: Long) -> Boolean,
        carbGramsBetween: (startMillis: Long, endMillis: Long) -> Float,
        sensorStartMillis: Long? = null,
        tuning: Tuning = Tuning.DEFAULT
    ): List<Episode> {
        if (!isfMgdlPerUnit.isFinite() || isfMgdlPerUnit <= 0f) return emptyList()
        val t = tuning.sanitized()
        val trace = sanitize(samples)
        if (trace.size < 3) return emptyList()

        val episodes = mutableListOf<Episode>()
        var i = 0
        while (i < trace.size - 1) {
            val rate = rateFrom(trace, i, trace.size - 1)
            if (rate == null || rate >= -t.suspectDropMgdlPerMinute) {
                i++
                continue
            }
            val onsetIndex = i
            var nadirIndex = i + 1
            while (nadirIndex + 1 < trace.size && trace[nadirIndex + 1].mgdl <= trace[nadirIndex].mgdl) {
                nadirIndex++
            }
            val episode = confirm(trace, onsetIndex, nadirIndex, isfMgdlPerUnit,
                iobUnitsAt, dosePeakPassedAt, carbGramsBetween, sensorStartMillis, t)
            if (episode != null) {
                episodes += episode
                while (i < trace.size && trace[i].timestampMillis <= episode.recoveryMillis) i++
            } else {
                i = nadirIndex
            }
        }
        return episodes
    }

    /**
     * Judges whether the trace ENDS in a fall that carries the compression signature —
     * the live-alarm variant of [detect], for a hold decision at the moment a low alarm
     * would fire. Conditions 1-4 of the retrospective detector apply (steep mean fall,
     * quiet baseline, IOB cannot explain, dose past peak); rebound and carbs cannot be
     * known yet and are the hold window's job to observe. Returns null on any doubt:
     * stale data, a hole, a broken IOB value, or an incomplete picture all mean the low
     * is treated as real.
     */
    fun assessOngoing(
        samples: List<Sample>,
        nowMillis: Long,
        isfMgdlPerUnit: Float,
        iobUnits: Float,
        dosePeakPassed: Boolean,
        tuning: Tuning = Tuning.DEFAULT
    ): OngoingSuspect? {
        if (!isfMgdlPerUnit.isFinite() || isfMgdlPerUnit <= 0f) return null
        if (!iobUnits.isFinite() || iobUnits < 0f) return null
        if (!dosePeakPassed) return null
        val t = tuning.sanitized()
        val trace = sanitize(samples)
        if (trace.size < 3) return null

        val current = trace.last()
        if (nowMillis - current.timestampMillis > STALE_READING_MINUTES * MINUTE_MS) return null

        var onsetIndex = trace.size - 1
        while (onsetIndex > 0 &&
            trace[onsetIndex - 1].mgdl >= trace[onsetIndex].mgdl &&
            trace[onsetIndex].timestampMillis - trace[onsetIndex - 1].timestampMillis <= t.maxGapMs
        ) {
            onsetIndex--
        }
        // A flat shoulder walked through above is baseline, not fall: onset is its last
        // sample, so a constant baseline does not swallow its own quiet window.
        while (onsetIndex < trace.size - 2 && trace[onsetIndex + 1].mgdl == trace[onsetIndex].mgdl) {
            onsetIndex++
        }
        if (onsetIndex == trace.size - 1) return null

        val onset = trace[onsetIndex]
        val depth = onset.mgdl - current.mgdl
        if (depth < t.minDropDepthMgdl) return null
        val fallMinutes = (current.timestampMillis - onset.timestampMillis) / MINUTE_MS.toFloat()
        if (fallMinutes <= 0f) return null
        val meanRate = -depth / fallMinutes
        if (depth / fallMinutes < t.suspectDropMgdlPerMinute) return null

        if (!baselineQuietBefore(trace, onsetIndex, t)) return null

        val explainable = iobUnits * isfMgdlPerUnit
        if (depth <= explainable * t.unexplainedFactor) return null

        return OngoingSuspect(
            onsetMillis = onset.timestampMillis,
            baselineMgdl = onset.mgdl,
            currentMgdl = current.mgdl,
            meanDropMgdlPerMinute = meanRate,
            depthMgdl = depth,
            explainableDropMgdl = explainable
        )
    }

    private fun confirm(
        trace: List<Sample>,
        onsetIndex: Int,
        nadirIndex: Int,
        isfMgdlPerUnit: Float,
        iobUnitsAt: (Long) -> Float,
        dosePeakPassedAt: (Long) -> Boolean,
        carbGramsBetween: (Long, Long) -> Float,
        sensorStartMillis: Long?,
        t: Tuning
    ): Episode? {
        val onset = trace[onsetIndex]
        val nadir = trace[nadirIndex]
        val depth = onset.mgdl - nadir.mgdl
        if (depth < t.minDropDepthMgdl) return null

        val fallMinutes = (nadir.timestampMillis - onset.timestampMillis) / MINUTE_MS.toFloat()
        if (fallMinutes <= 0f) return null
        if (depth / fallMinutes < t.suspectDropMgdlPerMinute) return null

        var steepest = 0f
        for (k in onsetIndex until nadirIndex) {
            if (trace[k + 1].timestampMillis - trace[k].timestampMillis > t.maxGapMs) return null
            rateFrom(trace, k, nadirIndex)?.let { steepest = min(steepest, it) }
        }

        if (!baselineQuietBefore(trace, onsetIndex, t)) return null

        val iob = iobUnitsAt(onset.timestampMillis)
        if (!iob.isFinite() || iob < 0f) return null
        val explainable = iob * isfMgdlPerUnit
        if (depth <= explainable * t.unexplainedFactor) return null
        if (!dosePeakPassedAt(onset.timestampMillis)) return null

        val recoveryDeadline = nadir.timestampMillis + t.recoveryWindowMinutes * MINUTE_MS
        var recovery: Sample? = null
        var previous = nadir
        for (k in nadirIndex + 1 until trace.size) {
            val sample = trace[k]
            if (sample.timestampMillis > recoveryDeadline) break
            if (sample.timestampMillis - previous.timestampMillis > t.maxGapMs) return null
            if (sample.mgdl < nadir.mgdl) return null
            previous = sample
            if (sample.mgdl >= onset.mgdl - t.recoveryBandMgdl) {
                recovery = sample
                break
            }
        }
        if (recovery == null) return null

        val carbs = carbGramsBetween(
            onset.timestampMillis - t.carbLookbackMinutes * MINUTE_MS,
            recovery.timestampMillis
        )
        if (!carbs.isFinite() || carbs >= t.negligibleCarbGrams) return null

        val ageHours = sensorStartMillis?.let { start ->
            ((onset.timestampMillis - start) / MINUTE_MS / 60f).takeIf { it >= 0f }
        }
        return Episode(
            onsetMillis = onset.timestampMillis,
            nadirMillis = nadir.timestampMillis,
            recoveryMillis = recovery.timestampMillis,
            baselineMgdl = onset.mgdl,
            nadirMgdl = nadir.mgdl,
            steepestDropMgdlPerMinute = steepest,
            iobUnitsAtOnset = iob,
            explainableDropMgdl = explainable,
            sensorAgeHoursAtOnset = ageHours
        )
    }

    /**
     * The pre-onset window must be genuinely quiet: enough recorded span, no holes, no
     * sample below the onset level beyond tolerance (a recent dip means recent
     * instability), and a mean slope that is not already falling.
     */
    private fun baselineQuietBefore(trace: List<Sample>, onsetIndex: Int, t: Tuning): Boolean {
        val onset = trace[onsetIndex]
        val windowStart = onset.timestampMillis - t.flatWindowMinutes * MINUTE_MS
        var firstIndex = onsetIndex
        while (firstIndex > 0 && trace[firstIndex - 1].timestampMillis >= windowStart) firstIndex--
        if (firstIndex == onsetIndex) return false

        val first = trace[firstIndex]
        if (onset.timestampMillis - first.timestampMillis < t.minFlatSpanMinutes * MINUTE_MS) return false
        for (k in firstIndex until onsetIndex) {
            if (trace[k + 1].timestampMillis - trace[k].timestampMillis > t.maxGapMs) return false
            if (trace[k].mgdl < onset.mgdl - t.flatDipToleranceMgdl) return false
        }
        val spanMinutes = (onset.timestampMillis - first.timestampMillis) / MINUTE_MS.toFloat()
        return (onset.mgdl - first.mgdl) / spanMinutes >= t.flatMinRateMgdlPerMinute
    }

    /**
     * Rate from sample [i] to the first later sample at least [MIN_RATE_SPACING_MS]
     * away, bounded by [lastIndex] — adjacent-pair rates on sub-30 s cadences are noise,
     * and skipping them entirely would blind the scan on dense streams.
     */
    private fun sanitize(samples: List<Sample>): List<Sample> =
        samples.asSequence()
            .filter { it.mgdl.isFinite() && it.mgdl > 0f }
            .groupBy { it.timestampMillis }
            .map { (_, sameInstant) -> sameInstant.minBy { it.mgdl } }
            .sortedBy { it.timestampMillis }

    private fun rateFrom(trace: List<Sample>, i: Int, lastIndex: Int): Float? {
        val from = trace[i]
        for (j in i + 1..lastIndex) {
            val dtMs = trace[j].timestampMillis - from.timestampMillis
            if (dtMs >= MIN_RATE_SPACING_MS) {
                return (trace[j].mgdl - from.mgdl) / (dtMs / MINUTE_MS.toFloat())
            }
        }
        return null
    }
}
