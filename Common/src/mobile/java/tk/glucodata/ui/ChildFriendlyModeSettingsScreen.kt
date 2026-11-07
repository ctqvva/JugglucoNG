package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsSwitchItem

@Composable
internal fun ChildFriendlyModeSettingsScreen(navController: NavController) {
    val settings = rememberChildFriendlyModeSettings()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.child_friendly_mode_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "description") {
                Text(
                    text = stringResource(R.string.child_friendly_mode_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            item(key = "protections") {
                val color = MaterialTheme.colorScheme.primary
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_calibration_title),
                        checked = settings.hideCalibration,
                        onCheckedChange = {
                            ChildFriendlyMode.setProtection(
                                context,
                                ChildFriendlyProtection.CALIBRATION,
                                it,
                            )
                        },
                        icon = Icons.Default.WaterDrop,
                        iconTint = color,
                        position = CardPosition.TOP,
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.reset_sensor),
                        checked = settings.hideSensorReset,
                        onCheckedChange = {
                            ChildFriendlyMode.setProtection(
                                context,
                                ChildFriendlyProtection.SENSOR_RESET,
                                it,
                            )
                        },
                        icon = Icons.Default.SettingsBackupRestore,
                        iconTint = color,
                        position = CardPosition.MIDDLE,
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.data_mode),
                        subtitle = stringResource(R.string.calibration_algorithm),
                        checked = settings.hideAdvancedSensor,
                        onCheckedChange = {
                            ChildFriendlyMode.setProtection(
                                context,
                                ChildFriendlyProtection.ADVANCED_SENSOR,
                                it,
                            )
                        },
                        icon = Icons.Default.Tune,
                        iconTint = color,
                        position = CardPosition.MIDDLE,
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.graph_smoothing_title),
                        checked = settings.hideSmoothing,
                        onCheckedChange = {
                            ChildFriendlyMode.setProtection(
                                context,
                                ChildFriendlyProtection.SMOOTHING,
                                it,
                            )
                        },
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        iconTint = color,
                        position = CardPosition.MIDDLE,
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.danger_zone),
                        subtitle = stringResource(R.string.clear_history_desc_short),
                        checked = settings.hideDataClearing,
                        onCheckedChange = {
                            ChildFriendlyMode.setProtection(
                                context,
                                ChildFriendlyProtection.DATA_CLEARING,
                                it,
                            )
                        },
                        icon = Icons.Default.Delete,
                        iconTint = color,
                        position = CardPosition.BOTTOM,
                    )
                }
            }
        }
    }
}
