package tk.glucodata.ui.util

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

@Immutable
data class AdaptiveWindowMetrics(
    val widthDp: Int,
    val heightDp: Int,
    val shortestSideDp: Int,
    val widthClass: AdaptiveWindowWidthClass,
    val heightClass: AdaptiveWindowHeightClass,
    val layoutDensity: AdaptiveLayoutDensity,
    val fontScale: Float,
    val isLandscape: Boolean
) {
    val isCompact: Boolean
        get() = layoutDensity == AdaptiveLayoutDensity.Compact
}

@Composable
fun rememberAdaptiveWindowMetrics(): AdaptiveWindowMetrics {
    val configuration = LocalConfiguration.current
    return remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.orientation,
        configuration.fontScale
    ) {
        val widthDp = configuration.screenWidthDp
        val heightDp = configuration.screenHeightDp
        val shortestSideDp = minOf(widthDp, heightDp)
        val widthClass = adaptiveWindowWidthClass(widthDp)
        val heightClass = adaptiveWindowHeightClass(heightDp)
        val layoutDensity = when {
            shortestSideDp < 360 -> AdaptiveLayoutDensity.Compact
            heightDp < 700 -> AdaptiveLayoutDensity.Compact
            configuration.fontScale >= 1.15f -> AdaptiveLayoutDensity.Compact
            widthClass == AdaptiveWindowWidthClass.Expanded &&
                heightClass != AdaptiveWindowHeightClass.Compact -> AdaptiveLayoutDensity.Comfortable
            else -> AdaptiveLayoutDensity.Regular
        }

        AdaptiveWindowMetrics(
            widthDp = widthDp,
            heightDp = heightDp,
            shortestSideDp = shortestSideDp,
            widthClass = widthClass,
            heightClass = heightClass,
            layoutDensity = layoutDensity,
            fontScale = configuration.fontScale,
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
    }
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
