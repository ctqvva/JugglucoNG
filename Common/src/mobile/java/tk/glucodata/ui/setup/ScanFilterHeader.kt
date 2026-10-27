package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tk.glucodata.R
import tk.glucodata.ui.components.AdaptiveTrailingRow

@Composable
internal fun ScanFilterHeader(title: String, showAllDevices: Boolean, onToggleFilter: () -> Unit) {
    AdaptiveTrailingRow(
        modifier = Modifier.fillMaxWidth(),
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
