package tk.glucodata.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable

/**
 * The M3 tooltip for an icon-only button: a long press (or hover) shows [label], the same text
 * the icon gives TalkBack. Wrap the button, not the icon. No label, no tooltip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconButtonTooltip(label: String?, content: @Composable () -> Unit) {
    if (label.isNullOrBlank()) {
        content()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}
