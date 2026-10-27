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
 * Nothing is extrapolated. A curve ends where its source stopped observing, even while still
 * acting: Lantus's clamp ended at 24 h at 72 %, and its label gives no end of action beyond
 * that ("24 hours was the end of the observation period"), so neither does this. Where a
 * curve stops above zero, [Band.trailing] carries the activity it stopped at, fading out over
 * a drawing distance only, so the chart can show that the data ends there rather than that
 * the insulin does. It is never part of the totals or the scale.
 *
 * A steady-state curve (Tresiba, Toujeo) was measured after days of daily doses, so it holds
 * the earlier doses' tails: Tresiba's is at 70 % at the moment of injection. Under the usual
 * assumption that doses add, the curve after the last of those doses is the sum of each day's
 * dose, `S(t) = a(t) + a(t + 24 h) + …`, so one dose's own action is `a(t) = S(t) − S(t + 24 h)`,
 * taken as zero past the observation. That single-dose shape adds like any other, so a missed
 * or moved dose, a changed amount or a dose split into two injections all show as they would,
 * and one day of it adds up to exactly one dose. A once-weekly curve (Awiqli) was measured over
 * only one dosing week, too short to take apart that way; its latest dose stands for the
 * regimen instead, scaled so one week of it adds up to one dose.
 */
internal object InsulinActivityStack {

    private const val DAY_MINUTES = 24 * 60

    /** A steady-state curve longer than this was measured on once-weekly doses, not daily ones. */
    private const val WEEKLY_CURVE_MINUTES = 3 * DAY_MINUTES

