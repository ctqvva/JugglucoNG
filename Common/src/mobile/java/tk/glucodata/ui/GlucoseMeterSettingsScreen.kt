@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import tk.glucodata.GlucoseMeterManager
import tk.glucodata.GlucoseMeterSnapshot
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.glucosemeter.MeterAdvertisement
import tk.glucodata.glucosemeter.MeterNames
import tk.glucodata.glucosemeter.MeterSyncPacing
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsChoice
import tk.glucodata.ui.components.SettingsControlsCard
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsNavSwitchItem
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.rememberBleScanner
import java.util.Locale

internal data class NearbyGlucoseMeter(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val requiresSatelliteCode: Boolean,
    /** Advertises as a glucose meter; anything else is listed apart, as it may still be one. */
    val looksLikeMeter: Boolean,
)

@SuppressLint("MissingPermission")
@Composable
fun GlucoseMeterSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scanner = rememberBleScanner()
    var meters by remember { mutableStateOf(GlucoseMeterManager.configuredMeters()) }
    var nearby by remember { mutableStateOf<List<NearbyGlucoseMeter>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanRequest by remember { mutableIntStateOf(0) }
    var satelliteCode by remember { mutableStateOf(GlucoseMeterManager.satelliteCode()) }
    var pendingSatellite by remember { mutableStateOf<NearbyGlucoseMeter?>(null) }
    var pendingForget by remember { mutableStateOf<GlucoseMeterSnapshot?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    val editing = meters.firstOrNull { it.index == editingIndex }
    // Whether a search has run to its end, so "nothing found" is not said before one.
    var scanned by remember { mutableStateOf(false) }
    var recheckMinutes by remember { mutableStateOf(GlucoseMeterManager.recheckMinutes()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) scanRequest += 1
        else Toast.makeText(context, R.string.turn_on_nearby_devices_permission, Toast.LENGTH_LONG).show()
    }

    // Only poll while the screen is actually on-screen — composition alone outlives
    // backgrounding, and this loop would otherwise keep running there.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                meters = GlucoseMeterManager.configuredMeters()
                delay(2_000L)
            }
        }
    }

    LaunchedEffect(scanRequest) {
        if (scanRequest == 0) return@LaunchedEffect
        nearby = emptyList()
        scanned = false
        scanning = true
        scanner.startScan(
            // Unfiltered: not every meter advertises the glucose service (or the
            // Verio one), and Android filters the advertisement away before the
            // app ever sees it, so a filtered scan just reports nothing at all.
            // This is a foreground, user-driven scan, so the screen-off limits
            // on unfiltered scans do not apply here.
            serviceUuids = emptyList(),
            onResult = { result ->
                val device = result.device
                val address = runCatching { device.address }.getOrNull() ?: return@startScan
                val name = runCatching { device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                    ?: return@startScan
                if (nearby.none { it.address == address }) {
                    val services = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
                    nearby = nearby + NearbyGlucoseMeter(
                        device = device,
                        name = name,
                        address = address,
                        requiresSatelliteCode = MeterAdvertisement.isSatellite(name, services),
                        looksLikeMeter = MeterAdvertisement.looksLikeMeter(name, services),
                    )
                }
            },
            onError = { error ->
                scanning = false
                val message = if (error is BleDeviceScanner.ScanStartError.BluetoothDisabled) {
                    R.string.bluetooth_is_turned_off
                } else {
                    R.string.wentwrong
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        )
        delay(15_000L)
        scanner.stopScan()
        scanning = false
        scanned = true
        if (nearby.isEmpty()) {
            Log.i("GlucoseMeterSettings", "no nearby devices found in 15s")
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanner.stopScan() }
    }

    fun addMeter(candidate: NearbyGlucoseMeter) {
        val index = GlucoseMeterManager.add(candidate.device, candidate.name)
        if (index >= 0) {
            meters = GlucoseMeterManager.configuredMeters()
            nearby = nearby.filterNot { it.address == candidate.address }
        } else {
            Toast.makeText(context, R.string.wentwrong, Toast.LENGTH_LONG).show()
        }
    }

    pendingForget?.let { meter ->
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text(stringResource(R.string.glucose_meter_forget_title)) },
            text = { Text(stringResource(R.string.glucose_meter_forget_desc, meter.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        GlucoseMeterManager.forget(meter.index)
                        meters = GlucoseMeterManager.configuredMeters()
                        pendingForget = null
                    },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(stringResource(R.string.glucose_meter_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingForget = null }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingSatellite?.let { candidate ->
        AlertDialog(
            onDismissRequest = { pendingSatellite = null },
            title = { Text(stringResource(R.string.satellite_meter_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.satellite_meter_code_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = satelliteCode,
                        onValueChange = { value ->
                            satelliteCode = value.filter(Char::isLetterOrDigit)
                                .take(32)
                                .uppercase(Locale.US)
                        },
                        label = { Text(stringResource(R.string.satellite_meter_code_label)) },
                        singleLine = true,
                        isError = satelliteCode.isNotEmpty() &&
                            !GlucoseMeterManager.isSatelliteCodeValid(satelliteCode),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        GlucoseMeterManager.updateSatelliteCode(satelliteCode)
                        addMeter(candidate)
                        pendingSatellite = null
                    },
                    enabled = GlucoseMeterManager.isSatelliteCodeValid(satelliteCode),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(stringResource(R.string.pair))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSatellite = null }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    editing?.let { meter ->
        MeterDetailSheet(
            meter = meter,
            onDismiss = { editingIndex = null },
            onRename = { name ->
                GlucoseMeterManager.rename(meter, name)
                meters = GlucoseMeterManager.configuredMeters()
                editingIndex = null
            },
            onForget = {
                editingIndex = null
                pendingForget = meter
            },
        )
    }

    // A meter that is already set up is not offered again, and what the scan could not tell
    // from a meter waits behind its own row instead of burying the meters.
    val configuredAddresses = meters.mapNotNull(GlucoseMeterSnapshot::address).toSet()
    val candidates = nearby.filterNot { it.address in configuredAddresses }
    val nearbyMeters = candidates.filter(NearbyGlucoseMeter::looksLikeMeter)
    val otherDevices = candidates.filterNot(NearbyGlucoseMeter::looksLikeMeter)
    val pairCandidate: (NearbyGlucoseMeter) -> Unit = { candidate ->
        if (candidate.requiresSatelliteCode) {
            satelliteCode = GlucoseMeterManager.satelliteCode()
            pendingSatellite = candidate
        } else {
            addMeter(candidate)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.meterlist),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        GlucoseMeterSettingsContent(
            meters = meters,
            nearbyMeters = nearbyMeters,
            otherDevices = otherDevices,
            scanning = scanning,
            scanned = scanned,
            recheckMinutes = recheckMinutes,
            onMeterEnabled = { meter, enabled ->
                GlucoseMeterManager.setEnabled(meter.index, enabled)
                meters = GlucoseMeterManager.configuredMeters()
            },
            onMeterClick = { meter -> editingIndex = meter.index },
            onRecheckMinutes = { minutes ->
                recheckMinutes = minutes
                GlucoseMeterManager.setRecheckMinutes(minutes)
            },
            onStartScan = {
                val missing = requiredMeterPermissions().filter {
                    ContextCompat.checkSelfPermission(context, it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) scanRequest += 1
                else permissionLauncher.launch(missing.toTypedArray())
            },
            onStopScan = {
                scanner.stopScan()
                scanning = false
                scanned = true
            },
            onPair = pairCandidate,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/** The screen's content from plain state, so it can be drawn without Bluetooth or the meter table. */
@Composable
internal fun GlucoseMeterSettingsContent(
    meters: List<GlucoseMeterSnapshot>,
    nearbyMeters: List<NearbyGlucoseMeter>,
    otherDevices: List<NearbyGlucoseMeter>,
    scanning: Boolean,
    scanned: Boolean,
    recheckMinutes: Int,
    onMeterEnabled: (GlucoseMeterSnapshot, Boolean) -> Unit,
    onMeterClick: (GlucoseMeterSnapshot) -> Unit,
    onRecheckMinutes: (Int) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPair: (NearbyGlucoseMeter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recheckNow = stringResource(R.string.glucose_meter_recheck_now)
    val recheckLabel: (Int) -> String = { minutes ->
        if (minutes == 0) recheckNow else context.getString(R.string.minutes_short_format, minutes)
    }
    // Folded again by every new search, which starts from an empty list.
    var showOtherDevices by remember(otherDevices.isEmpty()) { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item("meter_intro") {
            Text(
                stringResource(R.string.glucose_meters_journal_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (meters.isNotEmpty()) {
            item("configured_label") {
                SectionLabel(stringResource(R.string.glucose_meters_configured))
            }
            // Only a meter that never sleeps waits between checks, so the choice shows
            // with one, as the last card of the meters it belongs to.
            val showRecheck = meters.any { it.staysOn }
            val groupSize = meters.size + if (showRecheck) 1 else 0
            itemsIndexed(meters, key = { _, meter -> meter.index }) { index, meter ->
                SettingsNavSwitchItem(
                    title = meter.name,
                    subtitle = meterRowSubtitle(meter),
                    checked = meter.active,
                    onCheckedChange = { enabled -> onMeterEnabled(meter, enabled) },
                    onClick = { onMeterClick(meter) },
                    icon = Icons.Filled.Bloodtype,
                    iconTint = MaterialTheme.colorScheme.primary,
                    position = cardPositionFor(index, groupSize),
                )
            }
            if (showRecheck) {
                item("recheck") {
                  Column {
                    SettingsControlsCard(position = cardPositionFor(meters.size, groupSize)) {
                        SettingsChoice(
                            title = stringResource(R.string.glucose_meter_recheck),
                            options = MeterSyncPacing.CHOICES_MINUTES,
                            labels = MeterSyncPacing.CHOICES_MINUTES.map(recheckLabel),
                            selectedOption = recheckMinutes,
                            onOptionSelected = onRecheckMinutes,
                        )
                    }
                    MeterCaption(stringResource(R.string.glucose_meter_recheck_hint))
                  }
                }
            }
        }

        item("nearby_label") {
            SectionLabel(stringResource(R.string.glucose_meters_nearby))
        }
        item("scan_action") {
            if (scanning) {
                OutlinedButton(
                    onClick = onStopScan,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(stringResource(R.string.stop))
                }
            } else {
                Button(
                    onClick = onStartScan,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.glucose_meters_find))
                }
            }
        }
        if (scanning) {
            item("scan_progress") {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
        if (scanned && !scanning && nearbyMeters.isEmpty()) {
            item("scan_empty") {
                MeterCaption(stringResource(R.string.glucose_meters_none_found), top = 12.dp)
            }
        }
        itemsIndexed(nearbyMeters, key = { _, candidate -> candidate.address }) { index, candidate ->
            NearbyDeviceRow(
                candidate = candidate,
                icon = Icons.Filled.Bloodtype,
                iconTint = MaterialTheme.colorScheme.primary,
                position = cardPositionFor(index, nearbyMeters.size),
                onPair = { onPair(candidate) },
                modifier = Modifier.padding(top = if (index == 0) 12.dp else 0.dp),
            )
        }
        if (otherDevices.isNotEmpty()) {
            item("other_devices") {
                val chevronRotation by animateFloatAsState(
                    targetValue = if (showOtherDevices) 180f else 0f,
                    label = "otherDevicesChevron",
                )
                SettingsItem(
                    title = stringResource(R.string.glucose_meters_other),
                    subtitle = stringResource(R.string.glucose_meters_other_desc),
                    icon = Icons.Filled.Bluetooth,
                    onClick = { showOtherDevices = !showOtherDevices },
                    position = if (showOtherDevices) CardPosition.TOP else CardPosition.SINGLE,
                    animatePosition = true,
                    modifier = Modifier.padding(top = 12.dp),
                    trailingContent = {
                        Text(
                            String.format(Locale.getDefault(), "%d", otherDevices.size),
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                        )
                    },
                )
            }
            if (showOtherDevices) {
                itemsIndexed(otherDevices, key = { _, candidate -> candidate.address }) { index, candidate ->
                    NearbyDeviceRow(
                        candidate = candidate,
                        icon = Icons.Filled.Bluetooth,
                        iconTint = null,
                        // The header row above is the top of this stack.
                        position = if (index == otherDevices.lastIndex) CardPosition.BOTTOM else CardPosition.MIDDLE,
                        onPair = { onPair(candidate) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * Whether the meter is linked right now, then when it last gave a reading. Off and waiting
 * need no words: the switch already says them.
 */
@Composable
private fun meterRowSubtitle(meter: GlucoseMeterSnapshot): String {
    val lastReading = if (meter.lastReadingAt > 0L) {
        stringResource(R.string.glucose_meter_last_reading, readingTimeText(meter.lastReadingAt))
    } else {
        stringResource(R.string.glucose_meter_no_readings)
    }
    return if (meter.connected) {
        "${stringResource(R.string.status_connected)} \u00b7 $lastReading"
    } else {
        lastReading
    }
}

/** "3 hours ago" within the week, a date after it. */
private fun readingTimeText(timeMillis: Long): String = DateUtils.getRelativeTimeSpanString(
    timeMillis,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_MONTH,
).toString()

@Composable
private fun MeterCaption(text: String, top: Dp = 8.dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = top, end = 16.dp),
    )
}

/** A device the scan found: what it is, and the one thing to do with it. */
@Composable
private fun NearbyDeviceRow(
    candidate: NearbyGlucoseMeter,
    icon: ImageVector,
    iconTint: Color?,
    position: CardPosition,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsItem(
        title = candidate.name,
        subtitle = candidate.address,
        icon = icon,
        iconTint = iconTint,
        position = position,
        modifier = modifier,
        trailingContent = {
            // An icon, not a label: beside a labelled button a name like SatelliteOnline2738
            // breaks in two on a phone.
            val connect = stringResource(R.string.mirror_connect_action)
            IconButtonTooltip(connect) {
                FilledTonalIconButton(onClick = onPair, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Filled.Add, contentDescription = connect)
                }
            }
        },
    )
}

/**
 * A meter has a name to give it and a way out when it is no longer used, so its row opens a
 * sheet, the way a pen's does.
 */
@Composable
private fun MeterDetailSheet(
    meter: GlucoseMeterSnapshot,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onForget: () -> Unit,
) {
    val stored = if (meter.name == meter.deviceName) "" else meter.name
    var name by remember(meter.index) { mutableStateOf(stored) }
    val changed = MeterNames.clean(name) != stored
    StableModalBottomSheet(onDismissRequest = onDismiss, adaptiveHeight = true) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(meter.name, style = MaterialTheme.typography.titleLargeEmphasized)
            Spacer(Modifier.size(4.dp))
            Text(
                listOfNotNull(meter.deviceName.takeIf { it != meter.name }, meter.address)
                    .joinToString(" \u00b7 ")
                    .ifEmpty { meterRowSubtitle(meter) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.replace('\n', ' ').take(MeterNames.MAX_LENGTH) },
                label = { Text(stringResource(R.string.glucose_meter_name)) },
                placeholder = { Text(meter.deviceName) },
                supportingText = { Text(stringResource(R.string.glucose_meter_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (changed) onRename(name) else onDismiss() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.size(16.dp))
            Button(
                onClick = { onRename(name) },
                enabled = changed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(stringResource(R.string.save))
            }

            Spacer(Modifier.size(8.dp))
            TextButton(
                onClick = onForget,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text(
                    stringResource(R.string.glucose_meter_forget_title),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun cardPositionFor(index: Int, size: Int): CardPosition = when {
    size <= 1 -> CardPosition.SINGLE
    index == 0 -> CardPosition.TOP
    index == size - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}

private fun requiredMeterPermissions(): List<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    else -> emptyList()
}
