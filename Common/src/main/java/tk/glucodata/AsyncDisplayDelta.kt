package tk.glucodata

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** A canvas only reads a completed result; Room/native history is loaded on the worker. */
class AsyncDisplayDelta internal constructor(
    private val executor: Executor,
    private val resolve: (CurrentDisplaySource.Snapshot, Int) -> String
) {
    constructor() : this(worker, { shown, interval -> DisplayDeltaSource.resolve(true, shown, interval) })

    private data class Request(
        val shown: CurrentDisplaySource.Snapshot,
        val interval: Int,
        val revision: Long,
        val calibrationRevision: Long
    )

    private var request: Request? = null
    private var generation = 0L
    private var text = ""

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
        val next = Request(shown, GlucoseDelta.sanitizeIntervalMinutes(intervalMinutes), revision, CalibrationAccess.getRevision())
        if (request == next) return text
        request = next
        text = ""
        val expectedGeneration = ++generation
        executor.execute {
            if (synchronized(this) { generation != expectedGeneration }) return@execute
            val loaded = try {
                resolve(shown, next.interval)
            } catch (error: Exception) {
                Log.stack("AsyncDisplayDelta", "load failed", error)
                ""
            }
            val accepted = synchronized(this) {
                if (generation != expectedGeneration) false else {
                    text = loaded
                    true
                }
            }
            if (accepted) onLoaded.run()
        }
        return text
    }

    @Synchronized
    fun clear() {
        generation++
        request = null
        text = ""
    }

    private companion object {
        val worker: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FloatingDelta").apply { isDaemon = true }
        }
    }
}
