package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tk.glucodata.DexcomBluetoothSlot
import tk.glucodata.R
import tk.glucodata.ui.util.ConnectedButtonGroup

private val dexcomSlots = listOf(
    DexcomBluetoothSlot.RECEIVER_OR_PUMP,
    DexcomBluetoothSlot.MOBILE_APP,
    DexcomBluetoothSlot.SMART_WATCH,
)

private fun dexcomSlotShortLabelRes(slot: Int): Int = when (slot) {
    DexcomBluetoothSlot.RECEIVER_OR_PUMP -> R.string.dexcom_slot_receiver_short
    DexcomBluetoothSlot.SMART_WATCH -> R.string.dexcom_slot_watch_short
    else -> R.string.dexcom_slot_app_short
}

@Composable
internal fun dexcomSlotShortLabel(slot: Int): String = stringResource(dexcomSlotShortLabelRes(slot))

@Composable
private fun dexcomSlotLabel(slot: Int): String = stringResource(
    when (slot) {
        DexcomBluetoothSlot.RECEIVER_OR_PUMP -> R.string.dexcom_slot_receiver
        DexcomBluetoothSlot.SMART_WATCH -> R.string.dexcom_slot_watch
        else -> R.string.dexcom_slot_app
    }
)

private fun dexcomSlotIcon(slot: Int): ImageVector = when (slot) {
    DexcomBluetoothSlot.RECEIVER_OR_PUMP -> Icons.Filled.MedicalServices
    DexcomBluetoothSlot.SMART_WATCH -> Icons.Filled.Watch
    else -> Icons.Filled.PhoneAndroid
}

/** Slot picker for the setup wizard: the choice applies to sensors paired from now on. */
@Composable
internal fun DexcomBluetoothSlotSelector(
    selectedSlot: Int,
    onSlotSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dexcom_bluetooth_slot),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        ConnectedButtonGroup(
            options = dexcomSlots,
            selectedOption = selectedSlot,
            onOptionSelected = onSlotSelected,
            label = {},
            labelText = { context.getString(dexcomSlotShortLabelRes(it)) },
            icon = { dexcomSlotIcon(it) },
            modifier = Modifier.fillMaxWidth(),
            itemHeight = 48.dp,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = dexcomSlotLabel(selectedSlot),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dexcom_slot_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Moves a paired sensor to another slot; confirming makes it pair again. */
@Composable
internal fun DexcomBluetoothSlotDialog(
    currentSlot: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selected by remember(currentSlot) { mutableIntStateOf(currentSlot) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dexcom_bluetooth_slot)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dexcom_slot_change_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(Modifier.selectableGroup()) {
                    dexcomSlots.forEach { slot ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected == slot,
                                    onClick = { selected = slot },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == slot, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(dexcomSlotLabel(slot), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected) },
                enabled = selected != currentSlot,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(stringResource(R.string.dexcom_slot_change_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
