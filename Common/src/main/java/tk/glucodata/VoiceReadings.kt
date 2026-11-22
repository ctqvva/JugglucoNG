package tk.glucodata

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil

/**
 * Live control of spoken glucose readings ("Speak glucose"), for the surfaces that sit
 * outside Settings: the dashboard toggle and the glucose notification action (#840).
 *
 * Only spoken readings are switched. Spoken alarms, alarm sounds and everything else stay
 * as configured. When readings are speaking automatically because a chosen car or Bluetooth
 * audio device is connected, turning voice off mutes that connection session without changing
 * the saved Bluetooth auto-speak setting.
 */
object VoiceReadings {
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_USED = "voice_readings_used"
    private const val KEY_SHOW_DASHBOARD = "voice_quick_dashboard"
    private const val KEY_SHOW_NOTIFICATION = "voice_quick_notification"

    private val changeCount = MutableStateFlow(0L)

    /** Ticks whenever the on/off state or its settings change, from any surface. */
    val changes: StateFlow<Long> = changeCount.asStateFlow()

    /** What the user would hear: off, speaking (manually or via car/BT audio), or held by schedule. */
    data class Status(
        val on: Boolean,
        val intervalSeconds: Int,
        /** Schedule start in minutes after midnight while outside the schedule, else null. */
        val resumesAtMinutes: Int?,
        val autoAudioActive: Boolean = false,
        val autoAudioDeviceName: String? = null,
        val autoAudioViaCar: Boolean = false,
    )

    @JvmStatic
    fun isOn(): Boolean = !Applic.DontTalk && Natives.getVoiceActive()

    /** True when readings are currently speaking either manually or via car/Bluetooth audio. */
    @JvmStatic
    fun isActive(context: Context): Boolean {
        if (Applic.DontTalk) return false
        if (VoiceAudioTrigger.isSessionPaused(context)) return false
        return isOn() || VoiceAudioTrigger.isAutoReadingSpeechActive(context)
    }

    /**
     * Quick controls show only for people who use voice readings: on now, turned on
     * before (from any surface), given a schedule, or enabled for car/Bluetooth audio.
     */
    @JvmStatic
    fun isInUse(context: Context): Boolean {
        if (Applic.DontTalk) return false
        return isOn() ||
            prefs(context).getBoolean(KEY_USED, false) ||
            SpeakSchedule.isEnabled(context) ||
            VoiceAudioTrigger.isEnabled(context)
    }

    /** The dashboard switch beside the chart; on by default, off in Voice settings. */
    fun showOnDashboard(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_DASHBOARD, true)

    fun setShowOnDashboard(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_DASHBOARD, show).apply()
        changed()
    }

    /** The glucose notification's voice button; on by default, off in Voice settings. */
    @JvmStatic
    fun showInNotification(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_NOTIFICATION, true)

    fun setShowInNotification(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_NOTIFICATION, show).apply()
        changed()
    }

    @JvmStatic
    fun setOn(context: Context, on: Boolean) {
        if (Applic.DontTalk) return
        val app = context.applicationContext
        if (VoiceAudioTrigger.isMatchedDeviceConnected(app)) {
            // Pause or resume just this car/Bluetooth connection session without mutating
            // the user's persistent manual "Speak glucose" setting.
            VoiceAudioTrigger.setSessionMuted(app, !on)
            return
        }
        Talker.setSpeakGlucose(app, on)
        if (on) markUsed(app)
        changed()
    }

    fun status(context: Context): Status {
        val sessionPaused = VoiceAudioTrigger.isSessionPaused(context)
        val manualOn = isOn()
        val autoActive = VoiceAudioTrigger.isAutoReadingSpeechActive(context)
        val effectiveOn = !sessionPaused && (manualOn || autoActive)
        val defaultInterval = Talker.getSeparationSeconds()
        val intervalSeconds = if (autoActive) {
            VoiceAudioTrigger.effectiveIntervalSeconds(context, defaultInterval)
        } else {
            defaultInterval
        }
        val resumes = if (effectiveOn && manualOn && !autoActive && !SpeakSchedule.isWithinSchedule(context)) {
            SpeakSchedule.getStartMinutes(context)
        } else {
            null
        }
        val match = if (autoActive) VoiceAudioTrigger.currentMatch(context) else null
        return Status(
            on = effectiveOn,
            intervalSeconds = intervalSeconds,
            resumesAtMinutes = resumes,
            autoAudioActive = autoActive,
            autoAudioDeviceName = match?.displayLabel,
            autoAudioViaCar = match?.viaCarMode == true,
        )
    }

    /** "Every reading", "Every 5 min", or "From 07:00" while the schedule holds it back. */
    fun summary(context: Context, status: Status): String {
        status.resumesAtMinutes?.let {
            return context.getString(R.string.voice_resumes_at, SpeakSchedule.formatMinutes(it))
        }
        if (status.intervalSeconds <= 60) return context.getString(R.string.voice_every_reading)
        val minutes = ceil(status.intervalSeconds / 60.0).toInt()
        return context.getString(R.string.voice_every_minutes, minutes)
    }

    @JvmStatic
    fun markUsed(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_USED, false)) {
            prefs.edit().putBoolean(KEY_USED, true).apply()
        }
    }

    /** Tells every surface to re-read the state; the notification reposts its action. */
    @JvmStatic
    fun changed() {
        changeCount.value = changeCount.value + 1
        Notify.scheduleStatusChangedRefresh()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
