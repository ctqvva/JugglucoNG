package tk.glucodata.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The vertical colour banding a glucose curve is stroked with.
 *
 * The curve is not coloured per point but painted with a gradient whose stops
 * sit on the threshold lines, so the stretch that dips below target comes out
 * red purely by where it is on the canvas. The phone has drawn this way for a
 * while; the watch picked a single colour from the newest reading instead, so a
 * curve that had spent an hour low still rendered entirely neutral.
 *
 * The stops describe a piecewise-linear colour function of canvas y. Thresholds
 * routinely fall outside the drawn area — the watch fits its value range to the
 * data rather than to the alarm limits, so with a flat curve every threshold can
 * be off-canvas — and clamping their positions into the canvas would reorder the
 * stops and paint the wrong band across the visible area. The function is
 * therefore *clipped* to the canvas instead: off-canvas knots are dropped and the
 * colour at each edge is evaluated from the segment that crosses it.
 */
object GlucoseChartBands {

    /** Distance over which a band fades into the in-range tone, in pixels. */
    const val DEFAULT_FADE_PX = 18f

    /**
     * Gradient stops as (normalised position, colour), ascending.
     *
     * The y arguments are canvas positions of each threshold as returned by the
     * caller's value-to-y mapping, and may lie outside `0..chartHeightPx`.
     * [inRange] must be the neutral trace tone — passing a range-derived colour
     * paints the in-range stretches with it.
     */
    fun verticalStops(
        veryHigh: Color,
        high: Color,
        inRange: Color,
        low: Color,
        veryLow: Color,
        yVeryHigh: Float,
        yHigh: Float,
        yLow: Float,
        yVeryLow: Float,
        chartHeightPx: Float,
        fadePx: Float = DEFAULT_FADE_PX,
    ): List<Pair<Float, Color>> {
        if (chartHeightPx <= 0f) return emptyList()

        // Ideal knots, top to bottom. y grows downward and the thresholds
        // descend in value, so these are already in order — but a thin target
        // band can make the two fade knots cross, so positions are forced
        // non-decreasing rather than trusted.
        val knots = ArrayList<Pair<Float, Color>>(6)
        var last = Float.NEGATIVE_INFINITY
        fun knot(position: Float, color: Color) {
            val ordered = maxOf(position, last)
            knots += ordered to color
            last = ordered
        }
        knot(yVeryHigh, veryHigh)
        knot(yHigh, high)
        knot(yHigh + fadePx, inRange)
        knot(yLow - fadePx, inRange)
        knot(yLow, low)
        knot(yVeryLow, veryLow)

        val result = ArrayList<Pair<Float, Color>>(knots.size + 2)
        result += 0f to colorAt(knots, 0f)
        knots.forEach { (position, color) ->
            if (position > 0f && position < chartHeightPx) {
                result += (position / chartHeightPx) to color
            }
        }
        result += 1f to colorAt(knots, chartHeightPx)
        return result
    }

    /** The banding colour at canvas position [y], extending the end knots. */
    private fun colorAt(knots: List<Pair<Float, Color>>, y: Float): Color {
        if (knots.isEmpty()) return Color.Transparent
        if (y <= knots.first().first) return knots.first().second
        if (y >= knots.last().first) return knots.last().second
        for (index in 0 until knots.size - 1) {
            val (startY, startColor) = knots[index]
            val (endY, endColor) = knots[index + 1]
            if (y in startY..endY) {
                val span = endY - startY
                if (span <= 0f) return endColor
                return lerp(startColor, endColor, (y - startY) / span)
            }
        }
        return knots.last().second
    }
}
