package tk.glucodata.ui.alerts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.MAX_ALERT_DURATION_SECONDS
import tk.glucodata.alerts.MIN_ALERT_DURATION_SECONDS
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.alerts.maxSoundDelaySecondsFor
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.util.ConnectedButtonGroup

/**
 * A shared component that renders the standard list of alert configuration options.
 * Valid for Master, Regular, and Custom alerts.
 * 
 * Options included:
 * - Feedback Modes (Sound, Vibrate, Flash)
 * - Alert Style (Notification, Alarm, Both)
 * - Duration
 * - Haptics (Soft, Steady, Strong, Escalating)
 * - Sound Picker (Conditional)
 * - Override Do Not Disturb
 * - Active Time Range
 * - Retry Settings
 * - Default Snooze
 */
@Composable
fun CommonAlertSettings(
    config: AlertConfig,
    onConfigChange: (AlertConfig) -> Unit,
    onPickSound: (AlertConfig) -> Unit,
    onTest: () -> Unit,
    showTestButton: Boolean = true,
    // Optional Header Content (e.g., Thresholds)
    headerContent: (@Composable () -> Unit)? = null
) {
    val sectionHorizontalPadding = 16.dp

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // === Header (Thresholds/Durations) ===
        headerContent?.let {
            Column(
                modifier = Modifier.padding(horizontal = sectionHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                it()
            }
        }

        if (showTestButton) {
            OutlinedButton(
                onClick = onTest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = sectionHorizontalPadding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.test_alert))
            }
        }

        // === Feedback Modes (Sound, Vibrate, Flash) ===
        // Source: GlobalAlertSettingsCard.kt
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = sectionHorizontalPadding)
        ) {
            Text(stringResource(R.string.modes))
            val modes = listOf("Sound", "Vibrate", "Flash")
            val selectedModes = mutableListOf<String>().apply {
                if (config.soundEnabled) add("Sound")
                if (config.vibrationEnabled) add("Vibrate")
                if (config.flashEnabled) add("Flash")
            }
            val modeLabels = mapOf(
                "Sound" to stringResource(R.string.soundname),
                "Vibrate" to stringResource(R.string.vibrationname),
                "Flash" to stringResource(R.string.flash)
            )

            ConnectedButtonGroup(
                options = modes,
                selectedOptions = selectedModes,
                multiSelect = true,
                onOptionSelected = { mode ->
                    val newConfig = when(mode) {
                        "Sound" -> config.copy(soundEnabled = !config.soundEnabled)
                        "Vibrate" -> config.copy(vibrationEnabled = !config.vibrationEnabled)
                        "Flash" -> config.copy(flashEnabled = !config.flashEnabled)
                        else -> config
                    }
                    onConfigChange(newConfig)
                },
                labelText = { modeLabels[it] ?: it },
                label = {
                    val labelRes = when (it) {
                        "Sound" -> R.string.soundname
                        "Vibrate" -> R.string.vibrationname
                        else -> R.string.flash
                    }
                    Text(stringResource(labelRes))
                },
                icon = { mode ->
                    when(mode) {
                         "Sound" -> if(selectedModes.contains(mode)) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.VolumeOff
                         "Vibrate" -> if(selectedModes.contains(mode)) Icons.Default.Vibration else Icons.Default.Smartphone
                         "Flash" -> if(selectedModes.contains(mode)) Icons.Default.FlashOn else Icons.Default.FlashOff
                         else -> null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), // Transparent-ish on PrimaryContainer
                unselectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )

            AnimatedVisibility(visible = config.soundEnabled || config.vibrationEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.intensity),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val hapticProfileLabels = HapticProfile.entries.associateWith { it.localizedName() }
                    ConnectedButtonGroup(
                        options = listOf(
                            HapticProfile.SOFT,
                            HapticProfile.STEADY,
                            HapticProfile.STRONG,
                            HapticProfile.ESCALATING
                        ),
                        selectedOption = config.hapticProfile,
                        onOptionSelected = { onConfigChange(config.copy(hapticProfile = it)) },
                        labelText = { hapticProfileLabels[it] ?: it.displayName },
                        label = { Text(hapticProfileLabels[it] ?: it.displayName, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.fillMaxWidth(),
                        itemHeight = 36.dp,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        unselectedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                        unselectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        
        // === Alert Style ===
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = sectionHorizontalPadding)
        ) {
            Text(stringResource(R.string.alert_style))
            val deliveryModeLabels = AlertDeliveryMode.entries.associateWith { it.localizedName() }
            ConnectedButtonGroup(
                options = AlertDeliveryMode.entries,
                selectedOption = config.deliveryMode,
                onOptionSelected = { onConfigChange(config.copy(deliveryMode = it)) },
                labelText = { deliveryModeLabels[it] ?: it.displayName },
                label = { Text(deliveryModeLabels[it] ?: it.displayName, style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.fillMaxWidth(),
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                unselectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // === Duration ===
        AnimatedVisibility(visible = config.soundEnabled || config.vibrationEnabled || config.flashEnabled) {
            DurationSlider(
                label = stringResource(R.string.duration_label),
                value = config.alarmDurationSeconds,
                range = MIN_ALERT_DURATION_SECONDS..MAX_ALERT_DURATION_SECONDS,
                stepSize = 1,
                onValueChange = { onConfigChange(config.copy(alarmDurationSeconds = it)) },
                modifier = Modifier.padding(horizontal = sectionHorizontalPadding),
                valueText = { seconds -> "$seconds ${stringResource(R.string.sec)}" }
            )
        }

        // === Sound delay (vibrate first, audio after N seconds) ===
        // Only meaningful when both sound and vibration are on: otherwise there
        // is nothing to delay, or a silent gap with no signal at all.
        AnimatedVisibility(visible = config.soundEnabled && config.vibrationEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickableToggleRow(
                    icon = Icons.Default.Timer,
                    title = stringResource(R.string.sound_delay_title),
                    subtitle = stringResource(R.string.sound_delay_desc),
                    checked = config.soundDelayEnabled,
                    onCheckedChange = { onConfigChange(config.copy(soundDelayEnabled = it)) }
                )
                AnimatedVisibility(visible = config.soundDelayEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val maxDelay = maxSoundDelaySecondsFor(config.type)
                        DurationSlider(
                            label = stringResource(R.string.sound_delay_label),
                            value = config.soundDelaySeconds.coerceIn(0, maxDelay),
                            range = 0..maxDelay,
                            stepSize = 5,
                            onValueChange = { onConfigChange(config.copy(soundDelaySeconds = it)) },
                            modifier = Modifier.padding(horizontal = sectionHorizontalPadding),
                            valueText = { seconds -> "$seconds ${stringResource(R.string.sec)}" }
                        )
                        if (config.type == AlertType.LOW || config.type == AlertType.VERY_LOW) {
                            Row(
                                modifier = Modifier.padding(horizontal = sectionHorizontalPadding),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    stringResource(R.string.sound_delay_hypo_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // === Sound Settings (Conditional) ===
        AnimatedVisibility(visible = config.soundEnabled) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Alert Sound Picker
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onPickSound(config) }
                        .padding(horizontal = sectionHorizontalPadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.alert_sound),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            getSoundDisplayText(config.customSoundUri, config.type.id),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Override Silent Mode toggle (inside Sound section)
                ClickableToggleRow(
                    icon = Icons.Default.VolumeOff,
                    title = stringResource(R.string.override_silent_mode),
                    subtitle = stringResource(R.string.override_silent_mode_desc),
                    checked = config.overrideDND,
                    onCheckedChange = { onConfigChange(config.copy(overrideDND = it)) }
                )
            }
        }

        // === Time Range ===
        TimeRangeSettings(
            enabled = config.timeRangeEnabled,
            startHour = config.activeStartHour,
            startMinute = config.activeStartMinute,
            endHour = config.activeEndHour,
            endMinute = config.activeEndMinute,
            onEnabledChange = { onConfigChange(config.copy(timeRangeEnabled = it)) },
            onStartChange = { hour, minute -> onConfigChange(config.copy(activeStartHour = hour, activeStartMinute = minute)) },
            onEndChange = { hour, minute -> onConfigChange(config.copy(activeEndHour = hour, activeEndMinute = minute)) }
        )

        // === Retry ===
        RetrySettings(
            enabled = config.retryEnabled,
            intervalMinutes = config.retryIntervalMinutes,
            retryCount = config.retryCount,
            onEnabledChange = { onConfigChange(config.copy(retryEnabled = it)) },
            onIntervalChange = { onConfigChange(config.copy(retryIntervalMinutes = it)) },
            onCountChange = { onConfigChange(config.copy(retryCount = it)) }
        )
        
        // === Snooze ===
        DurationSlider(
            label = stringResource(R.string.default_snooze),
            value = config.defaultSnoozeMinutes,
            range = 5..60,
            stepSize = 5,
            onValueChange = { onConfigChange(config.copy(defaultSnoozeMinutes = it)) },
            modifier = Modifier.padding(horizontal = sectionHorizontalPadding)
        )
    }
}
