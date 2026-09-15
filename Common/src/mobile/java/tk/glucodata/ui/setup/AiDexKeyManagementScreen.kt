package tk.glucodata.ui.setup

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.ui.util.ConnectedButtonGroup

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
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serialInput by remember(initialSerial) { mutableStateOf(initialSerial) }
    var keySaved by remember { mutableStateOf(false) }
    var keyRefresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    val normalizedSerial = AiDexPairingMaterialFile.normalizeSerial(serialInput)

    var phone by remember { mutableStateOf(AiDexProvisioningStore.accountLabel(context)) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginMethod by remember { mutableStateOf(AiDexAccountLoginMethod.SMS) }
    val normalizedPhone = AiDexCnCloudClient.normalizeCnPhone(phone)

    LaunchedEffect(normalizedSerial, keyRefresh) {
        keySaved = normalizedSerial?.let { serial ->
            withContext(Dispatchers.IO) { AiDexProvisioningStore.hasSaved(context, serial) }
        } == true
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val serial = normalizedSerial
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
                keyRefresh += 1
                statusIsError = false
                status = context.getString(R.string.aidex_key_saved)
            } else {
                statusIsError = true
                status = context.getString(R.string.aidex_key_import_failed)
            }
        }
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
        Text(
            stringResource(R.string.aidex_key_management_desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

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

                if (keySaved) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                exportLauncher.launch("aidex_${normalizedSerial.orEmpty()}.json")
                            },
                            enabled = !busy && normalizedSerial != null,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.export))
                        }
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ottai_credentials_replace))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ottai_credentials_import))
                    }
                }

                Button(
                    onClick = {
                        val serial = normalizedSerial ?: return@Button
                        busy = true
                        status = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                AiDexCnCloudClient.getProvisionedKeys(
                                    serial,
                                    AiDexProvisioningStore.token(context),
                                )
                            }
                            if (result.code == 800) {
                                AiDexProvisioningStore.clearSession(context)
                                onSignedOut()
                            }
                            val keys = result.value
                            val saved = keys != null && withContext(Dispatchers.IO) {
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
                                context.getString(R.string.aidex_key_saved)
                            } else {
                                result.error.ifBlank { context.getString(R.string.error) }
                            }
                        }
                    },
                    enabled = !busy && signedIn && normalizedSerial != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.aidex_key_fetch_action))
                    }
                }
                if (!signedIn) {
                    Text(
                        stringResource(R.string.aidex_key_fetch_requires_sign_in),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

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
                    ) {
                        Text(stringResource(R.string.ottai_login_button))
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
        TextButton(
            onClick = onClose,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.close))
        }
    }
}
