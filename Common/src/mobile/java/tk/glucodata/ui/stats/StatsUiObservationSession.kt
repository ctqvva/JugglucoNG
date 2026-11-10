package tk.glucodata.ui.stats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** One cancellable source-observation scope shared by the visible statistics consumers. */
internal class StatsUiObservationSession(
    private val parentScope: CoroutineScope,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
) {
    // Acquire/release are called by Compose lifecycle effects on the main thread.
    private val consumers = mutableSetOf<Any>()
    var scope: CoroutineScope? = null
        private set

    fun acquire(consumer: Any) {
        if (!consumers.add(consumer) || consumers.size != 1) return
        scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        onStart()
    }

    fun release(consumer: Any) {
        if (!consumers.remove(consumer) || consumers.isNotEmpty()) return
        val previousScope = scope
        scope = null
        previousScope?.cancel()
        onStop()
    }
}
