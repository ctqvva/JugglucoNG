package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.Log
import tk.glucodata.VoiceReadings

/**
 * The glucose notification's "Turn off voice" / "Turn on voice" action. The intent carries
 * the target state rather than "toggle", so a stale notification tapped twice cannot flip
 * the setting back.
 */
class VoiceReadingsReceiver : BroadcastReceiver() {

    companion object {
        private const val LOG_ID = "VoiceReadingsReceiver"
        const val ACTION_SET_VOICE = "tk.glucodata.ACTION_SET_VOICE_READINGS"
        const val EXTRA_ON = "voice_on"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_VOICE || !intent.hasExtra(EXTRA_ON)) return
        val on = intent.getBooleanExtra(EXTRA_ON, false)
        Log.i(LOG_ID, "notification action: voice readings ${if (on) "on" else "off"}")
        VoiceReadings.setOn(context.applicationContext, on)
    }
}
