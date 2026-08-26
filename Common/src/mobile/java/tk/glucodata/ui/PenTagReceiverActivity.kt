package tk.glucodata.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import tk.glucodata.InsulinPenManager
import tk.glucodata.Log
import tk.glucodata.MainActivity
import tk.glucodata.NovoPen.PenUnattendedImportPolicy
import tk.glucodata.NovoPen.Scan

/**
 * Receives an insulin-pen tap while the app is not in front. The system starts this
 * for an ISO-DEP tag (see nfc_pen_tech_filter.xml); it draws nothing, reads the pen on
 * a background thread, and finishes. The import itself, and what is said about it,
 * lives in [InsulinPenManager] and [Scan] — this is only the doorway.
 *
 * Only reachable while the "import in the background" setting has enabled the
 * component. A sensor, or a tap with the app already in front, goes to MainActivity
 * exactly as it would have without this activity.
 */
class PenTagReceiverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var reading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (reading) return
        val tag: Tag? = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (intent == null || tag == null || NfcAdapter.ACTION_TECH_DISCOVERED != intent.action) {
            finish()
            return
        }
        if (!PenUnattendedImportPolicy.isPenTag(tag.techList) || MainActivity.isInForeground()) {
            // Not a pen, or the app is up and its reader mode is the right home for the
            // tag: hand it on as the system would have, and get out of the way.
            forwardToMainActivity(intent)
            finish()
            return
        }
        if (!InsulinPenManager.isEnabled() || !InsulinPenManager.isBackgroundImportEnabled()) {
            finish()
            return
        }
        reading = true
        handler.postDelayed({ if (!isFinishing) finish() }, READ_TIMEOUT_MS)
        val appContext = applicationContext
        Thread({
            try {
                Scan.onTag(appContext, tag, true)
            } catch (error: Throwable) {
                Log.e(LOG_ID, "background pen read failed: ${Log.stackline(error)}")
            } finally {
                handler.post { if (!isFinishing) finish() }
            }
        }, "PenTagReceiver").start()
    }

    private fun forwardToMainActivity(intent: Intent) {
        runCatching {
            startActivity(
                Intent(intent).apply {
                    setClass(this@PenTagReceiverActivity, MainActivity::class.java)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure { error -> Log.e(LOG_ID, "forward to MainActivity failed: ${Log.stackline(error)}") }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val LOG_ID = "PenTagReceiver"

        /** A full pen read takes a few seconds of contact; longer than this and it has failed. */
        const val READ_TIMEOUT_MS = 15_000L
    }
}
