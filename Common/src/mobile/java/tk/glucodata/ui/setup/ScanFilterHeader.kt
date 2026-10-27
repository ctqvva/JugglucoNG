package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.components.AdaptiveTrailingRow

/** "Searching for sensors…" with the sensors-only / all-devices filter beside it. */
@Composable
internal fun ScanFilterHeader(
    title: String,
    showAllDevices: Boolean,
    modifier: Modifier = Modifier,
    onToggleFilter: () -> Unit,
) {
    AdaptiveTrailingRow(
        modifier = modifier.fillMaxWidth(),
        // The text button's own padding is the gap.
        spacing = 0.dp,
        trailingContent = {
            TextButton(
                onClick = onToggleFilter,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text(
                    stringResource(
                        if (showAllDevices) R.string.show_sensors_only else R.string.see_all_devices
                    )
                )
            }
        },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}
