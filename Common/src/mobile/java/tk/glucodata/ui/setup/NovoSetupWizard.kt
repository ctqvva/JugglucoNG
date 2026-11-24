package tk.glucodata.ui.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.drivers.bioe.BioeApi
import tk.glucodata.drivers.bioe.BioeApiResult
import tk.glucodata.drivers.bioe.BioeRegistry
import tk.glucodata.drivers.novo.NovoCalibration
import tk.glucodata.drivers.novo.NovoRegistry
import tk.glucodata.ui.components.AppTopBar

private const val NOVO_WIZARD_TAG = "NovoSetupWizard"

private enum class NovoSetupStep { ENTRY, SAVED }

private fun parseByte(input: String): Byte? {
    val text = input.trim().removePrefix("0x")
    if (text.length != 2) return null
    return runCatching { text.toInt(16).toByte() }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovoSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit,
) {
    val ui = rememberWizardUiMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(NovoSetupStep.ENTRY) }
    var deviceCode by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf(BioeRegistry.getApiToken(context)) }
    var kInput by remember { mutableStateOf("") }
    var bInput by remember { mutableStateOf("") }
    var passwordLowInput by remember { mutableStateOf("") }
    var passwordHighInput by remember { mutableStateOf("") }
    var useAtMs by remember { mutableStateOf(0L) }
    var serverMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<NovoCalibration?>(null) }

    val k = kInput.trim().toDoubleOrNull()
    val b = bInput.trim().toDoubleOrNull()
    val passwordLow = parseByte(passwordLowInput)
    val passwordHigh = parseByte(passwordHighInput)
    val passwordsValid =
        (passwordLowInput.isBlank() || passwordLow != null) &&
            (passwordHighInput.isBlank() || passwordHigh != null)
    val valid = NovoRegistry.isValidDeviceCode(deviceCode) &&
        k != null && k > 0.0 && k.isFinite() && b != null && b.isFinite() && passwordsValid

    fun save(calibration: NovoCalibration) {
        val sensorId = NovoRegistry.addSensor(
            context = context,
            deviceCode = deviceCode.trim(),
            calibration = calibration,
            passwordLow = passwordLow ?: 0,
            passwordHigh = passwordHigh ?: 0,
            useAtMs = useAtMs,
        )
        if (sensorId == null) {
            showError = true
            return
        }
        if (tokenInput.isNotBlank()) BioeRegistry.setApiToken(context, tokenInput.trim())
        Log.i(NOVO_WIZARD_TAG, "Saved NOVO sensor $sensorId")
        step = NovoSetupStep.SAVED
    }

    fun loadFromServer() {
        if (tokenInput.isBlank()) {
            serverMessage = context.getString(R.string.bioe_server_load_failed)
            return
        }
        isLoading = true
        serverMessage = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                BioeApi.fetchDevices(tokenInput.trim())
            }
            isLoading = false
            showError = false
            when (result) {
                is BioeApiResult.Success -> {
                    val device = BioeApi.selectDeviceExact(result.value, deviceCode)
                    if (device?.calibration == null) {
                        serverMessage = context.getString(R.string.bioe_server_no_device)
                    } else {
                        kInput = device.kRaw
                        bInput = device.bRaw
                        useAtMs = device.useAtMs
                        if (deviceCode.isBlank()) deviceCode = device.deviceCode
                        BioeRegistry.setApiToken(context, tokenInput.trim())
                        serverMessage = context.getString(R.string.bioe_server_loaded)
                    }
                }
                is BioeApiResult.HttpError -> serverMessage = context.getString(
                    R.string.bioe_server_load_failed_http,
                    context.getString(R.string.bioe_server_load_failed),
                    result.code,
                )
                is BioeApiResult.TransportError, is BioeApiResult.ProtocolError ->
                    serverMessage = context.getString(R.string.bioe_server_load_failed)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pendingSave?.let { calibration ->
            pendingSave = null
            save(calibration)
        }
    }

    fun requestPermissionsAndSave(calibration: NovoCalibration) {
        if (!hasBleScanPermissions(context)) {
            val required = requiredBleScanPermissions()
            if (required.isNotEmpty()) {
                pendingSave = calibration
                permissionLauncher.launch(required)
                return
            }
        }
        save(calibration)
    }

    BackHandler {
        if (step == NovoSetupStep.ENTRY) onDismiss() else step = NovoSetupStep.ENTRY
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.novo_sensor),
                onNavigateBack = onDismiss,
                navigationContentDescription = stringResource(R.string.cancel),
            )
        }
    ) { padding ->
        when (step) {
            NovoSetupStep.ENTRY -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ui.horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Spacer(Modifier.height(ui.spacerMedium))
                tk.glucodata.ui.CgmReadinessSetupBanner(onOpenReadiness = onNavigateToReadiness)
                Spacer(Modifier.height(ui.spacerLarge))
                Text(
                    text = stringResource(R.string.novo_sensor_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(ui.spacerMedium))
                OutlinedTextField(
                    value = deviceCode,
                    onValueChange = { input ->
                        showError = false
                        deviceCode = input.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)
                    },
                    label = { Text(stringResource(R.string.novo_device_code_label)) },
                    singleLine = true,
                    isError = showError && !NovoRegistry.isValidDeviceCode(deviceCode),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(ui.spacerMedium))
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it; serverMessage = "" },
                    label = { Text(stringResource(R.string.bioe_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(ui.spacerMedium))
                OutlinedButton(
                    onClick = ::loadFromServer,
                    enabled = tokenInput.isNotBlank() && !isLoading,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ui.buttonHeight),
                ) {
                    Text(
                        text = stringResource(
                            if (isLoading) R.string.bioe_server_loading
                            else R.string.bioe_load_from_server
                        )
                    )
                }
                Spacer(Modifier.height(ui.spacerMedium))
                OutlinedTextField(
                    value = kInput,
                    onValueChange = { showError = false; kInput = it },
                    label = { Text(stringResource(R.string.bioe_k_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = showError && (k == null || k <= 0.0 || !k.isFinite()),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(ui.spacerMedium))
                OutlinedTextField(
                    value = bInput,
                    onValueChange = { showError = false; bInput = it },
                    label = { Text(stringResource(R.string.bioe_b_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = showError && (b == null || !b.isFinite()),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(ui.spacerMedium))
                OutlinedTextField(
                    value = passwordLowInput,
                    onValueChange = { showError = false; passwordLowInput = it },
                    label = { Text(stringResource(R.string.novo_password_low_label)) },
                    singleLine = true,
                    isError = showError && !passwordsValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(ui.spacerMedium))
                OutlinedTextField(
                    value = passwordHighInput,
                    onValueChange = { showError = false; passwordHighInput = it },
                    label = { Text(stringResource(R.string.novo_password_high_label)) },
                    singleLine = true,
                    isError = showError && !passwordsValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (serverMessage.isNotBlank()) {
                    Spacer(Modifier.height(ui.spacerMedium))
                    Text(
                        text = serverMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showError) {
                    Spacer(Modifier.height(ui.spacerMedium))
                    Text(
                        text = stringResource(R.string.novo_setup_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(ui.spacerLarge))
                OutlinedButton(
                    onClick = {
                        if (!valid) {
                            showError = true
                        } else {
                            requestPermissionsAndSave(NovoCalibration(k, b))
                        }
                    },
                    enabled = valid,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ui.buttonHeight),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
            NovoSetupStep.SAVED -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = ui.horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    SensorSetupConnectingScreen(
                        ui = ui,
                        sensorLabel = NovoRegistry.canonicalSensorId(deviceCode),
                        title = stringResource(R.string.bioe_setup_saved_title),
                        supportingText = stringResource(R.string.bioe_setup_saved_message),
                    )
                }
                OutlinedButton(
                    onClick = onComplete,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ui.buttonHeight),
                ) {
                    Text(stringResource(R.string.ok))
                }
                Spacer(Modifier.height(ui.spacerLarge))
            }
        }
    }
}
