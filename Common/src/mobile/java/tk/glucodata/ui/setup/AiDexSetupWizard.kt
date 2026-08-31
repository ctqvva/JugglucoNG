package tk.glucodata.ui.setup

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.drivers.aidex.AiDexCnCloudClient
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.AiDexSerialIdentity
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.rememberBleScanner
import java.util.UUID

enum class AiDexSetupStep {
    SCAN,
    LOGIN,
    CONNECTING,
    SUCCESS
}

private enum class AiDexLoginMethod(val labelRes: Int) {
    SMS(R.string.ottai_login_sms),
    PASSWORD(R.string.ottai_login_password),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDexSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit
) {
    val tag = "AiDexSetupWizard"
    val ui = rememberWizardUiMetrics()
    var currentStep by remember { mutableStateOf(AiDexSetupStep.SCAN) }
    var selectedDeviceName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var signedIn by remember { mutableStateOf(AiDexProvisioningStore.hasSession(context)) }
    val navigateBack = {
        if (currentStep == AiDexSetupStep.SCAN) onDismiss() else currentStep = AiDexSetupStep.SCAN
    }
    BackHandler {
        navigateBack()
    }

    LaunchedEffect(currentStep) {
        if (currentStep == AiDexSetupStep.SUCCESS) {
            delay(SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS)
            onComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aidex_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentStep,
            modifier = Modifier.padding(padding),
            label = "AiDexWizard"
        ) { step ->
            when (step) {
                AiDexSetupStep.SCAN -> AiDexScanStep(
                    ui = ui,
                    signedIn = signedIn,
                    onNavigateToReadiness = onNavigateToReadiness,
                    onSignIn = { currentStep = AiDexSetupStep.LOGIN },
                    onDeviceSelected = { selectedName, address, isFGeneration ->
                        try {
                            val name = selectedName.trim()
                            if (name.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.aidex_parse_error, selectedName),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@AiDexScanStep
                            }

                            selectedDeviceName = name
                            currentStep = AiDexSetupStep.CONNECTING

                            // Initiate Connection Logic
                            scope.launch {
                                try {
                                    if (isFGeneration &&
                                        !AiDexProvisioningStore.installSaved(context, name) &&
                                        AiDexProvisioningStore.hasSession(context)
                                    ) {
                                        val result = withContext(Dispatchers.IO) {
                                            AiDexCnCloudClient.getProvisionedKeys(
                                                name,
                                                AiDexProvisioningStore.token(context),
                                            )
                                        }
                                        if (result.code == 800) {
                                            AiDexProvisioningStore.clearSession(context)
                                            signedIn = false
                                        }
                                        val keys = result.value
                                        if (keys == null || !withContext(Dispatchers.IO) {
                                                AiDexProvisioningStore.saveAndInstall(
                                                    context,
                                                    name,
                                                    keys.secret,
                                                    keys.iv,
                                                )
                                            }
                                        ) {
                                            val detail = result.error.takeIf(String::isNotBlank)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.error) +
                                                    detail?.let { ": $it" }.orEmpty(),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                            currentStep = AiDexSetupStep.SCAN
                                            return@launch
                                        }
                                    }

                                    // Add only after any required F-generation material is ready.
                                    SensorBluetooth.addAiDexSensor(context, name, address)

                                    // 2. Wait a bit then show success
                                    kotlinx.coroutines.delay(2000)
                                    currentStep = AiDexSetupStep.SUCCESS
                                } catch (t: Throwable) {
                                    Log.e(tag, "Failed to add/select AiDex sensor: ${t.message}")
                                    Toast.makeText(context, context.getString(R.string.nobluetooth), Toast.LENGTH_LONG).show()
                                    currentStep = AiDexSetupStep.SCAN
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e(tag, "onDeviceSelected failed: ${t.message}")
                            Toast.makeText(context, context.getString(R.string.nobluetooth), Toast.LENGTH_LONG).show()
                            currentStep = AiDexSetupStep.SCAN
                        }
                    }
                )
                AiDexSetupStep.LOGIN -> AiDexLoginStep(
                    ui = ui,
                    signedIn = signedIn,
                    onSignedIn = {
                        signedIn = true
                        currentStep = AiDexSetupStep.SCAN
                    },
                    onSignedOut = { signedIn = false },
                    onClose = { currentStep = AiDexSetupStep.SCAN },
                )
                AiDexSetupStep.CONNECTING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SensorSetupConnectingScreen(
                        ui = ui,
                        sensorLabel = selectedDeviceName.ifBlank { null }
                    )
                }
                AiDexSetupStep.SUCCESS -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SensorSetupSuccessScreen(
                        ui = ui,
                        sensorLabel = selectedDeviceName.ifBlank { null }
                    )
                }
            }
        }
    }
}

