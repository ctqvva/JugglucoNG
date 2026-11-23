package tk.glucodata.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * What scrolls under a screen's [AppTopBar], as its content reports it.
 *
 * The bar reads the scroller's own position instead of adding up nested-scroll deltas, so it is
 * right after a jump to a search result, a restored position, or a wizard step that starts over.
 */
@Stable
internal class TopBarScroll {
    // Two wizard steps are both on screen while one slides out: the newest is the one arriving.
    private val scrollers = mutableStateListOf<ScrollableState>()

    val contentUnderBar by derivedStateOf { scrollers.lastOrNull()?.canScrollBackward == true }

    fun add(scroller: ScrollableState) {
        scrollers.add(scroller)
    }

    fun remove(scroller: ScrollableState) {
        scrollers.remove(scroller)
    }
}

internal val LocalTopBarScroll = staticCompositionLocalOf<TopBarScroll?> { null }

/**
 * The frame of every pushed screen: a Scaffold whose [AppTopBar] knows when content is under it.
 * The screen's scroller says so with [scrollsUnderTopBar].
 *
 * Padding round the content belongs inside the scroller (`contentPadding`, or a `padding` after
 * `verticalScroll`), never between it and the bar: outside, it is an empty strip that content
 * vanishes into before it reaches the bar.
 */
@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val topBarScroll = remember { TopBarScroll() }
    CompositionLocalProvider(LocalTopBarScroll provides topBarScroll) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    }
}

/**
 * Marks this as the scroller whose top edge meets the screen's [AppTopBar], so the bar can set
 * itself off from content that has gone under it. Not for a list pinned below something else.
 * Outside an [AppScaffold] it does nothing.
 */
@Composable
fun <T : ScrollableState> T.scrollsUnderTopBar(): T {
    val topBarScroll = LocalTopBarScroll.current
    if (topBarScroll != null) {
        DisposableEffect(topBarScroll, this) {
            topBarScroll.add(this@scrollsUnderTopBar)
            onDispose { topBarScroll.remove(this@scrollsUnderTopBar) }
        }
    }
    return this
}
