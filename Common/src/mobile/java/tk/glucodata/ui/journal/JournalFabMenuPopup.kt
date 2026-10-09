package tk.glucodata.ui.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Anchors the menu above the FAB and consumes taps used to dismiss it. */
@Composable
internal fun JournalFabMenuPopup(
    menuProgress: Float,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val gapPx = with(LocalDensity.current) { 10.dp.roundToPx() }
    // This zero-size anchor sits at the FAB's top end. The popup grows upward
    // from it, so its position does not depend on the button or menu height.
    Box(modifier = modifier) {
        Popup(
            alignment = Alignment.BottomEnd,
            offset = IntOffset(0, -gapPx),
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .graphicsLayer {
                        alpha = menuProgress.coerceIn(0f, 1f)
                        translationY = 8.dp.toPx() * (1f - menuProgress)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    ),
                content = content
            )
        }
    }
}
