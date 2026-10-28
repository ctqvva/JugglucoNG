package tk.glucodata.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The row a settings search result points at, inside the screen the result opens. The
 * Settings tab sets it just before navigating; the row wearing the same anchor
 * ([settingsSearchAnchor]) scrolls itself into view and pulses once, so a result for
 * "xDrip+ broadcast" lands on that switch, not on the top of "Apps on this phone".
 */
object SettingsSearchReveal {
    // Long enough for the screen to open and a lazy list to page down to the row; short
    // enough that a row never pulses on a later, unrelated visit.
    private const val LIFETIME_MS = 3_000L

    @Volatile private var pending: String? = null
    @Volatile private var requestedAt = 0L

    /** Called with null for a result that is a screen of its own, so nothing stale survives. */
    fun request(anchor: String?) {
        pending = anchor
        requestedAt = SystemClock.uptimeMillis()
    }

    internal fun pendingAnchor(): String? =
        pending?.takeIf { SystemClock.uptimeMillis() - requestedAt < LIFETIME_MS }

    internal fun take(anchor: String): Boolean {
        if (pendingAnchor() != anchor) return false
        pending = null
        return true
    }
}

/**
 * Marks the composable as the place search result [anchor] points at. When that result was
 * just opened, the row is brought into view and covered once by a primary state layer that
 * fades in and out, drawn in [shape] so it follows the row's corners. [onReveal] runs first,
 * for a row that has to open before it can be seen (a collapsed card, say).
 */
fun Modifier.settingsSearchAnchor(
    anchor: String?,
    shape: Shape = RectangleShape,
    onReveal: () -> Unit = {},
): Modifier = if (anchor == null) this else composed {
    val requester = remember { BringIntoViewRequester() }
    val layer = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val color = MaterialTheme.colorScheme.primary
    val inSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val outSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    // Room above and below, so the row does not land flush against the top bar or the edge.
    val margin = with(LocalDensity.current) { 48.dp.toPx() }
    val currentOnReveal by rememberUpdatedState(onReveal)

    LaunchedEffect(anchor) {
        if (!SettingsSearchReveal.take(anchor)) return@LaunchedEffect
        currentOnReveal()
        withFrameNanos { } // the first layout, so there is a size to bring into view
        requester.bringIntoView(Rect(0f, -margin, size.width.toFloat(), size.height + margin))
        layer.animateTo(1f, inSpec)
        delay(900)
        layer.animateTo(0f, outSpec)
    }

    this
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .drawWithContent {
            drawContent()
            if (layer.value > 0f) {
                drawOutline(
                    outline = shape.createOutline(this.size, layoutDirection, this),
                    color = color.copy(alpha = RevealLayerAlpha * layer.value),
                )
            }
        }
}

// M3's dragged state layer: stronger than pressed (0.10) so it reads as "here", not as a tap.
private const val RevealLayerAlpha = 0.16f

/**
 * For a lazy list, whose far rows are not composed until scrolled near: when the pending
 * anchor is one of [anchors], pages down until the row has taken it (it then finishes the
 * scroll itself) or the list ends. Only list anchors that always exist on the screen, or
 * an absent one would page to the bottom for nothing.
 */
@Composable
fun RevealSettingsSearchAnchor(listState: LazyListState, anchors: Set<String>) {
    LaunchedEffect(listState) {
        val anchor = SettingsSearchReveal.pendingAnchor()?.takeIf { it in anchors } ?: return@LaunchedEffect
        withFrameNanos { }
        while (SettingsSearchReveal.pendingAnchor() == anchor && listState.canScrollForward) {
            listState.scrollBy(listState.layoutInfo.viewportSize.height * 0.75f)
            // One frame to compose the new rows, one for the anchored row's effect to start.
            withFrameNanos { }
            withFrameNanos { }
        }
    }
}
