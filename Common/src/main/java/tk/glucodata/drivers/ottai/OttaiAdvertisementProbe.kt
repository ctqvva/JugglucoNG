// OttaiAdvertisementProbe.kt — observe-only "is the sensor advertising?" scan during an outage.

package tk.glucodata.drivers.ottai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler

/**
 * Performs one short, filtered scan for a single Ottai sensor's advertisement after its link drops.
 *
 * Purely a measurement. The driver never scans during an outage — 16 SensorBluetooth lines in the
 * 8995-line 2026-08-01 trace, zero scan results, not one advertisement timestamp for
 * 70:D0:7E:42:31:DE — so the app cannot tell a jammed sensor from an absent one and the real
 * ceiling on any reconnect change is unknown. This answers only "when did it advertise again", and
 * deliberately does nothing with the answer: it never connects, never touches the parked
 * autoConnect GATT, and never feeds the activation-candidate path.
 *
 * Same shape as [tk.glucodata.drivers.sibionics.SibionicsAdvertisementRecovery], which is the
 * proven non-destructive scan in this app, including its filtered-scan requirement: Android 8.1+
 * silently discards results from an unfiltered scan while the screen is off, which is exactly the
 * background outage this runs in.
 */
@SuppressLint("MissingPermission")
internal class OttaiAdvertisementProbe(
    context: Context?,
    private val handler: Handler,
    private val onFinished: (rssi: Int?) -> Unit,
) {
    // Nullable, because a driver can be constructed from a headless service before Applic.app is
    // assigned, and a missing context must cost the measurement, not the sensor.
    private val appContext = context?.applicationContext
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var timeout: Runnable? = null

    @get:Synchronized
    val isActive: Boolean
        get() = callback != null

    @Synchronized
    fun start(address: String, timeoutMs: Long): Boolean {
        if (callback != null) return true
        val context = appContext ?: return false
        val bluetoothScanner = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?.takeIf { it.isEnabled }
                ?.bluetoothLeScanner
        }.getOrNull() ?: return false

        lateinit var scanCallback: ScanCallback
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!result.device.address.equals(address, ignoreCase = true)) return
                val rssi = result.rssi
                handler.post { complete(scanCallback, rssi) }
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
            // BALANCED, not the LOW_LATENCY the Sibionics recovery uses. That one REPLACES the
            // connect — SibionicsBleManager cancels its reconnect runnable before starting it —
            // whereas this scan runs alongside a connectGatt that is already initiating, in the
            // very regime the outage is about (~95% of the downtime is spent waiting for an
            // advertisement to get through). A 100%-duty-cycle scan across every outage would be
            // ~1300s of added continuous scanning per sensor over a window like 2026-08-01, and
            // could plausibly degrade the thing it exists to measure; "when did it advertise
            // again", to the second, tolerates a duty-cycled scan.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
            bluetoothScanner.startScan(filters, settings, scanCallback)
            true
        }.getOrElse {
            stop()
            false
        }
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

    private fun complete(expectedCallback: ScanCallback, rssi: Int?) {
        synchronized(this) {
            if (callback !== expectedCallback) return
            stop()
        }
        onFinished(rssi)
    }
}
