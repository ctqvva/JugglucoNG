package tk.glucodata.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.StyledSwitch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tk.glucodata.R
import tk.glucodata.Natives
import tk.glucodata.ui.util.BleDeviceScanner

import android.graphics.BitmapFactory
import kotlin.math.max
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.drivers.sibionics.SibionicsConstants
import tk.glucodata.drivers.sibionics.SibionicsRegistry

enum class SibionicsType(val displayNameRes: Int, val subtype: Int, val setupVisible: Boolean = true) {
    EU(R.string.eusibionics, 0),
    HEMATONIX(R.string.hematonix, 1),
    CHINESE(R.string.chsibionics, 2),
    SIBIONICS2(R.string.sibionics2, 3),
    GS3(R.string.gs3, 4, setupVisible = false),
}

private fun SibionicsType.toManagedVariant(): SibionicsConstants.Variant =
    SibionicsConstants.Variant.fromLegacySubtype(subtype)

/**
 * Sibionics 2 split labels carry the probe identifier in AI (21) (e.g. EU2VCZUQPSHD5Q), not the
 * printed P... serial, so the generation cannot be read off the code. For that type any
 * structurally valid Sibionics label is accepted and the user's type choice stands; BLE discovery
 * still only offers Sibionics 2 transmitters. The other types keep the generation check.
 */
internal fun SibionicsType.acceptsSetupQr(raw: String?): Boolean =
    if (this == SibionicsType.SIBIONICS2) {
        SibionicsRegistry.isSupportedQrPayload(raw)
    } else {
        SibionicsRegistry.isSupportedQrPayload(raw, toManagedVariant())
    }

internal fun SibionicsType.acceptsBleSetupDevice(name: String?): Boolean =
    when (this) {
        SibionicsType.SIBIONICS2 -> SibionicsConstants.isSibionics2TransmitterName(name)
        SibionicsType.GS3 -> false
        else -> !name.isNullOrBlank() && !SibionicsConstants.isSibionics2TransmitterName(name)
    }

enum class SibionicsSetupStep {
    SELECT_TYPE,
    SCAN_SENSOR,
    CONNECTING
}

// Helper to decode QR from URI
suspend fun decodeBitmapQr(context: android.content.Context, uri: android.net.Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }

            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return@withContext null

            val maxDimension = max(width, height)
            var baseSample = 1
            while (maxDimension / baseSample > 1600) {
                baseSample *= 2
            }

            val attempts = linkedSetOf(baseSample, baseSample / 2, 1)
                .filter { it >= 1 }

            for (sampleSize in attempts) {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                } ?: continue

                try {
                    decodeQrFromBitmap(bitmap)?.let { return@withContext it }
                } finally {
                    bitmap.recycle()
                }
            }

            null
        } catch (e: Exception) {
            android.util.Log.e("QrDecode", "Error decoding QR", e)
            null
        } catch (oom: OutOfMemoryError) {
            android.util.Log.e("QrDecode", "Out of memory decoding QR image: $uri", oom)
            null
        }
    }
}

private fun decodeQrFromBitmap(bitmap: android.graphics.Bitmap): String? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val source = RGBLuminanceSource(width, height, pixels)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        MultiFormatReader().decode(binaryBitmap).text
    } catch (_: Exception) {
        null
    }
}

/**
 * Manual entry dialog for Sibionics SENSOR codes.
 * User enters Batch (10) and Serial (21) from the label.
 */
