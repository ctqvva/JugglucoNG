package tk.glucodata.ui

import tk.glucodata.data.journal.IobCurvePoint

/**
 * How fast logged insulin is acting, in units an hour, stacked by insulin.
 *
 * Each dose's curve is a shape, its peak at 1; scaled by the dose and divided by the shape's
 * area it becomes the rate the dose is working at, so a 20 U basal spread over 42 hours reads
 * as the low, wide band it is and a 6 U bolus as a taller, short one. Doses add: two
 * overlapping corrections stack into one taller shape rather than drawing two curves across
 * each other, and each insulin keeps its own layer and colour.
 *
 * Shapes are drawn smooth, through a monotone cubic between the curve's points: it never
 * overshoots a point or dips below zero, so peaks stay where the source put them.
 *
 * Two kinds of curve need more than that to add up honestly:
 * - A curve that stops above zero (glargine U100 is still at 72 % when its 24 h clamp ends)
 *   would drop off a cliff there. It fades out over a quarter of its length instead, and that
 *   fade is reported apart as [Band.approximate], since no source measured it.
 * - A steady-state curve (Tresiba, Toujeo) was measured after days of daily doses, so it holds
 *   the earlier doses' tails: Tresiba's is at 70 % at the moment of injection. Under the usual
 *   assumption that doses add, the curve after the last of those doses is the sum of each
 *   day's dose, `S(t) = a(t) + a(t + 24 h) + …`, so one dose's own action is
 *   `a(t) = S(t) − S(t + 24 h)`. That single-dose shape adds like any other, so a missed or
 *   moved dose, a changed amount or a dose split into two injections all show as they would.
 *   A once-weekly curve (Awiqli) was measured over only one dosing week, too short to take
 *   apart that way; its latest dose stands for the regimen instead, scaled so one week of it
 *   adds up to one dose.
 */
internal object InsulinActivityStack {

    private const val DAY_MINUTES = 24 * 60

    /** A steady-state curve longer than this was measured on once-weekly doses, not daily ones. */
    private const val WEEKLY_CURVE_MINUTES = 3 * DAY_MINUTES

    /** How long the approximate fade-out past a curve that stops above zero runs, as a share of the curve. */
    private const val FADE_OUT_SHARE = 0.25f

    /** One dose: when, how much, its curve, the [layer] (insulin) it stacks in, and whether its curve is steady-state. */
    internal class Dose(
        val timestamp: Long,
        val units: Float,
        val points: List<IobCurvePoint>,
        val layer: Int,
        val steadyState: Boolean = false
    ) {
        internal val shape: Shape = shapeFor(points, steadyState)
        internal val areaHours: Float get() = shape.areaHours
        internal val endMillis: Long = timestamp + (shape.endMinute * 60_000f).toLong()
    }

    /** The insulin band at each sample time. */
    internal class Band(
        /** `tops[layer][i]`: the running total up to and including that layer. */
        val tops: Array<FloatArray>,
        /** `approximate[layer][i]`: how much of that layer's own share is an unmeasured fade-out. */
        val approximate: Array<FloatArray>
    )

    /** A curve made ready to draw: smooth, with its fade-out, its area, and where its points fall. */
    internal class Shape private constructor(
        private val curve: Smooth,
        /** Past this minute the activity is the approximate fade-out. */
        val measuredEndMinute: Float,
        val endMinute: Float,
        /** A once-weekly steady-state curve: the latest dose stands for the regimen. */
        val holdsLatest: Boolean,
        /** Activity-hours one dose's worth of this shape adds up to. */
        val areaHours: Float,
        /** The minutes the curve bends at, for sampling. */
        val knotMinutes: FloatArray
    ) {
        fun activity(minute: Float): Float = curve.at(minute)

        companion object {
            fun of(points: List<IobCurvePoint>, steadyState: Boolean): Shape {
                val source = Smooth.of(points)
                val sourceEnd = source.endMinute
                return when {
                    steadyState && sourceEnd > WEEKLY_CURVE_MINUTES -> Shape(
                        curve = source,
                        measuredEndMinute = source.measuredEndMinute,
                        endMinute = sourceEnd,
                        holdsLatest = true,
                        areaHours = source.integral(0f, source.measuredEndMinute) / 60f,
                        knotMinutes = source.knotMinutes
                    )
                    steadyState -> {
                        // One dose's own action: what the regimen does, less what it still does a
                        // day later. It is taken at the steady-state curve's own points and the
                        // same points a day earlier, and starts from zero at the injection.
                        val minutes = (source.knotMinutes.map { it } + source.knotMinutes.map { it - DAY_MINUTES })
                            .filter { it >= 0f && it <= source.measuredEndMinute }
                            .plus(0f)
                            .distinct()
                            .sorted()
                        val single = Smooth.of(minutes.map { minute ->
                            val activity = if (minute == 0f) 0f else (source.at(minute) - source.at(minute + DAY_MINUTES)).coerceAtLeast(0f)
                            IobCurvePoint(minute.toInt(), activity)
                        })
                        Shape(
                            curve = single,
                            measuredEndMinute = single.measuredEndMinute,
                            endMinute = single.endMinute,
                            holdsLatest = false,
                            areaHours = single.integral(0f, single.endMinute) / 60f,
                            knotMinutes = single.knotMinutes
                        )
                    }
                    else -> Shape(
                        curve = source,
                        measuredEndMinute = source.measuredEndMinute,
                        endMinute = sourceEnd,
                        holdsLatest = false,
                        areaHours = source.integral(0f, sourceEnd) / 60f,
                        knotMinutes = source.knotMinutes
                    )
                }
            }
        }
    }

