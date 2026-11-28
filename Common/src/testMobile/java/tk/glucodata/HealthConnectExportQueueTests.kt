package tk.glucodata

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HealthConnectExportQueueTests {
    @Test fun busyRequestsCoalesceWithoutDroppingEitherSensor() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val calls = mutableListOf<Long>()
        val queue = HealthConnectExportQueue(CoroutineScope(coroutineContext)) {
            calls += it.sensorPtr
            if (calls.size == 1) { started.complete(Unit); release.await() }
            if (calls.size == 3) finished.complete(Unit)
            false
        }
        queue.request(1, "old")
        started.await()
        repeat(100) { queue.request(1, "old") }
        queue.request(2, "current")
        release.complete(Unit)
        withTimeout(5_000) { finished.await() }
        assertEquals(listOf(1L, 1L, 2L), calls)
    }

    @Test fun requestAfterFinalAcknowledgementRunsWithoutAnotherLiveTrigger() = runBlocking {
        var cursor = 0
        var lateGap = false
        val starts = mutableListOf<Int>()
        val finished = CompletableDeferred<Unit>()
        lateinit var queue: HealthConnectExportQueue
        queue = HealthConnectExportQueue(CoroutineScope(coroutineContext)) {
            val retry = runHealthConnectExportPass(
                snapshot = { starts += cursor; cursor to 10 },
                insert = { _, _ -> true },
                acknowledge = {
                    cursor = it
                    if (!lateGap) {
                        lateGap = true
                        cursor = 2
                        queue.request(1, "sensor")
                    } else finished.complete(Unit)
                    true
                },
            )
            retry
        }
        queue.request(1, "sensor")
        withTimeout(5_000) { finished.await() }
        assertEquals(listOf(0, 2), starts)
        assertEquals(10, cursor)
    }

    @Test fun fifthRewindContinuesInAnotherPassAndLetsCurrentSensorRun() = runBlocking {
        var acknowledgements = 0
        var cursor = 0
        val passes = mutableListOf<Long>()
        val finished = CompletableDeferred<Unit>()
        lateinit var queue: HealthConnectExportQueue
        queue = HealthConnectExportQueue(CoroutineScope(coroutineContext)) { request ->
            passes += request.sensorPtr
            if (request.sensorPtr == 2L) false else {
                if (passes.size == 1) queue.request(2, "current")
                runHealthConnectExportPass(
                    snapshot = { cursor to 10 },
                    insert = { _, _ -> true },
                    acknowledge = {
                        acknowledgements++
                        if (acknowledgements <= 5) false else {
                            cursor = it
                            finished.complete(Unit)
                            true
                        }
                    },
                )
            }
        }
        queue.request(1, "old")
        withTimeout(5_000) { finished.await() }
        assertEquals(listOf(1L, 2L, 1L), passes)
        assertEquals(6, acknowledgements)
        assertEquals(10, cursor)
    }

    @Test fun zeroPointerNeverStartsAnExport() = runBlocking {
        var calls = 0
        val queue = HealthConnectExportQueue(CoroutineScope(coroutineContext)) { calls++; false }
        queue.request(0, "no shell")
        assertEquals(0, calls)
    }
}
