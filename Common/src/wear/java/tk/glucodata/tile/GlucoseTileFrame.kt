package tk.glucodata.tile

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The geometry and cache identity of the glucose tile, kept free of Android types so the
 * sums can be tested without a watch.
 *
 * The tile is one image: the same trace, value, arrow and time the chart complications
 * draw, so the two never disagree about what a reading looks like.
 */
internal object GlucoseTileFrame {
    /**
     * Share of the screen diameter the picture takes. A rectangle of w x h only sits inside a
     * round screen when w² + h² stays under the diameter squared, and the tile also gives up
     * a strip at the top for the system clock, so this leaves a margin on both.
     */
    private const val WIDTH_SHARE = 0.84f
    private const val HEIGHT_SHARE = 0.50f

    /** A picture wider than this only costs memory and PNG bytes on a watch-sized screen. */
    private const val MAX_WIDTH_PX = 640

    /** Below this a layout request is bogus (a preview with no real device), so use a stock watch. */
    private const val FALLBACK_SCREEN_DP = 192

    data class Size(val widthPx: Int, val heightPx: Int)

    /** Width and height in pixels of the trace picture for a screen of the given size. */
    fun chartSize(screenWidthDp: Int, screenHeightDp: Int, density: Float): Size {
        val dpi = density.takeIf { it.isFinite() && it > 0f } ?: 2f
        val diameter = min(
            screenWidthDp.takeIf { it > 0 } ?: FALLBACK_SCREEN_DP,
            screenHeightDp.takeIf { it > 0 } ?: FALLBACK_SCREEN_DP,
        )
        val width = min((diameter * WIDTH_SHARE * dpi).roundToInt(), MAX_WIDTH_PX)
        val height = (width * (HEIGHT_SHARE / WIDTH_SHARE)).roundToInt()
        return Size(max(width, 1), max(height, 1))
    }

    /** Side of the square picture used when there is a reading but no history to draw. */
    fun valueSide(size: Size): Int = size.heightPx

    /** Pixels back to the density-independent size the layout wants. */
    fun dp(px: Int, density: Float): Float {
        val dpi = density.takeIf { it.isFinite() && it > 0f } ?: 2f
        return px / dpi
    }

    /**
     * Identity of one rendering. The system keeps a tile's resources by this string and does not
     * ask for them again while it is unchanged, so it must change whenever the picture would:
     * with a new reading, a changed arrow, a different unit or size, and once a minute because
     * the trace slides along with the clock even when the reading does not.
     */
    fun version(
        size: Size,
        text: String?,
        timeMillis: Long,
        rate: Float,
        isMmol: Boolean,
        nowMillis: Long,
    ): String {
        val arrow = if (rate.isFinite()) (rate * 10f).roundToInt() else 0
        return "${size.widthPx}x${size.heightPx}|${text.orEmpty()}|$timeMillis|$arrow|" +
            "${if (isMmol) "mmol" else "mgdl"}|${nowMillis / 60_000L}"
    }
}
