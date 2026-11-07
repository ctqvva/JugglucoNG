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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.drivers.aidex.AiDexSetupKeyGate
import tk.glucodata.drivers.aidex.AiDexPairingMaterialFile
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.AiDexSerialIdentity
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.rememberBleScanner
import java.util.UUID

enum class AiDexSetupStep {
    SCAN,
    KEY_MANAGEMENT,
    SAVED_SENSORS,
    CONNECTING,
    SUCCESS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDexSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit
) {
    val fullModeGuard = tk.glucodata.ui.restricted.rememberFullModeGuard()
    val restrictedLocked = tk.glucodata.RestrictedMode.state.collectAsStateWithLifecycle().value.locked
    val tag = "AiDexSetupWizard"
    val ui = rememberWizardUiMetrics()
    var currentStep by remember { mutableStateOf(AiDexSetupStep.SCAN) }
    // Restricted again with key management open: back to the scan, where keys are not touched.
    LaunchedEffect(restrictedLocked) {
        if (restrictedLocked && currentStep in setOf(AiDexSetupStep.KEY_MANAGEMENT, AiDexSetupStep.SAVED_SENSORS)) currentStep = AiDexSetupStep.SCAN
    }
    var selectedDeviceName by remember { mutableStateOf("") }
    var selectedSensor by remember { mutableStateOf<AiDexKeySensor?>(null) }
    var requiredSerial by remember { mutableStateOf<String?>(null) }
    var connectionStarting by remember { mutableStateOf(false) }
    var nearbyKeySensors by remember { mutableStateOf<List<AiDexKeySensor>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyForm = remember { AiDexKeyManagementState(accountLabel = AiDexProvisioningStore.accountLabel(context)) }
    var signedIn by remember { mutableStateOf(AiDexProvisioningStore.hasSession(context)) }
    DisposableEffect(context) {
        val removeListener = AiDexProvisioningStore.addSessionListener(context) {
            signedIn = AiDexProvisioningStore.hasSession(context)
        }
        signedIn = AiDexProvisioningStore.hasSession(context)
        onDispose { removeListener() }
    }
    val navigateBack = {
        when (currentStep) {
            AiDexSetupStep.SCAN -> onDismiss()
            AiDexSetupStep.SAVED_SENSORS -> {
                keyForm.refreshKeys()
                currentStep = AiDexSetupStep.KEY_MANAGEMENT
            }
            else -> currentStep = AiDexSetupStep.SCAN
        }
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

    val connectSensor: (AiDexKeySensor) -> Unit = connect@{ sensor ->
        if (connectionStarting) return@connect
        connectionStarting = true
        val bare = AiDexPairingMaterialFile.normalizeSerial(sensor.serial)
        val name = bare?.let { "X-$it" } ?: sensor.serial.trim()
        selectedDeviceName = name
        keyForm.selectSerial(bare ?: name)
        selectedSensor = sensor
        scope.launch {
            try {
                val allowed = withContext(Dispatchers.IO) {
                    AiDexSetupKeyGate.mayConnect(context, name, sensor.name)
                }
                if (!allowed) {
                    requiredSerial = name
                    fullModeGuard { currentStep = AiDexSetupStep.KEY_MANAGEMENT }
                    return@launch
                }
                requiredSerial = null
                currentStep = AiDexSetupStep.CONNECTING
                AiDexProvisioningStore.installSaved(context, name)
                SensorBluetooth.addAiDexSensor(context, name, sensor.address)
                delay(2000)
                currentStep = AiDexSetupStep.SUCCESS
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(tag, "Failed to add/select AiDex sensor: ${t.message}")
                Toast.makeText(context, context.getString(R.string.nobluetooth), Toast.LENGTH_LONG).show()
                currentStep = AiDexSetupStep.SCAN
            } finally {
                connectionStarting = false
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (currentStep == AiDexSetupStep.SAVED_SENSORS)
                    R.string.ottai_saved_sensors_title else R.string.aidex_setup_title),
                onNavigateBack = navigateBack,
                navigationContentDescription = stringResource(R.string.cancel),
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
                    onNavigateToReadiness = onNavigateToReadiness,
                    // Saved pairing keys can be deleted there: full mode on a restricted phone.
                    onManageKeys = { fullModeGuard { currentStep = AiDexSetupStep.KEY_MANAGEMENT } },
                    onSensorsChanged = { if (it.isNotEmpty()) nearbyKeySensors = it },
                    onDeviceSelected = { selectedName, address, advertisedName ->
                        connectSensor(AiDexKeySensor(selectedName, advertisedName, address))
                    }
                )
                AiDexSetupStep.KEY_MANAGEMENT -> {
                    val scan = rememberAiDexKeyScan(nearbyKeySensors + listOfNotNull(selectedSensor))
                    LaunchedEffect(scan.sensors) {
                        if (scan.sensors.isNotEmpty()) nearbyKeySensors = scan.sensors
                    }
                    AiDexKeyManagementScreen(
                        ui = ui,
                        initialSerial = AiDexPairingMaterialFile.normalizeSerial(selectedDeviceName).orEmpty(),
                        signedIn = signedIn,
                        onSignedIn = { signedIn = true },
                        onSignedOut = { signedIn = false },
                        sensors = scan.sensors,
                        requiredSerial = requiredSerial,
                        onConnect = connectSensor,
                        formState = keyForm,
                        searching = scan.searching,
                        scanError = scan.error,
                        onOpenSavedSensors = { currentStep = AiDexSetupStep.SAVED_SENSORS },
                    )
                }
                AiDexSetupStep.SAVED_SENSORS -> AiDexSavedSensorsScreen(
                    ui = ui,
                    knownSensors = nearbyKeySensors,
                    selectedSerial = keyForm.serialInput.value,
                    onSelect = { serial ->
                        keyForm.selectSerial(serial)
                        currentStep = AiDexSetupStep.KEY_MANAGEMENT
                    },
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
internal fun AiDexScanStep(
    ui: WizardUiMetrics,
    onNavigateToReadiness: () -> Unit,
    onManageKeys: () -> Unit,
    onSensorsChanged: (List<AiDexKeySensor>) -> Unit = {},
    onDeviceSelected: (String, String, String) -> Unit
) {
    data class ScanCandidate(
        val address: String,
        val rawName: String,
        val selectionName: String,
        val serial: String?,
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

    LaunchedEffect(devices) {
        onSensorsChanged(devices.mapNotNull { candidate ->
            candidate.serial?.let { AiDexKeySensor(it, candidate.rawName, candidate.address) }
        })
    }

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

                candidate.serial?.let { AiDexSetupKeyGate.requiresKeys(context, it, candidate.displayName) }
                if (!showAllDevices && !candidate.isLikelyAiDex) return@startScan
                val next = ScanCandidate(
                    address = address,
                    rawName = candidate.displayName,
                    selectionName = candidate.selectionName,
                    serial = candidate.serial,
                    isLikelyAiDex = candidate.isLikelyAiDex,
                    detectedViaFf30 = candidate.detectedViaFf30,
                )
                val existing = devices.firstOrNull { it.address == address }
                devices = if (existing == null) {
                    devices + next
                } else {
                    val preferNextIdentity = next.serial != null && existing.serial == null
                    devices.map { current ->
                        if (current.address != address) current else current.copy(
                            rawName = if (preferNextIdentity) next.rawName else current.rawName,
                            selectionName = if (preferNextIdentity) next.selectionName else current.selectionName,
                            serial = if (preferNextIdentity) next.serial else current.serial,
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
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        PinnedOrScrollingList(
            modifier = Modifier.weight(1f),
            header = {
                tk.glucodata.ui.CgmReadinessSetupBanner(
                    modifier = Modifier.padding(horizontal = ui.horizontalPadding, vertical = ui.spacerMedium),
                    onOpenReadiness = onNavigateToReadiness
                )
                Spacer(Modifier.height(ui.spacerMedium))
                ScanFilterHeader(
                    title = stringResource(R.string.aidex_searching_sensors),
                    showAllDevices = showAllDevices,
                    onToggleFilter = { showAllDevices = !showAllDevices },
                    modifier = Modifier.padding(horizontal = ui.horizontalPadding),
                )
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
                                modifier = Modifier.heightIn(min = ui.buttonHeight),
                                shapes = ButtonDefaults.shapes(),
                                contentPadding = ButtonDefaults.ContentPadding
                            ) {
                                Text(stringResource(buttonRes))
                            }
                        }
                    }
                }
            },
            footer = {
                SettingsItem(
                    title = stringResource(R.string.aidex_key_management_title),
                    subtitle = stringResource(R.string.aidex_key_management_entry_desc),
                    icon = Icons.Default.Key,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = CardPosition.SINGLE,
                    onClick = onManageKeys,
                    modifier = Modifier
                        .padding(
                            start = ui.horizontalPadding,
                            end = ui.horizontalPadding,
                            bottom = ui.spacerMedium,
                        ),
                )
            },
        ) {
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
                            device.rawName,
                        )
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * A header and footer pinned around a scrolling list, while the list keeps room for at least one
 * row. When they would squeeze it shut (short window, large text), all three scroll as one list.
 */
@Composable
internal fun PinnedOrScrollingList(
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable () -> Unit,
    listContent: LazyListScope.() -> Unit,
) {
    // Natural heights, measured in either layout (unbounded, so a squeezed footer still reports
    // what it needs); the same content at the same width has the same height.
    var headerHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }
    val handOver = remember { ListHandOver() }
    BoxWithConstraints(modifier) {
        val minListHeight = with(LocalDensity.current) {
            (MinPinnedListHeight * fontScale.coerceAtLeast(1f)).roundToPx()
        }
        val pinned = constraints.maxHeight - headerHeight - footerHeight >= minListHeight
        // The list being replaced hands over its place, so a switch (a banner appearing, rotation,
        // font size) doesn't jump back to the top. The scrolling list has the header as item 0.
        val listState = rememberSaveable(pinned, saver = LazyListState.Saver) {
            val from = handOver.state
            val index = from?.firstVisibleItemIndex ?: 0
            val offset = from?.firstVisibleItemScrollOffset ?: 0
            when {
                from == null -> LazyListState()
                pinned -> if (index == 0) LazyListState() else LazyListState(index - 1, offset)
                index == 0 && offset == 0 -> LazyListState()
                else -> LazyListState(index + 1, offset)
            }
        }
        SideEffect { handOver.state = listState }
        val measuredHeader: @Composable () -> Unit = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { headerHeight = it.height },
                content = header,
            )
        }
        val measuredFooter: @Composable () -> Unit = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { footerHeight = it.height }
            ) { footer() }
        }
        if (pinned) {
            Column(Modifier.fillMaxSize()) {
                measuredHeader()
                LazyColumn(modifier = Modifier.weight(1f), state = listState, content = listContent)
                measuredFooter()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                item { measuredHeader() }
                listContent()
                item { measuredFooter() }
            }
        }
    }
}

// One two-line list row.
private val MinPinnedListHeight = 72.dp

private class ListHandOver {
    var state: LazyListState? = null
}

internal data class AiDexScanDetection(
    val displayName: String,
    val selectionName: String,
    val serial: String?,
    val isLikelyAiDex: Boolean,
    val detectedViaFf30: Boolean,
)

private val AIDEX_CGM_SERVICE_UUID: UUID = UUID.fromString("0000181f-0000-1000-8000-00805f9b34fb")
private val AIDEX_VENDOR_SERVICE_UUID: UUID = UUID.fromString("0000f000-0000-1000-8000-00805f9b34fb")
private val AIDEX_FF30_SERVICE_UUID: UUID = UUID.fromString("0000ff30-0000-1000-8000-00805f9b34fb")

internal fun detectAiDexCandidate(
    address: String,
    deviceName: String?,
    scanRecordName: String?,
    scanRecordBytes: ByteArray?,
    advertisedServiceUuids: List<UUID>?,
): AiDexScanDetection {
    val localName = extractAiDexLocalName(scanRecordBytes)
    val names = linkedSetOf<String>()
    listOf(scanRecordName, localName, deviceName)
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .forEach { names.add(it) }

    val recognizedName = names.firstOrNull {
        AiDexSerialIdentity.canonicalFromAdvertisement(it) != null
    }
    val serial = recognizedName?.let(AiDexSerialIdentity::canonicalFromAdvertisement)
    val nameLooksAiDex = names.any(::looksLikeAiDexFamilyName)
    val hasFf30 = advertisedServiceUuids?.contains(AIDEX_FF30_SERVICE_UUID) == true ||
        scanRecordAdvertises16BitService(scanRecordBytes, 0xFF30)
    val hasPrimaryServiceHint =
        advertisedServiceUuids?.any { it == AIDEX_CGM_SERVICE_UUID || it == AIDEX_VENDOR_SERVICE_UUID } == true ||
            scanRecordAdvertises16BitService(scanRecordBytes, 0x181F) ||
            scanRecordAdvertises16BitService(scanRecordBytes, 0xF000)
    val isLikelyAiDex = serial != null || nameLooksAiDex || hasFf30 || hasPrimaryServiceHint
    val displayName = recognizedName ?: names.firstOrNull() ?: address
    val selectionName = serial ?: AiDexSerialIdentity.fallbackCanonicalFromAddress(address)
    return AiDexScanDetection(
        displayName = displayName,
        selectionName = selectionName,
        serial = serial,
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