    /**
     * A curve through its points by monotone cubic (Fritsch–Carlson), so it bends smoothly but
     * never overshoots a point or goes below zero, and past its last point a fade to zero over
     * [FADE_OUT_SHARE] of its length if it stops above zero.
     */
    private class Smooth(
        private val minutes: FloatArray,
        private val values: FloatArray,
        private val slopes: FloatArray,
        private val fadeMinutes: Float,
        private val fadeSlope: Float
    ) {
        val measuredEndMinute: Float get() = minutes.last()
        val endMinute: Float get() = minutes.last() + fadeMinutes
        val knotMinutes: FloatArray get() = if (fadeMinutes > 0f) minutes + endMinute else minutes

        fun at(minute: Float): Float {
            if (minute < minutes.first()) return 0f
            val last = minutes.lastIndex
            if (minute >= minutes[last]) {
                if (fadeMinutes <= 0f || minute >= minutes[last] + fadeMinutes) {
                    return if (minute == minutes[last]) values[last] else 0f
                }
                val t = (minute - minutes[last]) / fadeMinutes
                return hermite(values[last], fadeSlope * fadeMinutes, 0f, 0f, t).coerceAtLeast(0f)
            }
            var low = 0
            var high = last
            while (high - low > 1) {
                val middle = (low + high) ushr 1
                if (minutes[middle] <= minute) low = middle else high = middle
            }
            val span = minutes[high] - minutes[low]
            if (span <= 0f) return values[high]
            val t = (minute - minutes[low]) / span
            return hermite(values[low], slopes[low] * span, values[high], slopes[high] * span, t).coerceAtLeast(0f)
        }

        /** Simpson's rule at about two-minute steps. */
        fun integral(from: Float, to: Float): Float {
            if (to <= from) return 0f
            val steps = (((to - from) / 2f).toInt().coerceAtLeast(1) + 1) and 1.inv()
            val h = (to - from) / steps
            var sum = at(from) + at(to)
            for (index in 1 until steps) sum += at(from + index * h) * if (index % 2 == 1) 4f else 2f
            return sum * h / 3f
        }

        companion object {
            fun of(points: List<IobCurvePoint>): Smooth {
                val sorted = points.sortedBy { it.minute }.distinctBy { it.minute }.ifEmpty { listOf(IobCurvePoint(0, 0f)) }
                val minutes = FloatArray(sorted.size) { sorted[it].minute.toFloat() }
                val values = FloatArray(sorted.size) { sorted[it].activity.coerceAtLeast(0f) }
                val slopes = monotoneSlopes(minutes, values)
                val length = minutes.last() - minutes.first()
                val fadeMinutes = if (values.last() > 0.0001f && length > 0f) length * FADE_OUT_SHARE else 0f
                // Leave the last point as the curve was heading, if it was falling, but never so
                // steeply that the fade would dip below zero before it ends.
                val fadeSlope = if (fadeMinutes > 0f) {
                    slopes.last().coerceIn(-3f * values.last() / fadeMinutes, 0f)
                } else {
                    0f
                }
                return Smooth(minutes, values, slopes, fadeMinutes, fadeSlope)
            }

            private fun monotoneSlopes(x: FloatArray, y: FloatArray): FloatArray {
                val n = x.size
                val slopes = FloatArray(n)
                if (n < 2) return slopes
                val h = FloatArray(n - 1) { x[it + 1] - x[it] }
                val delta = FloatArray(n - 1) { if (h[it] > 0f) (y[it + 1] - y[it]) / h[it] else 0f }
                if (n == 2) {
                    slopes[0] = delta[0]
                    slopes[1] = delta[0]
                    return slopes
                }
                for (k in 1 until n - 1) {
                    val before = delta[k - 1]
                    val after = delta[k]
                    slopes[k] = if (before * after <= 0f) {
                        0f
                    } else {
                        val w1 = 2f * h[k] + h[k - 1]
                        val w2 = h[k] + 2f * h[k - 1]
                        (w1 + w2) / (w1 / before + w2 / after)
                    }
                }
                slopes[0] = endSlope(h[0], h[1], delta[0], delta[1])
                slopes[n - 1] = endSlope(h[n - 2], h[n - 3], delta[n - 2], delta[n - 3])
                return slopes
            }

            private fun endSlope(h0: Float, h1: Float, d0: Float, d1: Float): Float {
                val slope = ((2f * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
                return when {
                    slope * d0 <= 0f -> 0f
                    d0 * d1 < 0f && kotlin.math.abs(slope) > 3f * kotlin.math.abs(d0) -> 3f * d0
                    else -> slope
                }
            }

            private fun hermite(y0: Float, m0: Float, y1: Float, m1: Float, t: Float): Float {
                val t2 = t * t
                val t3 = t2 * t
                return (2f * t3 - 3f * t2 + 1f) * y0 + (t3 - 2f * t2 + t) * m0 +
                    (-2f * t3 + 3f * t2) * y1 + (t3 - t2) * m1
            }
        }
    }

    private val shapes = object : LinkedHashMap<Pair<List<IobCurvePoint>, Boolean>, Shape>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<List<IobCurvePoint>, Boolean>, Shape>?) = size > 64
    }

