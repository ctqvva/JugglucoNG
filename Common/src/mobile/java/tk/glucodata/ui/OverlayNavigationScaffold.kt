package tk.glucodata.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/** Scroll-end and floating-control clearance; it does not shrink the drawing viewport. */
internal val LocalNavigationPanelInset = compositionLocalOf { 0.dp }

@Composable
internal fun OverlayNavigationScaffold(
    overlaysContent: Boolean,
    bottomBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    val direction = LocalLayoutDirection.current
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = bottomBar
    ) { padding ->
        val panelInset = if (overlaysContent) padding.calculateBottomPadding() else 0.dp
        val viewportPadding = PaddingValues(
            start = padding.calculateStartPadding(direction),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(direction),
            bottom = if (overlaysContent) 0.dp else padding.calculateBottomPadding()
        )
        // Consume the system inset once, even when content draws underneath the panel.
        // Lists add panelInset to their content padding; FABs use it as bottom clearance.
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
            CompositionLocalProvider(LocalNavigationPanelInset provides panelInset) {
                content(viewportPadding)
            }
        }
    }
}
