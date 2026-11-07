package tk.glucodata.ui.setup

import androidx.compose.material3.ButtonDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.drivers.aidex.AiDexCnCloudClient
import tk.glucodata.drivers.aidex.AiDexPairingMaterialFile
import tk.glucodata.drivers.aidex.bloodcontrol.BloodControlAidex
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter
import tk.glucodata.drivers.aidex.AiDexSetupKeyGate
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.components.SectionLabel

private enum class AiDexAccountLoginMethod(val labelRes: Int) {
    SMS(R.string.ottai_login_sms),
    PASSWORD(R.string.ottai_login_password),
}

@Composable
internal fun AiDexKeyManagementScreen(
    ui: WizardUiMetrics,
    initialSerial: String,
    signedIn: Boolean,
    onSignedIn: () -> Unit,
    onSignedOut: () -> Unit,
    bloodControlAvailable: Boolean = BloodControlAidex.available,
    sensors: List<AiDexKeySensor> = emptyList(),
    requiredSerial: String? = null,
    onConnect: ((AiDexKeySensor) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRussian = LocalConfiguration.current.locales[0]?.language == "ru"
    var bloodControlSelected by remember { mutableStateOf(false) }
    var serialInput by remember(initialSerial) { mutableStateOf(initialSerial) }
    var serialEdited by remember(initialSerial) { mutableStateOf(initialSerial.isNotBlank()) }
    var keySaved by remember { mutableStateOf(false) }
    var connectionAllowedFor by remember { mutableStateOf<String?>(null) }
    var savedSensors by remember { mutableStateOf<List<AiDexSavedKeySensor>>(emptyList()) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var keyRefresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    val normalizedSerial = AiDexPairingMaterialFile.normalizeSerial(serialInput)
    val sensorChoices = remember(sensors) { mergeAiDexKeySensors(sensors) }
    val connectSensor = sensorChoices.firstOrNull { it.serial == normalizedSerial && it.address.isNotBlank() }
    val fullModeGuard = tk.glucodata.ui.restricted.rememberFullModeGuard()
    val unsavedChoices = sensorChoices.filter { choice -> savedSensors.none { it.serial == choice.serial } }

    LaunchedEffect(sensorChoices) {
        if (!serialEdited && serialInput.isBlank()) {
            val preferred = sensorChoices.firstOrNull { it.connected } ?: sensorChoices.singleOrNull()
            if (preferred != null) serialInput = preferred.serial
        }
    }

    var phone by remember { mutableStateOf(AiDexProvisioningStore.accountLabel(context)) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginMethod by remember { mutableStateOf(AiDexAccountLoginMethod.SMS) }
    val normalizedPhone = AiDexCnCloudClient.normalizeCnPhone(phone)

    LaunchedEffect(normalizedSerial, keyRefresh, sensorChoices) {
        connectionAllowedFor = null
        keySaved = normalizedSerial?.let { serial ->
            withContext(Dispatchers.IO) { AiDexProvisioningStore.hasSaved(context, serial) }
        } == true
        connectionAllowedFor = connectSensor?.takeIf { sensor ->
            withContext(Dispatchers.IO) { AiDexSetupKeyGate.mayConnect(context, sensor.serial, sensor.name) }
        }?.serial
        savedSensors = withContext(Dispatchers.IO) {
            val managed = AiDexManagedSensorIdentityAdapter.persistedSensorIds(context)
                .mapNotNull(AiDexPairingMaterialFile::normalizeSerial).toSet()
            AiDexProvisioningStore.savedSerials(context, sensorChoices.map { it.serial } + managed +
                listOfNotNull(normalizedSerial) + BloodControlAidex.cachedSerials(context))
                .map { serial -> AiDexSavedKeySensor(serial, serial in managed,
                    sensorChoices.any { it.serial == serial && it.connected }) }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val serial = pendingExport
        pendingExport = null
        if (uri != null && serial != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                val json = AiDexProvisioningStore.exportJson(context, serial)
                    ?: return@withContext false
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            statusIsError = !saved
            status = context.getString(
                if (saved) R.string.ottai_save_ok else R.string.ottai_save_nothing,
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val importedSerial = withContext(Dispatchers.IO) {
                val json = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull() ?: return@withContext null
                AiDexProvisioningStore.importJson(context, json)
            }
            if (importedSerial != null) {
                serialInput = importedSerial
                serialEdited = true
                keyRefresh += 1
                statusIsError = false
                status = ""
            } else {
                statusIsError = true
                status = context.getString(R.string.aidex_key_import_failed)
            }
        }
    }

    pendingDelete?.let { serial ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ottai_delete_title)) },
            text = { Text(stringResource(R.string.aidex_delete_keys_message, serial)) },
            confirmButton = {
                Button(onClick = { fullModeGuard {
                    pendingDelete = null
                    busy = true
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            val managed = AiDexManagedSensorIdentityAdapter.persistedSensorIds(context)
                                .any { AiDexPairingMaterialFile.normalizeSerial(it) == serial }
                            !managed && AiDexProvisioningStore.removeSaved(context, serial)
                        }
                        busy = false
                        keyRefresh += 1
                        statusIsError = !removed
                        status = if (removed) context.getString(R.string.ottai_delete_done, serial)
                            else context.getString(R.string.error)
                    }
                } }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDelete = null }, shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ui.horizontalPadding, vertical = ui.spacerMedium),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.aidex_key_management_title),
            style = MaterialTheme.typography.titleLarge,
        )
        AiDexSavedSensorsSection(savedSensors, !busy,
            onSelect = { serialInput = it; serialEdited = true; status = "" },
            onExport = { serial -> fullModeGuard {
                pendingExport = serial
                exportLauncher.launch("aidex_$serial.json")
            } },
            onDelete = { serial -> fullModeGuard { pendingDelete = serial } },
        )
        if (unsavedChoices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.found_devices_title), topPadding = 0.dp)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 216.dp)) {
                        items(unsavedChoices, key = { it.serial }) { sensor ->
                            ListItem(
                                headlineContent = { Text(sensor.serial) },
                                supportingContent = { Text(sensor.name.ifBlank { sensor.address }) },
                                leadingContent = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                                trailingContent = {
                                    if (normalizedSerial == sensor.serial) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                                    }
                                },
                                modifier = Modifier.selectable(
                                    selected = normalizedSerial == sensor.serial,
                                    enabled = !busy,
                                    onClick = {
                                        serialInput = sensor.serial
                                        serialEdited = true
                                        status = ""
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
        val showBloodControl = isRussian && bloodControlAvailable
        if (showBloodControl) {
            ConnectedButtonGroup(
                options = listOf(false, true),
                selectedOption = bloodControlSelected,
                onOptionSelected = { bloodControlSelected = it; status = "" },
                label = { Text(if (it) "BloodControl API" else "AideX CN") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val useBloodControl = showBloodControl && bloodControlSelected

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = serialInput,
                    onValueChange = {
                        serialInput = it.take(32)
                        serialEdited = true
                        status = ""
                    },
                    label = { Text(stringResource(R.string.serial_number_label)) },
                    singleLine = true,
                    isError = serialInput.isNotBlank() && normalizedSerial == null,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    stringResource(
                        if (keySaved) R.string.aidex_key_saved else R.string.aidex_key_missing,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (keySaved) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!keySaved && requiredSerial != null && normalizedSerial == AiDexPairingMaterialFile.normalizeSerial(requiredSerial)) {
                    Text(stringResource(R.string.aidex_keys_before_connect),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (keySaved) R.string.ottai_credentials_replace else R.string.ottai_credentials_import))
                }

                if (!keySaved) {
                    Button(
                        onClick = {
                            val serial = normalizedSerial ?: return@Button
                            busy = true
                            status = ""
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    if (useBloodControl) {
                                        val bc = BloodControlAidex.requestAndInstall(context, serial)
                                        tk.glucodata.drivers.aidex.AiDexCnResult<tk.glucodata.drivers.aidex.AiDexProvisionedKeys>(
                                            bc.keys, error = bc.error,
                                        )
                                    } else AiDexCnCloudClient.getProvisionedKeys(
                                        serial,
                                        AiDexProvisioningStore.token(context),
                                    )
                                }
                                if (result.code == 800) {
                                    AiDexProvisioningStore.clearSession(context)
                                    onSignedOut()
                                }
                                val keys = result.value
                                val saved = if (useBloodControl) result.error.isEmpty() else keys != null && withContext(Dispatchers.IO) {
                                    AiDexProvisioningStore.saveAndInstall(
                                        context,
                                        serial,
                                        keys.secret,
                                        keys.iv,
                                    )
                                }
                                busy = false
                                statusIsError = !saved
                                status = if (saved) {
                                    keyRefresh += 1
                                    ""
                                } else {
                                    result.error.ifBlank { context.getString(R.string.error) }
                                }
                            }
                        },
                        enabled = !busy && (useBloodControl || signedIn) && normalizedSerial != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (useBloodControl) "Запросить ключ" else stringResource(R.string.aidex_key_fetch_action))
                        }
                    }
                }
                if (!keySaved && !signedIn && !useBloodControl) {
                    Text(
                        stringResource(R.string.aidex_key_fetch_requires_sign_in),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connectSensor != null && onConnect != null) {
                    Button(onClick = { onConnect(connectSensor) },
                        enabled = !busy && connectionAllowedFor == connectSensor.serial,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding) {
                        Text(stringResource(R.string.ottai_connect_saved))
                    }
                }
            }
        }

        if (!useBloodControl) {
            HorizontalDivider()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.mq_account_section_credentials),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (signedIn) {
                        Text(
                            stringResource(R.string.mq_account_status_signed_in),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        AiDexProvisioningStore.accountLabel(context).takeIf(String::isNotBlank)?.let {
                            Text("+86 $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(
                            onClick = {
                                AiDexProvisioningStore.clearSession(context)
                                onSignedOut()
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) {
                            Text(stringResource(R.string.mq_account_sign_out_action))
                        }
                    } else {
                        ConnectedButtonGroup(
                            options = AiDexAccountLoginMethod.entries.toList(),
                            selectedOption = loginMethod,
                            onOptionSelected = {
                                loginMethod = it
                                status = ""
                            },
                            label = { Text(stringResource(it.labelRes)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = phone,
                            onValueChange = {
                                phone = it.filter(Char::isDigit).take(11)
                                status = ""
                            },
                            label = { Text(stringResource(R.string.ottai_phone_hint)) },
                            leadingIcon = { Text("+86") },
                            isError = phone.isNotBlank() && normalizedPhone == null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (loginMethod == AiDexAccountLoginMethod.SMS) {
                            OutlinedButton(
                                onClick = {
                                    val validPhone = normalizedPhone ?: return@OutlinedButton
                                    busy = true
                                    status = ""
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            AiDexCnCloudClient.requestLoginCode(validPhone)
                                        }
                                        busy = false
                                        statusIsError = !result.isSuccess
                                        status = if (result.isSuccess) {
                                            context.getString(
                                                R.string.ottai_code_sent_email,
                                                "+86 $validPhone",
                                            )
                                        } else {
                                            result.error
                                        }
                                    }
                                },
                                enabled = !busy && normalizedPhone != null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = ButtonDefaults.ContentPadding,
                            ) {
                                Text(stringResource(R.string.ottai_send_code))
                            }
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.filter(Char::isDigit) },
                                label = { Text(stringResource(R.string.ottai_code_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.ottai_password_hint)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Button(
                            onClick = {
                                val validPhone = normalizedPhone ?: return@Button
                                busy = true
                                status = ""
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        when (loginMethod) {
                                            AiDexAccountLoginMethod.SMS ->
                                                AiDexCnCloudClient.loginWithCode(validPhone, code)
                                            AiDexAccountLoginMethod.PASSWORD ->
                                                AiDexCnCloudClient.loginWithPassword(validPhone, password)
                                        }
                                    }
                                    val token = result.value
                                    val saved = token != null && withContext(Dispatchers.IO) {
                                        AiDexProvisioningStore.saveSession(context, validPhone, token)
                                    }
                                    busy = false
                                    if (saved) {
                                        password = ""
                                        code = ""
                                        statusIsError = false
                                        status = context.getString(R.string.mq_account_status_signed_in)
                                        onSignedIn()
                                    } else {
                                        statusIsError = true
                                        status = result.error.ifBlank {
                                            context.getString(R.string.ottai_login_fail)
                                        }
                                    }
                                }
                            },
                            enabled = !busy && normalizedPhone != null &&
                                (if (loginMethod == AiDexAccountLoginMethod.SMS) code.isNotBlank()
                                else password.isNotBlank()),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) {
                            Text(stringResource(R.string.ottai_login_button))
                        }
                    }
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

    }
}
