package tk.glucodata.predictionbench

import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Features of the recent past at time `t`, read through a view so they stay causal:
 * the changes over the last 5/15/30/60 minutes, the distance from the trailing 24 h mean,
 * the level, the time of day, and — zero without a journal — insulin on board, the part of it
 * acting now, and carbs on board.
 */
internal object PastFeatures {
    const val COUNT = 11
    const val CGM_ONLY = 8
    private const val MINUTE = 60_000L
    private const val DAY = 24L * 60 * MINUTE
    val names = listOf("d5", "d15", "d30", "d60", "dev24h", "level", "todSin", "todCos", "iob", "activeInsulin", "cob")

    fun gapMs(view: OriginView): Long = max(6 * MINUTE, (view.cadenceMinutes * 2.5 * MINUTE).toLong())

    /** Fills [out]; false when the past is too thin around [t] to describe. */
    fun at(view: OriginView, t: Long, out: DoubleArray, withJournal: Boolean = true): Boolean {
        val gap = gapMs(view)
        val v0 = view.mgdlAt(t, gap)
        val v5 = view.mgdlAt(t - 5 * MINUTE, gap)
        val v15 = view.mgdlAt(t - 15 * MINUTE, gap)
        val v30 = view.mgdlAt(t - 30 * MINUTE, gap)
        val v60 = view.mgdlAt(t - 60 * MINUTE, gap)
        val mean = view.meanMgdl(t - DAY, t)
        if (!(v0.isFinite() && v5.isFinite() && v15.isFinite() && v30.isFinite() && v60.isFinite() && mean.isFinite())) return false
        val tod = 2.0 * PI * (Math.floorMod(t + TimeZone.getDefault().getOffset(t), DAY).toDouble() / DAY)
        out[0] = (v0 - v5).toDouble()
        out[1] = (v0 - v15).toDouble()
        out[2] = (v0 - v30).toDouble()
        out[3] = (v0 - v60).toDouble()
        out[4] = (v0 - mean).toDouble()
        out[5] = v0.toDouble()
        out[6] = sin(tod)
        out[7] = cos(tod)
        val onBoard = if (withJournal) view.onBoard(t) else OnBoard.NONE
        out[8] = onBoard.iobUnits.toDouble()
        out[9] = onBoard.activeInsulinUnits.toDouble()
        out[10] = onBoard.cobGrams.toDouble()
        return true
    }
}

/**
 * Training rows for the user's own past: features on a 5-minute grid, and the change actually
 * seen `h` minutes later — only where that outcome is at or before the forecast moment.
 */
internal class PastExamples(view: OriginView, horizons: IntArray, windowMs: Long, withJournal: Boolean) {
    val features = ArrayList<DoubleArray>()
    val times = ArrayList<Long>()
    /** targets[k][row] = mg/dL change at horizons[k], NaN where not yet observed. */
    val targets: Array<FloatArray>

    init {
        val gap = PastFeatures.gapMs(view)
        val start = maxOf(view.time(0) + 60 * 60_000L, view.originTime - windowMs)
        val step = 5 * 60_000L
        var t = start - Math.floorMod(start, step) + step
        val rows = ArrayList<FloatArray>()
        val minH = horizons.minOrNull() ?: 0
        while (t + minH * 60_000L <= view.originTime) {
            val f = DoubleArray(PastFeatures.COUNT)
            if (PastFeatures.at(view, t, f, withJournal)) {
                val level = f[5].toFloat()
                rows += FloatArray(horizons.size) { k ->
                    val target = t + horizons[k] * 60_000L
                    if (target > view.originTime) Float.NaN else view.mgdlAt(target, gap) - level
                }
                features += f
                times += t
            }
            t += step
        }
        targets = Array(horizons.size) { k -> FloatArray(rows.size) { rows[it][k] } }
    }
}

/**
 * Direct multi-horizon ridge regression on the user's own history, refitted once a day
 * walk-forward. The simplest learner that can find a personal pattern: how much of a
 * recent move tends to continue, and where this person's glucose tends to drift.
 * Until [minRows] past examples exist it answers with the last reading.
 */
