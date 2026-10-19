package tk.glucodata.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A screen's own outside-tap catcher stops at the screen; the navigation bar or rail is
 * outside it. While something a tap outside closes is open (the journal FAB menu), the screen
 * registers its close here and MainApp lays a catcher over the bar or rail, so a tab tap
 * closes it instead of switching pages, as a popup would.
 */
@Stable
class NavigationTapCatcher {
    var onDismiss: (() -> Unit)? by mutableStateOf(null)
}

val LocalNavigationTapCatcher = staticCompositionLocalOf<NavigationTapCatcher?> { null }

/** While [active], a tap on the navigation bar or rail calls [onDismiss] and goes no further. */
@Composable
fun CatchNavigationTaps(active: Boolean, onDismiss: () -> Unit) {
    val catcher = LocalNavigationTapCatcher.current ?: return
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(catcher, active) {
        if (!active) return@DisposableEffect onDispose {}
        val dismiss = { currentOnDismiss() }
        catcher.onDismiss = dismiss
        onDispose { if (catcher.onDismiss === dismiss) catcher.onDismiss = null }
    }
}

/** Call last in the Box around the navigation bar or rail. */
@Composable
fun BoxScope.NavigationTapCatcherLayer(catcher: NavigationTapCatcher) {
    val dismiss = catcher.onDismiss ?: return
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(dismiss) { detectTapGestures { dismiss() } }
    )
}