@Composable
fun ManualSensorEntryDialog(
    type: SibionicsType,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var batch by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var batchValid by remember { mutableStateOf(false) }

    // Set examples based on sensor type
    val (batchExample, serialExample) = if (type == SibionicsType.SIBIONICS2) {
        "LT46250671C" to "P2250671014ATR89"
    } else {
        "LT41250946H" to "250946432T452CBZ43"
    }

    // The QR path refuses a code whose serial form belongs to another sensor type; typing the
    // code by hand must not be the way around that check.
    val serialValid = SibionicsRegistry.isManualSerialForVariant(serial, type.toManagedVariant())
    val serialTypeMismatch = serial.length in 10..20 && !serialValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_sensor_codes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.enter_sensor_label_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = batch,
                    onValueChange = {
                        batch = it.uppercase().filter { c -> c.isLetterOrDigit() }
                        batchValid = batch.length in 8..16
                    },
                    label = { Text(stringResource(R.string.batch_code_label)) },
                    placeholder = { Text(batchExample, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    supportingText = { Text(stringResource(R.string.batch_code_supporting, batchExample)) },
                    singleLine = true,
                    isError = batch.isNotEmpty() && !batchValid,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = serial,
                    onValueChange = {
                        serial = it.uppercase().filter { c -> c.isLetterOrDigit() }
                    },
                    label = { Text(stringResource(R.string.serial_number_label)) },
                    placeholder = { Text(serialExample, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    supportingText = {
                        if (serialTypeMismatch) {
                            Text(
                                stringResource(
                                    R.string.sibionics_serial_wrong_type,
                                    stringResource(type.displayNameRes),
                                ),
                            )
                        } else {
                            Text(stringResource(R.string.serial_number_supporting, serialExample))
                        }
                    },
                    singleLine = true,
                    isError = serial.isNotEmpty() && !serialValid,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(batch + serial) },
                enabled = batchValid && serialValid
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Manual entry dialog for Sibionics 2 TRANSMITTER code.
 * User enters the transmitter name (last 10 chars of QR).
 */
@Composable
fun ManualTransmitterEntryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    val isValid = code.length in 8..12

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_transmitter_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.enter_transmitter_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() } },
                    label = { Text(stringResource(R.string.transmitter_id_label)) },
                    // ui-guardrails: allow text_literal - an example transmitter ID
                    placeholder = { Text("TW6CCWHS9L7D", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                    supportingText = { Text(stringResource(R.string.transmitter_id_supporting)) },
                    singleLine = true,
                    isError = code.isNotEmpty() && !isValid,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(code) },
                enabled = isValid
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}


// Helper to construct a "Fake QR" string that satisfies native requirements
// Returns null if input is invalid (prevents native crashes)
fun constructFakeSibionicsQr(input: String, targetLength: Int = 59): String? {
    val trimmedInput = input.trim()

    // VALIDATION: Reject obviously invalid input
    if (trimmedInput.isBlank() || trimmedInput.length < 3) {
        android.util.Log.w("SibionicsSetup", "Input too short: $trimmedInput")
        return null
    }

    // Reject input containing suspicious patterns that suggest malformed data
    // (slashes indicate a full QR URL that wasn't properly parsed)
    if (trimmedInput.count { it == '/' } > 2) {
        android.util.Log.w("SibionicsSetup", "Input contains too many slashes (malformed QR?): $trimmedInput")
        return null
    }

    val magicCode = "0697283164"

    // For full sensor payloads, keep the scanned content intact so native parsing behavior
    // matches the legacy scanner path. Avoid re-packing into synthetic payloads.
    val compactRaw = trimmedInput.filterNot { it.isWhitespace() }
    val gtinPos = compactRaw.indexOf(magicCode)
    if (targetLength >= 65 && gtinPos in 0..4 && (compactRaw.length - gtinPos) >= 55) {
        return compactRaw
    }

    // If input is already a full QR (contains magic code and is long), validate structure
    // FORCE-RECONSTRUCTION: We intentionally bypass this 'fast path' to ensure we strip 
    // any potential invisible characters or trailing garbage that might offset the native
    // parser's fixed-length window.
    // The native parser (namefromSIgegs) relies on strict offsets from the end of the string.
    /*
    if (trimmedInput.length >= 30 && trimmedInput.contains(magicCode)) {
        // Additional check: must not have garbage appended
        if (trimmedInput.length > 80) {
            android.util.Log.w("SibionicsSetup", "QR string too long (corrupted?): len=${trimmedInput.length}")
            return null
        }
        return trimmedInput
    }
    */

    // Extract just the alphanumeric code part if user pasted a partial URL
    val codeToUse = if (trimmedInput.contains("/")) {
        // Try to extract just the code part after last slash
        val lastSlashIdx = trimmedInput.lastIndexOf("/")
        val extracted = trimmedInput.substring(lastSlashIdx + 1).filter { it.isLetterOrDigit() }
        if (extracted.length >= 3) extracted else trimmedInput.filter { it.isLetterOrDigit() }
    } else {
        // Clean input: only keep alphanumeric
        trimmedInput.filter { it.isLetterOrDigit() }
    }

    if (codeToUse.isBlank()) {
        android.util.Log.w("SibionicsSetup", "No valid code extracted from input")
        return null
    }

    // CASE 1: Sensor QR (Sibionics sensor)
    // IMPORTANT: Do NOT truncate batch+serial for real QR payloads.
    // Native code relies on full payload length and end-window extraction.
    if (targetLength >= 65) {
        // For short BLE-name fallback (e.g. 47PV45HHCG0), synthesize a 70-char payload
        // so native always takes the >=65 branch and derives a stable 16-char sensor id
        // whose short form matches the BLE name at offset +5.
        if (codeToUse.length <= 11) {
            // For BLE-name fallback (no full QR), build a short sensor name where the first
            // 4 chars mirror BLE tail. This is generic and must not depend on variant markers
            // like LT*.
            val shortName = (codeToUse.takeLast(4) + codeToUse).take(11).padEnd(11, '0')
            val syntheticName16 = "00000$shortName" // short view = chars [5..15]
            val suffix = "X" // native extracts [len-17, len-1), so suffix is ignored
            val desiredLen = maxOf(targetLength, 65)
            val prefixLen = desiredLen - syntheticName16.length - suffix.length
            val prefixPaddingLen = (prefixLen - magicCode.length).coerceAtLeast(0)
            val prefix = magicCode + "0".repeat(prefixPaddingLen)
            return prefix + syntheticName16 + suffix
        }

        // Generic sensor fallback for batch+serial/manual inputs.
        val codePart = codeToUse
        val minPrefixLen = 53 - magicCode.length
        val prefixPadding = if (minPrefixLen > 0) "0".repeat(minPrefixLen) else ""
        return magicCode + prefixPadding + codePart
    }

    // CASE 2: Sibionics 2 Transmitter (Target 59)
    return tk.glucodata.PhotoScan.buildSibionics2TransmitterPayload(trimmedInput)
}

internal fun constructBleOnlySibionicsQr(deviceName: String): String? {
    val normalized = SibionicsConstants.normalizeBleName(deviceName)
    if (normalized.length !in 8..16) return null
    val shortName = (normalized.takeLast(4) + normalized).take(11).padEnd(11, '0')
    val syntheticName16 = "00000$shortName"
    val desiredLength = 70
    val magicCode = "0697283164"
    val prefix = magicCode + "0".repeat(desiredLength - magicCode.length - syntheticName16.length - 1)
    return prefix + syntheticName16 + "X"
}

/**
 * Modern Material 3 Expressive wizard for setting up Sibionics sensors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SibionicsSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit
) {
    val ui = rememberWizardUiMetrics()
    val context = LocalContext.current
    var currentStep by remember { mutableStateOf(SibionicsSetupStep.SELECT_TYPE) }
    var selectedType by remember { mutableStateOf(SibionicsType.EU) }
    var sensorName by remember { mutableStateOf("") }
    var scannerTouchActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val handleDismiss: () -> Unit = onDismiss
    val handleBack: () -> Unit = {
        when (currentStep) {
            SibionicsSetupStep.SELECT_TYPE -> handleDismiss()
            SibionicsSetupStep.SCAN_SENSOR -> currentStep = SibionicsSetupStep.SELECT_TYPE
            SibionicsSetupStep.CONNECTING -> handleDismiss()
        }
    }

    BackHandler(onBack = handleBack)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scaffoldModifier = if (scannerTouchActive) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)

    Scaffold(
        modifier = scaffoldModifier,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.sibionics_setup_title),
                onNavigateBack = handleBack,
                navigationContentDescription = stringResource(R.string.cancel),
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                } else {
                    slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            label = "WizardStep"
        ) { step ->
            when (step) {
                SibionicsSetupStep.SELECT_TYPE -> SelectTypeStep(
                    compact = ui.compact,
                    selectedType = selectedType,
                    onNavigateToReadiness = onNavigateToReadiness,
                    onTypeSelected = { type ->
                        selectedType = type
                    },
                    onNext = {
                        currentStep = SibionicsSetupStep.SCAN_SENSOR
                    },
                    onBack = {
                        onDismiss()
                    }
                )

                SibionicsSetupStep.SCAN_SENSOR -> ScanSensorStep(
                    compact = ui.compact,
                    selectedType = selectedType,
                    onManagedEntry = { raw, device ->
                        val record = SibionicsRegistry.addSensorAndStart(
                            context = context.applicationContext,
                            rawInput = raw,
                            address = device?.address,
                            displayName = null,
                            variant = selectedType.toManagedVariant(),
                            bleName = device?.name,
                        )
                        sensorName = record.displayName
                        currentStep = SibionicsSetupStep.CONNECTING
                        tk.glucodata.Applic.Toaster(
                            tk.glucodata.Applic.app.getString(R.string.sibionics_managed_sensor_added, record.displayName)
                        )
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            onComplete()
                        }
                    },
                    onBleDeviceEntry = { device ->
                        val record = SibionicsRegistry.addSensorAndStart(
                            context = context.applicationContext,
                            rawInput = device.name,
                            address = device.address,
                            displayName = device.name,
                            variant = selectedType.toManagedVariant(),
                            bleName = device.name,
                        )
                        sensorName = record.displayName
                        currentStep = SibionicsSetupStep.CONNECTING
                        tk.glucodata.Applic.Toaster(
                            tk.glucodata.Applic.app.getString(R.string.sibionics_managed_sensor_added, record.displayName)
                        )
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            onComplete()
                        }
                    },
                    onScannerTouchInteractionChanged = { active ->
                        scannerTouchActive = active
                    }
                )

                SibionicsSetupStep.CONNECTING -> ConnectingStep(
                    ui = ui,
                    sensorLabel = sensorName.ifBlank { null }
                )
            }
        }
    }
}

@Composable
fun ScanSensorStep(
    compact: Boolean,
    selectedType: SibionicsType,
    onManagedEntry: (String, BleDeviceScanner.SibionicsBleDevice?) -> Unit,
    onBleDeviceEntry: (BleDeviceScanner.SibionicsBleDevice) -> Unit,
    onScannerTouchInteractionChanged: (Boolean) -> Unit
) {
    val contentPadding = if (compact) 12.dp else 16.dp
    val sectionGap = if (compact) 8.dp else 12.dp
    val buttonHeight = 48.dp
    var showManualEntry by remember { mutableStateOf(false) }
    var handledScan by remember { mutableStateOf(false) }
    var scanRejection by remember { mutableStateOf<String?>(null) }
    var scannerTouchActive by remember { mutableStateOf(false) }
    var bleProbeScanning by remember { mutableStateOf(true) }
    var bleProbeDevices by remember { mutableStateOf(listOf<BleDeviceScanner.SibionicsBleDevice>()) }
    var selectedBleAddress by remember { mutableStateOf<String?>(null) }
    var galleryDecodeInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val matchingBleDevices = remember(bleProbeDevices, selectedType) {
        bleProbeDevices.filter { selectedType.acceptsBleSetupDevice(it.name) }
    }
    val selectedBleDevice = remember(matchingBleDevices, selectedBleAddress) {
        matchingBleDevices.firstOrNull { it.address == selectedBleAddress }
    }
    val sibionics2Label = stringResource(SibionicsType.SIBIONICS2.displayNameRes)
    val selectedTypeLabel = stringResource(selectedType.displayNameRes)
    val submitManagedQr: (String) -> Boolean = { raw ->
        if (selectedType.acceptsSetupQr(raw)) {
            scanRejection = null
            onManagedEntry(raw, selectedBleDevice)
            true
        } else {
            // Rendered in place of the instruction line, not toasted: a toast is not guaranteed
            // to be seen (or shown at all) and this is the only feedback a rejected scan has.
            val isSibionics2Code = SibionicsRegistry.qrPayloadIsV120Identity(raw)
            scanRejection = when (isSibionics2Code) {
                null -> context.getString(R.string.invalid_code_format)
                true -> context.getString(R.string.sibionics_code_wrong_type, sibionics2Label)
                false -> context.getString(R.string.sibionics_code_not_for_type, selectedTypeLabel)
            }
            tk.glucodata.Log.i("SibionicsSetup", "rejected scan for ${selectedType.name}: $scanRejection")
            false
        }
    }
    val launchFullscreenScan = rememberUnifiedQrScanLauncher(
        requestCode = tk.glucodata.MainActivity.REQUEST_BARCODE,
        title = stringResource(R.string.sibionics_setup_title),
        onScanResult = { raw ->
            if (!handledScan) {
                handledScan = submitManagedQr(raw)
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose { onScannerTouchInteractionChanged(false) }
    }

    LaunchedEffect(bleProbeScanning, selectedType) {
        if (bleProbeScanning) {
            try {
                BleDeviceScanner.scanForSibionicsByService().collect { found ->
                    if (!selectedType.acceptsBleSetupDevice(found.name)) return@collect
                    bleProbeDevices = bleProbeDevices
                        .toMutableList()
                        .apply {
                            val existingIndex = indexOfFirst { it.address == found.address }
                            if (existingIndex >= 0) {
                                this[existingIndex] = found
                            } else {
                                add(found)
                            }
                        }
                        .sortedBy { it.name.uppercase() }
                }
            } finally {
                bleProbeScanning = false
            }
        }
    }
    
    // Gallery Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                galleryDecodeInProgress = true
                try {
                    val decoded = decodeBitmapQr(context, uri)
                    if (decoded != null) {
                        submitManagedQr(decoded)
                    } else {
                        tk.glucodata.Applic.Toaster(tk.glucodata.Applic.app.getString(R.string.no_qr_found))
                    }
                } finally {
                    galleryDecodeInProgress = false
                }
            }
        } else {
            galleryDecodeInProgress = false
        }
    }

    if (showManualEntry) {
        ManualSensorEntryDialog(
            type = selectedType,
            onDismiss = { showManualEntry = false },
            onConfirm = { code ->
                showManualEntry = false
                onManagedEntry(code, selectedBleDevice)
            }
        )
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        userScrollEnabled = !scannerTouchActive
    ) {
        item {
            Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
            InlineQrScannerCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 320.dp else 380.dp),
                scannerEnabled = !galleryDecodeInProgress,
                onScanResult = { raw ->
                    if (handledScan) true else submitManagedQr(raw).also { handledScan = it }
                },
                onManualFallback = launchFullscreenScan,
                manualFallbackLabel = stringResource(R.string.scan_qr_button),
                onTouchInteractionChanged = { active ->
                    scannerTouchActive = active
                    onScannerTouchInteractionChanged(active)
                }
            )
            Text(
                text = scanRejection
                    // The split generation needs the transmitter connection code; the generic
                    // text sends the user to the sensor box, whose code is then rejected.
                    ?: if (selectedType == SibionicsType.SIBIONICS2) {
                        stringResource(R.string.scan_sensor_instruction_sibionics2)
                    } else {
                        stringResource(R.string.scan_sensor_instruction)
                    },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (scanRejection != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = if (compact) 8.dp else 12.dp, bottom = if (compact) 16.dp else 20.dp)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        bleProbeDevices = emptyList()
                        selectedBleAddress = null
                        bleProbeScanning = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(buttonHeight)
                ) {
                    if (bleProbeScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.scanning_devices))
                    } else {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.search_bluetooth))
                    }
                }

                if (matchingBleDevices.isEmpty() && !bleProbeScanning) {
                    Text(
                        text = stringResource(R.string.sibionics_ble_probe_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }

                matchingBleDevices.forEach { item ->
                    val selected = item.address == selectedBleAddress
                    ListItem(
                        headlineContent = { Text(text = item.name, maxLines = 1) },
                        supportingContent = {
                            Text(text = "${item.address} • RSSI ${item.rssi}", maxLines = 1)
                        },
                        leadingContent = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                        trailingContent = {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(if (compact) 12.dp else 16.dp))
                            .clickable {
                                selectedBleAddress = if (selected) null else item.address
                            },
                    )
                }

                if (selectedBleDevice != null) {
                    Button(
                        onClick = { onBleDeviceEntry(selectedBleDevice) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(buttonHeight),
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${stringResource(R.string.use_bluetooth)} · ${selectedBleDevice.name}",
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(sectionGap))
            // Add Gallery Button
            OutlinedButton(
                onClick = { 
                    galleryDecodeInProgress = true
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    ) 
                },
                modifier = Modifier.fillMaxWidth().height(buttonHeight)
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.select_gallery_button))
            }

            Spacer(modifier = Modifier.height(if (compact) 12.dp else 16.dp))

            TextButton(
                onClick = { showManualEntry = true },
                        modifier = Modifier.fillMaxWidth().height(buttonHeight)

            ) {
                Text(stringResource(R.string.enter_code_manually))
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectTypeStep(
    compact: Boolean,
    selectedType: SibionicsType,
    onNavigateToReadiness: () -> Unit,
    onTypeSelected: (SibionicsType) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val horizontalPadding = if (compact) 16.dp else 24.dp
    val verticalPadding = if (compact) 12.dp else 16.dp
    val listGap = if (compact) 12.dp else 16.dp
    val cardPadding = if (compact) 16.dp else 20.dp
    val buttonHeight = 48.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        Spacer(modifier = Modifier.height(if (compact) 12.dp else 16.dp))
        Text(
            text = stringResource(R.string.select_sibionics_type),
            style = MaterialTheme.typography.headlineSmallEmphasized,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.choose_sibionics_variant),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
        tk.glucodata.ui.CgmReadinessSetupBanner(onOpenReadiness = onNavigateToReadiness)
        Spacer(modifier = Modifier.height(if (compact) 12.dp else 16.dp))

        // Selection Cards (M3 Expressive - no dividers, surface tonality)
        val listScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(listScrollState),
            verticalArrangement = Arrangement.spacedBy(listGap) // Generous spacing instead of dividers
        ) {
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(listGap),
            ) {
                SibionicsType.entries.filter { it.setupVisible }.forEach { type ->
                    val isSelected = (type == selectedType)

                    val containerColor by animateColorAsState(
                        targetValue = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        animationSpec = androidx.compose.animation.core.tween(250),
                        label = "containerColor"
                    )
                
                    val borderColor by animateColorAsState(
                        targetValue = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            Color.Transparent,
                        animationSpec = androidx.compose.animation.core.tween(250),
                        label = "borderColor"
                    )
                    val checkAlpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0f,
                        animationSpec = androidx.compose.animation.core.tween(180),
                        label = "checkAlpha"
                    )
                    val cardShape = RoundedCornerShape(if (compact) 16.dp else 20.dp)
                
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onTypeSelected(type) },
                        shape = cardShape,
                        color = containerColor,
                        border = androidx.compose.foundation.BorderStroke(2.dp, borderColor),
                        tonalElevation = if (isSelected) 0.dp else 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(cardPadding),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(type.displayNameRes),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        
                            Box(
                                modifier = Modifier.size(if (compact) 24.dp else 28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = checkAlpha),
                                    modifier = Modifier.size(if (compact) 20.dp else 24.dp)
                                )
                            }
                        }
                    }
                }
            }

        }

        Spacer(modifier = Modifier.height(if (compact) 12.dp else 16.dp))

        // Full-width Next button (M3 Expressive - prominent primary action)
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight),
        ) {
            Text(
                text = stringResource(R.string.continue_action),
                style = MaterialTheme.typography.labelLarge
            )
        }
        
        Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
        
        // Secondary cancel action
        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(buttonHeight),

            ) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
fun ScanTransmitterStep(
    compact: Boolean,
    resetEnabled: Boolean,
    onResetChanged: (Boolean) -> Unit,
    onScanClick: () -> Unit,
    onBack: () -> Unit,
    onDeviceFound: (String) -> Unit,
    onManualEntry: (String) -> Unit
) {
    var isScanning by remember { mutableStateOf(true) } // Active by default
    var foundDevices by remember { mutableStateOf(setOf<String>()) }
    var showManualEntry by remember { mutableStateOf(false) }
    val contentPadding = if (compact) 12.dp else 16.dp
    val buttonHeight = 48.dp
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Gallery Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val decoded = decodeBitmapQr(context, uri)
                if (decoded != null) {
                    onManualEntry(decoded)
                } else {
                    tk.glucodata.Applic.Toaster(tk.glucodata.Applic.app.getString(R.string.no_qr_found))
                }
            }
        }
    }

    // Auto-stop scanning after 10s is handled by the flow, but we track UI state
    LaunchedEffect(isScanning) {
        if (isScanning) {
            try {
                BleDeviceScanner.scanForSibionics().collect { name ->
                    if (name !in foundDevices) {
                        foundDevices = foundDevices + name
                    }
                }
            } finally {
                isScanning = false
            }
        }
    }

    if (showManualEntry) {
        ManualTransmitterEntryDialog(
             onDismiss = { showManualEntry = false },
             onConfirm = { code ->
                 showManualEntry = false
                 onManualEntry(code)
             }
        )
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top 
    ) {
        item {
            Spacer(modifier = Modifier.height(if (compact) 8.dp else 12.dp))
            
            Text(
                text = stringResource(R.string.scan_transmitter_title),
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
             Text(
                text = stringResource(R.string.scan_transmitter_desc),
                style = MaterialTheme.typography.bodyMedium,
                 textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = if (compact) 12.dp else 16.dp)
            )

            OutlinedButton(
                onClick = { isScanning = !isScanning },
                modifier = Modifier.fillMaxWidth().height(buttonHeight)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(if (compact) 20.dp else 24.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scanning_devices))
                } else {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search_bluetooth))
                }
            }
            Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
        }

        if (foundDevices.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.found_devices_title), 
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
                Spacer(Modifier.height(8.dp))
            }
            items(foundDevices.toList()) { device ->
                ListItem(
                    headlineContent = { Text(device) },
                    leadingContent = { Icon(Icons.Default.Bluetooth, null) },
                    modifier = Modifier
                        .clickable { onDeviceFound(device) }
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            item {
                Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
            }
        } else if (isScanning) {
             item {
                Text(
                    stringResource(R.string.looking_for_transmitters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
             }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (compact) 12.dp else 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onResetChanged(!resetEnabled) }
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(if (compact) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.resetname),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.clear_previous_state),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
                StyledSwitch(
                    checked = resetEnabled,
                    onCheckedChange = null
                )
            }

            Text(
               text = stringResource(R.string.or_scan_qr),
               style = MaterialTheme.typography.labelLarge,
               color = MaterialTheme.colorScheme.primary,
               modifier = Modifier.fillMaxWidth(),
               textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
    
            Button(
                onClick = onScanClick,
                modifier = Modifier.fillMaxWidth().height(buttonHeight)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_transmitter_button))
            }
            
            Spacer(Modifier.height(if (compact) 8.dp else 8.dp))

            // Add Gallery Button (Transmitter)
            OutlinedButton(
                onClick = { 
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    ) 
                },
                modifier = Modifier.fillMaxWidth().height(buttonHeight)
            ) {
                Icon(Icons.Default.Image, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.select_gallery_button))
            }

            Spacer(Modifier.height(if (compact) 8.dp else 8.dp))
            
            TextButton(
                onClick = { showManualEntry = true },
                modifier = Modifier.fillMaxWidth().height(buttonHeight)
            ) {
                 Text(stringResource(R.string.enter_code_manually))
            }
            
            Spacer(Modifier.height(if (compact) 16.dp else 24.dp))
        }
    }
}

@Composable
fun ConnectingStep(
    ui: WizardUiMetrics,
    sensorLabel: String?
) {
    SensorSetupConnectingScreen(
        ui = ui,
        sensorLabel = sensorLabel
    )
}

private fun finishSetup(sensorPtr: Long, reset: Boolean) {
    if (sensorPtr != 0L && reset) {
         // This might be redundant if already called in onScanClick, but safe
         Natives.setSensorptrResetSibionics2(sensorPtr, true)
    }

    if (Natives.getusebluetooth()) {
        tk.glucodata.SensorBluetooth.updateDevices()
        tk.glucodata.SuperGattCallback.glucosealarms.setLossAlarm()
    } else {
        Natives.updateUsedSensors()
    }
    tk.glucodata.Applic.wakemirrors()
    tk.glucodata.MainActivity.onSensorScanResult = null
    tk.glucodata.MainActivity.onTransmitterScanResult = null
}
