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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import tk.glucodata.RestrictedMode
import tk.glucodata.drivers.aidex.AiDexCnCloudClient
import tk.glucodata.drivers.aidex.AiDexPairingMaterialFile
import tk.glucodata.drivers.aidex.bloodcontrol.BloodControlAidex
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.AiDexSetupKeyGate
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.AdaptiveButtonRow
import androidx.compose.ui.platform.testTag

internal enum class AiDexAccountLoginMethod(val labelRes: Int) {
    SMS(R.string.ottai_login_sms),
    PASSWORD(R.string.ottai_login_password),
}

/** Wizard-owned, in-memory form state survives visiting the optional saved-sensor page. */
internal class AiDexKeyManagementState(initialSerial: String = "", accountLabel: String = "") {
    val serialInput = mutableStateOf(initialSerial)
    val serialEdited = mutableStateOf(initialSerial.isNotBlank())
    val bloodControlSelected = mutableStateOf(false)
    val phone = mutableStateOf(accountLabel)
    val code = mutableStateOf("")
    val password = mutableStateOf("")
    val loginMethod = mutableStateOf(AiDexAccountLoginMethod.SMS)
    val keyRefresh = mutableIntStateOf(0)

    fun selectSerial(serial: String) {
        serialInput.value = serial
        serialEdited.value = true
        refreshKeys()
    }

    fun refreshKeys() {
        keyRefresh.intValue++
    }
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
    onOpenSavedSensors: () -> Unit = {},
    formState: AiDexKeyManagementState? = null,
    searching: Boolean = false,
    scanError: tk.glucodata.ui.util.BleDeviceScanner.ScanStartError? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRussian = LocalConfiguration.current.locales[0]?.language == "ru"
    val form = formState ?: remember(initialSerial) {
        AiDexKeyManagementState(initialSerial, AiDexProvisioningStore.accountLabel(context))
    }
    var bloodControlSelected by form.bloodControlSelected
    var serialInput by form.serialInput
    var serialEdited by form.serialEdited
    var keySaved by remember { mutableStateOf(false) }
    var connectionAllowedFor by remember { mutableStateOf<String?>(null) }
    var savedCount by remember { mutableIntStateOf(0) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var keyRefresh by form.keyRefresh
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    val normalizedSerial = AiDexPairingMaterialFile.normalizeSerial(serialInput)
    val sensorChoices = remember(sensors) { mergeAiDexKeySensors(sensors) }
    val connectSensor = sensorChoices.firstOrNull { it.serial == normalizedSerial && it.address.isNotBlank() }
    val fullModeGuard = tk.glucodata.ui.restricted.rememberFullModeGuard()

    LaunchedEffect(sensorChoices) {
        if (!serialEdited && serialInput.isBlank()) {
            val preferred = sensorChoices.firstOrNull { it.connected } ?: sensorChoices.singleOrNull()
            if (preferred != null) serialInput = preferred.serial
        }
    }

    var phone by form.phone
    var code by form.code
    var password by form.password
    var loginMethod by form.loginMethod
    val normalizedPhone = AiDexCnCloudClient.normalizeCnPhone(phone)

    LaunchedEffect(normalizedSerial, keyRefresh, sensorChoices) {
        connectionAllowedFor = null
        keySaved = normalizedSerial?.let { serial ->
            withContext(Dispatchers.IO) { AiDexProvisioningStore.hasSaved(context, serial) }
        } == true
        connectionAllowedFor = connectSensor?.takeIf { sensor ->
            withContext(Dispatchers.IO) { AiDexSetupKeyGate.mayConnect(context, sensor.serial, sensor.name) }
        }?.serial
        savedCount = withContext(Dispatchers.IO) {
            loadAiDexSavedKeySensors(context, sensorChoices, listOfNotNull(normalizedSerial)).size
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val serial = pendingExport
        pendingExport = null
        if (uri != null && serial != null && !RestrictedMode.isLocked()) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                AiDexKeyDocuments.exportKeys(context, serial, uri)
            }
            if (RestrictedMode.isLocked()) return@launch
            statusIsError = !saved
            status = context.getString(
                if (saved) R.string.ottai_save_ok else R.string.ottai_save_nothing,
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && !RestrictedMode.isLocked()) scope.launch {
            val importedSerial = withContext(Dispatchers.IO) {
                AiDexKeyDocuments.importKeys(context, uri)
            }
            if (RestrictedMode.isLocked()) return@launch
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

    Column(Modifier.fillMaxSize()) {
        if (searching) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth().testTag("aidex-key-scan-progress"))
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
            SettingsItem(
                title = stringResource(R.string.ottai_saved_sensors_title),
                subtitle = ottaiSavedSensorsSummary(savedCount),
                icon = Icons.Default.Key,
                onClick = if (busy) null else ({ fullModeGuard { onOpenSavedSensors() } }),
            )
            if (searching) {
                Text(stringResource(R.string.aidex_searching_sensors), style = MaterialTheme.typography.bodyMedium)
            } else if (scanError != null) {
                val message = when (scanError) {
                    tk.glucodata.ui.util.BleDeviceScanner.ScanStartError.NoPermission ->
                        if (android.os.Build.VERSION.SDK_INT >= 31) R.string.turn_on_nearby_devices_permission
                        else R.string.turn_on_location_permission
                    tk.glucodata.ui.util.BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.bluetooth_is_turned_off
                    else -> R.string.nobluetooth
                }
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            if (sensorChoices.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.found_devices_title), topPadding = 0.dp)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 216.dp)) {
                            items(sensorChoices, key = { it.serial }) { sensor ->
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

                    if (keySaved) {
                        AdaptiveButtonRow(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { fullModeGuard {
                                    pendingExport = normalizedSerial
                                    exportLauncher.launch("aidex_${normalizedSerial.orEmpty()}.json")
                                } },
                                enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp),
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = ButtonDefaults.ContentPadding,
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.export))
                            }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                                enabled = !busy,
                                modifier = Modifier.heightIn(min = 48.dp),
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = ButtonDefaults.ContentPadding,
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.ottai_credentials_replace))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ContentPadding,
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ottai_credentials_import))
                        }
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
}
