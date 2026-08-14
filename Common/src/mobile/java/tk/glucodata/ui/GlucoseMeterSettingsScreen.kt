@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import tk.glucodata.GlucoseMeterManager
import tk.glucodata.GlucoseMeterSnapshot
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.util.BleDeviceScanner
import tk.glucodata.ui.util.rememberBleScanner
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class NearbyGlucoseMeter(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val requiresSatelliteCode: Boolean,
)

private val satelliteMeterServiceUuid =
    UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

private val glucoseMeterServiceUuids = listOf(
    UUID.fromString("00001808-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("af9df7a1-e595-11e3-96b4-0002a5d5c51b"),
    satelliteMeterServiceUuid,
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
        scanning = true
        scanner.startScan(
            serviceUuids = glucoseMeterServiceUuids,
            onResult = { result ->
                val device = result.device
                val address = runCatching { device.address }.getOrNull() ?: return@startScan
                val name = runCatching { device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                    ?: return@startScan
                if (nearby.none { it.address == address }) {
                    val requiresSatelliteCode = result.scanRecord?.serviceUuids
                        ?.any { it.uuid == satelliteMeterServiceUuid } == true
                    nearby = nearby + NearbyGlucoseMeter(
                        device = device,
                        name = name,
                        address = address,
                        requiresSatelliteCode = requiresSatelliteCode,
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
                ) {
                    Text(stringResource(R.string.pair))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSatellite = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.meterlist)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item("meter_intro") {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(
                                Icons.Filled.Bloodtype,
                                contentDescription = null,
                                modifier = Modifier.padding(14.dp).size(28.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.glucose_meters_desc),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.glucose_meters_journal_desc),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item("configured_label") {
                SectionLabel(stringResource(R.string.glucose_meters_configured))
            }
            if (meters.isEmpty()) {
                item("configured_empty") {
                    SettingsItem(
                        title = stringResource(R.string.glucose_meters_none),
                        subtitle = stringResource(R.string.glucose_meters_none_desc),
                        icon = Icons.Filled.History,
                        iconTint = MaterialTheme.colorScheme.secondary,
                    )
                }
            } else {
                items(meters, key = GlucoseMeterSnapshot::index) { meter ->
                    val status = when {
                        meter.connected -> stringResource(R.string.connected)
                        meter.active -> stringResource(R.string.active)
                        else -> stringResource(R.string.off)
                    }
                    val lastReading = if (meter.lastReadingAt > 0L) {
                        stringResource(
                            R.string.glucose_meter_last_reading,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(meter.lastReadingAt))
                        )
                    } else {
                        stringResource(R.string.glucose_meter_no_readings)
                    }
                    SettingsSwitchItem(
                        title = meter.name,
                        subtitle = "$status · $lastReading",
                        checked = meter.active,
                        icon = Icons.Filled.Bloodtype,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onCheckedChange = { enabled ->
                            GlucoseMeterManager.setEnabled(meter.index, enabled)
                            meters = GlucoseMeterManager.configuredMeters()
                        },
                    )
                }
            }

            item("nearby_label") {
                SectionLabel(stringResource(R.string.glucose_meters_nearby))
            }
            item("scan_action") {
                Button(
                    onClick = {
                        val missing = requiredMeterPermissions().filter {
                            ContextCompat.checkSelfPermission(context, it) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) scanRequest += 1
                        else permissionLauncher.launch(missing.toTypedArray())
                    },
                    enabled = !scanning,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null)
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(if (scanning) R.string.scanning_devices else R.string.finddevices))
                }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            items(nearby, key = NearbyGlucoseMeter::address) { candidate ->
                SettingsItem(
                    title = candidate.name,
                    subtitle = candidate.address,
                    icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    position = CardPosition.SINGLE,
                    onClick = {
                        if (candidate.requiresSatelliteCode) {
                            satelliteCode = GlucoseMeterManager.satelliteCode()
                            pendingSatellite = candidate
                        } else {
                            addMeter(candidate)
                        }
                    },
                    trailingContent = { Text(stringResource(R.string.pair)) },
                )
            }
        }
    }
}

private fun requiredMeterPermissions(): List<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    else -> emptyList()
}
