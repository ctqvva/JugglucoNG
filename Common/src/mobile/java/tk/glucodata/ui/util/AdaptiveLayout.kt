package tk.glucodata.ui.util

import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AdaptiveWindowWidthClass {
    Compact,
    Medium,
    Expanded
}

enum class AdaptiveWindowHeightClass {
    Compact,
    Medium,
    Expanded
}

enum class AdaptiveLayoutDensity {
    Compact,
    Regular,
    Comfortable
}

enum class AdaptiveContentWidthClass {
    Compact,
    Medium,
    Expanded
}

/** How roomy the window is. [widthDp] and [heightDp] are in the dp the caller lays out in. */
@Immutable
data class AdaptiveWindowMetrics(
    val widthDp: Int,
    val heightDp: Int,
    val shortestSideDp: Int,
    val widthClass: AdaptiveWindowWidthClass,
    val heightClass: AdaptiveWindowHeightClass,
    val layoutDensity: AdaptiveLayoutDensity,
    val isLandscape: Boolean
) {
    val isCompact: Boolean
        get() = layoutDensity == AdaptiveLayoutDensity.Compact

    /**
     * The window has compact room right now, whatever the reason: it is [isCompact], or an
     * enlarged display size has used the room up. For layouts where a fixed-size neighbour would
     * otherwise take the space the main text needs.
     */
    val hasCompactRoom: Boolean
        get() = isCompact || isCompactRoom(widthDp, heightDp)
}

private fun isCompactRoom(widthDp: Int, heightDp: Int) = minOf(widthDp, heightDp) < 360 || heightDp < 700

@Composable
fun rememberAdaptiveWindowMetrics(): AdaptiveWindowMetrics {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val windowPx = LocalWindowInfo.current.containerSize
    return remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.densityDpi,
        configuration.orientation,
        density,
        windowPx
    ) {
        adaptiveWindowMetrics(
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp,
            screenDensity = configuration.densityDpi / 160f,
            density = density,
            nativeDensity = DisplayMetrics.DENSITY_DEVICE_STABLE / 160f,
            windowWidthPx = windowPx.width,
            windowHeightPx = windowPx.height,
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
    }
}

/**
 * [screenWidthDp] and [screenHeightDp] are the configuration's, in the system's dp
 * ([screenDensity]). The app may lay out in larger or smaller dp than that ([density]): the
 * theme limits how far Display size enlarges them.
 *
 * Text size takes no part in the layout density, and neither does a display size set above the
 * device's own ([nativeDensity]). Both are requests for larger content, and Compact would answer
 * them with smaller type. Only a window that is small at the device's own density is Compact.
 */
internal fun adaptiveWindowMetrics(
    screenWidthDp: Int,
    screenHeightDp: Int,
    screenDensity: Float,
    density: Float,
    nativeDensity: Float,
    windowWidthPx: Int,
    windowHeightPx: Int,
    isLandscape: Boolean
): AdaptiveWindowMetrics {
    // The configuration's dp are whole numbers, some Android versions rounding them and some
    // cutting them off. Converted to another density that error decides the class of a window near
    // a limit, so convert the window's own pixels wherever they are what the configuration rounded.
    fun exactPx(screenDp: Int, windowPx: Int): Float {
        val screenPx = screenDp * screenDensity
        return if (abs(windowPx - screenPx) <= screenDensity) windowPx.toFloat() else screenPx
    }
    val widthPx = exactPx(screenWidthDp, windowWidthPx)
    val heightPx = exactPx(screenHeightDp, windowHeightPx)
    val widthDp = if (density == screenDensity) screenWidthDp else (widthPx / density).roundToInt()
    val heightDp = if (density == screenDensity) screenHeightDp else (heightPx / density).roundToInt()

    val unenlargedDensity = minOf(density, nativeDensity)
    val unenlargedWidthDp = (widthPx / unenlargedDensity).roundToInt()
    // Before Android 15 the configuration leaves the system bars out of its height, and those
    // grow with the display size too.
    val systemBarsPx = (windowHeightPx - heightPx).coerceAtLeast(0f)
    val systemBarsGrowthPx = systemBarsPx * (1f - unenlargedDensity / screenDensity)
    val unenlargedHeightDp = ((heightPx + systemBarsGrowthPx) / unenlargedDensity).roundToInt()
    val widthClass = adaptiveWindowWidthClass(widthDp)
    val heightClass = adaptiveWindowHeightClass(heightDp)
    val layoutDensity = when {
        isCompactRoom(unenlargedWidthDp, unenlargedHeightDp) -> AdaptiveLayoutDensity.Compact
        widthClass == AdaptiveWindowWidthClass.Expanded &&
            heightClass != AdaptiveWindowHeightClass.Compact -> AdaptiveLayoutDensity.Comfortable
        else -> AdaptiveLayoutDensity.Regular
    }

    return AdaptiveWindowMetrics(
        widthDp = widthDp,
        heightDp = heightDp,
        shortestSideDp = minOf(widthDp, heightDp),
        widthClass = widthClass,
        heightClass = heightClass,
        layoutDensity = layoutDensity,
        isLandscape = isLandscape
    )
}

fun adaptiveWindowWidthClass(widthDp: Int): AdaptiveWindowWidthClass = when {
    widthDp < 600 -> AdaptiveWindowWidthClass.Compact
    widthDp < 840 -> AdaptiveWindowWidthClass.Medium
    else -> AdaptiveWindowWidthClass.Expanded
}

fun adaptiveWindowHeightClass(heightDp: Int): AdaptiveWindowHeightClass = when {
    heightDp < 480 -> AdaptiveWindowHeightClass.Compact
    heightDp < 900 -> AdaptiveWindowHeightClass.Medium
    else -> AdaptiveWindowHeightClass.Expanded
}

fun adaptiveContentWidthClass(
    width: Dp,
    compactMax: Dp,
    mediumMax: Dp
): AdaptiveContentWidthClass = when {
    width < compactMax -> AdaptiveContentWidthClass.Compact
    width < mediumMax -> AdaptiveContentWidthClass.Medium
    else -> AdaptiveContentWidthClass.Expanded
}
