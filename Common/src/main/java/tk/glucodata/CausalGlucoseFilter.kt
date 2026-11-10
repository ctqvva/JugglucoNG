package tk.glucodata

import kotlin.math.exp
import kotlin.math.max

/**
 * A causal level-and-trend Kalman filter for the newest reading.
 *
 * The averaging window [GlucoseSmoothing] runs is centred, so at the live edge it has no future
 * and falls back to a one-sided fit: the newest value is the noisiest one, it is the one that
 * leaves the phone, and the chart later redraws the same minute differently. This filter only
 * ever looks back.
 *
 * It keeps no state between calls on purpose. An estimate for sample `t` is computed from the
 * samples in `(t − window, t]` and nothing else, so the live path, a backfill sent minutes later
 * and a redraw all produce the same number for the same minute, and nothing has to be stored next
 * to the readings. With these parameters the filter settles within a few samples, so the history
 * the window cuts off has no measurable effect.
 *
 * Model and tuning follow AAPS's UnscentedKalmanFilterPlugin: state `[G, Ġ]` in mg/dL and
 * mg/dL/min, `G' = G + Ġ·dt`, `Ġ' = Ġ·exp(−dt/τ)`, process noise scaled by `dt/5`. For this
 * linear model an unscented filter reduces to the ordinary one, so there are no sigma points.
 * R is fixed: an R learned over long history would make the result depend on what was processed
 * before, which is exactly what the window is there to rule out.
 *
 * A surprising sample is not dropped. Its innovation is scored against χ² and, past the threshold,
 * R is inflated until it sits on the threshold — a lone spike is mostly ignored, a sustained move
 * is followed. There is no low floor: a real fall is expensive only while it is unconfirmed.
 */
object CausalGlucoseFilter {

    /** How far back one estimate looks. Older readings never influence it. */
    const val WINDOW_MS = 60L * 60_000L

    private const val MILLIS_PER_MINUTE = 60_000.0

    /** The process noise is specified per five minutes and scaled by `dt / this`. */
    private const val Q_REFERENCE_MINUTES = 5.0

    private const val MIN_GLUCOSE_VARIANCE = 0.1
    private const val MIN_RATE_VARIANCE = 0.001

    internal data class Params(
        /** Glucose process noise, mg/dL² per five minutes. */
        val qGlucose: Double = 1.0,
        /** Rate process noise, (mg/dL/min)² per five minutes. */
        val qRate: Double = 0.35,
        /** Measurement noise, mg/dL². */
        val r: Double = 25.0,
        /** Upper bound for R inflated by an outlier, mg/dL². */
        val rEffMax: Double = 400.0,
        /** 99.99 % for one degree of freedom. */
        val chiSquaredThreshold: Double = 15.13,
        val rateDecayMinutes: Double = 30.0,
        /** Physiological bound on the rate state, mg/dL/min. */
        val maxRate: Double = 4.0,
        /** Rate variance the filter starts from, so the first moves teach it the slope fast. */
        val initialRateVariance: Double = 4.0,
    )

    internal val DEFAULT = Params()

    internal data class Estimate(val mgdl: Double, val rateMgdlPerMin: Double)

