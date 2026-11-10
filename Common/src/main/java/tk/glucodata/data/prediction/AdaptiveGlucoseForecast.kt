package tk.glucodata.data.prediction

import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CGM history as the adaptive forecast reads it: ascending readings in mg/dL, each tagged
 * with the sensor segment it belongs to.
 *
 * A value is only ever read inside one segment. Interpolating across a sensor change would
 * make a jump between two sensors' calibrations look like a glucose movement, so a feature
 * window that reaches back into the previous sensor is simply unavailable.
 */
class AdaptiveHistory(
    val times: LongArray,
    val mgdl: FloatArray,
    val segment: IntArray,
) {
    init {
        require(times.size == mgdl.size && times.size == segment.size)
    }

    val size: Int get() = times.size

    /** The longest gap a value is interpolated across: 2.5 readings, at least 6 minutes. */
    val maxGapMs: Long by lazy {
        val gaps = (1 until size).map { times[it] - times[it - 1] }.filter { it > 0 }.sorted()
        val cadence = if (gaps.isEmpty()) 5 * MINUTE_MS else gaps[gaps.size / 2]
        max(6 * MINUTE_MS, (cadence * 2.5).toLong())
    }

    private val prefix: DoubleArray by lazy {
        val sums = DoubleArray(size + 1)
        for (i in 0 until size) sums[i + 1] = sums[i] + mgdl[i]
        sums
    }

    /** Last index with time ≤ [t], or −1. */
    fun indexAtOrBefore(t: Long): Int {
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** mg/dL at [t] inside [segmentId], interpolated across at most [maxGapMs]; NaN otherwise. */
    fun valueAt(t: Long, segmentId: Int): Float {
        val i = indexAtOrBefore(t)
        if (i < 0 || segment[i] != segmentId) return Float.NaN
        if (times[i] == t) return mgdl[i]
        if (i + 1 >= size || segment[i + 1] != segmentId) return Float.NaN
        val t0 = times[i]
        val t1 = times[i + 1]
        if (t1 - t0 > maxGapMs) return Float.NaN
        return mgdl[i] + (mgdl[i + 1] - mgdl[i]) * ((t - t0).toFloat() / (t1 - t0).toFloat())
    }

    /**
     * Whether [segmentId] has readings across all of [fromMs, toMs] with no gap longer than
     * [maxGapMs]. Five spot lookups can each find a value while a hole sits between them.
     */
    fun continuous(fromMs: Long, toMs: Long, segmentId: Int): Boolean {
        val first = indexAtOrBefore(fromMs)
        val last = indexAtOrBefore(toMs)
        if (first < 0 || last < first) return false
        for (i in first..last) {
            if (segment[i] != segmentId) return false
            if (i > first && times[i] - times[i - 1] > maxGapMs) return false
        }
        return true
    }

    /** Mean of the readings in [fromMs, toMs], all sensors; NaN when there are none. */
    fun meanBetween(fromMs: Long, toMs: Long): Float {
        val hi = indexAtOrBefore(toMs)
        if (hi < 0) return Float.NaN
        val lo = indexAtOrBefore(fromMs - 1) + 1
        if (lo > hi) return Float.NaN
        return ((prefix[hi + 1] - prefix[lo]) / (hi + 1 - lo)).toFloat()
    }

    companion object {
        /**
         * Builds the history from parallel lists, starting a new segment wherever the sensor
         * changes. Non-finite and implausible values are dropped; equal timestamps keep the first.
         */
        fun of(times: LongArray, mgdl: FloatArray, sensors: List<String?>): AdaptiveHistory {
            val order = times.indices.sortedBy { times[it] }
            val keptTimes = ArrayList<Long>(times.size)
            val keptValues = ArrayList<Float>(times.size)
            val keptSegments = ArrayList<Int>(times.size)
            var segmentId = -1
            var lastSensor: String? = null
            for (i in order) {
                val v = mgdl[i]
                if (!v.isFinite() || v < 10f || v > 700f) continue
                if (keptTimes.isNotEmpty() && keptTimes.last() == times[i]) continue
                val sensor = sensors.getOrNull(i)
                if (segmentId < 0 || sensor != lastSensor) {
                    segmentId++
                    lastSensor = sensor
                }
                keptTimes += times[i]
                keptValues += v
                keptSegments += segmentId
            }
            return AdaptiveHistory(keptTimes.toLongArray(), keptValues.toFloatArray(), keptSegments.toIntArray())
        }
    }
}

/**
 * A fitted personal forecast: one ridge regression per horizon, predicting the change from
 * the current reading. Immutable; [AdaptiveGlucoseForecast.fit] makes a new one.
 */
class AdaptiveForecastModel(
    val version: Int,
    val trainedAtMs: Long,
    /** Horizons in minutes, ascending; the forecast has one point per horizon. */
    val horizons: IntArray,
    val means: DoubleArray,
    val scales: DoubleArray,
    /** weights[k] = intercept followed by one weight per feature, or null when horizon k had too little data. */
    val weights: Array<DoubleArray?>,
    /** Training rows behind the 30-minute horizon: the size of the personal evidence. */
    val trainingRows: Int,
    /** Days of history the training window covered. */
    val trainingDays: Float,
    /**
     * 30-minute holdout error of this model and of "no change" on the most recent days, in
     * mg/dL; NaN when there was not enough history to hold any back.
     */
    val holdoutRmse: Float,
    val holdoutPersistenceRmse: Float,
) {
    /**
     * Whether the model earned its place on held-back recent data. A model that could not be
     * checked yet counts as reliable; one that lost to "no change" does not.
     */
    val reliable: Boolean
        get() = !holdoutRmse.isFinite() || !holdoutPersistenceRmse.isFinite() ||
            holdoutRmse <= holdoutPersistenceRmse * AdaptiveGlucoseForecast.RELIABILITY_MARGIN

    fun encode(): String = buildString {
        append(version).append('|').append(trainedAtMs).append('|')
        append(horizons.joinToString(",")).append('|')
        append(means.joinToString(",")).append('|')
        append(scales.joinToString(",")).append('|')
        append(weights.joinToString(";") { it?.joinToString(",") ?: "-" }).append('|')
        append(trainingRows).append('|').append(trainingDays).append('|')
        append(holdoutRmse).append('|').append(holdoutPersistenceRmse)
    }

    companion object {
        fun decode(encoded: String?): AdaptiveForecastModel? = runCatching {
            val parts = encoded?.split('|') ?: return null
            if (parts.size != 10) return null
            fun doubles(s: String) = s.split(',').map { it.toDouble() }.toDoubleArray()
            val model = AdaptiveForecastModel(
                version = parts[0].toInt(),
                trainedAtMs = parts[1].toLong(),
                horizons = parts[2].split(',').map { it.toInt() }.toIntArray(),
                means = doubles(parts[3]),
                scales = doubles(parts[4]),
                weights = parts[5].split(';').map { if (it == "-") null else doubles(it) }.toTypedArray(),
                trainingRows = parts[6].toInt(),
                trainingDays = parts[7].toFloat(),
                holdoutRmse = parts[8].toFloat(),
                holdoutPersistenceRmse = parts[9].toFloat(),
            )
            model.takeIf {
                it.version == AdaptiveGlucoseForecast.MODEL_VERSION &&
                    it.weights.size == it.horizons.size &&
                    it.means.size == AdaptiveGlucoseForecast.FEATURES &&
                    it.scales.size == AdaptiveGlucoseForecast.FEATURES
            }
        }.getOrNull()
    }
}

/**
 * The adaptive ("personal") glucose forecast.
 *
 * What it learns, per horizon, is how much of the recent movement tends to carry on for this
 * person on their sensors, and how strongly glucose drifts back toward their own recent
 * average. Eight features, all from CGM history: the change over the last 5, 15, 30 and 60
 * minutes, the distance from the trailing 24 h mean, the level, and the time of day.
 *
 * Why this and not something larger: on the prediction benchmark (testMobile `predictionbench`)
 * it beat "no change" for every one of 25 people on Dexcom G6 (AZT1D; median −12 % RMSE at
 * 30 min) and every one of 23 on 15-minute Libre 2 (HUPA-UCM, held out from the choice; −16 %),
 * was within a few tenths of a mg/dL of a nearest-neighbour learner, and needs nothing beyond a
 * 9×9 solve per horizon. It does not read the journal: on the data available, adding logged
 * treatments made forecasts worse, not better.
 *
 * It never decides anything clinical. Alerts, dose hints and the treatment model stay with
 * the standard forecast.
 */
object AdaptiveGlucoseForecast {
    const val MODEL_VERSION = 1
    const val FEATURES = 8

    /** One point every 5 minutes out to two hours. */
    val HORIZONS: IntArray = IntArray(24) { (it + 1) * 5 }

    /** Two days of rows on the 5-minute grid. */
    const val MIN_TRAINING_ROWS = 576
    const val TRAINING_WINDOW_DAYS = 28
    const val HOLDOUT_DAYS = 2
    /** A holdout check needs this much history before the held-back days. */
    const val MIN_DAYS_FOR_HOLDOUT = 4

    /** A model may be this much worse than "no change" on holdout and still be used. */
    const val RELIABILITY_MARGIN = 1.02f

    private const val LAMBDA = 1.0
    private const val GRID_MS = 5 * MINUTE_MS
    private const val DAY_MS = 24L * 60 * MINUTE_MS

    /**
     * The feature vector at [t] into [out]. False when the hour behind [t] is not covered by
     * one sensor, or [t] itself has no reading.
     */
    fun features(history: AdaptiveHistory, t: Long, timeZone: TimeZone, out: DoubleArray): Boolean {
        val i = history.indexAtOrBefore(t)
        if (i < 0) return false
        val seg = history.segment[i]
        val v0 = history.valueAt(t, seg)
        val v5 = history.valueAt(t - 5 * MINUTE_MS, seg)
        val v15 = history.valueAt(t - 15 * MINUTE_MS, seg)
        val v30 = history.valueAt(t - 30 * MINUTE_MS, seg)
        val v60 = history.valueAt(t - 60 * MINUTE_MS, seg)
        val mean = history.meanBetween(t - DAY_MS, t)
        if (!(v0.isFinite() && v5.isFinite() && v15.isFinite() && v30.isFinite() && v60.isFinite() && mean.isFinite())) {
            return false
        }
        if (!history.continuous(t - 60 * MINUTE_MS, t, seg)) return false
        val tod = 2.0 * PI * (Math.floorMod(t + timeZone.getOffset(t), DAY_MS).toDouble() / DAY_MS)
        out[0] = (v0 - v5).toDouble()
        out[1] = (v0 - v15).toDouble()
        out[2] = (v0 - v30).toDouble()
        out[3] = (v0 - v60).toDouble()
        out[4] = (v0 - mean).toDouble()
        out[5] = v0.toDouble()
        out[6] = sin(tod)
        out[7] = cos(tod)
        return true
    }

    /**
     * Fits a model on [history] as of [nowMs]. Only outcomes at or before [nowMs] are used, so
     * a replay that hands this the past alone gets exactly what a phone would have learned.
     * Null when there are fewer than [MIN_TRAINING_ROWS] usable rows.
     */
    fun fit(history: AdaptiveHistory, nowMs: Long, timeZone: TimeZone = TimeZone.getDefault()): AdaptiveForecastModel? {
        val rows = trainingRows(history, nowMs, timeZone)
        if (rows.count30() < MIN_TRAINING_ROWS) return null
        val spanDays = (nowMs - rows.times.first()) / DAY_MS.toFloat()

        // Hold the most recent days back once, to check the model against "no change".
        var holdoutRmse = Float.NaN
        var holdoutPersistence = Float.NaN
        if (spanDays >= MIN_DAYS_FOR_HOLDOUT) {
            val cut = nowMs - HOLDOUT_DAYS * DAY_MS
            // Training rows whose outcome lies past the cut would see the holdout.
            val trainMask = BooleanArray(rows.size) { rows.times[it] + HORIZONS.last() * MINUTE_MS <= cut }
            val early = solve(rows, trainMask)
            if (early != null) {
                val k30 = HORIZONS.indexOf(30)
                var sse = 0.0
                var ssePersist = 0.0
                var n = 0
                for (r in 0 until rows.size) {
                    if (rows.times[r] < cut) continue
                    val target = rows.targets[k30][r]
                    if (!target.isFinite()) continue
                    val predicted = early.predictDelta(rows.features[r], k30) ?: continue
                    sse += (predicted - target) * (predicted - target)
                    ssePersist += target.toDouble() * target
                    n++
                }
                if (n >= 100) {
                    holdoutRmse = sqrt(sse / n).toFloat()
                    holdoutPersistence = sqrt(ssePersist / n).toFloat()
                }
            }
        }
        val full = solve(rows, BooleanArray(rows.size) { true }) ?: return null
        return AdaptiveForecastModel(
            version = MODEL_VERSION,
            trainedAtMs = nowMs,
            horizons = HORIZONS.copyOf(),
            means = full.means,
            scales = full.scales,
            weights = full.weights,
            trainingRows = rows.count30(),
            trainingDays = spanDays,
            holdoutRmse = holdoutRmse,
            holdoutPersistenceRmse = holdoutPersistence,
        )
    }

    /**
     * The forecast at [originMs] as changes from the reading there, one per horizon (NaN where
     * the model has no weights), or null when the features cannot be formed — a new sensor's
     * first hour, a gap, no reading at [originMs].
     */
    fun forecastDeltas(
        model: AdaptiveForecastModel,
        history: AdaptiveHistory,
        originMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): FloatArray? {
        val f = DoubleArray(FEATURES)
        if (!features(history, originMs, timeZone, f)) return null
        val fitted = Fitted(model.means, model.scales, model.weights)
        return FloatArray(model.horizons.size) { k -> fitted.predictDelta(f, k)?.toFloat() ?: Float.NaN }
    }

    private class Rows(
        val times: LongArray,
        val features: Array<DoubleArray>,
        /** targets[k][row]: change at HORIZONS[k], NaN where unobserved. */
        val targets: Array<FloatArray>,
    ) {
        val size: Int get() = times.size
        fun count30(): Int {
            val k = HORIZONS.indexOf(30)
            return targets[k].count { it.isFinite() }
        }
    }

    private fun trainingRows(history: AdaptiveHistory, nowMs: Long, timeZone: TimeZone): Rows {
        val times = ArrayList<Long>()
        val features = ArrayList<DoubleArray>()
        val targets = ArrayList<FloatArray>()
        if (history.size == 0) return Rows(LongArray(0), emptyArray(), Array(HORIZONS.size) { FloatArray(0) })
        val first = maxOf(history.times[0] + 60 * MINUTE_MS, nowMs - TRAINING_WINDOW_DAYS * DAY_MS)
        var t = first - Math.floorMod(first, GRID_MS) + GRID_MS
        val last = nowMs - HORIZONS.first() * MINUTE_MS
        while (t <= last) {
            val f = DoubleArray(FEATURES)
            if (features(history, t, timeZone, f)) {
                val seg = history.segment[history.indexAtOrBefore(t)]
                val level = f[5].toFloat()
                times += t
                features += f
                targets += FloatArray(HORIZONS.size) { k ->
                    val target = t + HORIZONS[k] * MINUTE_MS
                    if (target > nowMs) Float.NaN else history.valueAt(target, seg) - level
                }
            }
            t += GRID_MS
        }
        return Rows(
            times.toLongArray(),
            features.toTypedArray(),
            Array(HORIZONS.size) { k -> FloatArray(targets.size) { targets[it][k] } },
        )
    }

    private class Fitted(val means: DoubleArray, val scales: DoubleArray, val weights: Array<DoubleArray?>) {
        fun predictDelta(f: DoubleArray, k: Int): Double? {
            val w = weights.getOrNull(k) ?: return null
            var y = w[0]
            for (c in 0 until FEATURES) y += w[c + 1] * (f[c] - means[c]) / scales[c]
            return y
        }
    }

    private fun solve(rows: Rows, mask: BooleanArray): Fitted? {
        val used = (0 until rows.size).filter { mask[it] }
        if (used.isEmpty()) return null
        val means = DoubleArray(FEATURES)
        val scales = DoubleArray(FEATURES) { 1.0 }
        for (c in 0 until FEATURES) {
            val m = used.sumOf { rows.features[it][c] } / used.size
            val sd = sqrt(used.sumOf { (rows.features[it][c] - m) * (rows.features[it][c] - m) } / used.size)
            means[c] = m
            scales[c] = if (sd > 1e-9) sd else 1.0
        }
        val weights = Array(HORIZONS.size) { k ->
            val target = rows.targets[k]
            val ok = used.filter { target[it].isFinite() }
            if (ok.size < MIN_TRAINING_ROWS) return@Array null
            val yMean = ok.sumOf { target[it].toDouble() } / ok.size
            val a = Array(FEATURES) { DoubleArray(FEATURES) }
            val b = DoubleArray(FEATURES)
            val x = DoubleArray(FEATURES)
            for (r in ok) {
                for (c in 0 until FEATURES) x[c] = (rows.features[r][c] - means[c]) / scales[c]
                val y = target[r] - yMean
                for (i in 0 until FEATURES) {
                    b[i] += x[i] * y
                    for (j in 0 until FEATURES) a[i][j] += x[i] * x[j]
                }
            }
            for (i in 0 until FEATURES) a[i][i] += LAMBDA
            doubleArrayOf(yMean) + gaussianSolve(a, b)
        }
        if (weights.all { it == null }) return null
        return Fitted(means, scales, weights)
    }

    /** Gaussian elimination with partial pivoting for the small, symmetric positive-definite systems above. */
    internal fun gaussianSolve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() + b[i] }
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { abs(m[it][col]) }!!
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val p = m[col][col]
            if (abs(p) < 1e-12) continue
            for (r in col + 1 until n) {
                val factor = m[r][col] / p
                for (c in col..n) m[r][c] -= factor * m[col][c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = m[r][n]
            for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = if (abs(m[r][r]) < 1e-12) 0.0 else s / m[r][r]
        }
        return x
    }
}

private const val MINUTE_MS = 60_000L
