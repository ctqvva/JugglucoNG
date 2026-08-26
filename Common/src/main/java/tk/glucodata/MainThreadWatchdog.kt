package tk.glucodata

import android.os.Looper
import android.os.SystemClock

/**
 * Reports main-thread messages that ran long enough to drop frames or ANR.
 *
 * Choreographer already says *that* the main thread stalled and for how long,
 * and HWUI's "Davey!" says the frame was late, but neither says which message
 * was running. This wraps the main looper's message logging so a slow dispatch
 * names its own Handler/callback/what, which is the part needed to attribute a
 * multi-second block to a specific piece of work rather than guessing.
 *
 * Cost per message is two string comparisons and a clock read; only dispatches
 * at or over the threshold log anything. Diagnostic only — install from a debug
 * build.
 */
object MainThreadWatchdog {
    private const val TAG = "MainWatchdog"
    private const val DEFAULT_THRESHOLD_MS = 300L

    @Volatile private var installed = false
    private var dispatchStartMs = 0L
    private var currentMessage: String? = null
    private var thresholdMs = DEFAULT_THRESHOLD_MS

    /** Total main-thread time spent in over-threshold dispatches, for a session view. */
    @Volatile private var blockedTotalMs = 0L
    @Volatile private var blockedCount = 0L

    @JvmStatic
    @JvmOverloads
    fun install(thresholdMs: Long = DEFAULT_THRESHOLD_MS) {
        if (installed) return
        installed = true
        this.thresholdMs = thresholdMs.coerceAtLeast(1L)
        // setMessageLogging replaces any previous printer, so this must be the only
        // caller; Looper emits ">>>>> Dispatching to <target> <callback>: <what>"
        // before a message and "<<<<< Finished to ..." after it.
        Looper.getMainLooper().setMessageLogging { line ->
            if (line == null) return@setMessageLogging
            when {
                line.startsWith(">>>>>") -> {
                    dispatchStartMs = SystemClock.uptimeMillis()
                    currentMessage = line
                }
                line.startsWith("<<<<<") -> {
                    val started = dispatchStartMs
                    if (started != 0L) {
                        val elapsed = SystemClock.uptimeMillis() - started
                        if (elapsed >= this.thresholdMs) {
                            blockedTotalMs += elapsed
                            blockedCount++
                            Log.w(
                                TAG,
                                "main thread held ${elapsed}ms by ${currentMessage} " +
                                    "(session: ${blockedCount} stalls, ${blockedTotalMs}ms total)",
                            )
                        }
                    }
                    dispatchStartMs = 0L
                    currentMessage = null
                }
            }
        }
        Log.i(TAG, "installed, threshold=${this.thresholdMs}ms")
    }
}
