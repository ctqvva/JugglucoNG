package tk.glucodata.drivers.sibionics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.PowerManager

internal data class SibionicsAdvertisement(
    val device: BluetoothDevice,
    val name: String?,
)

/**
 * Performs one short scan after Android accepts connectGatt() but never reports a callback,
 * or after a connect attempt fails.
 * Some post-boot Bluetooth stacks will not connect to a saved address until its advertisement
 * has been observed again.
 */
@SuppressLint("MissingPermission")
internal class SibionicsAdvertisementRecovery(
    context: Context,
    private val handler: Handler,
    private val onFinished: (SibionicsAdvertisement?) -> Unit,
) {
    private val appContext = context.applicationContext
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var timeout: Runnable? = null

    @get:Synchronized
    val isActive: Boolean
        get() = callback != null

    @Synchronized
    fun start(address: String, advertisedName: String?, timeoutMs: Long): Boolean {
        if (callback != null) return true
        val bluetoothScanner = runCatching {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?.takeIf { it.isEnabled }
                ?.bluetoothLeScanner
        }.getOrNull() ?: return false

        lateinit var scanCallback: ScanCallback
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!result.device.address.equals(address, ignoreCase = true)) return
                val advertisement = SibionicsAdvertisement(
                    device = result.device,
                    name = result.scanRecord?.deviceName ?: result.device.name,
                )
                handler.post { complete(scanCallback, advertisement) }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post { complete(scanCallback, null) }
            }
        }

        scanner = bluetoothScanner
        callback = scanCallback
        timeout = Runnable { complete(scanCallback, null) }.also {
            handler.postDelayed(it, timeoutMs.coerceAtLeast(1L))
        }
        return runCatching {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // With the screen on, do not depend on an offloaded address filter: observing
            // any advertisement is short-lived, and the callback matches the address
            // strictly. With the screen off Android delivers nothing to an unfiltered scan
            // at all, so every background recovery used to wait out its timeout blind.
            bluetoothScanner.startScan(screenOffFilters(address, advertisedName), settings, scanCallback)
            true
        }.getOrElse {
            stop()
            false
        }
    }

    /** Null (unfiltered) while interactive; address OR advertised name otherwise. */
    private fun screenOffFilters(address: String, advertisedName: String?): List<ScanFilter>? {
        val interactive = runCatching {
            (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive
        }.getOrNull() ?: true
        if (interactive) return null
        val filters = buildList {
            runCatching { ScanFilter.Builder().setDeviceAddress(address.uppercase()).build() }
                .getOrNull()?.let(::add)
            advertisedName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                runCatching { ScanFilter.Builder().setDeviceName(name).build() }.getOrNull()?.let(::add)
            }
        }
        return filters.ifEmpty { null }
    }

    @Synchronized
    fun stop() {
        val activeCallback = callback
        timeout?.let(handler::removeCallbacks)
        timeout = null
        callback = null
        if (activeCallback != null) {
            runCatching { scanner?.stopScan(activeCallback) }
        }
        scanner = null
    }

    private fun complete(
        expectedCallback: ScanCallback,
        advertisement: SibionicsAdvertisement?,
    ) {
        synchronized(this) {
            if (callback !== expectedCallback) return
            stop()
        }
        onFinished(advertisement)
    }
}
