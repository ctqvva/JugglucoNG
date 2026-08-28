@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package tk.glucodata.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.drivers.api.ApiGlucoseSourceRegistry
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.components.cardShape

@Composable
fun ApiSourceSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val initial = ApiGlucoseSourceRegistry.loadConfig(context)

    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var preset by rememberSaveable { mutableStateOf(initial.normalizedPreset) }
    var url by rememberSaveable { mutableStateOf(initial.url.ifBlank { ApiGlucoseSourceRegistry.defaultUrl(initial.normalizedPreset) }) }
    var token by rememberSaveable { mutableStateOf(initial.token) }
    var peerId by rememberSaveable { mutableStateOf(initial.peerId) }
    var apiVersion by rememberSaveable { mutableStateOf(initial.apiVersion) }
    var headers by rememberSaveable { mutableStateOf(initial.headers) }
    var format by rememberSaveable { mutableStateOf(initial.normalizedFormat) }
    var pollSeconds by rememberSaveable { mutableStateOf(initial.pollSeconds.toString()) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var showPresetSheet by rememberSaveable { mutableStateOf(false) }

    fun presetNeedsCredentials(value: String): Boolean =
        when (ApiGlucoseSourceRegistry.normalizePreset(value)) {
            ApiGlucoseSourceRegistry.PRESET_TELEGRAM_BOT,
            ApiGlucoseSourceRegistry.PRESET_VK_DIRECT -> true
            else -> false
        }

    fun persist(connect: Boolean = false) {
        val seconds = pollSeconds.toIntOrNull()?.coerceAtLeast(30)
            ?: ApiGlucoseSourceRegistry.DEFAULT_POLL_SECONDS
        if (connect && enabled) {
            val sensorId = ApiGlucoseSourceRegistry.enableSourceSensor(
                context = context,
                preset = preset,
                url = url,
                token = token,
                peerId = peerId,
                apiVersion = apiVersion,
                headers = headers,
                format = format,
                pollSeconds = seconds,
            )
            if (sensorId == null) {
                enabled = false
                ApiGlucoseSourceRegistry.saveConfig(
                    context = context,
                    enabled = false,
                    preset = preset,
                    url = url,
                    token = token,
                    peerId = peerId,
                    apiVersion = apiVersion,
                    headers = headers,
                    format = format,
                    pollSeconds = seconds,
                )
                val messageRes = if (presetNeedsCredentials(preset)) {
                    R.string.api_source_credentials_required
                } else {
                    R.string.api_source_url_required
                }
                Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
            }
        } else if (!enabled) {
            ApiGlucoseSourceRegistry.saveConfig(
                context = context,
                enabled = false,
                preset = preset,
                url = url,
                token = token,
                peerId = peerId,
                apiVersion = apiVersion,
                headers = headers,
                format = format,
                pollSeconds = seconds,
            )
            if (connect) {
                ApiGlucoseSourceRegistry.disableSourceSensor(context)
            }
        } else {
            ApiGlucoseSourceRegistry.saveConfig(
                context = context,
                enabled = enabled,
                preset = preset,
                url = url,
                token = token,
                peerId = peerId,
                apiVersion = apiVersion,
                headers = headers,
                format = format,
                pollSeconds = seconds,
            )
        }
    }

    fun applyPreset(nextPreset: String) {
        val normalizedPreset = ApiGlucoseSourceRegistry.normalizePreset(nextPreset)
        preset = normalizedPreset
        url = ApiGlucoseSourceRegistry.defaultUrl(normalizedPreset)
        format = ApiGlucoseSourceRegistry.defaultFormat(normalizedPreset)
        showPresetSheet = false
        persist(connect = enabled)
    }

    if (showPresetSheet) {
        SourcePresetPickerSheet(
            title = stringResource(R.string.api_source_preset_sheet_title),
            presets = sourcePresetSpecs(),
            onDismiss = { showPresetSheet = false },
            onPreset = ::applyPreset
        )
    }

    val activeAlpha = if (enabled) 1f else 0.68f
    val isVk = preset == ApiGlucoseSourceRegistry.PRESET_VK_DIRECT
    val isTelegram = preset == ApiGlucoseSourceRegistry.PRESET_TELEGRAM_BOT
    val isCredentialPreset = isVk || isTelegram

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.api_source_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { persist(connect = enabled); navController.popBackStack() }) {
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
            item("api_source_card") {
                SectionLabel(stringResource(R.string.api_source_title), topPadding = 0.dp)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = cardShape(CardPosition.SINGLE),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.heightIn(min = 54.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SourceIcon(enabled)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(apiSourcePresetTitle(preset)),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(apiSourcePresetDescription(preset)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StyledSwitch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    enabled = checked
                                    persist(connect = checked)
                                }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))

                        Column(
                            modifier = Modifier.alpha(activeAlpha),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            SourcePresetRow(
                                preset = preset,
                                onChangePreset = { showPresetSheet = true }
                            )

                            if (isCredentialPreset) {
                                OutlinedTextField(
                                    value = token,
                                    onValueChange = {
                                        token = it
                                        persist()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = {
                                        Text(
                                            stringResource(
                                                if (isTelegram) {
                                                    R.string.api_source_telegram_token
                                                } else {
                                                    R.string.api_source_vk_token
                                                }
                                            )
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                                    visualTransformation = if (showSecret) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { showSecret = !showSecret }) {
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
                                    value = peerId,
                                    onValueChange = {
                                        peerId = if (isTelegram) {
                                            it
                                        } else {
                                            it.filter { ch -> ch.isDigit() || ch == '-' }
                                        }
                                        persist()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = {
                                        Text(
                                            stringResource(
                                                if (isTelegram) {
                                                    R.string.api_source_telegram_chat_ids
                                                } else {
                                                    R.string.api_source_vk_sender_id
                                                }
                                            )
                                        )
                                    },
                                    supportingText = {
                                        Text(
                                            stringResource(
                                                if (isTelegram) {
                                                    R.string.api_source_telegram_note
                                                } else {
                                                    R.string.api_source_vk_note
                                                }
                                            )
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = if (isTelegram) KeyboardType.Text else KeyboardType.Number,
                                        imeAction = ImeAction.Next
                                    )
                                )
                            }

                            if (isVk) {
                                OutlinedTextField(
                                    value = apiVersion,
                                    onValueChange = {
                                        apiVersion = it
                                        persist()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.outbound_api_vk_version)) },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Next
                                    )
                                )
                            }

                            OutlinedTextField(
                                value = url,
                                onValueChange = {
                                    url = it
                                    persist()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.api_source_url_label)) },
                                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next
                                )
                            )

                            if (!isVk) {
                                OutlinedTextField(
                                    value = headers,
                                    onValueChange = {
                                        headers = it
                                        persist()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3,
                                    label = { Text(stringResource(R.string.outbound_api_headers)) },
                                    placeholder = { Text(stringResource(R.string.outbound_api_headers_placeholder)) },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Default
                                    )
                                )
                            }

                            OutlinedTextField(
                                value = pollSeconds,
                                onValueChange = {
                                    pollSeconds = it.filter { ch -> ch.isDigit() }
                                    persist()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.api_source_poll_seconds)) },
                                supportingText = { Text(stringResource(R.string.api_source_poll_seconds_desc)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceIcon(enabled: Boolean) {
    val tint = if (enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.size(42.dp),
        shape = cardShape(CardPosition.SINGLE, radius = 12.dp),
        color = tint.copy(alpha = if (enabled) 0.14f else 0.08f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SourcePresetRow(
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
                    text = stringResource(R.string.api_source_preset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(apiSourcePresetTitle(preset)),
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
private fun SourcePresetPickerSheet(
    title: String,
    presets: List<SourcePresetSpec>,
    onDismiss: () -> Unit,
    onPreset: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() }
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
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPreset(preset.id) },
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
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = preset.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(preset.titleRes),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(preset.descriptionRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private data class SourcePresetSpec(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector
)

private fun sourcePresetSpecs(): List<SourcePresetSpec> =
    listOf(
        SourcePresetSpec(
            id = ApiGlucoseSourceRegistry.PRESET_CUSTOM_JSON,
            titleRes = R.string.api_source_preset_custom_json,
            descriptionRes = R.string.api_source_preset_custom_json_desc,
            icon = Icons.Filled.CloudDownload
        ),
        SourcePresetSpec(
            id = ApiGlucoseSourceRegistry.PRESET_TELEGRAM_BOT,
            titleRes = R.string.api_source_preset_telegram,
            descriptionRes = R.string.api_source_preset_telegram_desc,
            icon = Icons.AutoMirrored.Filled.Send
        ),
        SourcePresetSpec(
            id = ApiGlucoseSourceRegistry.PRESET_VK_DIRECT,
            titleRes = R.string.api_source_preset_vk_direct,
            descriptionRes = R.string.api_source_preset_vk_direct_desc,
            icon = Icons.Filled.CloudDownload
        )
    )

private fun apiSourcePresetTitle(preset: String): Int =
    when (ApiGlucoseSourceRegistry.normalizePreset(preset)) {
        ApiGlucoseSourceRegistry.PRESET_TELEGRAM_BOT -> R.string.api_source_preset_telegram
        ApiGlucoseSourceRegistry.PRESET_VK_DIRECT -> R.string.api_source_preset_vk_direct
        else -> R.string.api_source_preset_custom_json
    }

private fun apiSourcePresetDescription(preset: String): Int =
    when (ApiGlucoseSourceRegistry.normalizePreset(preset)) {
        ApiGlucoseSourceRegistry.PRESET_TELEGRAM_BOT -> R.string.api_source_preset_telegram_desc
        ApiGlucoseSourceRegistry.PRESET_VK_DIRECT -> R.string.api_source_preset_vk_direct_desc
        else -> R.string.api_source_preset_custom_json_desc
    }
