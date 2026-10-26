package tk.glucodata.ui

import tk.glucodata.data.journal.IobCurvePoint
import tk.glucodata.data.journal.JournalIobMath

/**
 * How fast logged insulin is acting, in units an hour, stacked by insulin.
 *
 * Each dose's curve is a shape, its peak at 1; scaled by the dose and divided by the curve's
 * area it becomes the rate the dose is working at, so a 20 U basal spread over 42 hours reads
 * as the low, wide band it is and a 6 U bolus as a taller, short one. Doses add: two
 * overlapping corrections stack into one taller shape rather than drawing two curves across
 * each other, and each insulin keeps its own layer and colour.
 *
 * A [Dose.steadyState] curve is different: it was measured after days of repeated doses, so it
 * already holds the earlier doses' tails (Tresiba's starts at 70 % at the moment of injection).
 * Adding those up would count the tails again. Instead the latest such dose of an insulin stands
 * for all of them, scaled so one dosing interval of it adds up to one dose: the regimen's
 * activity, running out along the measured curve if the next dose doesn't come.
 */
internal object InsulinActivityStack {

    /** A steady-state curve longer than this was measured on once-weekly doses, not daily ones. */
    private const val WEEKLY_CURVE_MINUTES = 3 * 24 * 60

    /**
     * One dose: when, how much, its curve, the [layer] (insulin) it stacks in, and whether its
     * curve is a [steadyState] one.
     */
    internal class Dose(
        val timestamp: Long,
        val units: Float,
        val points: List<IobCurvePoint>,
        val layer: Int,
        val steadyState: Boolean = false
    ) {
        internal val areaHours: Float = if (steadyState) {
            curveAreaHours(points, upToMinute = dosingIntervalMinutes(points))
        } else {
            curveAreaHours(points)
        }
        internal val endMillis: Long = timestamp + (points.lastOrNull()?.minute ?: 0) * 60_000L
    }

    /** A day, or the whole curve for a once-weekly insulin. */
    internal fun dosingIntervalMinutes(points: List<IobCurvePoint>): Int {
        val end = points.lastOrNull()?.minute ?: 0
        return if (end > WEEKLY_CURVE_MINUTES) end else 24 * 60
    }

    /** The area under a curve up to [upToMinute] in activity-hours, as the trapezoids between its points. */
    fun curveAreaHours(points: List<IobCurvePoint>, upToMinute: Int = Int.MAX_VALUE): Float {
        var area = 0f
        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            if (a.minute >= upToMinute) break
            val endMinute = minOf(b.minute, upToMinute)
            val endActivity = if (endMinute == b.minute) b.activity else JournalIobMath.interpolate(points, endMinute.toFloat())
            area += (a.activity + endActivity) * 0.5f * (endMinute - a.minute).coerceAtLeast(0)
        }
        return area / 60f
    }

    /** How many units an hour [dose] is acting at [atMillis]; nothing before it or past its curve. */
    fun rate(dose: Dose, atMillis: Long): Float {
        if (atMillis < dose.timestamp || atMillis > dose.endMillis || dose.areaHours <= 0.0001f) return 0f
        if (!dose.units.isFinite() || dose.units <= 0f) return 0f
        val minute = (atMillis - dose.timestamp) / 60_000f
        return dose.units * JournalIobMath.interpolate(dose.points, minute).coerceAtLeast(0f) / dose.areaHours
    }

    /**
     * The stack at each of [times]: `tops[layer][i]` is the running total up to and including
     * [layer], so layer 0 rests on zero and each layer's band lies between the one below and its
     * own top.
     */
    fun stack(doses: List<Dose>, layers: Int, times: LongArray): Array<FloatArray> {
        val tops = Array(layers) { FloatArray(times.size) }
        val latestSteadyState = arrayOfNulls<Dose>(layers)
        for (i in times.indices) {
            val at = times[i]
            latestSteadyState.fill(null)
            for (dose in doses) {
                if (dose.layer !in 0 until layers) continue
                if (dose.steadyState) {
                    if (dose.timestamp <= at && (latestSteadyState[dose.layer]?.timestamp ?: Long.MIN_VALUE) < dose.timestamp) {
                        latestSteadyState[dose.layer] = dose
                    }
                } else {
                    tops[dose.layer][i] += rate(dose, at)
                }
            }
            for (layer in 0 until layers) {
                latestSteadyState[layer]?.let { tops[layer][i] += rate(it, at) }
            }
            for (layer in 1 until layers) tops[layer][i] += tops[layer - 1][i]
        }
        return tops
    }

    /**
     * Where to sample the stack between [start] and [end] to draw it exactly: the ends, and every
     * corner of every dose's curve, with a millisecond before each start and after each end so a
     * curve that starts or stops above zero steps there. Curves are straight between their
     * points, so nothing between these samples can be missed, however short the dose or wide
     * the chart.
     */
    fun sampleTimes(doses: List<Dose>, start: Long, end: Long): LongArray {
        if (end <= start) return longArrayOf(start)
        val times = ArrayList<Long>(2 + doses.sumOf { it.points.size + 2 })
        times += start
        times += end
        fun add(at: Long) {
            if (at in (start + 1) until end) times += at
        }
        for (dose in doses) {
            add(dose.timestamp - 1)
            for (point in dose.points) add(dose.timestamp + point.minute * 60_000L)
            add(dose.endMillis + 1)
        }
        times.sort()
        val distinct = LongArray(times.size)
        var count = 0
        for (at in times) {
            if (count == 0 || distinct[count - 1] != at) distinct[count++] = at
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
