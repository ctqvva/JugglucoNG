package tk.glucodata.ui.setup

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.drivers.aidex.AiDexDriver
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.AiDexScanIdentity
import tk.glucodata.drivers.aidex.AiDexSetupPolicy
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.rememberBleScanner

enum class AiDexSetupStep {
    SCAN,
    KEY_MANAGEMENT,
    CONNECTING,
    SUCCESS
}

private const val AIDEX_SETUP_SESSION_TIMEOUT_MS = 90_000L
/** Bounds the wait while Android is pairing; see [AiDexSetupPolicy.decideConnectingState]. */
private const val AIDEX_SETUP_HARD_TIMEOUT_MS = 180_000L
/**
 * Room after a pairing poll before the soft deadline can roll back: a pairing confirmed at the
 * last moment still needs the key exchange after it (the driver allows up to about 35 s), and a
 * failed one shows up as broadcast-only a few seconds after the bond drops.
 */
private const val AIDEX_SETUP_PAIRING_GRACE_MS = 40_000L
/** How long "Not connected" waits for Retry; well inside the driver's own 10-minute retry. */
private const val AIDEX_SETUP_NOT_CONNECTED_LIMIT_MS = 180_000L

private fun aiDexSetupDriver(serial: String, address: String): AiDexDriver? =
    SensorBluetooth.mygatts().firstOrNull { callback ->
        callback is AiDexDriver &&
            SensorIdentity.matches(callback.SerialNumber, serial) &&
            address.equals(callback.mActiveDeviceAddress, ignoreCase = true)
    } as? AiDexDriver

