package tk.glucodata.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    OTTAI
}

/** One sensor family the user can add: shared by the picker sheet and the empty-state cards. */
internal data class SensorTypeOption(
    val type: SensorType,
    val icon: ImageVector,
    val titleRes: Int
)

internal val sensorTypeOptions = listOf(
    SensorTypeOption(SensorType.LIBRE, Icons.Default.Nfc, R.string.libre_sensor),
    SensorTypeOption(SensorType.SIBIONICS, Icons.Default.QrCodeScanner, R.string.sibionics_sensor),
    SensorTypeOption(SensorType.DEXCOM, Icons.Default.QrCodeScanner, R.string.dexcom_sensor),
    SensorTypeOption(SensorType.AIDEX, Icons.Default.Bluetooth, R.string.aidex_sensor),
    SensorTypeOption(SensorType.ICANHEALTH, Icons.Default.Bluetooth, R.string.icanhealth_sensor),
    SensorTypeOption(SensorType.ANYTIME, Icons.Default.Bluetooth, R.string.anytime_sensor),
    SensorTypeOption(SensorType.MQ, Icons.Default.Bluetooth, R.string.mq_sensor),
    SensorTypeOption(SensorType.OTTAI, Icons.Default.Bluetooth, R.string.ottai_sensor),
    SensorTypeOption(SensorType.ACCUCHEK, Icons.Default.QrCodeScanner, R.string.accuchek_sensor),
    SensorTypeOption(SensorType.CARESENS_AIR, Icons.Default.QrCodeScanner, R.string.caresens_air_sensor),
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
    val bottomPadding = if (compact) 18.dp else 28.dp
    val itemSpacing = if (compact) 6.dp else 8.dp
    val itemVerticalPadding = if (compact) 8.dp else 10.dp
    val iconContainerSize = if (compact) 36.dp else 42.dp
    val iconInnerPadding = if (compact) 8.dp else 10.dp
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
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = if (compact) 2.dp else 4.dp)
                )
            }

            items(
                items = sensorTypeOptions,
                key = { it.type.name }
            ) { entry ->
                SensorTypeItem(
                    icon = entry.icon,
                    title = stringResource(entry.titleRes),
                    onClick = {
                        onSensorSelected(entry.type)
                        onDismiss()
                    },
                    itemVerticalPadding = itemVerticalPadding,
                    iconContainerSize = iconContainerSize,
                    iconInnerPadding = iconInnerPadding,
                    itemMinHeight = itemMinHeight,
                    compact = compact
                )
            }
        }
    }
}

@Composable
private fun SensorTypeItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    itemVerticalPadding: Dp = 12.dp,
    iconContainerSize: Dp = 48.dp,
    iconInnerPadding: Dp = 12.dp,
    itemMinHeight: Dp = 64.dp,
    compact: Boolean = false
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = itemMinHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(iconContainerSize)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(iconInnerPadding)
                )
            }

            Spacer(modifier = Modifier.width(if (compact) 10.dp else 12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
