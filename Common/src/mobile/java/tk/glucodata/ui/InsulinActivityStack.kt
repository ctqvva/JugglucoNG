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
 */
internal object InsulinActivityStack {

    /** One dose: when, how much, its curve, and the [layer] (insulin) it stacks in. */
    internal class Dose(val timestamp: Long, val units: Float, val points: List<IobCurvePoint>, val layer: Int) {
        internal val areaHours: Float = curveAreaHours(points)
        internal val endMillis: Long = timestamp + (points.lastOrNull()?.minute ?: 0) * 60_000L
    }

    /** The area under a curve in activity-hours, as the trapezoids between its points. */
    fun curveAreaHours(points: List<IobCurvePoint>): Float {
        var area = 0f
        for (index in 0 until points.lastIndex) {
            val a = points[index]
            val b = points[index + 1]
            area += (a.activity + b.activity) * 0.5f * (b.minute - a.minute).coerceAtLeast(0)
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
        for (i in times.indices) {
            val at = times[i]
            for (dose in doses) {
                if (dose.layer !in 0 until layers) continue
                tops[dose.layer][i] += rate(dose, at)
            }
            for (layer in 1 until layers) tops[layer][i] += tops[layer - 1][i]
        }
        return tops
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
