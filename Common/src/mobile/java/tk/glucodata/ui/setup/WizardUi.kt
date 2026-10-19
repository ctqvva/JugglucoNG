package tk.glucodata.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics

data class WizardUiMetrics(
    val compact: Boolean,
    val horizontalPadding: Dp,
    val heroSize: Dp,
    val heroInnerPadding: Dp,
    val spacerLarge: Dp,
    val spacerMedium: Dp,
    val spacerSmall: Dp,
    val buttonHeight: Dp
)

@Composable
fun rememberWizardUiMetrics(): WizardUiMetrics {
    val compact = rememberAdaptiveWindowMetrics().isCompact

    return if (compact) {
        WizardUiMetrics(
            compact = true,
            horizontalPadding = 16.dp,
            heroSize = 96.dp,
            heroInnerPadding = 20.dp,
            spacerLarge = 24.dp,
            spacerMedium = 12.dp,
            spacerSmall = 8.dp,
            buttonHeight = 46.dp
        )
    } else {
        WizardUiMetrics(
            compact = false,
            horizontalPadding = 24.dp,
            heroSize = 120.dp,
            heroInnerPadding = 24.dp,
            spacerLarge = 32.dp,
            spacerMedium = 16.dp,
            spacerSmall = 8.dp,
            buttonHeight = 48.dp
        )
    }
}