class RidgeDirect(
    override val name: String,
    private val lambda: Double = 1.0,
    private val refitEveryMs: Long = 24L * 60 * 60_000,
    private val windowMs: Long = 28L * 24 * 60 * 60_000,
    private val minRows: Int = 576,
    /** Default: the CGM features only. Include 8–10 to let it use the journal. */
    private val featureMask: BooleanArray = BooleanArray(PastFeatures.COUNT) { it < PastFeatures.CGM_ONLY },
) : Forecaster {

    private class Model(val trainedAt: Long, val weights: Array<DoubleArray?>, val means: DoubleArray, val scales: DoubleArray)

    private val models = HashMap<String, Model>()
    private val usesJournal = (PastFeatures.CGM_ONLY until PastFeatures.COUNT).any { featureMask[it] }

    override fun forecast(view: OriginView, horizons: IntArray): FloatArray {
        val last = view.mgdl(view.end)
        var model = models[view.laneId]
        if (model == null || view.originTime - model.trainedAt >= refitEveryMs) {
            model = fit(view, horizons)
            models[view.laneId] = model
        }
        val f = DoubleArray(PastFeatures.COUNT)
        if (!PastFeatures.at(view, view.originTime, f, usesJournal)) return FloatArray(horizons.size) { last }
        return FloatArray(horizons.size) { k ->
            val w = model.weights[k] ?: return@FloatArray last
            var y = w[0]
            var j = 1
            for (c in 0 until PastFeatures.COUNT) {
                if (!featureMask[c]) continue
                y += w[j++] * (f[c] - model.means[c]) / model.scales[c]
            }
            (f[5] + y).toFloat()
        }
    }

    private fun fit(view: OriginView, horizons: IntArray): Model {
        val ex = PastExamples(view, horizons, windowMs, usesJournal)
        val means = DoubleArray(PastFeatures.COUNT)
        val scales = DoubleArray(PastFeatures.COUNT) { 1.0 }
        val n = ex.features.size
        if (n > 0) {
            for (c in 0 until PastFeatures.COUNT) {
                val m = ex.features.sumOf { it[c] } / n
                val sd = sqrt(ex.features.sumOf { (it[c] - m) * (it[c] - m) } / n)
                means[c] = m
                scales[c] = if (sd > 1e-9) sd else 1.0
            }
        }
        val used = (0 until PastFeatures.COUNT).filter { featureMask[it] }
        val weights = Array(horizons.size) { k ->
            val rows = (0 until n).filter { ex.targets[k][it].isFinite() }
            if (rows.size < minRows) return@Array null
            val p = used.size
            val yMean = rows.sumOf { ex.targets[k][it].toDouble() } / rows.size
            val a = Array(p) { DoubleArray(p) }
            val b = DoubleArray(p)
            for (r in rows) {
                val x = DoubleArray(p) { (ex.features[r][used[it]] - means[used[it]]) / scales[used[it]] }
                val y = ex.targets[k][r] - yMean
                for (i in 0 until p) {
                    b[i] += x[i] * y
                    for (j in 0 until p) a[i][j] += x[i] * x[j]
                }
            }
            for (i in 0 until p) a[i][i] += lambda
            doubleArrayOf(yMean) + solve(a, b)
        }
        return Model(view.originTime, weights, means, scales)
    }
}

/**
 * "Situations like this": the [k] past moments of this user whose recent shape, level and
 * time of day were most similar, and the median of what happened next. Non-parametric and
 * personal, and it describes a spread rather than one line. The library is rebuilt once a day
 * from outcomes already observed.
 */
