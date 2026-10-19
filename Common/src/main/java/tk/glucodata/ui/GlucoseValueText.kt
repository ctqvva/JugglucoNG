package tk.glucodata.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Renders a resolved [DisplayValues] as one string with each lane in its own
 * tone: "4,4 · 3,1" for auto+raw, and a third lane when a calibration adds one.
 *
 * Shared between phone and watch. It lived in the phone's UI source set, so the
 * watch had nothing to render the secondary lane with and simply dropped it —
 * the view mode was settable there but never visible.
 */
fun buildGlucoseString(
    dvs: DisplayValues,
    primaryColor: Color,
    secondaryColor: Color,
    unitColor: Color,
    includeUnit: Boolean = false,
    unit: String = "",
    tertiaryColor: Color? = null
): AnnotatedString {
    return buildAnnotatedString {
        withStyle(SpanStyle(color = primaryColor)) {
            append(dvs.primaryStr)

            // If single value, append unit here if requested
            if (includeUnit && dvs.secondaryStr == null) {
                append(" ")
                withStyle(SpanStyle(color = unitColor)) {
                    append(unit)
                }
            }
        }
        if (dvs.secondaryStr != null) {
            append(" · ")
            withStyle(SpanStyle(color = secondaryColor)) {
                append(dvs.secondaryStr)
            }
        }
        // Tertiary value (when 3 values exist)
        if (dvs.tertiaryStr != null) {
            append(" · ")
            withStyle(SpanStyle(color = tertiaryColor ?: secondaryColor.copy(alpha = 0.5f))) {
                append(dvs.tertiaryStr)
            }
        }
    }
}
