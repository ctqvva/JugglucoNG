package tk.glucodata.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tk.glucodata.SensorBluetooth
import androidx.compose.ui.platform.LocalContext
import tk.glucodata.drivers.aidex.AiDexSetupKeyGate
import tk.glucodata.drivers.aidex.AiDexDriver
import tk.glucodata.drivers.aidex.AiDexPairingMaterialFile
import tk.glucodata.drivers.aidex.AiDexSerialIdentity
import tk.glucodata.ui.util.rememberBleScanner
import tk.glucodata.ui.util.BleDeviceScanner

internal data class AiDexKeySensor(
    val serial: String,
    val name: String,
    val address: String,
    val connected: Boolean = false,
)

internal fun mergeAiDexKeySensors(sensors: List<AiDexKeySensor>): List<AiDexKeySensor> =
    sensors.mapNotNull { sensor ->
        AiDexPairingMaterialFile.normalizeSerial(sensor.serial)?.let { sensor.copy(serial = it) }
    }.sortedByDescending { it.connected }.distinctBy { it.serial }

internal fun aiDexKeySensorFromIdentity(
    storedSerial: String,
    name: String,
    address: String,
    connected: Boolean,
): AiDexKeySensor? {
    val fallback = AiDexSerialIdentity.fallbackCanonicalFromAddress(address)
    val serial = listOfNotNull(AiDexSerialIdentity.canonicalFromAdvertisement(name), storedSerial)
        .filterNot { it.equals(fallback, ignoreCase = true) }
        .firstNotNullOfOrNull(AiDexPairingMaterialFile::normalizeSerial) ?: return null
    return AiDexKeySensor(serial, name, address, connected)
}

internal data class AiDexKeyScanState(
    val sensors: List<AiDexKeySensor>,
    val searching: Boolean,
    val error: BleDeviceScanner.ScanStartError?,
)

/** Observe BLE identities only. Choosing a serial never fetches a key or connects a sensor. */
@Composable
internal fun rememberAiDexKeyScan(nearby: List<AiDexKeySensor>): AiDexKeyScanState {
    val context = LocalContext.current
    val known = remember {
        SensorBluetooth.gattcallbacks.mapNotNull { callback ->
            val driver = callback as? AiDexDriver ?: return@mapNotNull null
            val address = callback.mActiveDeviceAddress.orEmpty()
            val name = runCatching { callback.mygetDeviceName() }.getOrNull().orEmpty()
            aiDexKeySensorFromIdentity(callback.SerialNumber.orEmpty(), name, address,
                runCatching { driver.isVendorConnected() }.getOrDefault(false))
        }
    }
    var discovered by remember { mutableStateOf(nearby) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<BleDeviceScanner.ScanStartError?>(null) }
    val scanner = rememberBleScanner()
    DisposableEffect(scanner) {
        searching = true
        error = null
        scanner.startScan(onResult = { result ->
            val address = runCatching { result.device.address }.getOrNull() ?: return@startScan
            val record = result.scanRecord
            val candidate = detectAiDexCandidate(address,
                runCatching { result.device.name }.getOrNull(), record?.deviceName,
                record?.bytes, record?.serviceUuids?.map { it.uuid })
            val serial = candidate.serial ?: return@startScan
            AiDexSetupKeyGate.requiresKeys(context, serial, candidate.displayName)
            discovered = mergeAiDexKeySensors(discovered + AiDexKeySensor(serial, candidate.displayName, address))
        }, onError = { failure ->
            searching = false
            error = failure
        })
        onDispose { scanner.stopScan() }
    }
    return AiDexKeyScanState(mergeAiDexKeySensors(known + discovered), searching, error)
}
