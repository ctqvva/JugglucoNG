@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
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
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsChoice
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.rememberBleScanner
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import tk.glucodata.ui.components.DeviceAction
import tk.glucodata.ui.components.DeviceActionTone
import tk.glucodata.ui.components.DeviceCard
import tk.glucodata.ui.components.DeviceCardGap
import tk.glucodata.ui.components.DeviceDetail
import tk.glucodata.ui.components.DevicePauseButton
import tk.glucodata.ui.symbols.Symbols

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
    // What a configured Satellite is read with; [satelliteCode] is the pairing dialog's draft.
    var storedSatelliteCode by remember { mutableStateOf(GlucoseMeterManager.satelliteCode()) }
    var nowMinute by remember { mutableStateOf(System.currentTimeMillis() / 60_000L) }
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
                // The meters rarely change between polls, so the "3 minutes ago" on their
                // rows needs a clock of its own to move; a minute is all it shows.
                nowMinute = System.currentTimeMillis() / 60_000L
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
                        storedSatelliteCode = GlucoseMeterManager.satelliteCode()
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
            nowMillis = nowMinute * 60_000L,
            nearbyMeters = nearbyMeters,
            otherDevices = otherDevices,
            scanning = scanning,
            scanned = scanned,
            recheckMinutes = recheckMinutes,
            onMeterEnabled = { meter, enabled ->
                GlucoseMeterManager.setEnabled(meter.index, enabled)
                meters = GlucoseMeterManager.configuredMeters()
            },
            satelliteCode = storedSatelliteCode,
            onMeterSave = { meter, name, code ->
                GlucoseMeterManager.rename(meter, name)
                if (code != null) {
                    GlucoseMeterManager.updateSatelliteCode(code)
                    storedSatelliteCode = GlucoseMeterManager.satelliteCode()
                }
                meters = GlucoseMeterManager.configuredMeters()
            },
            onMeterForget = { meter -> pendingForget = meter },
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
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        )
    }
}

