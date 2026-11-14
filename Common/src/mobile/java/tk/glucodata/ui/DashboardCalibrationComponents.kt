package tk.glucodata.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.components.AdaptiveButtonRow

/** Keep translated action labels whole; stack the buttons when either needs more width. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardCalibrationActions(
    isCalibrationEnabled: Boolean,
    onClear: () -> Unit,
    onAddCalibration: () -> Unit
) {
    AdaptiveButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        FilledTonalButton(
            onClick = onClear,
            shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(
                topStart = 12.dp,
                bottomStart = 12.dp,
                topEnd = 4.dp,
                bottomEnd = 4.dp
            ), pressedShape = RoundedCornerShape(
                topStart = 8.dp,
                bottomStart = 8.dp,
                topEnd = 4.dp,
                bottomEnd = 4.dp
            )),
            contentPadding = ButtonDefaults.ContentPadding,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.clear))
        }

        // Right: Calibrate (always shown while the card is populated;
        // disabled state still reflects isCalibrationEnabled)
        Button(
            onClick = onAddCalibration,
            enabled = isCalibrationEnabled,
            shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(
                topStart = 4.dp,
                bottomStart = 4.dp,
                topEnd = 12.dp,
                bottomEnd = 12.dp
            ), pressedShape = RoundedCornerShape(
                topStart = 4.dp,
                bottomStart = 4.dp,
                topEnd = 8.dp,
                bottomEnd = 8.dp
            )),
            contentPadding = ButtonDefaults.ContentPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.WaterDrop,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.calibrate_action),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** Keep inactive entries readable; the strike-through distinguishes them from active entries. */
@Composable
internal fun DashboardCalibrationRowContent(
    timestamp: String,
    values: String,
    isDisabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(
                if (isDisabled) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // outlineVariant is a border role and has too little contrast for row text in dark mode.
        Text(
            text = timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = values,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (isDisabled) TextDecoration.LineThrough else TextDecoration.None
        )
    }
}
