package tk.glucodata.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Notify

/**
 * Manages snooze state for alerts.
 * 
 * Features:
 * - Track snooze expiry per alert type
 * - Support preemptive snooze (snooze before alert triggers)
 * - Schedule wake-up alarms for snooze expiry
 */
object SnoozeManager {
    
    private const val LOG_ID = "SnoozeManager"
    private const val PREFS_NAME = "tk.glucodata.snooze"
    private const val ACTION_SNOOZE_EXPIRED = "tk.glucodata.ACTION_SNOOZE_EXPIRED"
    private const val EXTRA_ALERT_TYPE_ID = "alert_type_id"
    
    private val prefs by lazy {
        Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val alarmManager by lazy {
        Applic.app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    
    /**
     * Snooze an alert for the specified duration.
     * 
     * @param alertType The type of alert to snooze
     * @param durationMinutes How long to snooze (in minutes)
     * @param preemptive If true, this is a preemptive snooze before alert triggered
     */
    fun snooze(alertType: AlertType, durationMinutes: Int, preemptive: Boolean = false) {
        val snoozeUntil = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        
        // Save snooze state
        prefs.edit()
            .putLong(keySnoozeUntil(alertType), snoozeUntil)
            .putBoolean(keyPreemptive(alertType), preemptive)
            .apply()
        
        AlertRuntimeManager.onAlertSnoozed(alertType)
        Notify.cancelRetrySession(alertType.id, "snoozed")
        // Acknowledged: a quiet window's silenced episode must not break through now.
        QuietWindow.clearSilencedEpisode(alertType.id)
        scheduleSnoozeExpirySafely(alertType, snoozeUntil)
        
        Log.i(LOG_ID, "Snoozed ${alertType.name} for $durationMinutes minutes (preemptive=$preemptive)")
    }
    
    /**
     * Check if an alert type is currently snoozed.
     */
    fun isSnoozed(alertType: AlertType): Boolean {
        val snoozeUntil = prefs.getLong(keySnoozeUntil(alertType), 0)
        return System.currentTimeMillis() < snoozeUntil
    }
    
    /**
     * Get the snooze state for an alert type.
     */
    fun getSnoozeState(alertType: AlertType): SnoozeState? {
        val snoozeUntil = prefs.getLong(keySnoozeUntil(alertType), 0)
        if (snoozeUntil == 0L || System.currentTimeMillis() >= snoozeUntil) {
            return null
        }
        
        return SnoozeState(
            alertType = alertType,
            snoozeUntilMillis = snoozeUntil,
            isPreemptive = prefs.getBoolean(keyPreemptive(alertType), false)
        )
    }
    
    /**
     * Get remaining snooze time in minutes.
     */
    fun getRemainingMinutes(alertType: AlertType): Int {
        val snoozeUntil = prefs.getLong(keySnoozeUntil(alertType), 0)
        val remaining = (snoozeUntil - System.currentTimeMillis()) / 60000
        return remaining.toInt().coerceAtLeast(0)
    }
    
    /**
     * Clear snooze for an alert type (dismiss or expired).
     */
    fun clearSnooze(alertType: AlertType) {
        prefs.edit()
            .remove(keySnoozeUntil(alertType))
            .remove(keyPreemptive(alertType))
            .apply()
        
        cancelSnoozeExpiry(alertType)
        
        Log.i(LOG_ID, "Cleared snooze for ${alertType.name}")
    }
    
    /**
     * Clear all snoozes.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        AlertType.entries.forEach { cancelSnoozeExpiry(it) }
        Log.i(LOG_ID, "Cleared all snoozes")
    }
    
    /**
     * Preemptively snooze low and/or high alerts.
     * Used when user has already taken action (eaten carbs, taken insulin).
     */
    fun preemptiveSnooze(
        snoozeLow: Boolean,
        snoozeHigh: Boolean,
        durationMinutes: Int
    ) {
        if (snoozeLow) {
            snooze(AlertType.LOW, durationMinutes, preemptive = true)
            snooze(AlertType.VERY_LOW, durationMinutes, preemptive = true)
            snooze(AlertType.PRE_LOW, durationMinutes, preemptive = true)
        }
        if (snoozeHigh) {
            snooze(AlertType.HIGH, durationMinutes, preemptive = true)
            snooze(AlertType.VERY_HIGH, durationMinutes, preemptive = true)
            snooze(AlertType.PRE_HIGH, durationMinutes, preemptive = true)
            snooze(AlertType.PERSISTENT_HIGH, durationMinutes, preemptive = true)
        }
    }
    
    /**
     * Get all currently active snoozes.
     */
    fun getAllActiveSnoozes(): List<SnoozeState> {
        return AlertType.entries.mapNotNull { getSnoozeState(it) }
    }
    
    // ---- Private helpers ----
    
    private fun keySnoozeUntil(type: AlertType) = "snooze_until_${type.id}"
    private fun keyPreemptive(type: AlertType) = "snooze_preemptive_${type.id}"

    private fun scheduleSnoozeExpirySafely(alertType: AlertType, expiryTime: Long) {
        try {
            scheduleSnoozeExpiry(alertType, expiryTime)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "scheduleSnoozeExpiry ${alertType.name}", t)
        }
    }
    
    private fun scheduleSnoozeExpiry(alertType: AlertType, expiryTime: Long) {
        val intent = Intent(ACTION_SNOOZE_EXPIRED).apply {
            setPackage(Applic.app.packageName)
            putExtra(EXTRA_ALERT_TYPE_ID, alertType.id)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            Applic.app,
            alertType.id + 1000,  // Offset to avoid conflicts
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                expiryTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                expiryTime,
                pendingIntent
            )
        }
    }
    
    private fun cancelSnoozeExpiry(alertType: AlertType) {
        val intent = Intent(ACTION_SNOOZE_EXPIRED).apply {
            setPackage(Applic.app.packageName)
            putExtra(EXTRA_ALERT_TYPE_ID, alertType.id)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            Applic.app,
            alertType.id + 1000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
    }
    
    /**
     * BroadcastReceiver for snooze expiry.
     * When snooze expires, we clear the snooze state and let normal
     * alert logic take over on next glucose reading.
     */
    class SnoozeExpiryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SNOOZE_EXPIRED) {
                val alertTypeId = intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1)
                val alertType = AlertType.fromId(alertTypeId)
                
                if (alertType != null) {
                    Log.i(LOG_ID, "Snooze expired for ${alertType.name}")
                    clearSnooze(alertType)
                    
                    // Optionally show a notification that snooze ended
                    // The next glucose reading will trigger the alert if still in range
                }
            }
        }
    }
}
