package tk.glucodata.ui.viewmodel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Capture UI order before dispatch: one independent FIFO worker per sensor. */
internal class OrderedSensorActions(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val queues = mutableMapOf<String, Channel<() -> Unit>>()

    fun submit(sensor: String, action: () -> Unit): Boolean = synchronized(queues) {
        val queue = queues.getOrPut(sensor) {
            Channel<() -> Unit>(Channel.UNLIMITED).also { channel ->
                scope.launch(dispatcher) {
                    for (work in channel) runCatching(work)
                }.invokeOnCompletion { channel.cancel() }
            }
        }
        queue.trySend(action).isSuccess
    }
}
