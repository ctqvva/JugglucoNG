package tk.glucodata.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * How a [SliderHeader] is set. The label always takes the type of the rows around the slider;
 * only the value changes weight.
 */
enum class SliderHeaderStyle {
    /** Settings cards, sheets and dialogs: the label is set like a settings row title. */
    Standard,

    /** Inside an alert card, whose own rows are set in bodyMedium. */
    Compact,

    /**
     * A glucose threshold that is the point of its card (Glucose range, an alert's own
     * threshold): a quiet label over a large reading.
     */
    Glucose,
}

/** The gap between a [SliderHeader] and the slider under it. */
val SliderHeaderGap = 4.dp

/**
 * The heading of a slider: what it sets at the start, the current value at the end, on one
 * line. A long (translated) label wraps; at large font sizes the value moves under it rather
 * than squeezing it. The value is never written into the label ("Opacity: 100%").
 *
 * Put the slider [SliderHeaderGap] under it. [enabled] dims the text with the slider, so pass
 * the slider's own flag.
 */
@Composable
fun SliderHeader(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    style: SliderHeaderStyle = SliderHeaderStyle.Standard,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val labelStyle: TextStyle
    val valueStyle: TextStyle
    val labelColor: Color
    val valueColor: Color
    when (style) {
        SliderHeaderStyle.Standard -> {
            labelStyle = typography.titleMedium
            valueStyle = typography.titleMediumEmphasized
            labelColor = colors.onSurface
            valueColor = colors.primary
        }
        SliderHeaderStyle.Compact -> {
            labelStyle = typography.bodyMedium
            valueStyle = typography.bodyMediumEmphasized
            labelColor = colors.onSurface
            valueColor = colors.primary
        }
        SliderHeaderStyle.Glucose -> {
            labelStyle = typography.titleMedium
            valueStyle = typography.titleLargeEmphasized
            labelColor = colors.onSurfaceVariant
            valueColor = colors.onSurface
        }
    }
    val disabled = colors.onSurface.copy(alpha = DisabledContentAlpha)
    AdaptiveTrailingRow(
        modifier = modifier.fillMaxWidth(),
        trailingContent = {
            Text(
                text = value,
                style = valueStyle.copy(fontFeatureSettings = "tnum"),
                color = if (enabled) valueColor else disabled,
            )
        },
    ) {
        Column {
            Text(
                text = label,
                style = labelStyle,
                color = if (enabled) labelColor else disabled,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = typography.bodyMedium,
                    color = if (enabled) colors.onSurfaceVariant else disabled,
                )
            }
        }
    }
}

// M3's disabled content opacity.
private const val DisabledContentAlpha = 0.38f
