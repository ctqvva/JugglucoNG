// JugglucoNG — Anytime / Yuwell CT3 Setup Wizard
//
// BLE-scan onboarding: scan for advertisers matching the Anytime device-name
// catalog (SN16, SN26..98, etc.) or carrying the proprietary 0x1000 service
// UUID, let the user optionally scan/enter the calibration QR sticker, then
// register the sensor via AnytimeRegistry and kick off a connect.
//
// Mirrors MQSetupWizard but without an account / follower / vendor bootstrap
// layer — Anytime CGM works fully offline once the QR code is captured.

package tk.glucodata.ui.setup

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.drivers.anytime.AnytimeAlgorithm
import tk.glucodata.drivers.anytime.AnytimeConstants
import tk.glucodata.drivers.anytime.AnytimeRegistry
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.rememberBleScanner

private enum class AnytimeSetupStep { SCAN, CONNECTING, SUCCESS }

private data class AnytimeScanCandidate(
    val address: String,
    val displayName: String,
    val isLikelyAnytime: Boolean,
    val advertisesPrimaryService: Boolean,
    val familyEntry: AnytimeConstants.FamilyEntry,
)

private data class AnytimeQrSetupValidation(
    val normalizedQr: String?,
    val error: String?,
    val summary: String?,
) {
    val isAllowed: Boolean get() = error == null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnytimeSetupWizard(
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val tag = "AnytimeSetupWizard"
    val ui = rememberWizardUiMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentStep by remember { mutableStateOf(AnytimeSetupStep.SCAN) }
    var selectedLabel by remember { mutableStateOf("") }
    var qrCodeContent by remember { mutableStateOf("") }
    var showManualQrEntry by remember { mutableStateOf(false) }
    val qrValidation = validateAnytimeSetupQr(qrCodeContent)

    if (showManualQrEntry) {
        AnytimeManualQrEntryDialog(
            initialValue = qrCodeContent,
            onDismiss = { showManualQrEntry = false },
            onConfirm = { code ->
                qrCodeContent = code
                showManualQrEntry = false
            },
        )
    }

    BackHandler {
        when (currentStep) {
            AnytimeSetupStep.SCAN -> onDismiss()
            else -> currentStep = AnytimeSetupStep.SCAN
        }
    }

    LaunchedEffect(currentStep) {
        if (currentStep == AnytimeSetupStep.SUCCESS) {
            delay(SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS)
            onComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.anytime_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentStep,
            modifier = Modifier.padding(padding),
            label = "AnytimeWizard"
        ) { step ->
            when (step) {
                AnytimeSetupStep.SCAN -> AnytimeScanStep(
                    ui = ui,
                    qrCodeContent = qrCodeContent,
                    qrValidation = qrValidation,
                    onQrCodeChanged = { qrCodeContent = normalizeAnytimeQrCode(it) },
                    onShowManualQrEntry = { showManualQrEntry = true },
                    onDeviceSelected = { candidate ->
                        if (!qrValidation.isAllowed) {
                            Toast.makeText(
                                context,
                                qrValidation.error ?: "Invalid Anytime QR code",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@AnytimeScanStep
                        }
                        selectedLabel = candidate.displayName.ifBlank { candidate.address }
                        currentStep = AnytimeSetupStep.CONNECTING
                        scope.launch {
                            try {
                                val normalizedQr = qrValidation.normalizedQr
                                val sensorId = AnytimeRegistry.addSensor(
                                    context = context,
                                    displayName = candidate.displayName.ifBlank { null },
                                    address = candidate.address,
                                    deviceName = candidate.displayName.ifBlank { null },
                                    qrCodeContent = normalizedQr,
                                    connectNow = false,
                                )
                                if (sensorId == null) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.nobluetooth),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    currentStep = AnytimeSetupStep.SCAN
                                    return@launch
                                }
                                AnytimeRegistry.connectSensor(context, sensorId)
                                delay(2000)
                                currentStep = AnytimeSetupStep.SUCCESS
                            } catch (t: Throwable) {
                                Log.e(tag, "Failed to add Anytime sensor: ${t.message}")
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.nobluetooth),
                                    Toast.LENGTH_LONG
                                ).show()
                                currentStep = AnytimeSetupStep.SCAN
                            }
                        }
                    }
                )
                AnytimeSetupStep.CONNECTING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SensorSetupConnectingScreen(
                        ui = ui,
                        sensorLabel = selectedLabel.ifBlank { null }
                    )
                }
                AnytimeSetupStep.SUCCESS -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SensorSetupSuccessScreen(
                        ui = ui,
                        sensorLabel = selectedLabel.ifBlank { null }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnytimeScanStep(
    ui: WizardUiMetrics,
    qrCodeContent: String,
    qrValidation: AnytimeQrSetupValidation,
    onQrCodeChanged: (String) -> Unit,
    onShowManualQrEntry: () -> Unit,
    onDeviceSelected: (AnytimeScanCandidate) -> Unit,
) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<AnytimeScanCandidate>>(emptyList()) }
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

    DisposableEffect(scanPermissionGranted, bluetoothEnabled, scanRetryKey, showAllDevices) {
        if (!scanPermissionGranted || !bluetoothEnabled) {
            scanner.stopScan()
            devices = emptyList()
            return@DisposableEffect onDispose { scanner.stopScan() }
        }

        devices = emptyList()
        scanner.startScan(
            onResult = { result ->
                val device = result.device
                val address = try {
                    device.address
                } catch (_: SecurityException) {
                    null
                } ?: return@startScan

                val record = result.scanRecord
                val scanName = try {
                    device.name
                } catch (_: SecurityException) {
                    null
                }
                val nameCandidates = listOfNotNull(scanName, record?.deviceName)
                    .mapNotNull { it.trim().takeIf(String::isNotBlank) }

                val bestName = nameCandidates.firstOrNull().orEmpty()
                val advertisesPrimary =
                    record?.serviceUuids?.any {
                        it.uuid == AnytimeConstants.SERVICE_PRIMARY ||
                                it.uuid == AnytimeConstants.SERVICE_LEGACY_CT2
                    } == true
                val nameLooksAnytime = nameCandidates.any(AnytimeConstants::isAnytimeDevice)
                val isLikelyAnytime = advertisesPrimary || nameLooksAnytime
                val familyEntry = AnytimeConstants.resolveFamily(bestName)

                if (!showAllDevices && !isLikelyAnytime) return@startScan

                if (devices.none { it.address.equals(address, ignoreCase = true) }) {
                    devices = devices + AnytimeScanCandidate(
                        address = address,
                        displayName = bestName,
                        isLikelyAnytime = isLikelyAnytime,
                        advertisesPrimaryService = advertisesPrimary,
                        familyEntry = familyEntry,
                    )
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ui.horizontalPadding,
                end = ui.horizontalPadding,
                top = ui.spacerMedium,
                bottom = ui.spacerLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(ui.spacerMedium),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.anytime_searching_sensors),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { showAllDevices = !showAllDevices }) {
                        Text(
                            if (showAllDevices) stringResource(R.string.show_sensors_only)
                            else stringResource(R.string.see_all_devices)
                        )
                    }
                }
            }
            items(devices) { device ->
                if (!showAllDevices && !device.isLikelyAnytime) return@items
                val title = device.displayName.ifBlank { stringResource(R.string.unknown) }
                val supporting = when {
                    device.advertisesPrimaryService ->
                        stringResource(R.string.anytime_detected_label, device.address)
                    device.isLikelyAnytime ->
                        "${device.familyEntry.family.displayName} · ${device.address}"
                    else -> stringResource(R.string.anytime_selectable_unrecognized, device.address)
                }
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text(supporting) },
                    leadingContent = { Icon(Icons.Default.Bluetooth, null) },
                    modifier = Modifier.clickable { onDeviceSelected(device) }
                )
                HorizontalDivider()
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        InlineQrScannerCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            onScanResult = onQrCodeChanged,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (qrCodeContent.isNotBlank()) {
                            val qrStatus = qrValidation.error ?: qrValidation.summary
                            Text(
                                text = "${stringResource(R.string.scan_sensor_qr)}: $qrCodeContent",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (!qrStatus.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = qrStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (qrValidation.error == null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedButton(
                            onClick = onShowManualQrEntry,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.enter_code_manually))
                        }
                    }
                }
            }

            if (!scanPermissionGranted || !bluetoothEnabled || scanError != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.anytime_no_sensors_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnytimeManualQrEntryDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_code_manually)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = normalizeAnytimeQrCode(it) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(normalizeAnytimeQrCode(text)) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun normalizeAnytimeQrCode(raw: String): String =
    raw.trim().uppercase().filter { it.isLetterOrDigit() }

