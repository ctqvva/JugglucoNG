package tk.glucodata

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Measures main-thread responsiveness two independent ways, because each alone
 * can be silent for the wrong reason.
 *
 * Choreographer reports *that* frames were dropped and HWUI reports a late
 * frame, but neither says what ran. `Activity: Slow operation ... since
 * onResume` measures across however many looper messages the resume took, so a
 * multi-second figure there does not mean any single message was slow.
 *
 * [dispatchProbe] wraps the looper's message printer: it counts *every*
 * dispatch and accumulates total busy time, so a report of "no slow dispatch"
 * is distinguishable from "the printer was never called" — the earlier version
 * only logged over-threshold dispatches and could not tell those apart, which
 * made a silent log useless.
 *
 * [pingProbe] is independent of the printer: a background thread posts a token
 * to the main handler at a fixed interval and records how long it waited to
 * run. That measures unresponsiveness whatever its shape — one long message,
 * a thousand short ones, or work outside message dispatch entirely.
 *
 * Diagnostic only; install from a debug build.
 */
object MainThreadWatchdog {
    private const val TAG = "MainWatchdog"
    private const val SLOW_DISPATCH_MS = 50L
    private const val PING_INTERVAL_MS = 500L
    private const val PING_REPORT_MS = 2_000L
    /** Wait this long for the ping before dumping the main thread's stack. */
    private const val PING_STACK_DUMP_MS = 1_500L
    private const val STACK_FRAMES = 45
    private const val DISPATCH_REPORT_INTERVAL = 2_000L

    @Volatile private var installed = false

    // --- printer probe state (main thread only) ---
    private var dispatchStartMs = 0L
    private var currentMessage: String? = null
    private var dispatchCount = 0L
    private var dispatchBusyMs = 0L
    private var slowDispatchCount = 0L
    private var slowestDispatchMs = 0L
    private var slowestDispatch: String? = null

    /**
     * Composition counters sampled across each dispatch. A frame callback that
     * held the main thread for seconds is either one expensive composition or a
     * recomposition loop; only the count taken *across that dispatch* separates
     * them, which a session total cannot.
     */
    private val compositionKeys = arrayOf(
        "compose.DashboardScreen",
        "compose.InteractiveGlucoseChart",
        "compose.chartCanvasDraw",
        "compose.SensorCard",
    )
    private val compositionsAtStart = LongArray(compositionKeys.size)

    @JvmStatic
    fun install() {
        if (installed) return
        installed = true
        installDispatchProbe()
        installPingProbe()
        Log.i(
            TAG,
            "installed: dispatch probe (slow>=${SLOW_DISPATCH_MS}ms, report every " +
                "$DISPATCH_REPORT_INTERVAL dispatches) + ping probe (every ${PING_INTERVAL_MS}ms, " +
                "report delay>=${PING_REPORT_MS}ms)",
        )
    }

