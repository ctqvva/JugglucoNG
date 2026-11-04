package tk.glucodata

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * Wake-up for a meter's paced recheck (MeterSyncPacing).
 *
 * The link to a meter that stays on is closed while it waits, so nothing in Bluetooth wakes the
 * phone for the next dial. An in-process timer measures uptime, which stops while the device
 * sleeps: with no sensor or other wake source the five minutes became however long the phone was
 * left alone. An alarm wakes the device on its own. The ticket travels with the alarm, so one
 * that was cancelled or superseded while it was being delivered does nothing.
 */
class MeterRecheckAlarm : BroadcastReceiver() {
    companion object {
        private const val LOG_ID = "MeterRecheckAlarm"
        private const val ACTION_RECHECK = "tk.glucodata.ACTION_METER_RECHECK"
        private const val EXTRA_METER = "meter"
        private const val EXTRA_TICKET = "ticket"

        @JvmStatic
        fun set(meterIndex: Int, ticket: Long, delayMillis: Long) {
            val alarms = Applic.app?.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val pending = intent(meterIndex, ticket)
            if (alarms == null || pending == null) {
                fallback(meterIndex, ticket, delayMillis)
                return
            }
            val at = SystemClock.elapsedRealtime() + delayMillis
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            runCatching {
                if (exact) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
                } else {
                    // Still wakes the device; only its timing is the system's to choose.
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
                }
            }.onFailure {
                Log.e(LOG_ID, "$meterIndex alarm refused, falling back to an in-process timer: ${it.message}")
                fallback(meterIndex, ticket, delayMillis)
            }
        }

        @JvmStatic
        fun cancel(meterIndex: Int) {
            val alarms = Applic.app?.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            intent(meterIndex, 0L)?.let { pending -> runCatching { alarms?.cancel(pending) } }
        }

        private fun fallback(meterIndex: Int, ticket: Long, delayMillis: Long) {
            Applic.scheduler.schedule(
                { BluetoothGlucoseMeter.pacedRecheck(meterIndex, ticket) },
                delayMillis,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun intent(meterIndex: Int, ticket: Long): PendingIntent? {
            val app = Applic.app ?: return null
            val intent = Intent(app, MeterRecheckAlarm::class.java).apply {
                action = ACTION_RECHECK
                putExtra(EXTRA_METER, meterIndex)
                putExtra(EXTRA_TICKET, ticket)
            }
            return PendingIntent.getBroadcast(
                app,
                meterIndex,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECHECK) return
        // Runs under the alarm's wakelock: the dial only hands the meter to Bluetooth, which
        // wakes the phone again once it connects. A process the alarm started has dialled every
        // meter at startup already, so finding none waiting is fine.
        BluetoothGlucoseMeter.pacedRecheck(
            intent.getIntExtra(EXTRA_METER, -1),
            intent.getLongExtra(EXTRA_TICKET, -1L),
        )
    }
}
