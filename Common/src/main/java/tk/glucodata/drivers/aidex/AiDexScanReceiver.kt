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

        // Nothing may escape onReceive: ActivityThread turns an uncaught exception here into a
        // process kill, and that takes BLE, alarms, low alerts and the Nightscout upload down until
        // the user notices. gattcallbacks is restructured from background threads (updateDevicers,
        // registry adds) that take no lock at all, so even the copy inside mygatts() can throw —
        // the catch, not the snapshot, is what keeps a bad moment a lost alarm instead of a dead
        // process. mygatts() still earns its place: it shortens the exposure to a plain array copy
        // instead of a full fuzzy-match iteration over the live list.
        try {
            Log.d(TAG, "OnReceive: scan alarm for $serial")

            // Brief wake lock to ensure we handle the alarm before CPU returns to sleep
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiDexBleManager:ReceiverWakeup")
            wl?.acquire(10_000L) // 10s should be plenty to start the scan

            // Filter by type before matching, not after: SensorIdentity.matches is deliberately
            // fuzzy, so a stale generic shell that resolves to the same canonical sensor and stands
            // earlier in the list would win the search and eat this alarm — the doze-proof half of
            // the broadcast scan schedule — while logging "not found".
            val callback = SensorBluetooth.mygatts()
                .filterIsInstance<AiDexBleManager>()
                .find { it.SerialNumber == serial || SensorIdentity.matches(it.SerialNumber, serial) }
            if (callback != null) {
                callback.handleBroadcastScanAlarm("alarm")
            } else {
                Log.w(TAG, "Native AiDex sensor $serial not found in callbacks")
            }
        } catch (t: Throwable) {
            Log.stack(TAG, "onReceive $serial", t)
        }
    }
}