@Composable
fun AiDexScanStep(
    ui: WizardUiMetrics,
    signedIn: Boolean,
    onNavigateToReadiness: () -> Unit,
    onSignIn: () -> Unit,
    onDeviceSelected: (String, String, Boolean) -> Unit
) {
    data class ScanCandidate(
        val address: String,
        val rawName: String,
        val selectionName: String,
        val serial: String?,
        val isFGeneration: Boolean,
        val isLikelyAiDex: Boolean,
        val detectedViaFf30: Boolean,
    )

    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<ScanCandidate>>(emptyList()) }
    val scanner = rememberBleScanner()
    var scanPermissionGranted by remember { mutableStateOf(hasBleScanPermissions(context)) }
    var bluetoothEnabled by remember { mutableStateOf(scanner.isBluetoothEnabled()) }
    var scanRetryKey by remember { mutableStateOf(0) }
    var scanError by remember { mutableStateOf<BleDeviceScanner.ScanStartError?>(null) }
    var requestedPermissionOnce by remember { mutableStateOf(false) }
    var showAllDevices by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scanPermissionGranted = hasBleScanPermissions(context)
        bluetoothEnabled = scanner.isBluetoothEnabled()
        scanError = null
        scanRetryKey += 1
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        bluetoothEnabled = scanner.isBluetoothEnabled()
        scanError = null
        scanRetryKey += 1
    }

    val requestScanPermission = {
        val required = requiredBleScanPermissions()
        if (required.isEmpty()) {
            scanPermissionGranted = true
            scanRetryKey += 1
        } else {
            permissionLauncher.launch(required)
        }
    }

    LaunchedEffect(Unit) {
        if (!scanPermissionGranted && !requestedPermissionOnce) {
            requestedPermissionOnce = true
            requestScanPermission()
        }
    }

    // Start Scanning Effect
    DisposableEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey, showAllDevices) {
        if (!scanPermissionGranted || !bluetoothEnabled) {
            scanner.stopScan()
            return@DisposableEffect onDispose { scanner.stopScan() }
        }

        scanner.startScan(
            onResult = { result ->
                val device = result.device
                val address = try {
                    device.address
                } catch (_: SecurityException) {
                    null
                } ?: return@startScan
                val record = result.scanRecord
                val candidate = detectAiDexCandidate(
                    address = address,
                    deviceName = try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    },
                    scanRecordName = record?.deviceName,
                    scanRecordBytes = record?.bytes,
                    advertisedServiceUuids = record?.serviceUuids?.map { it.uuid }
                )

                if (!showAllDevices && !candidate.isLikelyAiDex) return@startScan
                val next = ScanCandidate(
                    address = address,
                    rawName = candidate.displayName,
                    selectionName = candidate.selectionName,
                    serial = candidate.serial,
                    isFGeneration = candidate.isFGeneration,
                    isLikelyAiDex = candidate.isLikelyAiDex,
                    detectedViaFf30 = candidate.detectedViaFf30,
                )
                val existing = devices.firstOrNull { it.address == address }
                devices = if (existing == null) {
                    devices + next
                } else {
                    val preferNextIdentity = next.isFGeneration && !existing.isFGeneration ||
                        next.serial != null && existing.serial == null
                    devices.map { current ->
                        if (current.address != address) current else current.copy(
                            rawName = if (preferNextIdentity) next.rawName else current.rawName,
                            selectionName = if (preferNextIdentity) next.selectionName else current.selectionName,
                            serial = if (preferNextIdentity) next.serial else current.serial,
                            isFGeneration = current.isFGeneration || next.isFGeneration,
                            isLikelyAiDex = current.isLikelyAiDex || next.isLikelyAiDex,
                            detectedViaFf30 = current.detectedViaFf30 || next.detectedViaFf30,
                        )
                    }
                }
            },
            onError = { error ->
                scanError = error
                when (error) {
                    BleDeviceScanner.ScanStartError.NoPermission -> scanPermissionGranted = false
                    BleDeviceScanner.ScanStartError.BluetoothDisabled -> bluetoothEnabled = false
                    else -> Unit
                }
            }
        )
        onDispose { scanner.stopScan() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        tk.glucodata.ui.CgmReadinessSetupBanner(
            modifier = Modifier.padding(horizontal = ui.horizontalPadding, vertical = ui.spacerMedium),
            onOpenReadiness = onNavigateToReadiness
        )
        Spacer(Modifier.height(ui.spacerMedium))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ui.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.aidex_searching_sensors),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(
                onClick = { showAllDevices = !showAllDevices }
            ) {
                Text(
                    if (showAllDevices) {
                        stringResource(R.string.show_sensors_only)
                    } else {
                        stringResource(R.string.see_all_devices)
                    }
                )
            }
        }
        if (!scanPermissionGranted || !bluetoothEnabled || scanError != null) {
            Spacer(Modifier.height(ui.spacerMedium))
            Card(
                modifier = Modifier
                    .padding(horizontal = ui.horizontalPadding)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val messageRes = when {
                        !scanPermissionGranted && Build.VERSION.SDK_INT >= 31 -> R.string.turn_on_nearby_devices_permission
                        !scanPermissionGranted -> R.string.turn_on_location_permission
                        !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.bluetooth_is_turned_off
                        else -> R.string.nobluetooth
                    }
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(ui.spacerMedium))
                    val buttonRes = when {
                        !scanPermissionGranted -> R.string.permission
                        !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> R.string.enable_bluetooth
                        else -> R.string.search_bluetooth
                    }
                    Button(
                        onClick = {
                            when {
                                !scanPermissionGranted -> requestScanPermission()
                                !bluetoothEnabled || scanError is BleDeviceScanner.ScanStartError.BluetoothDisabled -> {
                                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                }
                                else -> {
                                    scanError = null
                                    scanPermissionGranted = hasBleScanPermissions(context)
                                    bluetoothEnabled = scanner.isBluetoothEnabled()
                                    scanRetryKey += 1
                                }
                            }
                        },
                        modifier = Modifier.height(ui.buttonHeight)
                    ) {
                        Text(stringResource(buttonRes))
                    }
                }
            }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { device ->
                val name = device.rawName.ifBlank { stringResource(R.string.unknown) }
                val serial = device.serial

                // If we're in "sensors only" mode, skip non-matching devices.
                if (!showAllDevices && !device.isLikelyAiDex) return@items

                val canSelect = device.isLikelyAiDex || showAllDevices

                ListItem(
                    headlineContent = {
                        Text(
                            if (serial != null) "$name ($serial)" else name
                        )
                    },
                    supportingContent = {
                        Text(
                            when {
                                serial != null -> device.address
                                device.detectedViaFf30 -> stringResource(R.string.aidex_detected_via_ff30, device.address)
                                device.isLikelyAiDex -> stringResource(R.string.aidex_selectable_unrecognized, device.address)
                                else -> stringResource(R.string.aidex_not_recognized, device.address)
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Bluetooth, null) },
                    modifier = Modifier.clickable(enabled = canSelect) {
                        onDeviceSelected(
                            device.selectionName,
                            device.address,
                            device.isFGeneration,
                        )
                    }
                )
                HorizontalDivider()
            }
        }
        TextButton(
            onClick = onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = ui.horizontalPadding),
        ) {
            Text(
                stringResource(
                    if (signedIn) R.string.mq_account_status_signed_in
                    else R.string.mq_account_sign_in_action,
                ),
            )
        }
    }
}