private fun validateAnytimeSetupQr(raw: String): AnytimeQrSetupValidation {
    val normalized = normalizeAnytimeQrCode(raw)
    if (normalized.isBlank()) {
        // QR is optional: allow setup without it. The driver will run in raw/linear
        // mode until a valid calibration QR is provided later.
        return AnytimeQrSetupValidation(
            normalizedQr = null,
            error = null,
            summary = null,
        )
    }

    val parsed = AnytimeAlgorithm.decodeQr(normalized)
    if (parsed == null) {
        return AnytimeQrSetupValidation(
            normalizedQr = null,
            error = "This QR code is not a valid Anytime calibration code. Clear it to continue without QR, or scan the sensor calibration QR.",
            summary = null,
        )
    }
    if (!parsed.isFactoryCalibration) {
        return AnytimeQrSetupValidation(
            normalizedQr = null,
            error = "This looks like a product/UDI label, not the calibration QR. Clear it to continue without QR, or scan the sensor calibration QR.",
            summary = null,
        )
    }
    return AnytimeQrSetupValidation(
        normalizedQr = normalized,
        error = null,
        summary = "Calibration QR OK · K=${"%.2f".format(parsed.k)} R=${"%.1f".format(parsed.r)} · ${parsed.lifeTime}d",
    )
}
