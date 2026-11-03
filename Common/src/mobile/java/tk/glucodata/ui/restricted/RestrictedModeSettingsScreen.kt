@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package tk.glucodata.ui.restricted

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.RestrictedMode
import tk.glucodata.ui.SensorConnectionSettings
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.DisclosingSwitchCard
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.sensorTypeOptions

/**
 * Settings → Advanced → Restricted mode. The adult's side of the feature: switch it on,
 * choose how full mode is unlocked, and choose what a restricted phone may still do.
 */
@Composable
fun RestrictedModeSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val state by RestrictedMode.state.collectAsStateWithLifecycle()
    val config = state.config
    var showPinSetup by remember { mutableStateOf(false) }
    var showPinOptions by remember { mutableStateOf(false) }
    var handoverEnabled by remember { mutableStateOf(SensorConnectionSettings.handoverEnabled(context)) }
    val biometricsAvailable = remember(context) { RestrictedBiometrics.isAvailable(context) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.restricted_mode_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        ) {
            item(key = "master") {
                MasterSwitchCard(
                    title = stringResource(R.string.restricted_mode_title),
                    subtitle = stringResource(R.string.restricted_mode_desc),
                    checked = config.enabled,
                    onCheckedChange = { on -> RestrictedMode.update(context) { it.copy(enabled = on) } },
                    icon = Icons.Default.ChildCare,
                )
            }

            item(key = "session") {
                AnimatedVisibility(visible = config.enabled) {
                    FullModeCard(
                        onLock = {
                            RestrictedMode.lock()
                            // The gate in MainApp closes this screen behind the lock.
                        },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            item(key = "unlock_label") { SectionLabel(stringResource(R.string.restricted_section_unlock)) }
            item(key = "unlock") {
                val color = MaterialTheme.colorScheme.primary
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsItem(
                        title = stringResource(R.string.restricted_pin_title),
                        subtitle = stringResource(
                            if (config.hasPin) R.string.restricted_pin_set else R.string.restricted_pin_not_set
                        ),
                        icon = Icons.Default.Password,
                        iconTint = color,
                        position = if (biometricsAvailable) CardPosition.TOP else CardPosition.SINGLE,
                        onClick = {
                            if (config.hasPin) showPinOptions = true else showPinSetup = true
                        },
                    )
                    if (biometricsAvailable) {
                        SettingsSwitchItem(
                            title = stringResource(R.string.restricted_biometric_title),
                            subtitle = stringResource(
                                if (config.hasPin) R.string.restricted_biometric_desc
                                else R.string.restricted_biometric_needs_pin
                            ),
                            checked = config.biometricUnlock,
                            enabled = config.hasPin,
                            onCheckedChange = { on ->
                                RestrictedMode.update(context) { it.copy(biometricUnlock = on) }
                            },
                            icon = Icons.Default.Fingerprint,
                            iconTint = color,
                            position = CardPosition.BOTTOM,
                        )
                    }
                }
            }

            item(key = "allowed_label") { SectionLabel(stringResource(R.string.restricted_section_allowed)) }
            item(key = "allowed") {
                val color = MaterialTheme.colorScheme.tertiary
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DisclosingSwitchCard(
                        title = stringResource(R.string.restricted_allow_sensor_change),
                        subtitle = stringResource(R.string.restricted_allow_sensor_change_desc),
                        icon = Icons.Default.Sensors,
                        iconTint = color,
                        checked = config.allowSensorChange,
                        onCheckedChange = { on ->
                            RestrictedMode.update(context) { it.copy(allowSensorChange = on) }
                        },
                        position = CardPosition.TOP,
                    ) {
                        SensorFamilyChoice(
                            selected = config.allowedSensorTypes,
                            onToggle = { name ->
                                RestrictedMode.update(context) {
                                    val types = it.allowedSensorTypes
                                    it.copy(allowedSensorTypes = if (name in types) types - name else types + name)
                                }
                            },
                        )
                        HandoverHint(
                            enabled = handoverEnabled,
                            onTurnOn = {
                                SensorConnectionSettings.setHandoverEnabled(context, true)
                                handoverEnabled = true
                            },
                        )
                    }
                    SettingsSwitchItem(
                        title = stringResource(R.string.restricted_allow_journal),
                        subtitle = stringResource(R.string.restricted_allow_journal_desc),
                        checked = config.allowJournal,
                        onCheckedChange = { on -> RestrictedMode.update(context) { it.copy(allowJournal = on) } },
                        icon = Icons.Default.EditNote,
                        iconTint = color,
                        position = CardPosition.MIDDLE,
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.settings_calibration_title),
                        subtitle = stringResource(R.string.restricted_allow_calibration_desc),
                        checked = config.allowCalibration,
                        onCheckedChange = { on -> RestrictedMode.update(context) { it.copy(allowCalibration = on) } },
                        icon = Icons.Default.WaterDrop,
                        iconTint = color,
                        position = CardPosition.MIDDLE,
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.restricted_allow_alarm_stop),
                        subtitle = stringResource(R.string.restricted_allow_alarm_stop_desc),
                        checked = config.allowAlarmStop,
                        onCheckedChange = { on -> RestrictedMode.update(context) { it.copy(allowAlarmStop = on) } },
                        icon = Icons.Default.AlarmOff,
                        iconTint = color,
                        position = CardPosition.BOTTOM,
                    )
                }
            }

            item(key = "always_hidden") {
                Text(
                    text = stringResource(R.string.restricted_always_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
        }
    }

    if (showPinSetup) {
        RestrictedPinSetupSheet(
            onChosen = { pin ->
                RestrictedMode.setPin(context, pin)
                showPinSetup = false
            },
            onDismiss = { showPinSetup = false },
        )
    }

    if (showPinOptions) {
        AlertDialog(
            onDismissRequest = { showPinOptions = false },
            title = { Text(stringResource(R.string.restricted_pin_title)) },
            text = { Text(stringResource(R.string.restricted_pin_options_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPinOptions = false
                        showPinSetup = true
                    },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(R.string.restricted_change_pin)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        RestrictedMode.setPin(context, null)
                        showPinOptions = false
                    },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(R.string.restricted_remove_pin)) }
            },
        )
    }
}

/** Where the session stands: full mode until the app leaves the screen, or until Lock now. */
@Composable
private fun FullModeCard(onLock: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.restricted_full_mode_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.restricted_full_mode_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = onLock,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.restricted_lock_now))
            }
        }
    }
}

@Composable
private fun SensorFamilyChoice(selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.restricted_sensor_types_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sensorTypeOptions.forEach { option ->
                val name = option.type.name
                val isSelected = name in selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(name) },
                    label = { Text(stringResource(option.titleRes)) },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    shapes = FilterChipDefaults.shapes(),
                )
            }
        }
        if (selected.isEmpty()) {
            Text(
                text = stringResource(R.string.restricted_sensor_types_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Changing sensors without the settings relies on the automatic handover: the new sensor is
 * added beside the old one and takes over when the old one ends.
 */
@Composable
private fun HandoverHint(enabled: Boolean, onTurnOn: () -> Unit) {
    Column {
        Text(
            text = stringResource(
                if (enabled) R.string.restricted_handover_on else R.string.restricted_handover_off
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!enabled) {
            TextButton(
                onClick = onTurnOn,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) { Text(stringResource(R.string.restricted_turn_on_handover)) }
        }
    }
}
