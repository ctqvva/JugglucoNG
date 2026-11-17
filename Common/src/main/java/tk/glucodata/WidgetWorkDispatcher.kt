package tk.glucodata

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Serializes widget I/O and keeps nested provider callbacks inside their broadcast's lifetime. */
class WidgetWorkDispatcher internal constructor(private val executor: Executor) {
    constructor() : this(Executors.newSingleThreadExecutor { task ->
        Thread(task, "LegacyWidget").apply { isDaemon = true }
    })

    private val inWorker = ThreadLocal<Boolean>()

    @JvmOverloads
    fun execute(work: Runnable, onComplete: Runnable = Runnable {}) {
        val run = Runnable {
            try {
                work.run()
            } finally {
                onComplete.run()
            }
        }
        if (inWorker.get() == true) {
            run.run()
        } else {
            executor.execute {
                inWorker.set(true)
                try {
                    run.run()
                } finally {
                    inWorker.remove()
                }
            }
        }
    }
}