/** The screen's content from plain state, so it can be drawn without Bluetooth or the meter table. */
@Composable
internal fun GlucoseMeterSettingsContent(
    meters: List<GlucoseMeterSnapshot>,
    nowMillis: Long,
    nearbyMeters: List<NearbyGlucoseMeter>,
    otherDevices: List<NearbyGlucoseMeter>,
    scanning: Boolean,
    scanned: Boolean,
    recheckMinutes: Int,
    satelliteCode: String,
    onMeterEnabled: (GlucoseMeterSnapshot, Boolean) -> Unit,
    onMeterSave: (GlucoseMeterSnapshot, name: String, satelliteCode: String?) -> Unit,
    onMeterForget: (GlucoseMeterSnapshot) -> Unit,
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
            itemsIndexed(meters, key = { _, meter -> meter.index }) { index, meter ->
                MeterCard(
                    meter = meter,
                    nowMillis = nowMillis,
                    satelliteCode = satelliteCode.takeIf { meter.needsSatelliteCode },
                    recheckMinutes = recheckMinutes,
                    recheckLabels = MeterSyncPacing.CHOICES_MINUTES.map(recheckLabel),
                    onRecheckMinutes = onRecheckMinutes,
                    onEnabled = { enabled -> onMeterEnabled(meter, enabled) },
                    onSave = { name, code -> onMeterSave(meter, name, code) },
                    onForget = { onMeterForget(meter) },
                    // The list's own gap is the 2dp of a grouped stack; cards stand apart.
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else DeviceCardGap - 2.dp),
                )
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
                        Symbols.BluetoothSearchingFilled,
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
        // With other devices found, their row already says the meter may be among them.
        if (scanned && !scanning && nearbyMeters.isEmpty() && otherDevices.isEmpty()) {
            item("scan_empty") {
                MeterCaption(stringResource(R.string.glucose_meters_none_found), top = 12.dp)
            }
        }
        itemsIndexed(nearbyMeters, key = { _, candidate -> candidate.address }) { index, candidate ->
            NearbyDeviceRow(
                candidate = candidate,
                icon = Symbols.BloodtypeFilled,
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
                    icon = Symbols.BluetoothFilled,
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
                            Symbols.ExpandMoreFilled,
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
                        icon = Symbols.BluetoothFilled,
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

/** "3 hours ago" within the week, a date after it. */
private fun readingTimeText(timeMillis: Long, nowMillis: Long): String = DateUtils.getRelativeTimeSpanString(
    timeMillis,
    // The clock ticks in whole minutes, so a reading from this minute is not "in the future".
    maxOf(nowMillis, timeMillis),
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

/** A device the scan found. The whole row connects it, as every row on this screen acts. */
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
        onClick = onPair,
        trailingContent = {
            Icon(
                Symbols.AddFilled,
                contentDescription = stringResource(R.string.mirror_connect_action),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * A configured meter, as the Mirror screen shows a link ([DeviceCard]): its name and state, when
 * it last gave a reading (the rest on a tap), then [Edit] [Forget]. Edit opens the name, and a
 * Satellite's code, in the card itself, so a wrong code is corrected where it shows.
 */
@Composable
private fun MeterCard(
    meter: GlucoseMeterSnapshot,
    nowMillis: Long,
    satelliteCode: String?,
    recheckMinutes: Int,
    recheckLabels: List<String>,
    onRecheckMinutes: (Int) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onSave: (name: String, satelliteCode: String?) -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable(meter.index) { mutableStateOf(false) }
    var detailsOpen by rememberSaveable(meter.index) { mutableStateOf(false) }
    val lastReading = if (meter.lastReadingAt > 0L) {
        readingTimeText(meter.lastReadingAt, nowMillis)
    } else {
        stringResource(R.string.glucose_meter_no_readings)
    }
    DeviceCard(
        title = meter.name,
        modifier = modifier,
        active = meter.active,
        status = stringResource(
            when {
                !meter.active -> R.string.deactivated
                meter.connected -> R.string.status_connected
                // A meter is asleep between measurements; it links again when it wakes.
                else -> R.string.status_waiting_for_connection
            }
        ),
        statusIcon = when {
            !meter.active -> Symbols.BluetoothDisabledFilled
            meter.connected -> Symbols.BluetoothConnectedFilled
            else -> Symbols.BluetoothFilled
        },
        glance = listOf(DeviceDetail(stringResource(R.string.glucose_meter_last_reading), lastReading)),
        details = listOfNotNull(
            // Once renamed, the name the meter itself gives is what tells it apart.
            meter.deviceName.takeIf { it != meter.name }
                ?.let { DeviceDetail(stringResource(R.string.glucose_meter_device_name), it) },
            meter.address?.let { DeviceDetail(stringResource(R.string.sensor_address), it) },
            satelliteCode?.takeIf { it.isNotEmpty() }
                ?.let { DeviceDetail(stringResource(R.string.satellite_meter_code_label), it) },
        ),
        detailsOpen = detailsOpen,
        onDetailsOpenChange = { detailsOpen = it },
        headerAction = {
            DevicePauseButton(
                paused = !meter.active,
                pauseLabel = stringResource(R.string.disable),
                resumeLabel = stringResource(R.string.enable),
                onClick = { onEnabled(!meter.active) },
            )
        },
        actions = listOf(
            DeviceAction(
                label = stringResource(R.string.edit),
                icon = Symbols.EditFilled,
                tone = if (editing) DeviceActionTone.Selected else DeviceActionTone.Neutral,
                onClick = { editing = !editing },
            ),
            DeviceAction(
                label = stringResource(R.string.glucose_meter_forget),
                icon = Symbols.DeleteFilled,
                tone = DeviceActionTone.Destructive,
                onClick = onForget,
            ),
        ),
        extra = {
            AnimatedVisibility(visible = editing) {
                MeterEditor(
                    meter = meter,
                    satelliteCode = satelliteCode,
                    onSave = { name, code ->
                        onSave(name, code)
                        editing = false
                    },
                    onClose = { editing = false },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            // Only a meter that never sleeps waits between checks, so its card holds the wait.
            if (meter.staysOn) {
                Column(Modifier.padding(top = 16.dp)) {
                    SettingsChoice(
                        title = stringResource(R.string.glucose_meter_recheck),
                        options = MeterSyncPacing.CHOICES_MINUTES,
                        labels = recheckLabels,
                        selectedOption = recheckMinutes,
                        onOptionSelected = onRecheckMinutes,
                    )
                    Text(
                        stringResource(R.string.glucose_meter_recheck_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/**
 * The name, and a Satellite's code, opened inside the meter's card under its buttons. The name
 * field takes the focus, and Save stays in view above the keyboard.
 */
@Composable
private fun MeterEditor(
    meter: GlucoseMeterSnapshot,
    satelliteCode: String?,
    onSave: (name: String, satelliteCode: String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val storedName = if (meter.name == meter.deviceName) "" else meter.name
    var name by rememberSaveable(meter.index) { mutableStateOf(storedName) }
    var code by rememberSaveable(meter.index) { mutableStateOf(satelliteCode.orEmpty()) }
    val codeChanged = satelliteCode != null && code != satelliteCode
    val codeValid = !codeChanged || GlucoseMeterManager.isSatelliteCodeValid(code)
    val changed = MeterNames.clean(name) != storedName || codeChanged
    val canSave = changed && codeValid
    val save = { onSave(name, code.takeIf { codeChanged }) }
    // Done saves, or closes an editor with nothing in it to keep; an invalid code stays open
    // to be corrected instead of taking the rest of the edit with it.
    val done = {
        when {
            canSave -> save()
            !changed -> onClose()
        }
    }

    val focus = remember { FocusRequester() }
    val saveInView = remember { BringIntoViewRequester() }
    val keyboardUp = WindowInsets.isImeVisible
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(keyboardUp) { if (keyboardUp) saveInView.bringIntoView() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.replace('\n', ' ').take(MeterNames.MAX_LENGTH) },
            label = { Text(stringResource(R.string.glucose_meter_name)) },
            placeholder = { Text(meter.deviceName) },
            supportingText = { Text(stringResource(R.string.glucose_meter_name_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = if (satelliteCode != null) ImeAction.Next else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { done() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (satelliteCode != null) {
            OutlinedTextField(
                value = code,
                onValueChange = { value ->
                    code = value.filter(Char::isLetterOrDigit).take(32).uppercase(Locale.US)
                },
                label = { Text(stringResource(R.string.satellite_meter_code_label)) },
                singleLine = true,
                isError = code.isNotEmpty() && !GlucoseMeterManager.isSatelliteCodeValid(code),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { done() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = save,
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(saveInView),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Text(stringResource(R.string.save))
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