    /** Shapes are worked out once per curve; the chart rebuilds its doses every frame. */
    @Synchronized
    private fun shapeFor(points: List<IobCurvePoint>, steadyState: Boolean): Shape =
        shapes.getOrPut(points to steadyState) { Shape.of(points, steadyState) }

    /** How many units an hour [dose] is acting at [atMillis]; nothing before it or past its fade-out. */
    fun rate(dose: Dose, atMillis: Long): Float {
        if (atMillis < dose.timestamp || atMillis > dose.endMillis || dose.areaHours <= 0.0001f) return 0f
        if (!dose.units.isFinite() || dose.units <= 0f) return 0f
        val minute = (atMillis - dose.timestamp) / 60_000f
        return dose.units * dose.shape.activity(minute) / dose.areaHours
    }

    /** The part of [rate] that is the approximate fade-out past the dose's last measured point. */
    fun approximateRate(dose: Dose, atMillis: Long): Float {
        val minute = (atMillis - dose.timestamp) / 60_000f
        return if (minute > dose.shape.measuredEndMinute) rate(dose, atMillis) else 0f
    }

    /**
     * The stack at each of [times]: layer 0 rests on zero and each layer's band lies between the
     * one below and its own top.
     */
    fun stack(doses: List<Dose>, layers: Int, times: LongArray): Band {
        val tops = Array(layers) { FloatArray(times.size) }
        val approximate = Array(layers) { FloatArray(times.size) }
        val latestHeld = LongArray(layers)
        for (i in times.indices) {
            val at = times[i]
            latestHeld.fill(Long.MIN_VALUE)
            for (dose in doses) {
                if (dose.layer !in 0 until layers) continue
                if (dose.shape.holdsLatest) {
                    if (dose.timestamp <= at) latestHeld[dose.layer] = maxOf(latestHeld[dose.layer], dose.timestamp)
                } else {
                    tops[dose.layer][i] += rate(dose, at)
                    approximate[dose.layer][i] += approximateRate(dose, at)
                }
            }
            // Every once-weekly dose given at that latest time counts: a dose split into two
            // injections is logged as two entries at the same minute.
            for (dose in doses) {
                if (dose.shape.holdsLatest && dose.layer in 0 until layers && dose.timestamp == latestHeld[dose.layer]) {
                    tops[dose.layer][i] += rate(dose, at)
                    approximate[dose.layer][i] += approximateRate(dose, at)
                }
            }
            for (layer in 1 until layers) tops[layer][i] += tops[layer - 1][i]
        }
        return Band(tops, approximate)
    }

    /**
     * Where to sample the stack between [start] and [end]: every [stepMillis], which keeps the
     * smooth curves smooth, plus every point of every dose's curve. Each shape peaks at one of
     * its points, so a dose shorter than the step still shows at its full height.
     */
    fun sampleTimes(doses: List<Dose>, start: Long, end: Long, stepMillis: Long): LongArray {
        if (end <= start) return longArrayOf(start)
        val step = stepMillis.coerceAtLeast(1L)
        val times = ArrayList<Long>(((end - start) / step).toInt() + 2 + doses.sumOf { it.shape.knotMinutes.size + 1 })
        var at = start
        while (at < end) {
            times += at
            at += step
        }
        times += end
        for (dose in doses) {
            times += dose.timestamp
            for (minute in dose.shape.knotMinutes) times += dose.timestamp + (minute * 60_000f).toLong()
        }
        val sorted = times.filter { it in start..end }.sorted()
        val distinct = LongArray(sorted.size)
        var count = 0
        for (time in sorted) {
            if (count == 0 || distinct[count - 1] != time) distinct[count++] = time
        }
        return distinct.copyOf(count)
    }

    /**
     * The rate the band's full height stands for: the highest total, rounded up to a whole or
     * half unit an hour, so panning across small changes leaves the scale where it is.
     */
    fun scaleFor(highest: Float): Float {
        if (!highest.isFinite() || highest <= 0f) return 1f
        val step = if (highest < 2f) 0.5f else 1f
        return (kotlin.math.ceil(highest / step) * step).coerceAtLeast(0.5f)
    }
}