    /** Headroom over the highest total, so the band's top isn't drawn against the scale. */
    private const val SCALE_HEADROOM = 1.1f

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
        /**
         * `trailing[layer][i]`: where a curve's data stopped while it was still acting, the rate it
         * stopped at, fading to nothing over the drawing distance passed to [stack]. Not a total.
         */
        val trailing: Array<FloatArray>
    )

    /** A curve made ready to draw: smooth, with its area and where its points fall. */
    internal class Shape private constructor(
        private val curve: Smooth,
        /** A once-weekly steady-state curve: the latest dose stands for the regimen. */
        val holdsLatest: Boolean,
        /** Activity-hours one dose's worth of this shape adds up to. */
        val areaHours: Float
    ) {
        val endMinute: Float get() = curve.endMinute
        /** The activity the source stopped observing at; above zero, the insulin was still acting. */
        val endActivity: Float get() = curve.at(curve.endMinute)
        /** The minutes the curve bends at, for sampling. */
        val knotMinutes: FloatArray get() = curve.knotMinutes

        fun activity(minute: Float): Float = curve.at(minute)

        companion object {
            fun of(points: List<IobCurvePoint>, steadyState: Boolean): Shape {
                val source = Smooth.of(points)
                return when {
                    steadyState && source.endMinute > WEEKLY_CURVE_MINUTES ->
                        Shape(source, holdsLatest = true, areaHours = source.integral(0f, source.endMinute) / 60f)
                    steadyState -> {
                        // One dose's own action: what the regimen does, less what it still does a
                        // day later. It is taken at the steady-state curve's own points and the
                        // same points a day earlier, and starts from zero at the injection.
                        val minutes = (source.knotMinutes.toList() + source.knotMinutes.map { it - DAY_MINUTES })
                            .filter { it >= 0f && it <= source.endMinute }
                            .plus(0f)
                            .distinct()
                            .sorted()
                        val single = Smooth.of(minutes.map { minute ->
                            val activity = if (minute == 0f) 0f else (source.at(minute) - source.at(minute + DAY_MINUTES)).coerceAtLeast(0f)
                            IobCurvePoint(minute.toInt(), activity)
                        })
                        Shape(single, holdsLatest = false, areaHours = single.integral(0f, single.endMinute) / 60f)
                    }
                    else -> Shape(source, holdsLatest = false, areaHours = source.integral(0f, source.endMinute) / 60f)
                }
            }
        }
    }

    /**
     * A curve through its points by monotone cubic (Fritsch–Carlson), so it bends smoothly but
     * never overshoots a point or goes below zero; nothing before its first point or past its last.
     */
    private class Smooth(
        private val minutes: FloatArray,
        private val values: FloatArray,
        private val slopes: FloatArray
    ) {
        val endMinute: Float get() = minutes.last()
        val knotMinutes: FloatArray get() = minutes

        fun at(minute: Float): Float {
            val last = minutes.lastIndex
            if (minute < minutes.first() || minute > minutes[last]) return 0f
            if (minute == minutes[last]) return values[last]
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
                return Smooth(minutes, values, monotoneSlopes(minutes, values))
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

    /** How many units an hour [dose] is acting at [atMillis]; nothing before it or past its curve. */
    fun rate(dose: Dose, atMillis: Long): Float {
        if (atMillis < dose.timestamp || atMillis > dose.endMillis || dose.areaHours <= 0.0001f) return 0f
        if (!dose.units.isFinite() || dose.units <= 0f) return 0f
        val minute = (atMillis - dose.timestamp) / 60_000f
        return dose.units * dose.shape.activity(minute) / dose.areaHours
    }

    /**
     * The stack at each of [times]: layer 0 rests on zero and each layer's band lies between the
     * one below and its own top. [trailingMillis] is how far, at the chart's zoom, a curve that
     * stops above zero trails off in [Band.trailing].
     */
    fun stack(doses: List<Dose>, layers: Int, times: LongArray, trailingMillis: Long = 0L): Band {
        val tops = Array(layers) { FloatArray(times.size) }
        val trailing = Array(layers) { FloatArray(times.size) }
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
                }
                trailing[dose.layer][i] += trailingRate(dose, at, trailingMillis)
            }
            // Every once-weekly dose given at that latest time counts: a dose split into two
            // injections is logged as two entries at the same minute.
            for (dose in doses) {
                if (dose.shape.holdsLatest && dose.layer in 0 until layers && dose.timestamp == latestHeld[dose.layer]) {
                    tops[dose.layer][i] += rate(dose, at)
                }
            }
            for (layer in 1 until layers) tops[layer][i] += tops[layer - 1][i]
        }
        return Band(tops, trailing)
    }

    private fun trailingRate(dose: Dose, atMillis: Long, trailingMillis: Long): Float {
        if (trailingMillis <= 0L || atMillis <= dose.endMillis || atMillis >= dose.endMillis + trailingMillis) return 0f
        if (dose.shape.endActivity <= 0.0001f || dose.areaHours <= 0.0001f || !dose.units.isFinite() || dose.units <= 0f) return 0f
        val t = (atMillis - dose.endMillis).toFloat() / trailingMillis
        val fade = 1f - t * t * (3f - 2f * t)
        return dose.units * dose.shape.endActivity / dose.areaHours * fade
    }

    /**
     * Where to sample the stack between [start] and [end]: every [stepMillis], which keeps the
     * smooth curves smooth, plus every point of every dose's curve and a millisecond past its
     * end. Each shape peaks at one of its points, so a dose shorter than the step still shows at
     * its full height.
     */
    fun sampleTimes(doses: List<Dose>, start: Long, end: Long, stepMillis: Long): LongArray {
        if (end <= start) return longArrayOf(start)
        val step = stepMillis.coerceAtLeast(1L)
        val times = ArrayList<Long>(((end - start) / step).toInt() + 2 + doses.sumOf { it.shape.knotMinutes.size + 2 })
        var at = start
        while (at < end) {
            times += at
            at += step
        }
        times += end
        for (dose in doses) {
            times += dose.timestamp
            for (minute in dose.shape.knotMinutes) times += dose.timestamp + (minute * 60_000f).toLong()
            times += dose.endMillis + 1
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
     * The rate the band's full height stands for: the highest total with a little headroom,
     * rounded up to a whole or half unit an hour.
     */
    fun scaleFor(highest: Float): Float {
        if (!highest.isFinite() || highest <= 0f) return 1f
        val wanted = highest * SCALE_HEADROOM
        val step = if (wanted < 2f) 0.5f else 1f
        return (kotlin.math.ceil(wanted / step) * step).coerceAtLeast(0.5f)
    }

    /**
     * Keeps the band's scale still while the chart moves. It grows at once when a taller total
     * comes into view, so nothing is clipped, but doesn't shrink while the user is panning: the
     * same basal dose keeps the same height as a big bolus scrolls off. When they let go, or
     * the zoom changes, it fits what is on screen again.
     */
    internal class ScaleHold {
        private var held = 0f
        private var heldZoom = Long.MIN_VALUE

        fun scale(highest: Float, zoomMillis: Long, navigating: Boolean): Float {
            val fitted = scaleFor(highest)
            held = when {
                zoomMillis != heldZoom || held <= 0f || !navigating -> fitted
                else -> maxOf(held, fitted)
            }
            heldZoom = zoomMillis
            return held
        }
    }
}
