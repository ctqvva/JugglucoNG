package tk.glucodata.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import tk.glucodata.R
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.util.ExpressiveMotion

/**
 * The top bar of every pushed screen: a title and, when there is somewhere to go back to,
 * the back arrow.
 *
 * Forty-odd screens used to build this by hand and drifted three ways — the arrow was the
 * deprecated non-mirrored glyph on some (pointing the wrong way in RTL languages), its label
 * was "Back", "Cancel", the screen's own name or nothing at all, and the container was
 * transparent on some screens and surface-coloured on others. One component, one answer.
 *
 * The container is transparent so the bar reads as part of the page rather than as a band
 * across it. Once content has scrolled under it (see [scrollsUnderTopBar]) it takes
 * `surfaceContainer`, the navigation bar's colour, so what disappears does so behind an edge.
 * The bar itself never moves.
 *
 * [below] is for what belongs to the bar rather than to the page, such as a wizard's progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onNavigateBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationContentDescription: String = stringResource(R.string.navigate_back),
    actions: @Composable RowScope.() -> Unit = {},
    below: @Composable () -> Unit = {},
) {
    val titleContent: @Composable () -> Unit = {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val navigationIcon: @Composable () -> Unit = {
        if (onNavigateBack != null) {
            IconButtonTooltip(navigationContentDescription) {
                IconButton(onClick = onNavigateBack, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        imageVector = Symbols.ArrowBackFilled,
                        contentDescription = navigationContentDescription,
                    )
                }
            }
        }
    }
    val raised = LocalTopBarScroll.current?.contentUnderBar == true
    val raisedColor = MaterialTheme.colorScheme.surfaceContainer
    val containerColor by animateColorAsState(
        // Its own colour with no alpha, not Color.Transparent: fading through transparent black
        // dims the bar half way on a light theme.
        targetValue = if (raised) raisedColor else raisedColor.copy(alpha = 0f),
        animationSpec = ExpressiveMotion.defaultEffects(),
        label = "topBarContainer",
    )
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent,
    )
    Column(modifier.drawBehind { drawRect(containerColor) }) {
        if (subtitle == null) {
            TopAppBar(
                title = titleContent,
                navigationIcon = navigationIcon,
                actions = actions,
                colors = colors,
            )
        } else {
            // The bar's own subtitle slot: it lays the two lines out and sizes the bar for them.
            TopAppBar(
                title = titleContent,
                subtitle = {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = navigationIcon,
                actions = actions,
                colors = colors,
            )
        }
        below()
    }
}
