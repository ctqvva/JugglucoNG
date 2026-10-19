package tk.glucodata.drivers.icanhealth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import tk.glucodata.Log
import tk.glucodata.SensorBluetooth

/**
 * Wake-up receiver for iCan reconnect alarms.
 *
 * The driver still uses an in-process delayed reconnect for normal foreground
 * timing, but this receiver provides an exact AlarmManager wake-up when the app
 * is backgrounded and Android may defer the in-process scheduler.
 */
class ICanHealthReconnectReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ICanHealthReconnect"
        const val ACTION_ICAN_RECONNECT = "tk.glucodata.drivers.icanhealth.ACTION_ICAN_RECONNECT"
        const val EXTRA_SERIAL = "serial"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_TRIGGER_AT_MS = "trigger_at_ms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ICAN_RECONNECT) {
            return
        }

        val serial = intent.getStringExtra(EXTRA_SERIAL)?.trim().orEmpty()
        val address = intent.getStringExtra(EXTRA_ADDRESS)?.trim().orEmpty()
        val token = intent.getLongExtra(EXTRA_TOKEN, -1L)
        val triggerAtMs = intent.getLongExtra(EXTRA_TRIGGER_AT_MS, 0L)
        if (serial.isEmpty() && address.isEmpty()) {
            return
        }

        Log.d(TAG, "OnReceive reconnect alarm serial=$serial address=$address token=$token triggerAtMs=$triggerAtMs")

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ICanHealth:ReconnectWakeup")
        wl?.acquire(10_000L)

        val callback = SensorBluetooth.gattcallbacks.firstOrNull { cb ->
            cb is ICanHealthBleManager &&
                (
                    (serial.isNotEmpty() && cb.SerialNumber.equals(serial, ignoreCase = true)) ||
                        (address.isNotEmpty() && cb.mActiveDeviceAddress?.equals(address, ignoreCase = true) == true)
                    )
        }

        if (callback is ICanHealthBleManager) {
            callback.handleReconnectAlarm(token)
        } else {
            Log.w(TAG, "No iCan callback found for reconnect alarm serial=$serial address=$address")
        }
    }
}
