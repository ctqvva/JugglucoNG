@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import tk.glucodata.GluciferPayload
import tk.glucodata.GluciferSendLimiter
import tk.glucodata.OutboundApiSettings
import tk.glucodata.R
import tk.glucodata.alerts.AlertType
import tk.glucodata.ui.components.AdaptiveTrailingRow

internal const val GLUCIFER_SETUP_GUIDE = "https://github.com/pannal/glucifer-ha#quick-setup"

@Composable
internal fun GluciferConnectionHeading() {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    AdaptiveTrailingRow(
        modifier = Modifier.fillMaxWidth(),
        trailingContent = {
            TextButton(onClick = { showHelp = true }, shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.help))
            }
        }
    ) {
        SettingsSubsectionTitle(stringResource(R.string.outbound_api_connection))
    }
    if (showHelp) GluciferHelpDialog(onDismiss = { showHelp = false })
}

/** The same switch rows as the other API presets, with optional details disclosed on demand. */
@Composable
internal fun GluciferOptions(
    destination: OutboundApiSettings.Destination,
    onChange: (OutboundApiSettings.Destination) -> Unit
) {
    var dataExpanded by rememberSaveable(destination.id) { mutableStateOf(false) }
    var alertsExpanded by rememberSaveable(destination.id) { mutableStateOf(false) }
    var timingExpanded by rememberSaveable(destination.id) { mutableStateOf(false) }
    var timingHelp by rememberSaveable(destination.id) { mutableStateOf(false) }
    val fields = listOf(
        "predictions" to R.string.wear_prediction_title,
        "trend" to R.string.stats_trend,
        "delta_mgdl" to R.string.glucifer_delta,
        "rate_mgdl_min" to R.string.glucifer_rate,
        "raw_mgdl" to R.string.glucifer_raw,
        "auto_mgdl" to R.string.glucifer_auto,
        "iob_u" to R.string.notification_show_iob_title,
        "eiob_u" to R.string.journal_eiob_display_title,
        "cob_g" to R.string.notification_show_cob_title,
        "battery_percent" to R.string.glucifer_phone_battery,
        "sensor_id" to R.string.glucifer_sensor_id,
        "sensor_generation" to R.string.glucifer_sensor_generation,
        "sensor_started_ms" to R.string.sensor_started,
        "sensor_expires_ms" to R.string.sensor_expected_end,
        "sensor_warmup" to R.string.glucifer_warmup
    )
    val alerts = AlertType.settingsEntries.mapNotNull { type ->
        val key = type.name.lowercase(Locale.ROOT)
        if (key in GluciferPayload.alerts) "alert:$key" to type.nameResId else null
    }
    fun selection(key: String, selected: Boolean) = onChange(destination.copy(
        gluciferFields = if (selected) destination.gluciferFields + key else destination.gluciferFields - key
    ))

    ApiDestinationControlPanel {
        GluciferDisclosure(
            title = stringResource(R.string.glucifer_data_title),
            summary = stringResource(R.string.glucifer_selection_summary,
                fields.count { it.first in destination.gluciferFields }, fields.size),
            expanded = dataExpanded,
            onExpandedChange = { dataExpanded = it }
        ) {
            Text(stringResource(R.string.glucifer_glucose_required),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                fields.forEachIndexed { index, (key, label) ->
                    if (index > 0) ControlDivider()
                    ToggleRow(stringResource(label), null, key in destination.gluciferFields) { selection(key, it) }
                }
            }
        }
        ControlDivider()
        GluciferDisclosure(
            title = stringResource(R.string.glucose_alerts_title),
            summary = stringResource(R.string.glucifer_selection_summary,
                alerts.count { it.first in destination.gluciferFields }, alerts.size),
            expanded = alertsExpanded,
            onExpandedChange = { alertsExpanded = it }
        ) {
            Column {
                alerts.forEachIndexed { index, (key, label) ->
                    if (index > 0) ControlDivider()
                    ToggleRow(stringResource(label), null, key in destination.gluciferFields) { selection(key, it) }
                }
            }
        }
        ControlDivider()
        ToggleRow(
            title = stringResource(R.string.glucifer_history_title),
            subtitle = stringResource(R.string.glucifer_days, 7),
            checked = destination.gluciferHistory,
            onCheckedChange = { onChange(destination.copy(gluciferHistory = it)) }
        )
        ControlDivider()
        ToggleRow(
            title = stringResource(R.string.glucifer_journal),
            subtitle = stringResource(R.string.glucifer_days, destination.gluciferJournalDays),
            checked = destination.gluciferJournal,
            onCheckedChange = { onChange(destination.copy(gluciferJournal = it)) }
        )
        AnimatedVisibility(visible = destination.gluciferJournal) {
            Column {
                ControlDivider()
                GluciferChoice(
                    title = stringResource(R.string.glucifer_journal_days),
                    value = destination.gluciferJournalDays,
                    options = listOf(1, 3, 7, 14, 30, 60, 90),
                    label = { stringResource(R.string.glucifer_days, it) },
                    onChange = { onChange(destination.copy(gluciferJournalDays = it)) }
                )
                ControlDivider()
                ToggleRow(stringResource(R.string.glucifer_journal_notes), null,
                    destination.gluciferJournalNotes) { onChange(destination.copy(gluciferJournalNotes = it)) }
                Text(stringResource(R.string.glucifer_journal_help), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ControlDivider()
        GluciferDisclosure(
            title = stringResource(R.string.glucifer_delivery_title),
            summary = if (destination.gluciferLiveBypass) stringResource(R.string.glucifer_live_short)
                else stringResource(R.string.glucifer_spacing_summary, gluciferIntervalLabel(destination.gluciferMinIntervalSeconds)),
            expanded = timingExpanded,
            onExpandedChange = { timingExpanded = it }
        ) {
            ToggleRow(stringResource(R.string.glucifer_live_short), null, destination.gluciferLiveBypass) {
                onChange(destination.copy(gluciferLiveBypass = it))
            }
            ControlDivider()
            GluciferChoice(
                title = stringResource(R.string.glucifer_fallback_short),
                value = destination.gluciferFallbackSeconds,
                options = GluciferSendLimiter.intervals,
                label = { gluciferIntervalLabel(it) },
                onChange = { onChange(destination.copy(gluciferFallbackSeconds = it)) }
            )
            ControlDivider()
            GluciferChoice(
                title = stringResource(if (destination.gluciferLiveBypass)
                    R.string.glucifer_spacing_short else R.string.glucifer_all_spacing_title),
                value = destination.gluciferMinIntervalSeconds,
                options = GluciferSendLimiter.intervals,
                label = { gluciferIntervalLabel(it) },
                onChange = { onChange(destination.copy(gluciferMinIntervalSeconds = it)) }
            )
            TextButton(onClick = { timingHelp = true }, modifier = Modifier.padding(horizontal = 16.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.help))
            }
        }
    }
    if (timingHelp) GluciferHelpDialog(timing = true, onDismiss = { timingHelp = false })
}

/** A disclosure uses the API editor's row grid; its children own their single 16dp inset. */
@Composable
private fun GluciferDisclosure(
    title: String,
    summary: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    GluciferValueRow(title, summary, expanded) { onExpandedChange(!expanded) }
    AnimatedVisibility(visible = expanded) {
        Column {
            ControlDivider()
            content()
        }
    }
}

@Composable
private fun GluciferValueRow(title: String, summary: String, expanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "gluciferDisclosure")
    AdaptiveTrailingRow(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .heightIn(min = 88.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        spacing = 16.dp,
        trailingContent = {
            Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun gluciferIntervalLabel(seconds: Int): String = when {
    seconds >= 3600 -> stringResource(R.string.glucifer_hours, seconds / 3600)
    seconds >= 60 -> stringResource(R.string.glucifer_minutes, seconds / 60)
    else -> stringResource(R.string.glucifer_seconds, seconds)
}

/** Long lists of time choices stay in a labelled selector, rather than filling the page. */
@Composable
private fun GluciferChoice(
    title: String,
    value: Int,
    options: List<Int>,
    label: @Composable (Int) -> String,
    onChange: (Int) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val labels = options.map { label(it) }
    Box {
        GluciferValueRow(title, label(value), expanded) { expanded = true }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
            ) {
                options.forEachIndexed { index, option ->
                    SelectableDropdownMenuItem(
                        selected = option == value,
                        shapes = MenuDefaults.itemShape(index, options.size),
                        text = { Text(labels[index]) },
                        onClick = { onChange(option); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
internal fun GluciferHelpDialog(timing: Boolean = false, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (timing) R.string.glucifer_delivery_title else R.string.glucifer_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (timing) {
                    Text(stringResource(R.string.glucifer_live_bypass_help))
                    Text(stringResource(R.string.glucifer_fallback_help))
                    Text(stringResource(R.string.glucifer_background_spacing_help))
                    Text(stringResource(R.string.glucifer_all_spacing_help))
                } else {
                    Text(stringResource(R.string.glucifer_help_intro))
                    Text(stringResource(R.string.glucifer_help_install))
                    Text(stringResource(R.string.glucifer_help_pair))
                    Text(stringResource(R.string.glucifer_help_test))
                    TextButton(onClick = { uriHandler.openUri(GLUCIFER_SETUP_GUIDE) }, shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.glucifer_setup_guide))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(stringResource(R.string.close)) }
        }
    )
}