class AnalogKnn(
    override val name: String,
    private val k: Int = 40,
    private val rebuildEveryMs: Long = 24L * 60 * 60_000,
    private val windowMs: Long = 28L * 24 * 60 * 60_000,
    private val minLibrary: Int = 576,
    /** Per-feature weights over [PastFeatures.names]. */
    private val weights: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0, 0.7, 0.5, 0.7, 0.3, 0.3, 0.0, 0.0, 0.0),
) : Forecaster {

    private class Library(val builtAt: Long, val x: Array<DoubleArray>, val deltas: Array<FloatArray>, val means: DoubleArray, val scales: DoubleArray)

    private val libraries = HashMap<String, Library>()
    private val usesJournal = (PastFeatures.CGM_ONLY until PastFeatures.COUNT).any { weights[it] != 0.0 }

    override fun forecast(view: OriginView, horizons: IntArray): FloatArray {
        val last = view.mgdl(view.end)
        var lib = libraries[view.laneId]
        if (lib == null || view.originTime - lib.builtAt >= rebuildEveryMs) {
            lib = build(view, horizons)
            libraries[view.laneId] = lib
        }
        val f = DoubleArray(PastFeatures.COUNT)
        if (lib.x.size < minLibrary || !PastFeatures.at(view, view.originTime, f, usesJournal)) return FloatArray(horizons.size) { last }
        val q = DoubleArray(PastFeatures.COUNT) { (f[it] - lib.means[it]) / lib.scales[it] * weights[it] }

        // Bounded selection of the k nearest.
        val bestIdx = IntArray(k) { -1 }
        val bestDist = DoubleArray(k) { Double.MAX_VALUE }
        var worst = 0
        for (r in lib.x.indices) {
            val row = lib.x[r]
            var d = 0.0
            for (c in q.indices) {
                val diff = row[c] - q[c]
                d += diff * diff
                if (d >= bestDist[worst]) break
            }
            if (d < bestDist[worst]) {
                bestDist[worst] = d
                bestIdx[worst] = r
                worst = bestDist.indices.maxByOrNull { bestDist[it] }!!
            }
        }
        val chosen = bestIdx.filter { it >= 0 }
        return FloatArray(horizons.size) { h ->
            val d = chosen.map { lib.deltas[h][it] }.filter { it.isFinite() }.sorted()
            if (d.isEmpty()) last else (f[5] + d[d.size / 2]).toFloat()
        }
    }

    private fun build(view: OriginView, horizons: IntArray): Library {
        val ex = PastExamples(view, horizons, windowMs, usesJournal)
        // Only moments whose whole future is already observed enter the library.
        val rows = ex.features.indices.filter { r -> horizons.indices.all { ex.targets[it][r].isFinite() } }
        val n = rows.size
        val means = DoubleArray(PastFeatures.COUNT)
        val scales = DoubleArray(PastFeatures.COUNT) { 1.0 }
        if (n > 0) {
            for (c in 0 until PastFeatures.COUNT) {
                val m = rows.sumOf { ex.features[it][c] } / n
                val sd = sqrt(rows.sumOf { (ex.features[it][c] - m) * (ex.features[it][c] - m) } / n)
                means[c] = m
                scales[c] = if (sd > 1e-9) sd else 1.0
            }
        }
        val x = Array(n) { i -> DoubleArray(PastFeatures.COUNT) { c -> (ex.features[rows[i]][c] - means[c]) / scales[c] * weights[c] } }
        val deltas = Array(horizons.size) { h -> FloatArray(n) { ex.targets[h][rows[it]] } }
        return Library(view.originTime, x, deltas, means, scales)
    }
}

/** Gaussian elimination with partial pivoting; [a] is small and symmetric positive definite. */
internal fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = b.size
    val m = Array(n) { i -> a[i].copyOf() + b[i] }
    for (col in 0 until n) {
        val pivot = (col until n).maxByOrNull { kotlin.math.abs(m[it][col]) }!!
        val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
        val p = m[col][col]
        if (kotlin.math.abs(p) < 1e-12) continue
        for (r in col + 1 until n) {
            val factor = m[r][col] / p
            for (c in col..n) m[r][c] -= factor * m[col][c]
        }
    }
    val x = DoubleArray(n)
    for (r in n - 1 downTo 0) {
        var s = m[r][n]
        for (c in r + 1 until n) s -= m[r][c] * x[c]
        x[r] = if (kotlin.math.abs(m[r][r]) < 1e-12) 0.0 else s / m[r][r]
    }
    return x
}
