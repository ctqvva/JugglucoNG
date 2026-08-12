package tk.glucodata.ui

import androidx.compose.ui.graphics.Color

/**
 * The vertical colour banding a glucose curve is stroked with.
 *
 * The curve is not coloured per point but painted with a gradient whose stops
 * sit on the threshold lines, so the stretch that dips below target comes out
 * red purely by where it is on the canvas. The phone has drawn this way for a
 * while; the watch picked a single colour from the newest reading instead, so a
 * curve that had spent an hour low still rendered entirely neutral.
 *
 * Both now build their stops here, which keeps the bands landing on the same
 * lines on both devices. Callers supply their own resolved colours — the phone
 * offers a softer in-range trace than its five-band setting preview does.
 */
object GlucoseChartBands {

    /** Distance over which a band fades into the in-range tone, in pixels. */
    const val DEFAULT_FADE_PX = 18f

    /**
     * Gradient stops as (normalised position, colour), sorted top to bottom.
     * The y arguments are canvas positions of each threshold, as returned by the
     * caller's value-to-y mapping; [chartHeightPx] is what they are normalised
     * against.
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
        val stops = mutableListOf<Pair<Float, Color>>()

        fun addStop(y: Float, color: Color) {
            stops += (y / chartHeightPx).coerceIn(0f, 1f) to color
        }

        addStop(0f, veryHigh)
        addStop(yVeryHigh, veryHigh)
        addStop(yHigh, high)
        addStop(yHigh + fadePx, inRange)
        addStop(yLow - fadePx, inRange)
        addStop(yLow, low)
        addStop(yVeryLow, veryLow)
        addStop(chartHeightPx, veryLow)

        return stops.sortedBy { it.first }
    }
}
