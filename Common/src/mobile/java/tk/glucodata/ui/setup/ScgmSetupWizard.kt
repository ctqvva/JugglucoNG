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
import androidx.compose.foundation.text.KeyboardOptions
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
import tk.glucodata.drivers.scgm.ScgmCalibration
import tk.glucodata.drivers.scgm.ScgmConstants
import tk.glucodata.drivers.scgm.ScgmRegistry
import tk.glucodata.ui.components.AppTopBar

private const val SCGM_WIZARD_TAG = "ScgmSetupWizard"

private enum class ScgmSetupStep { ENTRY, SAVED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScgmSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit,
) {
    val ui = rememberWizardUiMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(ScgmSetupStep.ENTRY) }
    var deviceCode by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf(ScgmRegistry.getApiToken(context)) }
    var kInput by remember { mutableStateOf("") }
    var bInput by remember { mutableStateOf("") }
    var thresholdInput by remember { mutableStateOf("") }
    var useAtMs by remember { mutableStateOf(0L) }
    var serverMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<ScgmCalibration?>(null) }

    val k = kInput.trim().toDoubleOrNull()
    val b = bInput.trim().toDoubleOrNull()
    val threshold = thresholdInput.trim().toDoubleOrNull() ?: ScgmConstants.DEFAULT_LOW_CURRENT_NA
    val thresholdValid = thresholdInput.isBlank() || (threshold.isFinite() && threshold >= 0.0)
    val valid = ScgmRegistry.isValidDeviceCode(deviceCode) &&
        k != null && k > 0.0 && k.isFinite() && b != null && b.isFinite() && thresholdValid

    fun save(calibration: ScgmCalibration) {
        val sensorId = ScgmRegistry.addSensor(
            context = context,
            deviceCode = deviceCode.trim(),
            calibration = calibration,
            useAtMs = useAtMs,
        )
        if (sensorId == null) {
            showError = true
            return
        }
        if (tokenInput.isNotBlank()) ScgmRegistry.setApiToken(context, tokenInput.trim())
        Log.i(SCGM_WIZARD_TAG, "Saved SCGM sensor $sensorId")
        step = ScgmSetupStep.SAVED
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
                    val device = BioeApi.selectDevice(result.value, deviceCode)
                    if (device?.calibration == null) {
                        serverMessage = context.getString(R.string.bioe_server_no_device)
                    } else {
                        kInput = device.kRaw
                        bInput = device.bRaw
                        thresholdInput = device.thresholdRaw
                        useAtMs = device.useAtMs
                        if (deviceCode.isBlank()) deviceCode = device.deviceCode
                        ScgmRegistry.setApiToken(context, tokenInput.trim())
                        serverMessage = context.getString(R.string.bioe_server_loaded)
                    }
                }
                is BioeApiResult.HttpError -> serverMessage =
                    context.getString(R.string.bioe_server_load_failed) + " (HTTP ${result.code})"
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

    fun requestPermissionsAndSave(calibration: ScgmCalibration) {
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
        if (step == ScgmSetupStep.ENTRY) onDismiss() else step = ScgmSetupStep.ENTRY
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.scgm_sensor),
                onNavigateBack = onDismiss,
                navigationContentDescription = stringResource(R.string.cancel),
            )
        }
    ) { padding ->
        when (step) {
            ScgmSetupStep.ENTRY -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = ui.horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                Spacer(Modifier.height(ui.spacerMedium))
                tk.glucodata.ui.CgmReadinessSetupBanner(onOpenReadiness = onNavigateToReadiness)
                Spacer(Modifier.height(ui.spacerLarge))
                Text(
                    text = stringResource(R.string.scgm_sensor_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(ui.spacerMedium))
                OutlinedTextField(
                    value = deviceCode,
                    onValueChange = { input ->
                        showError = false
                        deviceCode = input.filter { it.isDigit() || it.lowercase() in "abcdef" }.take(40)
                    },
                    label = { Text(stringResource(R.string.scgm_device_code_label)) },
                    singleLine = true,
                    isError = showError && !ScgmRegistry.isValidDeviceCode(deviceCode),
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
                    value = thresholdInput,
                    onValueChange = { showError = false; thresholdInput = it },
                    label = { Text(stringResource(R.string.bioe_threshold_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = showError && !thresholdValid,
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
                        text = stringResource(R.string.scgm_setup_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(ui.spacerLarge))
                OutlinedButton(
                    onClick = {
                        if (!valid || k == null || b == null) {
                            showError = true
                        } else {
                            requestPermissionsAndSave(ScgmCalibration(k, b, threshold))
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
            ScgmSetupStep.SAVED -> Column(
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
                        sensorLabel = ScgmRegistry.canonicalSensorId(deviceCode),
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