    private fun installDispatchProbe() {
        Looper.getMainLooper().setMessageLogging { line ->
            if (line == null) return@setMessageLogging
            if (line.startsWith(">>>>>")) {
                dispatchStartMs = SystemClock.uptimeMillis()
                currentMessage = line
                for (index in compositionKeys.indices) {
                    compositionsAtStart[index] = BatteryTrace.count(compositionKeys[index])
                }
                return@setMessageLogging
            }
            if (!line.startsWith("<<<<<")) return@setMessageLogging
            val started = dispatchStartMs
            dispatchStartMs = 0L
            val message = currentMessage
            currentMessage = null
            if (started == 0L) return@setMessageLogging

            val elapsed = SystemClock.uptimeMillis() - started
            dispatchCount++
            dispatchBusyMs += elapsed
            if (elapsed > slowestDispatchMs) {
                slowestDispatchMs = elapsed
                slowestDispatch = message
            }
            if (elapsed >= SLOW_DISPATCH_MS) {
                slowDispatchCount++
                val compositions = StringBuilder()
                for (index in compositionKeys.indices) {
                    val delta = BatteryTrace.count(compositionKeys[index]) - compositionsAtStart[index]
                    if (delta > 0L) {
                        if (compositions.isNotEmpty()) compositions.append(' ')
                        compositions.append(compositionKeys[index].removePrefix("compose."))
                            .append('=').append(delta)
                    }
                }
                val composed = if (compositions.isEmpty()) "none" else compositions.toString()
                Log.w(TAG, "slow dispatch ${elapsed}ms compositions[$composed]: $message")
            }
            // Unconditional periodic report: proves the probe is live, and shows
            // whether the main thread is busy in one long message or many short
            // ones — the distinction that decides where to look.
            if (dispatchCount % DISPATCH_REPORT_INTERVAL == 0L) {
                Log.i(
                    TAG,
                    "dispatches=$dispatchCount busy=${dispatchBusyMs}ms " +
                        "slow(>=${SLOW_DISPATCH_MS}ms)=$slowDispatchCount " +
                        "slowest=${slowestDispatchMs}ms in ${slowestDispatch}",
                )
            }
        }
    }

    private fun installPingProbe() {
        val mainHandler = Handler(Looper.getMainLooper())
        val thread = Thread({
            while (true) {
                try {
                    val postedAtMs = SystemClock.uptimeMillis()
                    val ran = java.util.concurrent.CountDownLatch(1)
                    if (!mainHandler.post { ran.countDown() }) return@Thread
                    // Dump the stack while the main thread is *still* stuck. Waiting
                    // for the stall to end and reporting a duration says how bad it
                    // is but never what it was doing; this names the frames.
                    if (!ran.await(PING_STACK_DUMP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        dumpMainThreadStack(SystemClock.uptimeMillis() - postedAtMs)
                        ran.await()
                    }
                    val waitedMs = SystemClock.uptimeMillis() - postedAtMs
                    if (waitedMs >= PING_REPORT_MS) {
                        Log.w(TAG, "main thread unresponsive for ${waitedMs}ms (ping probe)")
                    }
                    val sleepMs = PING_INTERVAL_MS - waitedMs
                    if (sleepMs > 0L) Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (error: Throwable) {
                    Log.stack(TAG, "ping probe", error)
                    return@Thread
                }
            }
        }, "MainWatchdog-ping")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY + 1
        thread.start()
    }

    private var lastStackSignature: String? = null
    private var repeatedStackCount = 0

    private fun dumpMainThreadStack(blockedForMs: Long) {
        val frames = runCatching { Looper.getMainLooper().thread.stackTrace }.getOrNull()
        if (frames == null || frames.isEmpty()) return
        // Collapse identical consecutive dumps: one long stall produces a dump every
        // ping, and repeating 45 frames each time buries everything else.
        val signature = frames.take(6).joinToString("|") { "${it.className}.${it.methodName}" }
        if (signature == lastStackSignature) {
            repeatedStackCount++
            if (repeatedStackCount % 8 != 0) return
            Log.w(TAG, "main thread STILL stuck ${blockedForMs}ms, same stack (x$repeatedStackCount)")
            return
        }
        lastStackSignature = signature
        repeatedStackCount = 0
        val builder = StringBuilder("main thread stuck ${blockedForMs}ms, stack:")
        for (frame in frames.take(STACK_FRAMES)) {
            builder.append("\n    at ").append(frame)
        }
        Log.w(TAG, builder.toString())
    }

    /** Dump the dispatch totals on demand, e.g. alongside the BatteryTrace timers. */
    @JvmStatic
    fun report() {
        Log.i(
            TAG,
            "REPORT dispatches=$dispatchCount busy=${dispatchBusyMs}ms " +
                "slow(>=${SLOW_DISPATCH_MS}ms)=$slowDispatchCount " +
                "slowest=${slowestDispatchMs}ms in ${slowestDispatch}",
        )
    }
}
