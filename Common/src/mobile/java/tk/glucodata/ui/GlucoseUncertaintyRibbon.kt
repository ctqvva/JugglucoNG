package tk.glucodata.ui

import androidx.compose.ui.geometry.Offset
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
    private const val BAND_ALPHA = 0.26f
    private const val EDGE_ALPHA = 0.42f
    private const val EDGE_WIDTH_PX = 1.5f

    /** Bands thinner than this add visual noise without telling the reader anything. */
    private const val MIN_VISIBLE_HEIGHT_PX = 1.2f

    /**
     * Draws the band for [renderData] over `[startIndex, endIndex)`.
     *
     * @param centerValueAt the value actually drawn for a point, which may
     *   differ from `point.value` when an app-level calibration is applied. The
     *   band is shifted by the same amount so it stays around the line it
     *   describes rather than around the uncalibrated value.
     * @param gapThresholdMs elapsed time that breaks a run, matching the line's
     *   own gap convention so band and line break in the same places.
     */
    fun DrawScope.drawUncertaintyRibbon(
        renderData: List<GlucosePoint>,
        startIndex: Int,
        endIndex: Int,
        viewportStartMs: Long,
        timeScale: Float,
        chartHeight: Float,
        yMin: Float,
        yScale: Float,
        gapThresholdMs: Long,
        color: Color,
        centerValueAt: (Int) -> Float,
    ) {
        if (endIndex <= startIndex || yScale <= 0f) return

        val upperPoints = ArrayList<Offset>(32)
        val lowerPoints = ArrayList<Offset>(32)
        var lastTimestamp = Long.MIN_VALUE
        var lastSensorSerial: String? = null
        var anyDrawn = false

        fun flush() {
            if (upperPoints.size >= 2) {
                drawBand(upperPoints, lowerPoints, color)
                anyDrawn = true
            }
            upperPoints.clear()
            lowerPoints.clear()
        }

        for (i in startIndex until endIndex) {
            val point = renderData[i]
            val uncertainty = point.uncertainty
            val center = centerValueAt(i)

            if (uncertainty == null || !uncertainty.isUsable ||
                !center.isFinite() || center <= 0.1f
            ) {
                flush()
                lastTimestamp = Long.MIN_VALUE
                lastSensorSerial = point.sensorSerial
                continue
            }

            val sensorChanged = lastSensorSerial != null && point.sensorSerial != null &&
                point.sensorSerial != lastSensorSerial
            val gapExceeded = lastTimestamp != Long.MIN_VALUE &&
                (point.timestamp - lastTimestamp) > gapThresholdMs
            if (sensorChanged || gapExceeded) flush()

            // Keep the band around the line as drawn: a calibration that shifts
            // the value shifts the interval that describes it by the same amount.
            val shift = center - point.value
            val x = (point.timestamp - viewportStartMs) * timeScale
            val upperY = chartHeight - ((uncertainty.upper + shift - yMin) * yScale)
            val lowerY = chartHeight - ((uncertainty.lower + shift - yMin) * yScale)
            if (!x.isFinite() || !upperY.isFinite() || !lowerY.isFinite()) {
                flush()
                lastTimestamp = Long.MIN_VALUE
                lastSensorSerial = point.sensorSerial
                continue
            }

            // Clipped generously rather than exactly: a band that leaves the
            // top of the viewport should still enter and exit through the edge
            // instead of disappearing.
            val clampedUpper = upperY.coerceIn(-CLIP_MARGIN_PX, chartHeight + CLIP_MARGIN_PX)
            val clampedLower = lowerY.coerceIn(-CLIP_MARGIN_PX, chartHeight + CLIP_MARGIN_PX)
            upperPoints += Offset(x, clampedUpper)
            lowerPoints += Offset(x, clampedLower)

            lastTimestamp = point.timestamp
            lastSensorSerial = point.sensorSerial
        }
        flush()
        if (!anyDrawn) return
    }

    private fun DrawScope.drawBand(
        upperPoints: List<Offset>,
        lowerPoints: List<Offset>,
        color: Color,
    ) {
        var maxHeight = 0f
        for (index in upperPoints.indices) {
            val height = lowerPoints[index].y - upperPoints[index].y
            if (height > maxHeight) maxHeight = height
        }
        if (maxHeight < MIN_VISIBLE_HEIGHT_PX) return

        val path = Path().apply {
            moveTo(upperPoints.first().x, upperPoints.first().y)
            for (index in 1 until upperPoints.size) {
                lineTo(upperPoints[index].x, upperPoints[index].y)
            }
            for (index in lowerPoints.indices.reversed()) {
                lineTo(lowerPoints[index].x, lowerPoints[index].y)
            }
            close()
        }
        drawPath(path, color.copy(alpha = BAND_ALPHA))
        drawEdge(upperPoints, color)
        drawEdge(lowerPoints, color)
    }

    private fun DrawScope.drawEdge(points: List<Offset>, color: Color) {
        val edge = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (index in 1 until points.size) lineTo(points[index].x, points[index].y)
        }
        drawPath(
            edge,
            color.copy(alpha = EDGE_ALPHA),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = EDGE_WIDTH_PX),
        )
    }

    private const val CLIP_MARGIN_PX = 2000f
}
