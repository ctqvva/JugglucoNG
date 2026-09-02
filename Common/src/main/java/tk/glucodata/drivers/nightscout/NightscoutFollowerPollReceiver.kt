package tk.glucodata.drivers.nightscout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import tk.glucodata.Log
import tk.glucodata.SensorBluetooth

/**
 * Wake-up for the follower's poll.
 *
 * A follower has no sensor of its own, so nothing else wakes the phone on its behalf. The
 * in-process timer the driver used measures in uptime, which stops while the device sleeps,
 * so a poll due in a minute happened whenever something else woke the phone instead: the app
 * being opened, most visibly, which is why a stale follower repaired itself seconds after
 * being looked at. An alarm wakes the device on its own.
 *
 * The alarm's own wakelock ends when this method returns, and the fetch runs on the driver's
 * thread, so this takes one of its own and hands responsibility for releasing it to the poll.
 */
class NightscoutFollowerPollReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NightscoutFollower"
        const val ACTION_POLL = "tk.glucodata.drivers.nightscout.ACTION_FOLLOWER_POLL"
        const val EXTRA_SERIAL = "serial"

        /** Long enough for a slow server, short enough that a lost release cannot drain a phone. */
        private const val WAKELOCK_TIMEOUT_MS = 60_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_POLL) return
        val serial = intent.getStringExtra(EXTRA_SERIAL)?.trim().orEmpty()
        if (serial.isEmpty()) return

        // mygatts() copies under the list's own lock; the list itself is mutated from the
        // scan and connect paths, which can run while an alarm arrives.
        val follower = SensorBluetooth.mygatts().firstOrNull { callback ->
            callback is NightscoutFollowerManager && callback.SerialNumber.equals(serial, ignoreCase = true)
        } as? NightscoutFollowerManager
        if (follower == null) {
            Log.w(TAG, "Poll alarm for $serial with no follower running")
            return
        }

        val wakelock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Juggluco::NightscoutFollowerPoll")
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        follower.onPollAlarm(wakelock)
    }
}
