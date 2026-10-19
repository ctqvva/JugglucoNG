package tk.glucodata.ui.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import tk.glucodata.Applic
import tk.glucodata.Log
import java.util.UUID

/**
 * BLE scanner used by AiDex setup wizard (instance methods) and
 * Sibionics setup wizard (companion scanForSibionics).
 *
 * Edit 72: Restored working scanForSibionics() from dev-latest.
 * The previous version was an empty stub that broke Sibionics sensor discovery.
 */
class BleDeviceScanner(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanCallback: ScanCallback? = null

    sealed class ScanStartError {
        object NoPermission : ScanStartError()
        object BluetoothDisabled : ScanStartError()
        object NoAdapter : ScanStartError()
        object ScannerUnavailable : ScanStartError()
        data class ScanFailed(val code: Int) : ScanStartError()
    }

    fun hasScanPermission(): Boolean = hasScanPermission(appContext)

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan(
        serviceUuids: List<UUID> = emptyList(),
        onResult: (ScanResult) -> Unit,
        onError: (ScanStartError) -> Unit = {}
    ) {
        if (!hasScanPermission()) {
            Log.e(LOG_ID, "startScan aborted: missing BLE scan permission")
            onError(ScanStartError.NoPermission)
            return
        }

        val btAdapter = adapter
        if (btAdapter == null) {
            Log.e(LOG_ID, "startScan aborted: Bluetooth adapter is null")
            onError(ScanStartError.NoAdapter)
            return
        }

        if (!btAdapter.isEnabled) {
            Log.e(LOG_ID, "startScan aborted: Bluetooth is disabled")
            onError(ScanStartError.BluetoothDisabled)
            return
        }

        val scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(LOG_ID, "startScan aborted: BluetoothLeScanner is null")
            onError(ScanStartError.ScannerUnavailable)
            return
        }

        if (scanCallback != null) {
            stopScan()
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let {
                    // Debug builds only: every setup wizard scans through here, and
                    // Log.i also feeds the trace ring users attach to reports, which
                    // would otherwise fill with the names and addresses of every
                    // device nearby.
                    if (Log.doLog) {
                        val uuids = it.scanRecord?.serviceUuids?.map { s -> s.uuid.toString() }
                        Log.i(LOG_ID, "scan result name=${runCatching { it.device.name }.getOrNull()}" +
                            " recordName=${it.scanRecord?.deviceName}" +
                            " addr=${runCatching { it.device.address }.getOrNull()}" +
                            " rssi=${it.rssi} services=$uuids")
                    }
                    onResult(it)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(LOG_ID, "BLE scan failed with errorCode=$errorCode")
                onError(ScanStartError.ScanFailed(errorCode))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = serviceUuids.map { uuid ->
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(uuid))
                .build()
        }

        try {
            scanner.startScan(filters.ifEmpty { null }, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(LOG_ID, "SecurityException during startScan: " + e.message)
            scanCallback = null
            onError(ScanStartError.NoPermission)
        } catch (e: Exception) {
            Log.e(LOG_ID, "Exception during startScan: " + e.message)
            scanCallback = null
            onError(ScanStartError.ScannerUnavailable)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null

        val btAdapter = adapter ?: return
        if (!btAdapter.isEnabled) {
            return
        }

        try {
            btAdapter.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.e(LOG_ID, "SecurityException during stopScan: " + e.message)
        } catch (e: Exception) {
            Log.e(LOG_ID, "Exception during stopScan: " + e.message)
        }
    }

    data class SibionicsBleDevice(
        val name: String,
        val address: String,
        val rssi: Int
    )

    companion object {
        private const val SCAN_DURATION = 10000L // 10 seconds
        private const val LOG_ID = "BleDeviceScanner"
        private val SIBIONICS_SERVICE_UUID: UUID = UUID.fromString("0000ff30-0000-1000-8000-00805f9b34fb")

        /** Check BLE scan permissions (mirrors Applic.mayscan() which is package-private) */
        private fun hasScanPermission(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 31) {
                return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            }
            if (Build.VERSION.SDK_INT >= 23) {
                return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
            return true
        }

        private fun hasScanPermission(): Boolean {
            val ctx = Applic.app ?: return false
            return hasScanPermission(ctx)
        }

        /**
         * Scans for Sibionics transmitters (BLE devices whose name starts with "P").
         * Returns a Flow that emits discovered device names, auto-stops after 10s.
         *
         * Edit 72: Restored from dev-latest. The previous version was an empty stub
         * that returned an empty flow, making Sibionics sensor setup impossible.
         */
        @SuppressLint("MissingPermission")
        fun scanForSibionics(): Flow<String> = callbackFlow {
            // Enforce permission check before interacting with Bluetooth adapter
            if (!hasScanPermission()) {
                Log.e(LOG_ID, "No bluetooth scan permissions, aborting scan")
                close()
                return@callbackFlow
            }

            val bluetoothManager = Applic.app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter

            // Double check adapter exists and is enabled
            if (adapter == null || !adapter.isEnabled) {
                Log.e(LOG_ID, "Bluetooth adapter null or disabled")
                close()
                return@callbackFlow
            }

            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                Log.e(LOG_ID, "BluetoothLeScanner is null")
                close()
                return@callbackFlow
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // Keep track of found devices to avoid duplicates in the stream
            val foundDevices = mutableSetOf<String>()

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.device?.let { device ->
                        val name = device.name
                        // Filter for devices starting with "P" (common for Sibionics/SiBio transmitters)
                        if (name != null && name.startsWith("P") && !foundDevices.contains(name)) {
                            foundDevices.add(name)
                            trySend(name)
                        }
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach { result ->
                        val name = result.device.name
                        if (name != null && name.startsWith("P") && !foundDevices.contains(name)) {
                            foundDevices.add(name)
                            trySend(name)
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(LOG_ID, "Scan failed with error code: $errorCode")
                    close()
                }
            }

            try {
                Log.i(LOG_ID, "Starting BLE scan for Sibionics...")
                scanner.startScan(null, settings, callback)
            } catch (e: SecurityException) {
                Log.e(LOG_ID, "SecurityException during startScan: " + e.message)
                close()
                return@callbackFlow
            } catch (e: Exception) {
                Log.e(LOG_ID, "Exception during startScan: " + e.message)
                close()
                return@callbackFlow
            }

            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                try {
                    if (hasScanPermission() && adapter.isEnabled) {
                        scanner.stopScan(callback)
                    }
                } catch (e: Exception) {
                    Log.e(LOG_ID, "Error stopping scan: " + e.message)
                }
                close()
            }, SCAN_DURATION)

            awaitClose {
                try {
                    if (hasScanPermission() && adapter.isEnabled) {
                        scanner.stopScan(callback)
                    }
                } catch (e: Exception) {
                    Log.e(LOG_ID, "Error stopping scan in awaitClose: " + e.message)
                }
                handler.removeCallbacksAndMessages(null)
            }
        }

        @SuppressLint("MissingPermission")
        fun scanForSibionicsByService(): Flow<SibionicsBleDevice> = callbackFlow {
            if (!hasScanPermission()) {
                Log.e(LOG_ID, "No bluetooth scan permissions, aborting FF30 scan")
                close()
                return@callbackFlow
            }

            val bluetoothManager = Applic.app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                Log.e(LOG_ID, "Bluetooth adapter null or disabled (FF30 scan)")
                close()
                return@callbackFlow
            }

            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                Log.e(LOG_ID, "BluetoothLeScanner is null (FF30 scan)")
                close()
                return@callbackFlow
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(SIBIONICS_SERVICE_UUID))
                    .build()
            )

            val seenAddresses = mutableSetOf<String>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    if (result == null) return
                    val address = result.device?.address ?: return
                    val record = result.scanRecord
                    val hasFf30 = record?.serviceUuids?.any { it.uuid == SIBIONICS_SERVICE_UUID } == true
                    if (!hasFf30) return

                    val name = result.device?.name ?: record.deviceName
                    if (name.isNullOrBlank()) return
                    if (!seenAddresses.add(address)) return

                    trySend(
                        SibionicsBleDevice(
                            name = name,
                            address = address,
                            rssi = result.rssi
                        )
                    )
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(LOG_ID, "FF30 scan failed with error code: $errorCode")
                    close()
                }
            }

            try {
                Log.i(LOG_ID, "Starting FF30 BLE scan for Sibionics-like devices...")
                scanner.startScan(filters, settings, callback)
            } catch (e: SecurityException) {
                Log.e(LOG_ID, "SecurityException during FF30 startScan: " + e.message)
                close()
                return@callbackFlow
            } catch (e: Exception) {
                Log.e(LOG_ID, "Exception during FF30 startScan: " + e.message)
                close()
                return@callbackFlow
            }

            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                try {
                    if (hasScanPermission() && adapter.isEnabled) {
                        scanner.stopScan(callback)
                    }
                } catch (e: Exception) {
                    Log.e(LOG_ID, "Error stopping FF30 scan: " + e.message)
                }
                close()
            }, SCAN_DURATION)

            awaitClose {
                try {
                    if (hasScanPermission() && adapter.isEnabled) {
                        scanner.stopScan(callback)
                    }
                } catch (e: Exception) {
                    Log.e(LOG_ID, "Error stopping FF30 scan in awaitClose: " + e.message)
                }
                handler.removeCallbacksAndMessages(null)
            }
        }
    }
}

@Composable
fun rememberBleScanner(): BleDeviceScanner {
    val context = LocalContext.current
    return remember { BleDeviceScanner(context) }
}
