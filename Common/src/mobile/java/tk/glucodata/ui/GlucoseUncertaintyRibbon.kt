package tk.glucodata.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The "fluid glucose" band: a continuous region between the stored credible
 * bounds, drawn behind the glucose line.
 *
 * The point is to stop the chart implying that every CGM sample is known with
 * identical precision. Where the estimator is confident the band collapses
 * toward the line; where it is not — a missed sample, a temperature anomaly, an
 * unresolved question of whether a dip is real — it opens up.
 *
 * Two rules keep it honest:
 *
 *  - The bounds are drawn exactly as stored. No smoothing is applied to them,
 *    because a smoothed bound is a claim about certainty that the estimator
 *    did not make.
 *  - Bounds are independent, so the line does not have to sit in the geometric
 *    middle of the band. An asymmetric posterior draws an asymmetric band.
 *
 * Readings with no uncertainty break the band rather than being bridged: the
 * absence of an interval is not an interval of zero width.
 */
internal object GlucoseUncertaintyRibbon {

    /**
     * Alpha for the band, plus a faint edge so its boundary reads on a dark
     * theme.
     *
     * The first version used 0.16 with no edge and was effectively invisible on
     * a real phone: the target-range fill is drawn over the top of it, and a
     * 16% tint under another translucent layer disappears. Restrained is the
     * goal; imperceptible is a bug.
     */
    private const val BAND_ALPHA = tk.glucodata.chart.UncertaintyBands.FILL_ALPHA
    private const val EDGE_ALPHA = tk.glucodata.chart.UncertaintyBands.EDGE_ALPHA
    private const val EDGE_WIDTH_PX = 1.5f

    /** Bands thinner than this add visual noise without telling the reader anything. */
    private const val MIN_VISIBLE_HEIGHT_PX = 1.2f

    /**
     * Draws the band for [renderData] over `[startIndex, endIndex)`.
     *
     * Bounds have already followed calibration and smoothing in the shared
     * chart model; both renderers consume the same resolved interval.
     * @param gapThresholdMs elapsed time that breaks a run, matching the line's
     *   own gap convention so band and line break in the same places.
     */
    /** One chart-owned workspace, reused across runs, series, and animation frames. */
    class Paths {
        val fill = Path()
        val upper = Path()
        val lower = Path()
        val edgeStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = EDGE_WIDTH_PX)
    }

    fun DrawScope.drawUncertaintyRibbon(
        renderData: List<tk.glucodata.chart.ChartPointModel>,
        startIndex: Int,
        endIndex: Int,
        viewportStartMs: Long,
        timeScale: Float,
        chartHeight: Float,
        yMin: Float,
        yScale: Float,
        gapThresholdMs: Long,
        color: Color,
        paths: Paths = Paths(),
    ) {
        if (endIndex <= startIndex || yScale <= 0f) return
        var runStart = -1
        var maxHeight = 0f
        var previous: tk.glucodata.chart.ChartPointModel? = null
        fun x(timestamp: Long) = (timestamp - viewportStartMs) * timeScale
        fun y(value: Float) = (chartHeight - (value - yMin) * yScale)
            .coerceIn(-CLIP_MARGIN_PX, chartHeight + CLIP_MARGIN_PX)
        fun flush(end: Int) {
            if (runStart < 0 || end - runStart < 2 || maxHeight < MIN_VISIBLE_HEIGHT_PX) return
            for (index in end - 1 downTo runStart) {
                val point = renderData[index]
                paths.fill.lineTo(x(point.timestamp), y(requireNotNull(point.uncertainty).lower))
            }
            paths.fill.close()
            drawPath(paths.fill, color.copy(alpha = BAND_ALPHA))
            drawPath(paths.upper, color.copy(alpha = EDGE_ALPHA), style = paths.edgeStroke)
            drawPath(paths.lower, color.copy(alpha = EDGE_ALPHA), style = paths.edgeStroke)
        }
        // Stream the visible slice directly into reusable paths. Do not construct
        // run lists or boxed Offset arrays for every point on every animation frame.
        for (index in startIndex until endIndex) {
            val point = renderData[index]
            val interval = point.uncertainty
            if (interval?.isUsable != true || !point.value.isFinite() || point.value <= 0.1f) {
                flush(index)
                runStart = -1
                previous = null
                continue
            }
            val before = previous
            if (before != null && (point.timestamp - before.timestamp > gapThresholdMs ||
                    (before.sensorId != null && point.sensorId != null &&
                        !tk.glucodata.SensorIdentity.matches(before.sensorId, point.sensorId)))) {
                flush(index)
                runStart = -1
            }
            val px = x(point.timestamp)
            val upperY = y(interval.upper)
            val lowerY = y(interval.lower)
            if (runStart < 0) {
                runStart = index
                maxHeight = 0f
                paths.fill.rewind()
                paths.upper.rewind()
                paths.lower.rewind()
                paths.fill.moveTo(px, upperY)
                paths.upper.moveTo(px, upperY)
                paths.lower.moveTo(px, lowerY)
            } else {
                paths.fill.lineTo(px, upperY)
                paths.upper.lineTo(px, upperY)
                paths.lower.lineTo(px, lowerY)
            }
            maxHeight = maxOf(maxHeight, lowerY - upperY)
            previous = point
        }
        flush(endIndex)
    }

    private const val CLIP_MARGIN_PX = 2000f
}
