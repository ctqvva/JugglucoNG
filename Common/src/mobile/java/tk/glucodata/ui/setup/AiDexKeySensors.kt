package tk.glucodata.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tk.glucodata.SensorBluetooth
import tk.glucodata.drivers.aidex.AiDexDriver
import tk.glucodata.drivers.aidex.AiDexPairingMaterialFile
import tk.glucodata.drivers.aidex.AiDexSerialIdentity
import tk.glucodata.ui.util.rememberBleScanner

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

/** Observe BLE identities only. Choosing a serial never fetches a key or connects a sensor. */
@Composable
internal fun rememberAiDexKeySensors(nearby: List<AiDexKeySensor>): List<AiDexKeySensor> {
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
    val scanner = rememberBleScanner()
    DisposableEffect(scanner) {
        scanner.startScan(onResult = { result ->
            val address = runCatching { result.device.address }.getOrNull() ?: return@startScan
            val record = result.scanRecord
            val candidate = detectAiDexCandidate(address,
                runCatching { result.device.name }.getOrNull(), record?.deviceName,
                record?.bytes, record?.serviceUuids?.map { it.uuid })
            val serial = candidate.serial ?: return@startScan
            discovered = mergeAiDexKeySensors(discovered + AiDexKeySensor(serial, candidate.displayName, address))
        })
        onDispose { scanner.stopScan() }
    }
    return mergeAiDexKeySensors(known + discovered)
}
