package tk.glucodata.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import tk.glucodata.R

/** Clearance around the ink in the wordmark drawables, in wordmark units (see docs/brand/build.py). */
private const val WORDMARK_MARGIN = 2f

/**
 * The Glucy lockup: the trace-and-sensor mark in a round badge, followed by the wordmark.
 *
 * It follows the theme the way the launcher's themed icon does: the badge is
 * `primaryContainer` with the launcher's monochrome layer in `onPrimaryContainer`. With
 * dynamic colour the in-app mark therefore matches the home screen. The fixed navy version
 * is for places the app doesn't theme (the store, the README).
 *
 * The proportions are the ones in docs/brand/README.md. The wordmark's cap height is 0.43
 * of the badge diameter, the gap is 0.28, and the cap line is centred on the badge. The
 * wordmark is drawn a step lighter on dark surfaces because light strokes on dark bleed
 * outwards.
 *
 * It reads as one element to screen readers, announced as the app name.
 */
@Composable
fun GlucyLockup(
    badgeSize: Dp,
    modifier: Modifier = Modifier,
    wordmarkColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val name = stringResource(R.string.app_name)
    val onDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val wordmark = painterResource(
        if (onDark) R.drawable.glucy_wordmark_dark else R.drawable.glucy_wordmark_light
    )
    // The drawables are one wordmark unit per dp; the cap height is 100 units.
    val cap = badgeSize * 0.43f
    val unit = cap / 100f
    val density = LocalDensity.current
    val (unitsWide, unitsHigh) = with(density) {
        wordmark.intrinsicSize.width.toDp().value to wordmark.intrinsicSize.height.toDp().value
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = name },
        verticalAlignment = Alignment.Top,
    ) {
        GlucyBadge(size = badgeSize)
        Spacer(Modifier.width(badgeSize * 0.28f))
        Image(
            painter = wordmark,
            contentDescription = null,
            colorFilter = ColorFilter.tint(wordmarkColor),
            modifier = Modifier
                .padding(top = badgeSize / 2 - cap / 2 - unit * WORDMARK_MARGIN)
                .size(width = unit * unitsWide, height = unit * unitsHigh),
        )
    }
}

/**
 * The mark alone in its round badge, themed like the launcher's themed icon. The launcher
 * layer is 108 units with the visible part in the central 72, so it is drawn at 1.5 × the
 * badge and the circle crops it as the launcher mask does.
 */
@Composable
fun GlucyBadge(
    size: Dp,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}
