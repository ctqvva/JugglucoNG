package tk.glucodata.drivers.mq

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class MQCloudWorkQueueTests {
    private fun CountDownLatch.awaitChecked() = assertTrue("worker timed out", await(5, TimeUnit.SECONDS))

    @Test fun blockedCloudRequestDoesNotBlockBleDispatch() {
        val ble = LinkedBlockingQueue<() -> Unit>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val errors = LinkedBlockingQueue<Throwable>()
        MQCloudWorkQueue("mq-test", { ble.add(it) }, { errors.add(it) }).use { queue ->
            queue.post { task ->
                started.countDown()
                release.awaitChecked()
                task.deliver { }
            }
            try {
                started.awaitChecked()
                var discoveryRan = false
                ble.add { discoveryRan = true }
                ble.poll(5, TimeUnit.SECONDS)!!.invoke()
                assertTrue(discoveryRan)
                assertEquals(1L, release.count)
            } finally {
                release.countDown()
            }
        }
        assertTrue(errors.isEmpty())
    }

    @Test fun resetDiscardsInFlightAndQueuedOldWorkButAllowsNewSession() {
        val ble = LinkedBlockingQueue<() -> Unit>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val newFinished = CountDownLatch(1)
        val staleApplied = AtomicBoolean(false)
        val queuedOldRan = AtomicBoolean(false)
        val errors = LinkedBlockingQueue<Throwable>()
        MQCloudWorkQueue("mq-test", { ble.add(it) }, { errors.add(it) }).use { queue ->
            queue.post { task ->
                started.countDown()
                release.awaitChecked()
                task.deliver { staleApplied.set(true) }
            }
            try {
                started.awaitChecked()
                queue.post { queuedOldRan.set(true) }
                queue.invalidate()
                queue.post { task -> task.deliver { newFinished.countDown() } }
            } finally {
                release.countDown()
            }
            ble.poll(5, TimeUnit.SECONDS)!!.invoke() // late old response
            ble.poll(5, TimeUnit.SECONDS)!!.invoke() // new session response
            newFinished.awaitChecked()
            assertFalse(staleApplied.get())
            assertFalse(queuedOldRan.get())
            assertTrue(errors.isEmpty())
        }
    }

    @Test fun resetAlsoDiscardsResultAlreadyWaitingOnBleHandler() {
        val ble = LinkedBlockingQueue<() -> Unit>()
        var applied = false
        MQCloudWorkQueue("mq-test", { ble.add(it) }, { throw AssertionError(it) }).use { queue ->
            queue.post { task -> task.deliver { applied = true } }
            val result = ble.poll(5, TimeUnit.SECONDS)!!
            queue.invalidate()
            result()
            assertFalse(applied)
        }
    }

    @Test fun closingDropsResultsButFinishesEndWearCleanup() {
        val ble = LinkedBlockingQueue<() -> Unit>()
        val cleanup = CountDownLatch(1)
        var applied = false
        val queue = MQCloudWorkQueue("mq-test", { ble.add(it) }, { throw AssertionError(it) })
        queue.post { task -> task.deliver { applied = true } }
        val result = ble.poll(5, TimeUnit.SECONDS)!!
        queue.postCleanup { cleanup.countDown() }
        queue.close()
        result()
        cleanup.awaitChecked()
        assertFalse(applied)
    }
}
