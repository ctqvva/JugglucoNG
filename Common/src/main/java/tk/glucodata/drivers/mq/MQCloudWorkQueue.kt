package tk.glucodata.drivers.mq

import java.util.concurrent.Executors

/** Network work never occupies the BLE handler. Results belong to one sensor session. */
internal class MQCloudWorkQueue(
    name: String,
    private val dispatchResult: (() -> Unit) -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
    private var generation = 0L
    private var closed = false

    inner class Task internal constructor(private val version: Long) {
        fun deliver(result: () -> Unit) {
            dispatchResult {
                synchronized(this@MQCloudWorkQueue) {
                    if (!closed && version == generation) {
                        try {
                            result()
                        } catch (error: Exception) {
                            onError(error)
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun post(work: (Task) -> Unit) {
        if (closed) return
        val version = generation
        executor.execute {
            val current = synchronized(this) { !closed && version == generation }
            if (current) {
                try {
                    work(Task(version))
                } catch (error: Exception) {
                    onError(error)
                }
            }
        }
    }

    /** End-wear bookkeeping may finish after the BLE manager is retired. */
    @Synchronized
    fun postCleanup(work: () -> Unit) {
        if (closed) return
        executor.execute {
            try {
                work()
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    @Synchronized
    fun invalidate() {
        generation++
    }

    @Synchronized
    override fun close() {
        closed = true
        generation++
        executor.shutdown()
    }
}
