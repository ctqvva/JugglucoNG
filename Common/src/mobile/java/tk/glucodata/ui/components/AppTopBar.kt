package tk.glucodata.ui.components

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import tk.glucodata.R

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
 * across it; with a [scrollBehavior] it takes `surfaceContainer` once content scrolls under
 * it, which is the Material treatment for a bar that has something behind it.
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
    scrollBehavior: TopAppBarScrollBehavior? = null,
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
            IconButton(onClick = onNavigateBack, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = navigationContentDescription,
                )
            }
        }
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    if (subtitle == null) {
        TopAppBar(
            title = titleContent,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
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
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
            colors = colors,
        )
    }
}
