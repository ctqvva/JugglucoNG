@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package tk.glucodata.ui

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.OutboundApiSettings
import tk.glucodata.R
import tk.glucodata.alerts.AlertType
import tk.glucodata.sms.SmsContact
import tk.glucodata.sms.SmsGateway
import tk.glucodata.sms.SmsPolicy
import tk.glucodata.sms.SmsWatchdog
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.util.ConnectedButtonGroup

/**
 * Editor for an SMS destination.
 *
 * Laid out in the order someone reasons about the feature: who gets texted, what
 * is worth texting about, how far it escalates, whether routine updates should
 * fall back to SMS, and what it is allowed to cost. The live preview at the
 * bottom is deliberate — an SMS is charged and irreversible, so the user should
 * see the exact text before anyone else does.
 */
@Composable
internal fun SmsDestinationEditor(
    destination: OutboundApiSettings.Destination,
    onChange: (OutboundApiSettings.Destination) -> Unit
) {
    val context = LocalContext.current
    val policy = destination.smsPolicy

    fun update(transform: (SmsPolicy) -> SmsPolicy) {
        onChange(destination.copy(smsPolicy = transform(policy).sanitized()))
    }

    SmsPermissionNotice()

    SettingsSubsectionTitle(stringResource(R.string.sms_section_who))
    OutlinedTextField(
        value = policy.subjectName,
        onValueChange = { value -> update { it.copy(subjectName = value) } },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.sms_subject_name)) },
        supportingText = { Text(stringResource(R.string.sms_subject_name_help)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next
        )
    )

    SmsContactsSection(policy = policy, onChange = { next -> update { next } })
    SmsSafetyNetSection(policy = policy, onChange = { next -> update { next } })
    SmsEscalationSection(policy = policy, onChange = { next -> update { next } })
    SmsRelaySection(policy = policy, onChange = { next -> update { next } })
    SmsLimitsSection(policy = policy, onChange = { next -> update { next } })

    SettingsSubsectionTitle(stringResource(R.string.sms_preview_title))
    val preview = remember(destination) {
        runCatching { SmsWatchdog.previewText(context, destination.id) }.getOrDefault("")
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(CardPosition.SINGLE, radius = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = preview.ifBlank { stringResource(R.string.sms_body_no_reading) },
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SmsPermissionNotice() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SmsGateway.hasPermission(context)) }
    val hasTelephony = remember { SmsGateway.hasTelephony(context) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    if (!hasTelephony) {
        SmsNoticeCard(
            text = stringResource(R.string.sms_no_telephony),
            action = null,
            onAction = {}
        )
        return
    }
    if (granted) return
    SmsNoticeCard(
        text = stringResource(R.string.sms_permission_desc),
        action = stringResource(R.string.sms_permission_grant),
        onAction = { launcher.launch(Manifest.permission.SEND_SMS) }
    )
}

@Composable
private fun SmsNoticeCard(text: String, action: String?, onAction: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(CardPosition.SINGLE, radius = 16.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (action != null) {
                TextButton(onClick = onAction, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(action) }
            }
        }
    }
}

// ------------------------------------------------------------------ contacts

