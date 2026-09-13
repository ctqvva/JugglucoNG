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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
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
 * The body every alert shares: master, standard and custom.
 *
 * What most people set sits first and needs no label to be read - the three
 * feedback toggles, the intensity under them, the notification/alarm choice,
 * the duration, the sound. Everything a typical user never touches is under
 * one collapsed "Advanced" row: silent-mode override, delayed sound, active
 * hours, retries, the default snooze, and whatever the alert adds through
 * [advancedContent].
 */
@Composable
fun CommonAlertSettings(
    config: AlertConfig,
    onConfigChange: (AlertConfig) -> Unit,
    onPickSound: (AlertConfig) -> Unit,
    onTest: () -> Unit,
    showTestButton: Boolean = true,
    // What the alert is about: thresholds, durations, look-ahead.
    headerContent: (@Composable () -> Unit)? = null,
    // The alert's own power-user options, rendered inside the Advanced section.
    advancedContent: (@Composable () -> Unit)? = null
) {
    val sectionHorizontalPadding = 16.dp
    var advancedExpanded by LocalAlertsAdvancedOpen.current

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

        // === Feedback: sound / vibrate / flash, and how hard ===
        // The buttons say what they are; a label over them repeated them.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = sectionHorizontalPadding)
        ) {
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
        }

        // Rows carry their own height and padding; no gap between them, or the
        // list reads as a stack of islands.
        Column {
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
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // The two things people come back to: does it get through silent mode,
            // and when is it allowed to fire at all.
            AnimatedVisibility(visible = config.soundEnabled) {
                ClickableToggleRow(
                    title = stringResource(R.string.override_silent_mode),
                    subtitle = stringResource(R.string.override_silent_mode_desc),
                    checked = config.overrideDND,
                    onCheckedChange = { onConfigChange(config.copy(overrideDND = it)) }
                )
            }
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

            // === Advanced: collapsed, one row, everything set once and left alone ===
            AdvancedSectionHeader(
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded }
            )
        }
        AnimatedVisibility(visible = advancedExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // === Intensity: soft to escalating ===
                AnimatedVisibility(visible = config.soundEnabled || config.vibrationEnabled) {
                    Column(modifier = Modifier.padding(horizontal = sectionHorizontalPadding)) {
                        run {
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

                // === Notification / alarm / both ===
                Column(modifier = Modifier.padding(horizontal = sectionHorizontalPadding)) {
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

                Column {
                // === Sound delay (vibrate first, audio after N seconds) ===
                // Only meaningful when both sound and vibration are on: otherwise there
                // is nothing to delay, or a silent gap with no signal at all.
                AnimatedVisibility(visible = config.soundEnabled && config.vibrationEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClickableToggleRow(
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

                // === Retry ===
                RetrySettings(
                    enabled = config.retryEnabled,
                    intervalMinutes = config.retryIntervalMinutes,
                    retryCount = config.retryCount,
                    onEnabledChange = { onConfigChange(config.copy(retryEnabled = it)) },
                    onIntervalChange = { onConfigChange(config.copy(retryIntervalMinutes = it)) },
                    onCountChange = { onConfigChange(config.copy(retryCount = it)) }
                )
                }

                // === Snooze ===
                DurationSlider(
                    label = stringResource(R.string.default_snooze),
                    value = config.defaultSnoozeMinutes,
                    range = 5..60,
                    stepSize = 5,
                    onValueChange = { onConfigChange(config.copy(defaultSnoozeMinutes = it)) },
                    modifier = Modifier.padding(horizontal = sectionHorizontalPadding)
                )

                advancedContent?.invoke()
            }
        }
    }
}

/**
 * Whether the Advanced sections on the alert screen are open. One state for the
 * whole screen: a reader who opened Advanced on one card wants it open on the
 * next, until they close it. The screen provides it; a card rendered elsewhere
 * gets a state of its own.
 */
val LocalAlertsAdvancedOpen = compositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

/**
 * The "Advanced" row inside a card body: text on the body's own left edge, a
 * chevron that turns, nothing else - it is not a setting.
 */
@Composable
internal fun AdvancedSectionHeader(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "advancedChevron")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.advanced),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation)
        )
    }
}
