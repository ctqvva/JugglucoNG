package tk.glucodata

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WidgetWorkDispatcherTests {
    private val queued = ArrayDeque<Runnable>()
    private val worker = WidgetWorkDispatcher(Executor { queued.addLast(it) })
    private val events = mutableListOf<String>()

    @Test fun aProviderCallbackDoesNoIoUntilItsWorkerRuns() {
        worker.execute(Runnable { events += "load and publish" }, Runnable { events += "finish broadcast" })
        assertEquals(emptyList<String>(), events)
        queued.removeFirst().run()
        assertEquals(listOf("load and publish", "finish broadcast"), events)
    }

    @Test fun nestedProviderCallbacksFinishBeforeTheBroadcastIsReleased() {
        worker.execute(Runnable {
            events += "dispatch onReceive"
            worker.execute(Runnable { events += "onUpdate history and render" })
        }, Runnable { events += "finish broadcast" })
        queued.removeFirst().run()
        assertEquals(listOf("dispatch onReceive", "onUpdate history and render", "finish broadcast"), events)
        assertEquals(0, queued.size)
    }

    @Test fun failedWorkStillFinishesItsBroadcastAndDoesNotLeaveTheCallerInWorkerMode() {
        worker.execute(Runnable { throw IllegalStateException("read failed") }, Runnable { events += "finish failed broadcast" })
        try {
            queued.removeFirst().run()
            fail("failure should reach the provider's handler")
        } catch (_: IllegalStateException) { }
        worker.execute(Runnable { events += "next update" })
        assertEquals(listOf("finish failed broadcast"), events)
        queued.removeFirst().run()
        assertEquals(listOf("finish failed broadcast", "next update"), events)
    }

    @Test fun updatesStayInOrderIncludingForcedPreferenceRefreshes() {
        worker.execute(Runnable { events += "ordinary reading" })
        worker.execute(Runnable { events += "forced interval edit" })
        worker.execute(Runnable { events += "new reading" })
        while (queued.isNotEmpty()) queued.removeFirst().run()
        assertEquals(listOf("ordinary reading", "forced interval edit", "new reading"), events)
    }
}
