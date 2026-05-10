@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package tk.glucodata.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import tk.glucodata.Applic
import tk.glucodata.OutboundApi
import tk.glucodata.OutboundApiSettings
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.components.cardShape

@Composable
fun OutboundApiSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(OutboundApiSettings.load(context)) }
    var expandedId by rememberSaveable {
        mutableStateOf(config.destinations.firstOrNull { it.enabled }?.id ?: config.destinations.firstOrNull()?.id)
    }
    var showSecretForId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OutboundApiSettings.Destination?>(null) }

    fun save(next: OutboundApiSettings.Config) {
        val normalized = next.copy(enabled = true)
        config = normalized
        OutboundApiSettings.save(context, normalized)
    }

    fun updateDestination(
        destinationId: String,
        transform: (OutboundApiSettings.Destination) -> OutboundApiSettings.Destination
    ) {
        save(config.copy(destinations = config.destinations.map { destination ->
            if (destination.id == destinationId) transform(destination) else destination
        }))
    }

    fun addDestination(preset: String) {
        val destination = OutboundApiSettings.createDestination(preset)
        save(config.copy(destinations = config.destinations + destination))
        expandedId = destination.id
        showSecretForId = null
        showAddSheet = false
    }

    fun deleteDestination(destination: OutboundApiSettings.Destination) {
        val remaining = config.destinations.filterNot { it.id == destination.id }
        save(config.copy(destinations = remaining))
        expandedId = remaining.firstOrNull { it.enabled }?.id ?: remaining.firstOrNull()?.id
        pendingDelete = null
        showSecretForId = null
    }

    fun sendTest(destination: OutboundApiSettings.Destination) {
        val result = OutboundApi.enqueueCurrentTest(context, destination.id)
        val message = when (result) {
            OutboundApi.TEST_QUEUED -> R.string.outbound_api_test_queued
            OutboundApi.TEST_NO_CURRENT_READING -> R.string.outbound_api_no_current_reading
            else -> R.string.outbound_api_not_configured
        }
        Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
        config = OutboundApiSettings.load(context)
    }

    LaunchedEffect(config.destinations.size) {
        if (expandedId != null && config.destinations.none { it.id == expandedId }) {
            expandedId = config.destinations.firstOrNull { it.enabled }?.id ?: config.destinations.firstOrNull()?.id
        }
    }

    if (showAddSheet) {
        PresetPickerSheet(
            title = stringResource(R.string.outbound_api_preset_sheet_title),
            presets = destinationPresetSpecs(),
            onDismiss = { showAddSheet = false },
            onPreset = ::addDestination
        )
    }

    pendingDelete?.let { destination ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.deletequestion)) },
            text = {
                Text(
                    stringResource(
                        R.string.outbound_api_delete_destination_message,
                        destination.resolvedName()
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteDestination(destination) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.outbound_api_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("outbound_destinations") {
                SectionLabel(stringResource(R.string.outbound_api_destinations), topPadding = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (config.destinations.isEmpty()) {
                        EmptyDestinationsCard()
                    } else {
                        config.destinations.forEach { destination ->
                            DestinationCard(
                                destination = destination,
                                expanded = destination.id == expandedId,
                                showSecret = showSecretForId == destination.id,
                                onToggleExpanded = {
                                    expandedId = if (expandedId == destination.id) null else destination.id
                                },
                                onEnabledChange = { checked ->
                                    updateDestination(destination.id) { it.copy(enabled = checked) }
                                    if (checked) expandedId = destination.id
                                },
                                onDelete = { pendingDelete = destination },
                                onShowSecretChange = { show -> showSecretForId = if (show) destination.id else null },
                                onChange = { updated -> updateDestination(destination.id) { updated } },
                                onChangePreset = { preset ->
                                    updateDestination(destination.id) { it.withPreset(preset) }
                                    showSecretForId = null
                                },
                                onSendTest = { sendTest(destination) }
                            )
                        }
                    }
                }
            }

            item("outbound_add") {
                FilledTonalButton(
                    onClick = { showAddSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.outbound_api_add_destination),
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }

            item("api_source_entry") {
                SectionLabel(stringResource(R.string.api_source_title), topPadding = 12.dp)
                SettingsItem(
                    title = stringResource(R.string.api_source_settings_title),
                    subtitle = stringResource(R.string.api_source_desc),
                    showArrow = true,
                    icon = Icons.Filled.CloudDownload,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.SINGLE,
                    onClick = { navController.navigate("settings/api-source") }
                )
            }
        }
    }
}

@Composable
private fun EmptyDestinationsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = cardShape(CardPosition.SINGLE),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = stringResource(R.string.outbound_api_no_destinations),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DestinationCard(
    destination: OutboundApiSettings.Destination,
    expanded: Boolean,
    showSecret: Boolean,
    onToggleExpanded: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onShowSecretChange: (Boolean) -> Unit,
    onChange: (OutboundApiSettings.Destination) -> Unit,
    onChangePreset: (String) -> Unit,
    onSendTest: () -> Unit
) {
    val container = if (destination.enabled) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(CardPosition.SINGLE),
        color = container
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .heightIn(min = 76.dp)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DestinationIcon(destination.normalizedPreset(), destination.enabled)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = destination.resolvedName(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(presetTitle(destination.normalizedPreset())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StyledSwitch(
                    checked = destination.enabled,
                    onCheckedChange = onEnabledChange
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
                IconButton(onClick = onToggleExpanded) {
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
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .alpha(if (destination.enabled) 1f else 0.68f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
                    DestinationEditor(
                        destination = destination,
                        showSecret = showSecret,
                        onShowSecretChange = onShowSecretChange,
                        onChange = onChange,
                        onChangePreset = onChangePreset
                    )
                    DestinationStatus(destination = destination)
                    Button(
                        onClick = onSendTest,
                        enabled = destination.isReady(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null)
                        Text(
                            text = stringResource(R.string.outbound_api_send_test),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationIcon(preset: String, enabled: Boolean) {
    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.size(42.dp),
        shape = cardShape(CardPosition.SINGLE, radius = 12.dp),
        color = tint.copy(alpha = if (enabled) 0.14f else 0.08f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (preset) {
                    OutboundApiSettings.PRESET_TELEGRAM_BOT,
                    OutboundApiSettings.PRESET_GLUCO_WATCH_VK,
                    OutboundApiSettings.PRESET_VK_MESSAGES -> Icons.Filled.Send
                    else -> Icons.Filled.CloudUpload
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun DestinationEditor(
    destination: OutboundApiSettings.Destination,
    showSecret: Boolean,
    onShowSecretChange: (Boolean) -> Unit,
    onChange: (OutboundApiSettings.Destination) -> Unit,
    onChangePreset: (String) -> Unit
) {
    val preset = destination.normalizedPreset()
    val isCustom = preset == OutboundApiSettings.PRESET_CUSTOM_JSON
    val isVk = preset == OutboundApiSettings.PRESET_GLUCO_WATCH_VK ||
        preset == OutboundApiSettings.PRESET_VK_MESSAGES
    val isTelegram = preset == OutboundApiSettings.PRESET_TELEGRAM_BOT
    var showPresetSheet by rememberSaveable(destination.id) { mutableStateOf(false) }

    if (showPresetSheet) {
        PresetPickerSheet(
            title = stringResource(R.string.outbound_api_preset_sheet_title),
            presets = destinationPresetSpecs(),
            onDismiss = { showPresetSheet = false },
            onPreset = {
                onChangePreset(it)
                showPresetSheet = false
            }
        )
    }

    SettingsSubsectionTitle(stringResource(R.string.outbound_api_connection))
    OutlinedTextField(
        value = destination.name,
        onValueChange = { onChange(destination.copy(name = it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.outbound_api_destination_name)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next
        )
    )

    PresetSummaryRow(
        preset = preset,
        onChangePreset = { showPresetSheet = true }
    )

    if (isTelegram || isVk) {
        OutlinedTextField(
            value = destination.token,
            onValueChange = { onChange(destination.copy(token = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = {
                Text(
                    stringResource(
                        if (isTelegram) R.string.outbound_api_telegram_token else R.string.outbound_api_vk_token
                    )
                )
            },
            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { onShowSecretChange(!showSecret) }) {
                    Icon(
                        if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            )
        )
        OutlinedTextField(
            value = destination.chatId,
            onValueChange = { onChange(destination.copy(chatId = it)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            label = { Text(stringResource(R.string.outbound_api_recipient_ids)) },
            supportingText = {
                Text(
                    stringResource(
                        if (isTelegram) {
                            R.string.outbound_api_telegram_recipient_help
                        } else {
                            R.string.outbound_api_vk_recipient_help
                        }
                    )
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            )
        )
    }

    OutlinedTextField(
        value = destination.url.ifBlank { OutboundApiSettings.defaultUrl(preset) },
        onValueChange = { onChange(destination.copy(url = it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.outbound_api_url_label)) },
        supportingText = {
            if (isTelegram) {
                Text(stringResource(R.string.outbound_api_url_template_help))
            }
        },
        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next
        )
    )

    OutlinedTextField(
        value = destination.headers,
        onValueChange = { onChange(destination.copy(headers = it)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = if (isCustom) 3 else 1,
        label = { Text(stringResource(R.string.outbound_api_headers)) },
        placeholder = { Text(stringResource(R.string.outbound_api_headers_placeholder)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Default
        )
    )

    if (isVk) {
        OutlinedTextField(
            value = destination.apiVersion,
            onValueChange = { onChange(destination.copy(apiVersion = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.outbound_api_vk_version)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            )
        )
    }

    SettingsSubsectionTitle(stringResource(R.string.outbound_api_delivery))
    OutlinedTextField(
        value = destination.minIntervalMinutes.toString(),
        onValueChange = { raw ->
            onChange(destination.copy(minIntervalMinutes = raw.filter { it.isDigit() }.toIntOrNull() ?: 0))
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.outbound_api_min_interval)) },
        supportingText = { Text(stringResource(R.string.outbound_api_min_interval_desc)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        )
    )
    TriggerPicker(destination = destination, onChange = onChange)
    TemplateEditor(destination = destination, onChange = onChange)
}

@Composable
private fun PresetSummaryRow(
    preset: String,
    onChangePreset: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape(CardPosition.SINGLE, radius = 10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.outbound_api_provider),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(presetTitle(preset)),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            TextButton(onClick = onChangePreset) {
                Text(stringResource(R.string.outbound_api_change_preset))
            }
        }
    }
}

@Composable
private fun TriggerPicker(
    destination: OutboundApiSettings.Destination,
    onChange: (OutboundApiSettings.Destination) -> Unit
) {
    var showSheet by rememberSaveable(destination.id) { mutableStateOf(false) }
    if (showSheet) {
        TriggerEditorSheet(
            destination = destination,
            onDismiss = { showSheet = false },
            onChange = onChange
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.outbound_api_trigger),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = triggerSummary(destination),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        TextButton(onClick = { showSheet = true }) {
            Text(stringResource(R.string.outbound_api_change_trigger))
        }
    }
}

@Composable
private fun TriggerEditorSheet(
    destination: OutboundApiSettings.Destination,
    onDismiss: () -> Unit,
    onChange: (OutboundApiSettings.Destination) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var lowText by rememberSaveable(destination.id) {
        mutableStateOf(formatThreshold(destination.triggerLowMgdl))
    }
    var highText by rememberSaveable(destination.id) {
        mutableStateOf(formatThreshold(destination.triggerHighMgdl))
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.outbound_api_trigger),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            triggerModes().forEach { (mode, titleRes, descRes) ->
                TriggerModeRow(
                    title = stringResource(titleRes),
                    description = stringResource(descRes),
                    selected = destination.normalizedTriggerMode() == mode,
                    onClick = { onChange(destination.copy(triggerMode = mode)) }
                )
            }
            OutlinedTextField(
                value = lowText,
                onValueChange = { raw ->
                    lowText = raw.filterThresholdInput()
                    parseThreshold(lowText, destination.triggerLowMgdl)?.let { threshold ->
                        onChange(destination.copy(triggerLowMgdl = threshold))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = {
                    Text(
                        stringResource(
                            R.string.outbound_api_trigger_low_threshold,
                            thresholdUnitLabel()
                        )
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next
                )
            )
            OutlinedTextField(
                value = highText,
                onValueChange = { raw ->
                    highText = raw.filterThresholdInput()
                    parseThreshold(highText, destination.triggerHighMgdl)?.let { threshold ->
                        onChange(destination.copy(triggerHighMgdl = threshold))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = {
                    Text(
                        stringResource(
                            R.string.outbound_api_trigger_high_threshold,
                            thresholdUnitLabel()
                        )
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                )
            )
        }
    }
}

@Composable
private fun TriggerModeRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = cardShape(CardPosition.SINGLE, radius = 12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun TemplateEditor(
    destination: OutboundApiSettings.Destination,
    onChange: (OutboundApiSettings.Destination) -> Unit
) {
    var field by remember(destination.id) {
        mutableStateOf(
            TextFieldValue(
                text = destination.messageTemplate,
                selection = TextRange(destination.messageTemplate.length)
            )
        )
    }
    LaunchedEffect(destination.id, destination.messageTemplate) {
        if (destination.messageTemplate != field.text) {
            field = TextFieldValue(
                text = destination.messageTemplate,
                selection = TextRange(destination.messageTemplate.length)
            )
        }
    }

    fun replaceSelection(token: String) {
        val start = minOf(field.selection.start, field.selection.end).coerceIn(0, field.text.length)
        val end = maxOf(field.selection.start, field.selection.end).coerceIn(0, field.text.length)
        val nextText = field.text.replaceRange(start, end, token)
        val nextCursor = start + token.length
        field = TextFieldValue(nextText, TextRange(nextCursor))
        onChange(destination.copy(messageTemplate = nextText))
    }

    OutlinedTextField(
        value = field,
        onValueChange = {
            field = it
            onChange(destination.copy(messageTemplate = it.text))
        },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        label = { Text(stringResource(R.string.outbound_api_message_template)) },
        supportingText = { Text(stringResource(R.string.outbound_api_template_desc)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Default
        )
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        templateTokens.forEach { token ->
            AssistChip(
                onClick = { replaceSelection(token) },
                label = { Text(token) }
            )
        }
    }
}

@Composable
private fun DestinationStatus(destination: OutboundApiSettings.Destination) {
    val context = LocalContext.current
    fun formatTime(epochMillis: Long): String {
        if (epochMillis <= 0L) return context.getString(R.string.outbound_api_status_never)
        return DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(epochMillis))
    }

    SettingsSubsectionTitle(stringResource(R.string.status))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.outbound_api_last_success_format, formatTime(destination.lastSuccessAtMs)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.outbound_api_last_attempt_format, formatTime(destination.lastAttemptAtMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (destination.lastResponseCode != 0) {
            Text(
                text = stringResource(R.string.outbound_api_last_code_format, destination.lastResponseCode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        destination.lastError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = stringResource(R.string.outbound_api_last_error_format, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsSubsectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun PresetPickerSheet(
    title: String,
    presets: List<PresetSpec>,
    onDismiss: () -> Unit,
    onPreset: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            presets.forEach { preset ->
                PresetSheetRow(
                    spec = preset,
                    onClick = { onPreset(preset.id) }
                )
            }
        }
    }
}

@Composable
private fun PresetSheetRow(
    spec: PresetSpec,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = cardShape(CardPosition.SINGLE, radius = 12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = cardShape(CardPosition.SINGLE, radius = 12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(spec.titleRes),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(spec.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class PresetSpec(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector
)

private fun destinationPresetSpecs(): List<PresetSpec> =
    listOf(
        PresetSpec(
            id = OutboundApiSettings.PRESET_CUSTOM_JSON,
            titleRes = R.string.outbound_api_preset_custom_json,
            descriptionRes = R.string.outbound_api_preset_custom_json_desc,
            icon = Icons.Filled.CloudUpload
        ),
        PresetSpec(
            id = OutboundApiSettings.PRESET_TELEGRAM_BOT,
            titleRes = R.string.outbound_api_preset_telegram,
            descriptionRes = R.string.outbound_api_preset_telegram_desc,
            icon = Icons.Filled.Send
        ),
        PresetSpec(
            id = OutboundApiSettings.PRESET_GLUCO_WATCH_VK,
            titleRes = R.string.outbound_api_preset_gluco_watch_vk,
            descriptionRes = R.string.outbound_api_preset_gluco_watch_vk_desc,
            icon = Icons.Filled.Send
        ),
        PresetSpec(
            id = OutboundApiSettings.PRESET_VK_MESSAGES,
            titleRes = R.string.outbound_api_preset_vk,
            descriptionRes = R.string.outbound_api_preset_vk_desc,
            icon = Icons.Filled.Send
        )
    )

private fun presetTitle(preset: String): Int =
    when (preset) {
        OutboundApiSettings.PRESET_TELEGRAM_BOT -> R.string.outbound_api_preset_telegram
        OutboundApiSettings.PRESET_GLUCO_WATCH_VK -> R.string.outbound_api_preset_gluco_watch_vk
        OutboundApiSettings.PRESET_VK_MESSAGES -> R.string.outbound_api_preset_vk
        else -> R.string.outbound_api_preset_custom_json
    }

private fun triggerModes(): List<Triple<String, Int, Int>> =
    listOf(
        Triple(
            OutboundApiSettings.TRIGGER_ALWAYS,
            R.string.outbound_api_trigger_always,
            R.string.outbound_api_trigger_always_desc
        ),
        Triple(
            OutboundApiSettings.TRIGGER_AT_OR_BELOW,
            R.string.outbound_api_trigger_at_or_below,
            R.string.outbound_api_trigger_at_or_below_desc
        ),
        Triple(
            OutboundApiSettings.TRIGGER_AT_OR_ABOVE,
            R.string.outbound_api_trigger_at_or_above,
            R.string.outbound_api_trigger_at_or_above_desc
        ),
        Triple(
            OutboundApiSettings.TRIGGER_OUTSIDE_RANGE,
            R.string.outbound_api_trigger_outside_range,
            R.string.outbound_api_trigger_outside_range_desc
        )
    )

@Composable
private fun triggerSummary(destination: OutboundApiSettings.Destination): String {
    val unit = thresholdUnitLabel()
    val low = formatThreshold(destination.triggerLowMgdl)
    val high = formatThreshold(destination.triggerHighMgdl)
    return when (destination.normalizedTriggerMode()) {
        OutboundApiSettings.TRIGGER_AT_OR_BELOW ->
            stringResource(R.string.outbound_api_trigger_summary_low, low, unit)
        OutboundApiSettings.TRIGGER_AT_OR_ABOVE ->
            stringResource(R.string.outbound_api_trigger_summary_high, high, unit)
        OutboundApiSettings.TRIGGER_OUTSIDE_RANGE ->
            stringResource(R.string.outbound_api_trigger_summary_range, low, high, unit)
        else -> stringResource(R.string.outbound_api_trigger_summary_always)
    }
}

private fun thresholdUnitLabel(): String =
    if (Applic.unit == 1) "mmol/L" else "mg/dL"

private fun formatThreshold(mgdl: Int): String =
    if (Applic.unit == 1) {
        String.format(Locale.US, "%.1f", mgdl / MGDL_PER_MMOLL)
    } else {
        mgdl.coerceAtLeast(1).toString()
    }

private fun parseThreshold(raw: String, fallbackMgdl: Int): Int? {
    val normalized = raw.replace(',', '.').trim()
    if (normalized.isBlank()) return null
    val value = normalized.toFloatOrNull() ?: return null
    if (!value.isFinite() || value <= 0f) return null
    val mgdl = if (Applic.unit == 1) {
        (value * MGDL_PER_MMOLL).roundToInt()
    } else {
        value.roundToInt()
    }
    return mgdl.coerceIn(1, 600).takeIf { it != fallbackMgdl } ?: mgdl.coerceIn(1, 600)
}

private fun String.filterThresholdInput(): String =
    filter { it.isDigit() || it == '.' || it == ',' }

private const val MGDL_PER_MMOLL = 18.0182f

private val templateTokens = listOf(
    "{value}",
    "{unit}",
    "{trend_arrow}",
    "{time}",
    "{mgdl}",
    "{mmol}",
    "{raw}",
    "{raw_mgdl}",
    "{raw_mmol}",
    "{rate_mgdl}",
    "{rate_mmol}",
    "{timestamp}",
    "{sensor}",
    "{recipient}",
    "{iob}",
    "{journal_iob}",
    "{cob}",
    "{journal}"
)
