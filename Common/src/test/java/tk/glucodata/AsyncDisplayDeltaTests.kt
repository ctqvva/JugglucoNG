package tk.glucodata

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

class AsyncDisplayDeltaTests {
    private val tasks = ArrayDeque<Runnable>()
    private val executor = Executor { tasks.addLast(it) }
    private var loads = 0
    private var redraws = 0
    private val redraw = Runnable { redraws++ }
    private val cache = AsyncDisplayDelta(executor) { _, interval -> loads++; "+$interval" }
    private val shown = deltaSnapshot()

    @Test fun drawingEnqueuesTheHistoryLoadAndOnlyReadsCompletedResults() {
        assertEquals("", cache.get(true, shown, 5, 0, redraw))
        assertEquals(0, loads)
        assertEquals("", cache.get(true, shown, 5, 0, redraw))
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(1, loads)
        assertEquals(1, redraws)
        assertEquals("+5", cache.get(true, shown, 5, 0, redraw))
        assertEquals(0, tasks.size)
    }

    @Test fun aNewIntervalOrUnitNeverReusesThePreviousText() {
        cache.get(true, shown, 5, 0, redraw)
        tasks.removeFirst().run()
        assertEquals("", cache.get(true, shown, 1, 0, redraw))
        tasks.removeFirst().run()
        assertEquals("+1", cache.get(true, shown, 1, 0, redraw))
        assertEquals("", cache.get(true, shown.copy(isMmol = true), 1, 0, redraw))
    }

    @Test fun supersededQueuedWorkDoesNotQueryHistoryOrPublishAnotherSensorsResult() {
        cache.get(true, shown, 5, 0, redraw)
        val other = shown.copy(sensorId = "other")
        cache.get(true, other, 1, 0, redraw)
        tasks.removeFirst().run()
        assertEquals(0, loads)
        assertEquals(0, redraws)
        tasks.removeFirst().run()
        assertEquals("+1", cache.get(true, other, 1, 0, redraw))
    }

    @Test fun disablingOrDetachingDropsPendingWorkAndCachedText() {
        cache.get(true, shown, 5, 0, redraw)
        assertEquals("", cache.get(false, shown, 5, 0, redraw))
        tasks.removeFirst().run()
        assertEquals(0, loads)
        cache.get(true, shown, 5, 0, redraw)
        cache.clear()
        tasks.removeFirst().run()
        assertEquals(0, redraws)
    }

    @Test fun detachingDuringAnInFlightQueryPreventsPublication() {
        lateinit var inFlight: AsyncDisplayDelta
        inFlight = AsyncDisplayDelta(executor) { _, _ -> inFlight.clear(); "+99" }
        inFlight.get(true, shown, 5, 0, redraw)
        tasks.removeFirst().run()
        assertEquals(0, redraws)
        assertEquals("", inFlight.get(true, shown, 5, 0, redraw))
    }

    @Test fun aHistoryRefreshRetriesAnEmptyResultAfterPersistenceCatchesUp() {
        val delayed = AsyncDisplayDelta(executor) { _, _ -> if (loads++ == 0) "" else "+10" }
        delayed.get(true, shown, 5, 0, redraw)
        tasks.removeFirst().run()
        assertEquals("", delayed.get(true, shown, 5, 1, redraw))
        tasks.removeFirst().run()
        assertEquals("+10", delayed.get(true, shown, 5, 1, redraw))
    }
}
