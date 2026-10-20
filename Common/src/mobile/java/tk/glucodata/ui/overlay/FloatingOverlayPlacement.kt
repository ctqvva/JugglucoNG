package tk.glucodata.ui.overlay

import kotlin.math.roundToInt

/**
 * The part of the display the freeform overlay may cover, in display pixels: the
 * display minus the status bar, navigation bar and camera cutout. Those system
 * windows sit above application overlays and take every touch on them, so an
 * overlay parked underneath stays visible but can never be grabbed again (#685).
 */
data class OverlaySafeArea(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Where the freeform overlay sits, as a share of the room it has left to travel on
 * each axis: 0 is flush with the left/top of the safe area, 1 flush with the
 * right/bottom. Kept instead of pixels so the overlay stays in the same spot of the
 * screen when it rotates or the pill changes size, and so no saved value can ever
 * put it out of reach.
 */
data class OverlayPosition(val x: Float, val y: Float) {
    companion object {
        /** Top centre, just below the status bar. */
        val DEFAULT = OverlayPosition(0.5f, 0f)
    }
}

/** Window x/y for [position] when the overlay measures [width] x [height]. */
internal fun overlayPixels(
    position: OverlayPosition,
    area: OverlaySafeArea,
    width: Int,
    height: Int
): Pair<Int, Int> {
    val x = area.left + (fraction(position.x) * travel(area.left, area.right, width)).roundToInt()
    val y = area.top + (fraction(position.y) * travel(area.top, area.bottom, height)).roundToInt()
    return x to y
}

/**
 * Inverse of [overlayPixels], clamping [x]/[y] into the safe area first. On an axis
 * with no room to travel (overlay as large as the area) the share is meaningless,
 * so [previous] keeps its value there.
 */
internal fun overlayPosition(
    x: Int,
    y: Int,
    area: OverlaySafeArea,
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
    area: OverlaySafeArea,
    width: Int,
    height: Int
): Pair<Int, Int> =
    (area.left + (x - area.left).coerceIn(0, travel(area.left, area.right, width))) to
        (area.top + (y - area.top).coerceIn(0, travel(area.top, area.bottom, height)))

// An overlay larger than the area pins to its left/top, so its start stays reachable.
private fun travel(start: Int, end: Int, size: Int): Int = (end - start - size).coerceAtLeast(0)

private fun fraction(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