    /**
     * Estimate at sample [at], from samples in `(t[at] − windowMs, t[at]]` only.
     *
     * [timesMs] must be ascending. Samples whose value is not finite or not positive are skipped.
     * A spacing above [gapMs] restarts the filter at the first sample after it — a hole is not
     * bridged by prediction. A repeated timestamp keeps the first sample.
     *
     * @return null when sample [at] is itself invalid.
     */
    internal fun estimateAt(
        timesMs: LongArray,
        mgdl: DoubleArray,
        at: Int,
        params: Params = DEFAULT,
        windowMs: Long = WINDOW_MS,
        gapMs: Long = GlucoseChartGap.THRESHOLD_MS,
    ): Estimate? {
        if (at !in timesMs.indices || at !in mgdl.indices || !isValid(mgdl[at])) return null
        val end = timesMs[at]
        var start = at
        while (start > 0 && timesMs[start - 1] > end - windowMs) start--

        var g = 0.0
        var rate = 0.0
        var p00 = 0.0
        var p01 = 0.0
        var p11 = 0.0
        var lastTime = Long.MIN_VALUE

        for (i in start..at) {
            val z = mgdl[i]
            if (!isValid(z)) continue
            val t = timesMs[i]
            if (lastTime == Long.MIN_VALUE || t - lastTime > gapMs) {
                g = z
                rate = 0.0
                p00 = params.r
                p01 = 0.0
                p11 = params.initialRateVariance
                lastTime = t
                continue
            }
            if (t <= lastTime) continue
            val dt = (t - lastTime) / MILLIS_PER_MINUTE
            lastTime = t

            // Predict with F = [[1, dt], [0, d]].
            val d = exp(-dt / params.rateDecayMinutes)
            val gPred = g + rate * dt
            val ratePred = rate * d
            val qScale = dt / Q_REFERENCE_MINUTES
            val a00 = p00 + 2.0 * dt * p01 + dt * dt * p11 + params.qGlucose * qScale
            val a01 = d * (p01 + dt * p11)
            val a11 = d * d * p11 + params.qRate * qScale

            // Update with H = [1, 0]; an outlier gets R inflated onto the χ² threshold.
            val innovation = z - gPred
            var s = a00 + params.r
            if (innovation * innovation / s > params.chiSquaredThreshold) {
                val inflatedR = (innovation * innovation / params.chiSquaredThreshold - a00)
                    .coerceIn(params.r, params.rEffMax)
                s = a00 + inflatedR
            }
            val k0 = a00 / s
            val k1 = a01 / s
            g = gPred + k0 * innovation
            rate = (ratePred + k1 * innovation).coerceIn(-params.maxRate, params.maxRate)
            p00 = max(a00 - k0 * k0 * s, MIN_GLUCOSE_VARIANCE)
            p01 = a01 - k0 * k1 * s
            p11 = max(a11 - k1 * k1 * s, MIN_RATE_VARIANCE)
        }
        return Estimate(g, rate)
    }

    /**
     * [estimateAt] for every point of an ascending series, in the series' own unit.
     *
     * [mgdlPerUnit] converts the lane to mg/dL for the filter (18.0182 for mmol/L, 1 for mg/dL)
     * and back. An invalid point keeps its original value, as [GlucoseSmoothing.smoothLane] does.
     * [sameSource] ends one filtered stretch and starts a new one: state is not carried across a
     * change of sensor, so an imported stretch cannot pull the selected sensor's estimate.
     */
    internal fun <T> filterLane(
        points: List<T>,
        timestamp: (T) -> Long,
        selector: (T) -> Float,
        mgdlPerUnit: Float,
        params: Params = DEFAULT,
        sameSource: (T, T) -> Boolean = { _, _ -> true },
    ): FloatArray {
        val size = points.size
        val originals = FloatArray(size) { selector(points[it]) }
        val values = DoubleArray(size) { originals[it].toDouble() * mgdlPerUnit }
        val filtered = FloatArray(size)
        var start = 0
        while (start < size) {
            var end = start
            while (end + 1 < size && sameSource(points[end], points[end + 1])) end++
            val times = LongArray(end - start + 1) { timestamp(points[start + it]) }
            val segment = DoubleArray(times.size) { values[start + it] }
            for (offset in times.indices) {
                val estimate = estimateAt(times, segment, offset, params)
                filtered[start + offset] = if (estimate == null) {
                    originals[start + offset]
                } else {
                    (estimate.mgdl / mgdlPerUnit).toFloat()
                }
            }
            start = end + 1
        }
        return filtered
    }

    private fun isValid(value: Double): Boolean = value.isFinite() && value > 0.0
}
