package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.alerts.SensorHandoverRuntime
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.RevealSettingsSearchAnchor
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem

/**
 * How the phone holds on to a sensor: handing over to the next one when the current one
 * ends, and two Bluetooth tweaks. All three are worth having but rarely touched, so they
 * live one level below the Settings tab rather than on it.
 */
@Composable
fun SensorConnectionSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    var handoverEnabled by remember { mutableStateOf(SensorConnectionSettings.handoverEnabled(context)) }
    var handoverAction by remember { mutableStateOf(SensorConnectionSettings.handoverOldAction(context)) }
    var showHandoverActionDialog by remember { mutableStateOf(false) }
    var highPriority by remember { mutableStateOf(SensorConnectionSettings.highPriority) }
    var autoConnect by remember { mutableStateOf(SensorConnectionSettings.autoConnect) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.settings_sensor_connection_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        val listState = rememberLazyListState()
        // With large text the Bluetooth group can start below the built rows.
        RevealSettingsSearchAnchor(
            listState,
            setOf(SettingsSearchAnchors.HANDOVER, SettingsSearchAnchors.TURBO, SettingsSearchAnchors.AUTOCONNECT)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
        ) {
            item(key = "handover") {
                val handoverColor = MaterialTheme.colorScheme.primary
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.sensor_handover_title),
                        subtitle = stringResource(R.string.sensor_handover_desc),
                        checked = handoverEnabled,
                        icon = Icons.Default.SwapHoriz,
                        iconTint = handoverColor,
                        position = if (handoverEnabled) CardPosition.TOP else CardPosition.SINGLE,
                        animatePosition = true,
                        searchAnchor = SettingsSearchAnchors.HANDOVER,
                        onCheckedChange = {
                            SensorConnectionSettings.setHandoverEnabled(context, it)
                            handoverEnabled = it
                        }
                    )
                    if (handoverEnabled) {
                        SettingsItem(
                            title = stringResource(R.string.sensor_handover_old_action_title),
                            subtitle = stringResource(
                                if (handoverAction == SensorHandoverRuntime.OLD_ACTION_REMOVE) {
                                    R.string.sensor_handover_action_remove
                                } else {
                                    R.string.sensor_handover_action_deactivate
                                }
                            ),
                            icon = Icons.Default.DeleteSweep,
                            iconTint = handoverColor,
                            position = CardPosition.BOTTOM,
                            onClick = { showHandoverActionDialog = true }
                        )
                    }
                }
            }

            item(key = "bluetooth_label") {
                SectionLabel(stringResource(R.string.settings_bluetooth_section))
            }
            item(key = "bluetooth") {
                val bluetoothColor = MaterialTheme.colorScheme.onSurfaceVariant
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_turbo_title),
                        subtitle = stringResource(R.string.settings_turbo_desc),
                        checked = highPriority,
                        icon = Icons.Default.Speed,
                        iconTint = bluetoothColor,
                        position = CardPosition.TOP,
                        searchAnchor = SettingsSearchAnchors.TURBO,
                        onCheckedChange = { SensorConnectionSettings.highPriority = it; highPriority = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_autoconnect_title),
                        subtitle = stringResource(R.string.settings_autoconnect_desc),
                        checked = autoConnect,
                        icon = Icons.Default.Autorenew,
                        iconTint = bluetoothColor,
                        position = CardPosition.BOTTOM,
                        searchAnchor = SettingsSearchAnchors.AUTOCONNECT,
                        onCheckedChange = { SensorConnectionSettings.autoConnect = it; autoConnect = it }
                    )
                }
            }
        }
    }

    if (showHandoverActionDialog) {
        SensorHandoverActionPickerDialog(
            currentAction = handoverAction,
            onSelect = {
                SensorConnectionSettings.setHandoverOldAction(context, it)
                handoverAction = it
                showHandoverActionDialog = false
            },
            onDismiss = { showHandoverActionDialog = false }
        )
    }
}
