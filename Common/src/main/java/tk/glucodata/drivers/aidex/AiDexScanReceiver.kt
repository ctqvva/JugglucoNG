package tk.glucodata.drivers.aidex

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import tk.glucodata.Log
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.drivers.aidex.native.ble.AiDexBleManager

/**
 * Receiver for AiDex broadcast scan wake-up alarms.
 * This wakes up the CPU to ensure the scan cycle continues even in deep sleep.
 */
class AiDexScanReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AiDexScanReceiver"
        const val ACTION_AIDEX_SCAN = "tk.glucodata.drivers.aidex.ACTION_AIDEX_SCAN"
        const val EXTRA_SERIAL = "serial"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AIDEX_SCAN) return
        
        val serial = intent.getStringExtra(EXTRA_SERIAL) ?: return
        Log.d(TAG, "OnReceive: scan alarm for $serial")

        // Brief wake lock to ensure we handle the alarm before CPU returns to sleep
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiDexBleManager:ReceiverWakeup")
        wl?.acquire(10_000L) // 10s should be plenty to start the scan

        val callback = SensorBluetooth.gattcallbacks.find {
            it.SerialNumber == serial || SensorIdentity.matches(it.SerialNumber, serial)
        }
        if (callback is AiDexBleManager) {
            callback.handleBroadcastScanAlarm("alarm")
        } else {
            Log.w(TAG, "Native AiDex sensor $serial not found in callbacks")
        }
    }
}
