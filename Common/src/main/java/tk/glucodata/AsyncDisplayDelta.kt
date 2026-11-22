package tk.glucodata

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** A canvas only reads a completed result; Room/native history is loaded on the worker. */
class AsyncDisplayDelta internal constructor(
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val resolve: (CurrentDisplaySource.Snapshot, Int) -> DisplayDeltaSource.Result?
) {
    constructor() : this(worker, resolve = { shown, interval -> DisplayDeltaSource.resolveResult(shown, interval) })

    private data class Inputs(
        val shown: CurrentDisplaySource.Snapshot,
        val interval: Int,
        val calibrationRevision: Long
    )

    private data class Request(val inputs: Inputs, val historyRevision: Long)

    private var request: Request? = null
    private var generation = 0L
    private var result: DisplayDeltaSource.Result? = null

    @Synchronized
    fun get(
        enabled: Boolean,
        shown: CurrentDisplaySource.Snapshot?,
        intervalMinutes: Int,
        revision: Long,
        onLoaded: Runnable
    ): String {
        if (!enabled || shown == null) {
            clear()
            return ""
        }
        val inputs = Inputs(shown, GlucoseDelta.sanitizeIntervalMinutes(intervalMinutes), CalibrationAccess.getRevision())
        val next = Request(inputs, revision)
        if (request == next) return visibleText(shown.timeMillis)
        // A history refresh can update the delta for the same reading while it stays visible.
        if (request?.inputs != inputs) result = null
        request = next
        val expectedGeneration = ++generation
        executor.execute {
            if (synchronized(this) { generation != expectedGeneration }) return@execute
            val loaded = try {
                resolve(shown, inputs.interval)
            } catch (error: Exception) {
                Log.stack("AsyncDisplayDelta", "load failed", error)
                null
            }
            val accepted = synchronized(this) {
                if (generation != expectedGeneration) false else {
                    result = loaded
                    true
                }
            }
            if (accepted) onLoaded.run()
        }
        return visibleText(shown.timeMillis)
    }

    private fun visibleText(shownAt: Long): String =
        result?.takeIf { clock() in shownAt..it.validUntilMillis }?.text.orEmpty()

    @Synchronized
    fun clear() {
        generation++
        request = null
        result = null
    }

    private companion object {
        val worker: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FloatingDelta").apply { isDaemon = true }
        }
    }
}