@Composable
private fun SmsContactsSection(policy: SmsPolicy, onChange: (SmsPolicy) -> Unit) {
    // Keyed by contact id rather than list position, so deleting a row cannot leave
    // a different contact expanded.
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsSubsectionTitle(stringResource(R.string.sms_section_contacts))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (policy.contacts.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = cardShape(CardPosition.SINGLE, radius = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Text(
                    text = stringResource(R.string.sms_no_contacts),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        policy.contacts.forEach { contact ->
            SmsContactCard(
                contact = contact,
                expanded = expandedId == contact.id,
                onToggleExpanded = {
                    expandedId = if (expandedId == contact.id) null else contact.id
                },
                onChange = { updated ->
                    onChange(
                        policy.copy(
                            contacts = policy.contacts.map { if (it.id == contact.id) updated else it }
                        )
                    )
                },
                onDelete = {
                    expandedId = null
                    onChange(
                        policy.copy(contacts = policy.contacts.filterNot { it.id == contact.id })
                    )
                }
            )
        }
        if (policy.contacts.size < SmsPolicy.MAX_CONTACTS) {
            FilledTonalButton(
                onClick = {
                    val added = SmsContact(number = "")
                    onChange(policy.copy(contacts = policy.contacts + added))
                    expandedId = added.id
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null)
                Text(
                    text = stringResource(R.string.sms_add_contact),
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun SmsContactCard(
    contact: SmsContact,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onChange: (SmsContact) -> Unit,
    onDelete: () -> Unit
) {
    // Free text while editing, keyed on the row rather than on its value: keying on
    // the number would re-seed this from the normalized form on every keystroke and
    // eat the separators as the user types them.
    var numberText by remember(contact.id) { mutableStateOf(contact.number) }
    val valid = SmsContact.isPlausibleNumber(numberText)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(CardPosition.SINGLE, radius = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .heightIn(min = 64.dp)
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = cardShape(CardPosition.SINGLE, radius = 18.dp),
                    color = stageColor(contact.normalizedStage())
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = (contact.normalizedStage() + 1).toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contact.displayName().ifBlank {
                            stringResource(R.string.sms_contact_number)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(stageLabel(contact.normalizedStage())) +
                            if (contact.relay) " · " + stringResource(R.string.sms_contact_relay_short) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDelete, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.sms_remove_contact)
                    )
                }
                IconButton(onClick = onToggleExpanded, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = numberText,
                        onValueChange = { value ->
                            numberText = value
                            onChange(contact.copy(number = SmsContact.normalizeNumber(value)))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = numberText.isNotBlank() && !valid,
                        label = { Text(stringResource(R.string.sms_contact_number)) },
                        supportingText = {
                            if (numberText.isNotBlank() && !valid) {
                                Text(stringResource(R.string.sms_contact_invalid))
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Next
                        )
                    )
                    OutlinedTextField(
                        value = contact.label,
                        onValueChange = { onChange(contact.copy(label = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.sms_contact_label)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        )
                    )
                    Text(
                        text = stringResource(R.string.sms_contact_stage),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val stageLabels = (0..SmsPolicy.MAX_STAGE).associateWith {
                        stringResource(stageLabel(it))
                    }
                    ConnectedButtonGroup(
                        options = (0..SmsPolicy.MAX_STAGE).toList(),
                        selectedOption = contact.normalizedStage(),
                        onOptionSelected = { onChange(contact.copy(stage = it)) },
                        label = { Text(stageLabels.getValue(it)) },
                        labelText = { stageLabels.getValue(it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ToggleRow(
                        title = stringResource(R.string.sms_contact_relay),
                        subtitle = stringResource(R.string.sms_contact_relay_desc),
                        checked = contact.relay,
                        onCheckedChange = { onChange(contact.copy(relay = it)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun stageColor(stage: Int) = when (stage) {
    0 -> MaterialTheme.colorScheme.primaryContainer
    1 -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHighest
}

private fun stageLabel(stage: Int): Int = when (stage) {
    0 -> R.string.sms_stage_first
    1 -> R.string.sms_stage_backup
    else -> R.string.sms_stage_last
}

// --------------------------------------------------------------- safety net

@Composable
private fun SmsSafetyNetSection(policy: SmsPolicy, onChange: (SmsPolicy) -> Unit) {
    SettingsSubsectionTitle(stringResource(R.string.sms_section_safety_net))
    SmsCard {
        ToggleRow(
            title = stringResource(R.string.sms_alarm_escalation_title),
            subtitle = stringResource(R.string.sms_alarm_escalation_desc),
            checked = policy.alarmEscalationEnabled,
            onCheckedChange = { onChange(policy.copy(alarmEscalationEnabled = it)) }
        )
        if (policy.alarmEscalationEnabled) {
            ControlDivider()
            NumberStepper(
                label = stringResource(R.string.sms_unacked_minutes),
                value = policy.unackedMinutes,
                range = 1..240,
                onChange = { onChange(policy.copy(unackedMinutes = it)) }
            )
            ControlDivider()
            SmsAlertTypePicker(policy = policy, onChange = onChange)
        }
        ControlDivider()
        ToggleRow(
            title = stringResource(R.string.sms_critical_title),
            subtitle = stringResource(R.string.sms_critical_desc),
            checked = policy.criticalEnabled,
            onCheckedChange = { onChange(policy.copy(criticalEnabled = it)) }
        )
        if (policy.criticalEnabled) {
            ControlDivider()
            GlucoseThresholdField(
                labelRes = R.string.sms_critical_low,
                mgdl = policy.criticalLowMgdl,
                onChange = { onChange(policy.copy(criticalLowMgdl = it)) }
            )
            GlucoseThresholdField(
                labelRes = R.string.sms_critical_high,
                mgdl = policy.criticalHighMgdl,
                onChange = { onChange(policy.copy(criticalHighMgdl = it)) }
            )
            ControlDivider()
            NumberStepper(
                label = stringResource(R.string.sms_critical_grace),
                value = policy.criticalGraceMinutes,
                range = 0..60,
                onChange = { onChange(policy.copy(criticalGraceMinutes = it)) }
            )
            ControlDivider()
            ToggleRow(
                title = stringResource(R.string.sms_critical_ignores_ack),
                subtitle = stringResource(R.string.sms_critical_ignores_ack_desc),
                checked = policy.criticalIgnoresAck,
                onCheckedChange = { onChange(policy.copy(criticalIgnoresAck = it)) }
            )
        }
        ControlDivider()
        ToggleRow(
            title = stringResource(R.string.sms_no_data_title),
            subtitle = stringResource(R.string.sms_no_data_desc),
            checked = policy.noDataEnabled,
            onCheckedChange = { onChange(policy.copy(noDataEnabled = it)) }
        )
        if (policy.noDataEnabled) {
            ControlDivider()
            NumberStepper(
                label = stringResource(R.string.sms_no_data_minutes),
                value = policy.noDataMinutes,
                range = 10..720,
                onChange = { onChange(policy.copy(noDataMinutes = it)) }
            )
        }
    }
}

@Composable
private fun SmsAlertTypePicker(policy: SmsPolicy, onChange: (SmsPolicy) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.sms_alarm_types),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.sms_alarm_types_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AlertType.settingsEntries.forEach { type ->
                val selected = type.id in policy.alarmAlertIds
                FilterChip(
                    selected = selected,
                    onClick = {
                        val next = if (selected) {
                            policy.alarmAlertIds - type.id
                        } else {
                            policy.alarmAlertIds + type.id
                        }
                        onChange(policy.copy(alarmAlertIds = next))
                    },
                    label = { Text(stringResource(type.nameResId)) }
                )
            }
        }
    }
}

// --------------------------------------------------------------- escalation

@Composable
private fun SmsEscalationSection(policy: SmsPolicy, onChange: (SmsPolicy) -> Unit) {
    SettingsSubsectionTitle(stringResource(R.string.sms_section_escalation))
    SmsCard {
        NumberStepper(
            label = stringResource(R.string.sms_stage_step),
            value = policy.stageStepMinutes,
            range = 0..120,
            onChange = { onChange(policy.copy(stageStepMinutes = it)) }
        )
        ControlDivider()
        NumberStepper(
            label = stringResource(R.string.sms_repeat_minutes),
            value = policy.repeatMinutes,
            range = 0..240,
            onChange = { onChange(policy.copy(repeatMinutes = it)) }
        )
        ControlDivider()
        NumberStepper(
            label = stringResource(R.string.sms_max_sends),
            value = policy.maxSendsPerEpisode,
            range = 1..10,
            onChange = { onChange(policy.copy(maxSendsPerEpisode = it)) }
        )
        ControlDivider()
        ToggleRow(
            title = stringResource(R.string.sms_all_clear_title),
            subtitle = stringResource(R.string.sms_all_clear_desc),
            checked = policy.allClearEnabled,
            onCheckedChange = { onChange(policy.copy(allClearEnabled = it)) }
        )
    }
}

// -------------------------------------------------------------------- relay

@Composable
private fun SmsRelaySection(policy: SmsPolicy, onChange: (SmsPolicy) -> Unit) {
    SettingsSubsectionTitle(stringResource(R.string.sms_section_relay))
    SmsCard {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.sms_relay_mode),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.sms_relay_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            val modeLabels = mapOf(
                SmsPolicy.RELAY_OFF to stringResource(R.string.sms_relay_off),
                SmsPolicy.RELAY_WHEN_OFFLINE to stringResource(R.string.sms_relay_when_offline),
                SmsPolicy.RELAY_ALWAYS to stringResource(R.string.sms_relay_always)
            )
            ConnectedButtonGroup(
                options = modeLabels.keys.toList(),
                selectedOption = policy.normalizedRelayMode(),
                onOptionSelected = { onChange(policy.copy(relayMode = it)) },
                label = { Text(modeLabels.getValue(it)) },
                labelText = { modeLabels.getValue(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (policy.normalizedRelayMode() != SmsPolicy.RELAY_OFF) {
            ControlDivider()
            NumberStepper(
                label = stringResource(R.string.sms_relay_interval),
                value = policy.relayIntervalMinutes,
                range = 5..720,
                onChange = { onChange(policy.copy(relayIntervalMinutes = it)) }
            )
            if (policy.normalizedRelayMode() == SmsPolicy.RELAY_WHEN_OFFLINE) {
                ControlDivider()
                NumberStepper(
                    label = stringResource(R.string.sms_relay_grace),
                    value = policy.relayOfflineGraceMinutes,
                    range = 0..240,
                    onChange = { onChange(policy.copy(relayOfflineGraceMinutes = it)) }
                )
            }
        }
    }
}

// ------------------------------------------------------------------- limits

@Composable
private fun SmsLimitsSection(policy: SmsPolicy, onChange: (SmsPolicy) -> Unit) {
    val context = LocalContext.current
    SettingsSubsectionTitle(stringResource(R.string.sms_section_limits))
    SmsCard {
        ToggleRow(
            title = stringResource(R.string.sms_escalate_only_offline),
            subtitle = stringResource(R.string.sms_escalate_only_offline_desc),
            checked = policy.escalateOnlyWhenOffline,
            onCheckedChange = { onChange(policy.copy(escalateOnlyWhenOffline = it)) }
        )
        ControlDivider()
        NumberStepper(
            label = stringResource(R.string.sms_max_per_hour),
            value = policy.maxPerHour,
            range = 1..60,
            onChange = { onChange(policy.copy(maxPerHour = it)) }
        )
        ControlDivider()
        NumberStepper(
            label = stringResource(R.string.sms_max_per_day),
            value = policy.maxPerDay,
            range = 1..400,
            onChange = { onChange(policy.copy(maxPerDay = it)) }
        )
        ControlDivider()
        Text(
            text = stringResource(
                R.string.sms_sent_today,
                remember { SmsWatchdog.remainingToday(context) }
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun SmsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(CardPosition.SINGLE, radius = 18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun GlucoseThresholdField(labelRes: Int, mgdl: Int, onChange: (Int) -> Unit) {
    var text by remember(mgdl) { mutableStateOf(formatThreshold(mgdl)) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filterThresholdInput()
            parseThreshold(text, mgdl)?.let(onChange)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        label = { Text(stringResource(labelRes, thresholdUnitLabel())) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next
        )
    )
}