@Composable
private fun AiDexLoginStep(
    ui: WizardUiMetrics,
    signedIn: Boolean,
    onSignedIn: () -> Unit,
    onSignedOut: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf(AiDexProvisioningStore.accountLabel(context)) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(AiDexLoginMethod.SMS) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }
    val normalizedPhone = AiDexCnCloudClient.normalizeCnPhone(phone)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ui.horizontalPadding, vertical = ui.spacerMedium),
        verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
    ) {
        Text(stringResource(R.string.aidex_setup_title), style = MaterialTheme.typography.titleLarge)

        if (signedIn) {
            Text(
                stringResource(R.string.mq_account_status_signed_in),
                style = MaterialTheme.typography.titleMedium,
            )
            AiDexProvisioningStore.accountLabel(context).takeIf(String::isNotBlank)?.let {
                Text("+86 $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(
                onClick = {
                    AiDexProvisioningStore.clearSession(context)
                    onSignedOut()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.mq_account_sign_out_action))
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.close))
            }
            return@Column
        }

        ConnectedButtonGroup(
            options = AiDexLoginMethod.entries.toList(),
            selectedOption = method,
            onOptionSelected = {
                method = it
                status = ""
                statusIsError = false
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
            modifier = Modifier.fillMaxWidth(),
        )

        if (method == AiDexLoginMethod.SMS) {
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
                            context.getString(R.string.ottai_code_sent_email, "+86 $validPhone")
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
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = {
                val validPhone = normalizedPhone ?: return@Button
                busy = true
                status = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        when (method) {
                            AiDexLoginMethod.SMS -> AiDexCnCloudClient.loginWithCode(validPhone, code)
                            AiDexLoginMethod.PASSWORD -> AiDexCnCloudClient.loginWithPassword(validPhone, password)
                        }
                    }
                    busy = false
                    val token = result.value
                    if (token != null && withContext(Dispatchers.IO) {
                            AiDexProvisioningStore.saveSession(context, validPhone, token)
                        }
                    ) {
                        password = ""
                        code = ""
                        onSignedIn()
                    } else {
                        statusIsError = true
                        status = result.error.ifBlank { context.getString(R.string.ottai_login_fail) }
                    }
                }
            },
            enabled = !busy && normalizedPhone != null &&
                (if (method == AiDexLoginMethod.SMS) code.isNotBlank() else password.isNotBlank()),
            modifier = Modifier.fillMaxWidth().heightIn(min = ui.buttonHeight),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.ottai_login_button))
            }
        }
    }
}

