package tk.glucodata.ui.alerts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tk.glucodata.BuildConfig
import tk.glucodata.R
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertType
import tk.glucodata.ui.components.StyledSwitch

@Composable
fun GlobalAlertSettingsCard(
    allConfigs: Map<AlertType, AlertConfig>,
    hasCustomAlertsEnabled: Boolean,
    onMasterToggle: (Boolean) -> Unit,
    onApplyToAll: (AlertConfig) -> Unit,
    onPickSound: (AlertConfig, (AlertConfig) -> Unit) -> Unit
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val isMasterEnabled = allConfigs.values.any { it.enabled } || hasCustomAlertsEnabled
    val seedConfig = remember(allConfigs, hasCustomAlertsEnabled) {
        allConfigs[AlertType.LOW] ?: allConfigs.values.firstOrNull() ?: AlertConfig(AlertType.LOW)
    }

    var draftConfig by remember { mutableStateOf(seedConfig) }
    var appliedDraft by remember { mutableStateOf(seedConfig) }
    val hasPendingChanges = !draftConfig.sameMasterDraft(appliedDraft)
    val canApplyToAll = shouldEnableApplyToAll(draftConfig, appliedDraft, allConfigs)

    LaunchedEffect(seedConfig) {
        if (!hasPendingChanges) {
            draftConfig = seedConfig
            appliedDraft = seedConfig
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "masterAlertChevron"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isMasterEnabled) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.74f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "masterAlertContainer"
    )
    val iconContainerColor = if (isMasterEnabled) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val iconTint = if (isMasterEnabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val expandedContainerColor = if (isMasterEnabled) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isMasterEnabled) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
                    .clickable { isExpanded = !isExpanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = iconContainerColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.master_alert_control),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        // The title already says "all alerts"; the line under it says on or off.
                        text = if (isMasterEnabled) {
                            stringResource(R.string.global_active)
                        } else {
                            stringResource(R.string.off)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    StyledSwitch(
                        checked = isMasterEnabled,
                        onCheckedChange = onMasterToggle
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = expandedContainerColor
                ) {
                    Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f))

                    Column(
                        modifier = Modifier.padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                onApplyToAll(draftConfig)
                                appliedDraft = draftConfig
                            },
                            enabled = canApplyToAll,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                            )
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.apply_to_all_alerts))
                        }

                        CommonAlertSettings(
                            config = draftConfig,
                            onConfigChange = { updated -> draftConfig = updated },
                            onPickSound = {
                                onPickSound(draftConfig) { updatedDraft ->
                                    draftConfig = updatedDraft
                                }
                            },
                            onTest = {},
                            showTestButton = false
                        )
                    }
                    }
                }
            }
        }
    }
}

internal fun shouldEnableApplyToAll(
    draftConfig: AlertConfig,
    appliedDraft: AlertConfig,
    allConfigs: Map<AlertType, AlertConfig>,
    packageName: String = BuildConfig.APPLICATION_ID
): Boolean {
    return !draftConfig.sameMasterDraft(appliedDraft) ||
        allConfigs.values.any { target ->
            val expected = draftConfig.copy(
                customSoundUri = BundledAlertSounds.forAlert(draftConfig.customSoundUri, packageName, target.type.id)
            )
            !target.sameMasterDraft(expected)
        }
}

internal fun AlertConfig.sameMasterDraft(other: AlertConfig): Boolean {
    return soundEnabled == other.soundEnabled &&
        vibrationEnabled == other.vibrationEnabled &&
        flashEnabled == other.flashEnabled &&
        deliveryMode == other.deliveryMode &&
        hapticProfile == other.hapticProfile &&
        customSoundUri == other.customSoundUri &&
        overrideDND == other.overrideDND &&
        alarmDurationSeconds == other.alarmDurationSeconds &&
        timeRangeEnabled == other.timeRangeEnabled &&
        activeStartHour == other.activeStartHour &&
        activeStartMinute == other.activeStartMinute &&
        activeEndHour == other.activeEndHour &&
        activeEndMinute == other.activeEndMinute &&
        retryEnabled == other.retryEnabled &&
        retryIntervalMinutes == other.retryIntervalMinutes &&
        retryCount == other.retryCount &&
        soundDelayEnabled == other.soundDelayEnabled &&
        soundDelaySeconds == other.soundDelaySeconds &&
        defaultSnoozeMinutes == other.defaultSnoozeMinutes
}
