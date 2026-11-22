package tk.glucodata.ui.overlay

import tk.glucodata.service.FloatingGlucoseService.CutoutEdge
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where the island pill sits against the camera cutout, in pixels. Pure, see the tests.
 *
 * The window is docked to a screen edge and the pill is placed in it by its near side: the
 * gap from the screen edge to the pill. To centre the pill on the cutout that gap is the
 * distance to the cutout's middle less half the pill's thickness across the edge, and the
 * thickness is the pill's height when docked to the top or bottom, its width on the left or
 * right. So no one gap fits both orientations, a font size or a digit more: the layout
 * works the gap out from the measured pill, and the setting is only a nudge from there.
 */
internal object FloatingIsland {
    /** True for the edges of an island standing upright beside the camera in landscape. */
    fun isSide(edge: CutoutEdge): Boolean = edge == CutoutEdge.LEFT || edge == CutoutEdge.RIGHT

    /**
     * The distance from the docked screen edge to the cutout's centre in display coordinates.
     */
    fun cutoutCentre(edge: CutoutEdge, x: Float, y: Float, displayWidth: Int, displayHeight: Int): Float =
        when (edge) {
            CutoutEdge.LEFT -> x
            CutoutEdge.RIGHT -> displayWidth - x
            CutoutEdge.BOTTOM -> displayHeight - y
            else -> y
        }

    /** The cutout's position along its edge, in the same full-display frame as the window. */
    data class CutoutAnchor(val centre: Float, val displayExtent: Int) {
        private fun windowStart(windowExtent: Int): Int = (centre - windowExtent / 2f).roundToInt()
            .coerceIn(0, (displayExtent - windowExtent).coerceAtLeast(0))

        fun windowOffset(windowExtent: Int): Int =
            windowStart(windowExtent) - (displayExtent - windowExtent).coerceAtLeast(0) / 2

        fun labelSpace(gap: Int): LabelSpace {
            val start = (centre - gap / 2f).roundToInt()
            return LabelSpace(start.coerceAtLeast(0), (start + gap).coerceAtMost(displayExtent), gap)
        }

        inner class LabelSpace(private val start: Int, private val end: Int, private val gap: Int) {
            private val beforeSpace = start
            private val afterSpace = displayExtent - end
            val maxLabelExtent = maxOf(beforeSpace, afterSpace)

            fun place(before: Int, after: Int): LabelPlacement {
                if (before <= beforeSpace && after <= afterSpace) {
                    val extent = (maxOf(before, after) * 2 + gap).coerceAtMost(displayExtent)
                    val origin = windowStart(extent)
                    return LabelPlacement(extent, start - origin - before, end - origin)
                }

                // Keep both groups readable on the roomier side of the camera. Stack
                // across the edge if the groups cannot fit there in sequence.
                val stacked = before + after > maxLabelExtent
                val occupied = if (stacked) maxOf(before, after) else before + after
                val extent = (occupied * 2 + gap).coerceAtMost(displayExtent)
                val origin = windowStart(extent)
                val first = if (beforeSpace > afterSpace) start - origin - occupied else end - origin
                return LabelPlacement(extent, first, first + if (stacked) 0 else before, stacked)
            }
        }
    }

    data class LabelPlacement(val extent: Int, val before: Int, val after: Int, val stacked: Boolean = false)

    fun centreLabels(gap: Int, before: Int, after: Int): LabelPlacement {
        val middle = maxOf(before, after)
        return LabelPlacement(middle * 2 + gap, middle - before, middle + gap)
    }

    /** The cutout's extent along the docked [edge]: its width on the top or bottom, its height on the left or right. */
    fun alongEdge(edge: CutoutEdge, width: Int, height: Int): Int = if (isSide(edge)) height else width

    /**
     * The gap between the screen edge and the pill's near side, for a pill [thickness]
     * thick across the edge, to centre it on the cutout [centre] from the edge, then moved
     * by [nudge] (positive away from the edge). Never negative: a pill thicker than twice
     * the distance sits against the edge. Without a reliable [centre], use the saved
     * absolute [manualOffset].
     */
    fun edgeGap(centre: Float, nudge: Float, thickness: Float, manualOffset: Float): Float =
        if (centre <= 0f) max(0f, manualOffset) else max(0f, centre + nudge - thickness / 2f)

    /** The pill's thickness across the docked [edge]: its width on the left or right, its height otherwise. */
    fun thickness(edge: CutoutEdge, pillWidth: Int, pillHeight: Int): Int = if (isSide(edge)) pillWidth else pillHeight

    /** The size of the window around a pill and where the pill sits in it. */
    data class Placement(val width: Int, val height: Int, val pillX: Int, val pillY: Int)

    /** The window of a [pillWidth] by [pillHeight] pill with [gap] between it and the screen edge it is docked to. */
    fun place(edge: CutoutEdge, pillWidth: Int, pillHeight: Int, gap: Int): Placement = when (edge) {
        CutoutEdge.LEFT -> Placement(pillWidth + gap, pillHeight, gap, 0)
        CutoutEdge.RIGHT -> Placement(pillWidth + gap, pillHeight, 0, 0)
        CutoutEdge.BOTTOM -> Placement(pillWidth, pillHeight + gap, 0, 0)
        else -> Placement(pillWidth, pillHeight + gap, 0, gap)
    }
}
