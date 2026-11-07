package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.SectionLabel

internal data class AiDexSavedKeySensor(val serial: String, val managed: Boolean, val connected: Boolean)

/** Same per-sensor controls and wrapping action layout as Ottai's saved sensors. */
@Composable
internal fun AiDexSavedSensorsSection(
    sensors: List<AiDexSavedKeySensor>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.ottai_saved_section_phone), topPadding = 0.dp)
        if (sensors.isEmpty()) {
            Text(stringResource(R.string.ottai_saved_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            sensors.forEachIndexed { index, sensor ->
                val presence = when {
                    sensor.connected -> stringResource(R.string.ottai_ble_scan_connected)
                    sensor.managed -> stringResource(R.string.ottai_ble_scan_registered)
                    else -> null
                }
                SavedSensorEntry(sensor.serial,
                    listOfNotNull(presence, stringResource(R.string.aidex_key_saved)).joinToString(" · "),
                    detail = null, position = ottaiRowPosition(index, sensors.size)) {
                    FilledTonalButton(onClick = { onSelect(sensor.serial) }, enabled = enabled,
                        shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) {
                        Text(stringResource(R.string.ottai_select))
                    }
                    OutlinedButton(onClick = { onExport(sensor.serial) }, enabled = enabled,
                        shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) {
                        Icon(Icons.Default.FileUpload, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export))
                    }
                    // Only unadded records can be deleted here. The sensor card manages
                    // the separate validated PAIR credential.
                    if (!sensor.managed) {
                        val label = stringResource(R.string.ottai_delete_saved)
                        IconButtonTooltip(label) {
                            IconButton(onClick = { onDelete(sensor.serial) }, enabled = enabled,
                                shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Default.Delete, label, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