private data class AiDexScanDetection(
    val displayName: String,
    val selectionName: String,
    val serial: String?,
    val isFGeneration: Boolean,
    val isLikelyAiDex: Boolean,
    val detectedViaFf30: Boolean,
)

private val AIDEX_CGM_SERVICE_UUID: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
private val AIDEX_VENDOR_SERVICE_UUID: UUID = UUID.fromString("0000f000-0000-1000-8000-00805f9b34fb")
private val AIDEX_FF30_SERVICE_UUID: UUID = UUID.fromString("0000ff30-0000-1000-8000-00805f9b34fb")

private fun detectAiDexCandidate(
    address: String,
    deviceName: String?,
    scanRecordName: String?,
    scanRecordBytes: ByteArray?,
    advertisedServiceUuids: List<UUID>?,
): AiDexScanDetection {
    val localName = extractAiDexLocalName(scanRecordBytes)
    val names = linkedSetOf<String>()
    listOf(deviceName, scanRecordName, localName)
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .forEach { names.add(it) }

    val fGenerationName = names.firstOrNull(AiDexSerialIdentity::isFGenerationAdvertisement)
    val serial = fGenerationName?.let(AiDexSerialIdentity::canonicalFromAdvertisement)
        ?: names.firstNotNullOfOrNull(AiDexSerialIdentity::canonicalFromAdvertisement)
    val nameLooksAiDex = names.any(::looksLikeAiDexFamilyName)
    val hasFf30 = advertisedServiceUuids?.contains(AIDEX_FF30_SERVICE_UUID) == true ||
        scanRecordAdvertises16BitService(scanRecordBytes, 0xFF30)
    val hasPrimaryServiceHint =
        advertisedServiceUuids?.any { it == AIDEX_CGM_SERVICE_UUID || it == AIDEX_VENDOR_SERVICE_UUID } == true ||
            scanRecordAdvertises16BitService(scanRecordBytes, 0x181F) ||
            scanRecordAdvertises16BitService(scanRecordBytes, 0xF000)
    val isLikelyAiDex = serial != null || nameLooksAiDex || hasFf30 || hasPrimaryServiceHint
    val displayName = fGenerationName ?: names.firstOrNull() ?: address
    val selectionName = serial ?: AiDexSerialIdentity.fallbackCanonicalFromAddress(address)
    return AiDexScanDetection(
        displayName = displayName,
        selectionName = selectionName,
        serial = serial,
        isFGeneration = fGenerationName != null,
        isLikelyAiDex = isLikelyAiDex,
        detectedViaFf30 = hasFf30,
    )
}

