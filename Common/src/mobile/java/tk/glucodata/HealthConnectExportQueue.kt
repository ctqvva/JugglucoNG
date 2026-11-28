package tk.glucodata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** One worker, with at most one waiting request per sensor (including the active sensor). */
internal class HealthConnectExportQueue(
    private val scope: CoroutineScope,
    private val export: suspend (Request) -> Boolean,
) {
    data class Request(val sensorPtr: Long, val sensorName: String)
    private val lock = Any()
    private val pending = LinkedHashMap<Long, Request>()
    private var running = false

    fun request(sensorPtr: Long, sensorName: String) {
        if (sensorPtr == 0L) return
        synchronized(lock) {
            pending[sensorPtr] = Request(sensorPtr, sensorName)
            if (!running) launchLocked()
        }
    }

    private fun launchLocked() {
        running = true
        scope.launch {
            try {
                while (isActive) {
                    val next = synchronized(lock) {
                        pending.entries.firstOrNull()?.let { pending.remove(it.key) }
                    } ?: break
                    if (export(next)) synchronized(lock) { pending.putIfAbsent(next.sensorPtr, next) }
                    // A busy recovered range must leave room for the current sensor's request.
                    yield()
                }
            } finally {
                synchronized(lock) {
                    running = false
                    // Covers a request arriving after the final acknowledgement or empty read.
                    if (pending.isNotEmpty() && scope.isActive) launchLocked()
                }
            }
        }
    }
}

/** Returns true when the rewind limit requires another queued pass. */
internal suspend fun runHealthConnectExportPass(
    snapshot: () -> Pair<Int, Int>,
    insert: suspend (start: Int, count: Int) -> Boolean,
    acknowledge: (position: Int) -> Boolean,
): Boolean {
    repeat(4) {
        val (from, end) = snapshot()
        var start = from
        if (start >= end) return false
        var rewound = false
        while (start < end) {
            val take = minOf(end - start, 500)
            if (!insert(start, take)) return false
            start += take
            if (!acknowledge(start)) {
                rewound = true
                break
            }
        }
        if (!rewound) return false
    }
    return true
}
