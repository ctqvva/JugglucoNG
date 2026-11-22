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
    private var now = 1_800_000_000_000L
    private val shown = deltaSnapshot(at = now)
    private val cache = AsyncDisplayDelta(executor, { now }) { _, interval ->
        loads++
        result("+$interval")
    }

    private fun result(text: String) = DisplayDeltaSource.Result(text, shown.timeMillis + Notify.glucosetimeout)

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
        assertEquals("", cache.get(true, shown, 1, 1, redraw))
        tasks.removeFirst().run()
        assertEquals("+1", cache.get(true, shown, 1, 1, redraw))
        assertEquals("", cache.get(true, shown.copy(isMmol = true), 1, 2, redraw))
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
        inFlight = AsyncDisplayDelta(executor, { now }) { _, _ ->
            inFlight.clear()
            result("+99")
        }
        inFlight.get(true, shown, 5, 0, redraw)
        tasks.removeFirst().run()
        assertEquals(0, redraws)
        assertEquals("", inFlight.get(true, shown, 5, 0, redraw))
    }

    @Test fun aHistoryRefreshRetriesAnEmptyResultAfterPersistenceCatchesUp() {
        val delayed = AsyncDisplayDelta(executor, { now }) { _, _ -> if (loads++ == 0) null else result("+10") }
        delayed.get(true, shown, 5, 0, redraw)
        tasks.removeFirst().run()
        assertEquals("", delayed.get(true, shown, 5, 1, redraw))
        tasks.removeFirst().run()
        assertEquals("+10", delayed.get(true, shown, 5, 1, redraw))
    }

    @Test fun aHistoryRefreshKeepsTheCompletedDeltaUntilTheReplacementIsReady() {
        val replacements = listOf(result("+10"), result("+12"), null)
        val refreshed = AsyncDisplayDelta(executor, { now }) { _, _ -> replacements[loads++] }
        refreshed.get(true, shown, 5, 0, redraw)
        tasks.removeFirst().run()

        assertEquals("+10", refreshed.get(true, shown, 5, 1, redraw))
        assertEquals("+10", refreshed.get(true, shown, 5, 2, redraw))
        tasks.removeFirst().run()
        assertEquals(1, loads)
        tasks.removeFirst().run()
        assertEquals("+12", refreshed.get(true, shown, 5, 2, redraw))

        assertEquals("+12", refreshed.get(true, shown, 5, 3, redraw))
        tasks.removeFirst().run()
        assertEquals("", refreshed.get(true, shown, 5, 3, redraw))
    }

    @Test fun aCalibrationChangeClearsTheCompletedDeltaImmediately() {
        var revision = 0L
        CalibrationAccess.register(object : CalibrationProvider {
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) = false
            override fun getCalibratedValue(
                value: Float, timestamp: Long, isRawMode: Boolean, emitDiagnostics: Boolean, sensorId: String?
            ) = value
            override fun getRevision() = revision
        })
        try {
            cache.get(true, shown, 5, 0, redraw)
            tasks.removeFirst().run()
            assertEquals("+5", cache.get(true, shown, 5, 0, redraw))

            revision++
            assertEquals("", cache.get(true, shown, 5, 1, redraw))
            assertEquals(1, tasks.size)
        } finally {
            CalibrationAccess.unregisterForTests()
        }
    }

    @Test fun aNewReadingClearsTheCompletedDeltaImmediately() {
        cache.get(true, shown, 5, 0, redraw)
        tasks.removeFirst().run()
        now++
        assertEquals("", cache.get(true, shown.copy(timeMillis = now), 5, 1, redraw))
    }

    @Test fun aMinuteRoundedDeltaExpiresWhileTheHistoryRefreshIsPending() {
        val anchor = now
        now += 25_000L
        val live = shown.copy(timeMillis = now)
        val measured = listOf(GlucosePoint(anchor - 5 * 60_000L, 100f), GlucosePoint(anchor, 110f))
        val rounded = AsyncDisplayDelta(executor, { now }) { snapshot, interval ->
            DisplayDeltaSource.resolveResult(snapshot, interval, { now }) { _, _, _ -> measured }
        }
        rounded.get(true, live, 5, 0, redraw)
        tasks.removeFirst().run()

        now = anchor + Notify.glucosetimeout
        assertEquals("+10", rounded.get(true, live, 5, 1, redraw))
        now++
        assertEquals("", rounded.get(true, live, 5, 1, redraw))
        tasks.removeFirst().run()
        assertEquals("", rounded.get(true, live, 5, 1, redraw))
    }
}