private fun looksLikeAiDexFamilyName(rawName: String): Boolean {
    val lowered = rawName.lowercase()
    return lowered.contains("aidex") ||
        lowered.contains("linx") ||
        lowered.contains("lumi") ||
        lowered.contains("vista")
}

private fun extractAiDexLocalName(scanRecord: ByteArray?): String? {
    if (scanRecord == null) return null
    var offset = 0
    while (offset < scanRecord.size - 1) {
        val len = scanRecord[offset].toInt() and 0xFF
        if (len == 0) break
        val next = offset + len + 1
        if (next > scanRecord.size) break
        val type = scanRecord[offset + 1].toInt() and 0xFF
        if (type == 0x08 || type == 0x09) {
            val start = offset + 2
            if (next > start) {
                return try {
                    String(scanRecord, start, next - start, Charsets.UTF_8)
                } catch (_: Throwable) {
                    null
                }
            }
        }
        offset = next
    }
    return null
}

private fun scanRecordAdvertises16BitService(scanRecord: ByteArray?, serviceShortUuid: Int): Boolean {
    if (scanRecord == null) return false
    var offset = 0
    while (offset < scanRecord.size - 1) {
        val len = scanRecord[offset].toInt() and 0xFF
        if (len == 0) break
        val next = offset + len + 1
        if (next > scanRecord.size) break
        val type = scanRecord[offset + 1].toInt() and 0xFF
        if (type == 0x02 || type == 0x03) {
            var uuidOffset = offset + 2
            while (uuidOffset + 1 < next) {
                val uuid = (scanRecord[uuidOffset].toInt() and 0xFF) or
                    ((scanRecord[uuidOffset + 1].toInt() and 0xFF) shl 8)
                if (uuid == serviceShortUuid) return true
                uuidOffset += 2
            }
        }
        offset = next
    }
    return false
}

internal fun requiredBleScanPermissions(): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        Build.VERSION.SDK_INT >= 23 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }
}

internal fun hasBleScanPermissions(context: Context): Boolean {
    return requiredBleScanPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
