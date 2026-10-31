package tk.glucodata.data.prediction.analysis

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The one background thread prediction analysis uses: recording forecasts and training the
 * adaptive model. Single-threaded so a recording and a fit never race, created on first use,
 * and idle — no polling, nothing scheduled — whenever nothing has been asked of it.
 */
internal object PredictionAnalysisWork {
    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PredictionAnalysis").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    fun execute(block: () -> Unit) {
        executor.execute(block)
    }

    fun schedule(delayMs: Long, block: () -> Unit) {
        executor.schedule(block, delayMs, TimeUnit.MILLISECONDS)
    }
}