@SuppressLint("MissingPermission")
private fun aiDexBondState(context: Context, address: String): Int = runCatching {
    context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)?.bondState
}.getOrNull() ?: BluetoothDevice.BOND_NONE

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
    var selectedDeviceAddress by remember { mutableStateOf("") }
    var rollbackSelectedOnAbort by remember { mutableStateOf(false) }
    var setupJob by remember { mutableStateOf<Job?>(null) }
    var connectingState by remember { mutableStateOf(AiDexSetupPolicy.ConnectingState.CONNECTING) }
    var notConnectedReason by remember { mutableStateOf(AiDexSetupPolicy.NotConnectedReason.CONNECT_FAILED) }
    // Success reached through a timeout on a paired sensor whose link is down right now.
    var addedWithoutLink by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var signedIn by remember { mutableStateOf(AiDexProvisioningStore.hasSession(context)) }

    // Polls the driver until it streams from this sensor. A new sensor has no glucose before its
    // warm-up ends, so a finished handshake is the success; the driver starts it on its own.
    // Deadlines run on elapsedRealtime, like the driver's own broadcast-only hold: a wall-clock
    // step must neither stretch "Not connected" past the driver's retry nor end a connect at once.
    suspend fun awaitSetupSession(name: String, address: String, mayRollback: Boolean) {
        var deadlines = AiDexSetupPolicy.initialDeadlines(
            nowMs = SystemClock.elapsedRealtime(),
            sessionTimeoutMs = AIDEX_SETUP_SESSION_TIMEOUT_MS,
            hardTimeoutMs = AIDEX_SETUP_HARD_TIMEOUT_MS,
        )
        // What Android did on the current attempt, for the "Not connected" text.
        var sawPairing = false
        var sawBonded = false
        var gaveUpBefore = false
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val driver = aiDexSetupDriver(name, address)
            val bondState = aiDexBondState(context, address)
            val pairing = bondState == BluetoothDevice.BOND_BONDING
            val gaveUp = driver?.broadcastOnlyConnection == true
            if (gaveUpBefore && !gaveUp) {
                // The driver tries again: a new attempt, with its own pairing story.
                sawPairing = false
                sawBonded = false
            }
            gaveUpBefore = gaveUp
            if (pairing) sawPairing = true
            if (sawPairing && bondState == BluetoothDevice.BOND_BONDED) sawBonded = true
            notConnectedReason = AiDexSetupPolicy.notConnectedReason(sawPairing, sawBonded)
            deadlines = AiDexSetupPolicy.nextDeadlines(
                current = deadlines,
                nowMs = now,
                pairingInProgress = pairing,
                driverGaveUp = gaveUp,
                sessionTimeoutMs = AIDEX_SETUP_SESSION_TIMEOUT_MS,
                hardTimeoutMs = AIDEX_SETUP_HARD_TIMEOUT_MS,
                graceMs = AIDEX_SETUP_PAIRING_GRACE_MS,
                notConnectedLimitMs = AIDEX_SETUP_NOT_CONNECTED_LIMIT_MS,
            )
            val state = AiDexSetupPolicy.decideConnectingState(
                sessionEstablished = driver?.isVendorConnected() == true,
                pairingInProgress = pairing,
                driverGaveUp = gaveUp,
                // A sensor this setup added that the rollback keeps anyway (the rollback's own rule):
                // its timeout is no failure. A configured sensor's failed re-setup stays a failure.
                sensorStays = mayRollback && driver?.let {
                    !AiDexSetupPolicy.mayRollBack(it.isVendorPaired(), it.hasCompletedHandshake())
                } == true,
                nowMs = now,
                deadlineMs = deadlines.softMs,
                hardDeadlineMs = deadlines.hardMs,
                notConnectedDeadlineMs = deadlines.notConnectedMs,
            )
            connectingState = state
            when (state) {
                AiDexSetupPolicy.ConnectingState.READY -> {
                    currentStep = AiDexSetupStep.SUCCESS
                    return
                }
                AiDexSetupPolicy.ConnectingState.TIMED_OUT -> {
                    if (mayRollback) {
                        SensorBluetooth.rollbackUnpairedAiDexSensor(context, name)
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.aidex_setup_session_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    currentStep = AiDexSetupStep.SCAN
                    return
                }
                AiDexSetupPolicy.ConnectingState.KEPT -> {
                    // Added here and kept by the rollback rule: not a failure, though the link
                    // may need Reconnect on the card.
                    // Ends through SUCCESS so this job returns before the wizard closes, instead of
                    // closing it from inside a running job.
                    addedWithoutLink = true
                    Toast.makeText(
                        context,
                        context.getString(R.string.aidex_setup_kept_reconnecting),
                        Toast.LENGTH_LONG
                    ).show()
                    currentStep = AiDexSetupStep.SUCCESS
                    return
                }
                else -> delay(500)
            }
        }
    }

    // A cancelled wait rolls the sensor back only while it is still the wizard's current job, so
    // the new job is installed before the old one is cancelled.
    fun retrySetupConnection() {
        val name = selectedDeviceName
        val address = selectedDeviceAddress
        val mayRollback = rollbackSelectedOnAbort
        val previous = setupJob
        connectingState = AiDexSetupPolicy.ConnectingState.CONNECTING
        setupJob = scope.launch {
            val thisJob = coroutineContext[Job]
            try {
                aiDexSetupDriver(name, address)?.let { driver ->
                    withContext(Dispatchers.IO) { driver.softReconnect() }
                }
                awaitSetupSession(name, address, mayRollback)
            } catch (t: CancellationException) {
                if (setupJob === thisJob && mayRollback) {
                    SensorBluetooth.rollbackUnpairedAiDexSensor(context, name)
                }
                throw t
            }
        }
        previous?.cancel()
    }
    val abortConnecting = {
        setupJob?.cancel()
        setupJob = null
        if (selectedDeviceName.isNotBlank() && rollbackSelectedOnAbort) {
            SensorBluetooth.rollbackUnpairedAiDexSensor(context, selectedDeviceName)
        }
    }
    val navigateBack: () -> Unit = {
        when (currentStep) {
            AiDexSetupStep.SCAN -> onDismiss()
            AiDexSetupStep.KEY_MANAGEMENT -> currentStep = AiDexSetupStep.SCAN
            AiDexSetupStep.CONNECTING -> {
                abortConnecting()
                currentStep = AiDexSetupStep.SCAN
            }
            AiDexSetupStep.SUCCESS -> onDismiss()
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
                    onNavigateToReadiness = onNavigateToReadiness,
                    onManageKeys = { currentStep = AiDexSetupStep.KEY_MANAGEMENT },
                    onDeviceSelected = { selectedName, address ->
                        try {
                            val name = selectedName.trim()
                            if (name.isEmpty() || !AiDexScanIdentity.canBind(name, address)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.aidex_parse_error, selectedName),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@AiDexScanStep
                            }

                            val alreadyConfigured = SensorBluetooth.isExistingAiDexSetup(context, name)
                            val mayRollback = !alreadyConfigured
                            if (selectedDeviceName.isNotBlank()
                                && !selectedDeviceName.equals(name, ignoreCase = true)
                                && rollbackSelectedOnAbort
                            ) {
                                SensorBluetooth.rollbackUnpairedAiDexSensor(context, selectedDeviceName)
                            }
                            selectedDeviceName = name
                            selectedDeviceAddress = address
                            rollbackSelectedOnAbort = mayRollback
                            connectingState = AiDexSetupPolicy.ConnectingState.CONNECTING
                            addedWithoutLink = false
                            currentStep = AiDexSetupStep.CONNECTING

                            setupJob?.cancel()
                            setupJob = scope.launch {
                                val thisJob = coroutineContext[Job]
                                try {
                                    // Saved material is optional. With none present, the BLE driver
                                    // always tries the serial-derived key first and reports a
                                    // missing-key status only if the sensor rejects it.
                                    AiDexProvisioningStore.installSaved(context, name)
                                    if (!SensorBluetooth.addAiDexSensor(context, name, address)) {
                                        if (mayRollback) {
                                            SensorBluetooth.rollbackUnpairedAiDexSensor(context, name)
                                        }
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.aidex_setup_session_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        currentStep = AiDexSetupStep.SCAN
                                        return@launch
                                    }
                                    awaitSetupSession(name, address, mayRollback)
                                } catch (t: CancellationException) {
                                    if (setupJob === thisJob && mayRollback) {
                                        SensorBluetooth.rollbackUnpairedAiDexSensor(context, name)
                                    }
                                    throw t
                                } catch (t: Throwable) {
                                    Log.e(tag, "Failed to add/select AiDex sensor: ${t.message}")
                                    if (mayRollback) {
                                        SensorBluetooth.rollbackUnpairedAiDexSensor(context, name)
                                    }
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
                AiDexSetupStep.KEY_MANAGEMENT -> AiDexKeyManagementScreen(
                    ui = ui,
                    initialSerial = selectedDeviceName,
                    signedIn = signedIn,
                    onSignedIn = { signedIn = true },
                    onSignedOut = { signedIn = false },
                    onClose = { currentStep = AiDexSetupStep.SCAN },
                )
                AiDexSetupStep.CONNECTING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val sensorLabel = selectedDeviceName.ifBlank { null }
                    when (connectingState) {
                        AiDexSetupPolicy.ConnectingState.AWAITING_PAIRING_CONFIRMATION -> SensorSetupConnectingScreen(
                            ui = ui,
                            sensorLabel = sensorLabel,
                            title = stringResource(R.string.aidex_setup_confirm_pairing_title),
                            supportingText = stringResource(R.string.aidex_setup_confirm_pairing_text)
                        )
                        AiDexSetupPolicy.ConnectingState.NOT_CONNECTED -> {
                            SensorSetupNotConnectedScreen(
                                ui = ui,
                                sensorLabel = sensorLabel,
                                title = stringResource(R.string.aidex_setup_not_connected_title),
                                supportingText = stringResource(
                                    when (notConnectedReason) {
                                        AiDexSetupPolicy.NotConnectedReason.PAIRING_NOT_CONFIRMED ->
                                            R.string.aidex_setup_pairing_not_confirmed
                                        AiDexSetupPolicy.NotConnectedReason.CONNECT_FAILED ->
                                            R.string.aidex_setup_connect_failed
                                    }
                                ),
                                action = {
                                    Button(onClick = { retrySetupConnection() }) {
                                        Text(stringResource(R.string.aidex_setup_retry))
                                    }
                                }
                            )
                        }
                        else -> SensorSetupConnectingScreen(
                            ui = ui,
                            sensorLabel = sensorLabel
                        )
                    }
                }
                AiDexSetupStep.SUCCESS -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SensorSetupSuccessScreen(
                        ui = ui,
                        sensorLabel = selectedDeviceName.ifBlank { null },
                        title = stringResource(
                            if (addedWithoutLink) R.string.aidex_setup_added_title else R.string.status_connected
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun AiDexScanStep(
    ui: WizardUiMetrics,
    onNavigateToReadiness: () -> Unit,
    onManageKeys: () -> Unit,
    onDeviceSelected: (String, String) -> Unit
) {
    data class ScanCandidate(
        val address: String,
        val rawName: String,
        val serial: String?,
        val isLikelyAiDex: Boolean,
        val detectedViaFf30: Boolean,
        val serialFromAdvert: Boolean,
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
                val candidate = AiDexScanIdentity.detectCandidate(
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
                val existing = devices.firstOrNull { it.address.equals(address, ignoreCase = true) }
                if (existing == null) {
                    devices = devices + ScanCandidate(
                        address = address,
                        rawName = candidate.displayName,
                        serial = candidate.serial,
                        isLikelyAiDex = candidate.isLikelyAiDex,
                        detectedViaFf30 = candidate.detectedViaFf30,
                        serialFromAdvert = candidate.serialFromAdvert,
                    )
                } else if (AiDexScanIdentity.shouldReplaceScanSerial(
                        existing.serial,
                        existing.serialFromAdvert,
                        candidate.serial,
                        candidate.serialFromAdvert,
                        address,
                    )
                ) {
                    devices = devices.map { row ->
                        if (!row.address.equals(address, ignoreCase = true)) row
                        else row.copy(
                            rawName = candidate.displayName,
                            serial = candidate.serial,
                            isLikelyAiDex = true,
                            detectedViaFf30 = row.detectedViaFf30 || candidate.detectedViaFf30,
                            serialFromAdvert = candidate.serialFromAdvert,
                        )
                    }
                } else if ((candidate.isLikelyAiDex && !existing.isLikelyAiDex) ||
                    (candidate.detectedViaFf30 && !existing.detectedViaFf30)
                ) {
                    devices = devices.map { row ->
                        if (!row.address.equals(address, ignoreCase = true)) row
                        else row.copy(
                            isLikelyAiDex = row.isLikelyAiDex || candidate.isLikelyAiDex,
                            detectedViaFf30 = row.detectedViaFf30 || candidate.detectedViaFf30,
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

                val canSelect = AiDexScanIdentity.canBind(device.serial, device.address)

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
                        onDeviceSelected(device.serial.orEmpty(), device.address)
                    }
                )
                HorizontalDivider()
            }
        }
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
    }
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
