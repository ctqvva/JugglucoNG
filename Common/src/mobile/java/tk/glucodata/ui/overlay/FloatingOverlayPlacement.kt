package tk.glucodata.ui.overlay

import kotlin.math.roundToInt

/**
 * The display the freeform overlay is kept on, in display pixels. The window is laid
 * out with FLAG_LAYOUT_NO_LIMITS, so without this a drag or a rotation could leave it
 * partly or wholly off-screen (#685). The status bar and cutout are part of it on
 * purpose: the pill may sit there.
 */
data class OverlayScreenArea(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Where the freeform overlay sits, as a share of the room it has left to travel on
 * each axis: 0 is flush with the left/top of the screen area, 1 flush with the
 * right/bottom. Kept instead of pixels so the overlay stays in the same spot of the
 * screen when it rotates or the pill changes size, and so no saved value can ever
 * put it off-screen.
 */
data class OverlayPosition(val x: Float, val y: Float) {
    companion object {
        /** Where "Reset position" puts it: clear of every system bar on any screen. */
        val CENTRE = OverlayPosition(0.5f, 0.5f)
    }
}

/** Window x/y for [position] when the overlay measures [width] x [height]. */
internal fun overlayPixels(
    position: OverlayPosition,
    area: OverlayScreenArea,
    width: Int,
    height: Int
): Pair<Int, Int> {
    val x = area.left + (fraction(position.x) * travel(area.left, area.right, width)).roundToInt()
    val y = area.top + (fraction(position.y) * travel(area.top, area.bottom, height)).roundToInt()
    return x to y
}

/**
 * Inverse of [overlayPixels], clamping [x]/[y] into the screen area first. On an axis
 * with no room to travel (overlay as large as the area) the share is meaningless,
 * so [previous] keeps its value there.
 */
internal fun overlayPosition(
    x: Int,
    y: Int,
    area: OverlayScreenArea,
    width: Int,
    height: Int,
    previous: OverlayPosition
): OverlayPosition {
    val travelX = travel(area.left, area.right, width)
    val travelY = travel(area.top, area.bottom, height)
    return OverlayPosition(
        x = if (travelX > 0) (x - area.left).coerceIn(0, travelX).toFloat() / travelX else previous.x,
        y = if (travelY > 0) (y - area.top).coerceIn(0, travelY).toFloat() / travelY else previous.y
    )
}

/** [x]/[y] moved the least distance that keeps the whole overlay inside [area]. */
internal fun clampOverlayPixels(
    x: Int,
    y: Int,
    area: OverlayScreenArea,
    width: Int,
    height: Int
): Pair<Int, Int> =
    (area.left + (x - area.left).coerceIn(0, travel(area.left, area.right, width))) to
        (area.top + (y - area.top).coerceIn(0, travel(area.top, area.bottom, height)))

// An overlay larger than the area pins to its left/top, so its start stays reachable.
private fun travel(start: Int, end: Int, size: Int): Int = (end - start - size).coerceAtLeast(0)

private fun fraction(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
