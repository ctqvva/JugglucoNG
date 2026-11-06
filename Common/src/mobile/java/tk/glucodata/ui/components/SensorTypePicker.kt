package tk.glucodata.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics

enum class SensorType {
    SIBIONICS,
    LIBRE,
    DEXCOM,
    ACCUCHEK,
    CARESENS_AIR,
    AIDEX,
    ICANHEALTH,
    MQ,
    ANYTIME,
    OTTAI,
    MEDTRUM
}

/** One sensor family the user can add: shared by the picker sheet and the empty-state cards. */
internal data class SensorTypeOption(
    val type: SensorType,
    val titleRes: Int,
    val subtitleRes: Int
)

internal val sensorTypeOptions = listOf(
    SensorTypeOption(SensorType.LIBRE, R.string.libre_sensor, R.string.libre_sensor_desc),
    SensorTypeOption(SensorType.DEXCOM, R.string.dexcom_sensor, R.string.dexcom_sensor_desc),
    SensorTypeOption(SensorType.SIBIONICS, R.string.sibionics_sensor, R.string.sibionics_sensor_desc),
    SensorTypeOption(SensorType.AIDEX, R.string.aidex_sensor, R.string.aidex_sensor_desc),
    SensorTypeOption(SensorType.OTTAI, R.string.ottai_sensor, R.string.ottai_sensor_desc),
    SensorTypeOption(SensorType.ICANHEALTH, R.string.icanhealth_sensor, R.string.icanhealth_sensor_desc),
    SensorTypeOption(SensorType.ANYTIME, R.string.anytime_sensor, R.string.anytime_sensor_desc),
    SensorTypeOption(SensorType.ACCUCHEK, R.string.accuchek_sensor, R.string.accuchek_sensor_desc),
    SensorTypeOption(SensorType.CARESENS_AIR, R.string.caresens_air_sensor, R.string.caresens_air_sensor_desc),
    SensorTypeOption(SensorType.MQ, R.string.mq_sensor, R.string.mq_sensor_desc),
    SensorTypeOption(SensorType.MEDTRUM, R.string.medtrum_sensor, R.string.medtrum_sensor_models),
)

/**
 * Bottom sheet to select which type of sensor to add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorTypePicker(
    onDismiss: () -> Unit,
    onSensorSelected: (SensorType) -> Unit
) {
    val metrics = rememberAdaptiveWindowMetrics()
    val compact = metrics.isCompact
    val horizontalPadding = if (compact) 12.dp else 16.dp
    val bottomPadding = if (compact) 16.dp else 28.dp
    val itemSpacing = 8.dp
    val itemVerticalPadding = if (compact) 8.dp else 10.dp
    val tileSize = if (compact) 40.dp else 48.dp
    val itemMinHeight = if (compact) 56.dp else 64.dp
    val sheetMaxHeight = metrics.heightDp.dp * (if (compact) 0.82f else 0.88f)
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { CompactSheetDragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                bottom = bottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            item {
                Text(
                    text = stringResource(R.string.select_sensor_type),
                    style = if (compact) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleLargeEmphasized,
                    modifier = Modifier.padding(bottom = if (compact) 2.dp else 4.dp)
                )
            }

            items(
                items = sensorTypeOptions,
                key = { it.type.name }
            ) { entry ->
                SensorTypeItem(
                    type = entry.type,
                    title = stringResource(entry.titleRes),
                    subtitle = stringResource(entry.subtitleRes),
                    onClick = {
                        onSensorSelected(entry.type)
                        onDismiss()
                    },
                    itemVerticalPadding = itemVerticalPadding,
                    tileSize = tileSize,
                    itemMinHeight = itemMinHeight,
                    compact = compact
                )
            }
        }
    }
}

@Composable
private fun SensorTypeItem(
    type: SensorType,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    itemVerticalPadding: Dp = 12.dp,
    tileSize: Dp = 48.dp,
    itemMinHeight: Dp = 64.dp,
    compact: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = itemMinHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 12.dp else 12.dp, vertical = itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SensorArtTile(
                type = type,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                tileSize = tileSize,
                interactionSource = interactionSource
            )

            Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
