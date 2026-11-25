package tk.glucodata

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionJournalCacheTests {
    private var revision = 1L
    private var now = 1_000_000L
    private var ready = 0
    private var failing = false
    private val loads = mutableListOf<CompletableDeferred<PredictionJournal>>()
    private val ranges = mutableListOf<Pair<Long, Long>>()
    private var held = false

    private val cache = PredictionJournalCache(
        scope = CoroutineScope(Dispatchers.Unconfined),
        revision = { revision },
        clock = { now },
        onMissedLoadReady = { ready++ },
        loadJournal = { start, end ->
            ranges += start to end
            val load = CompletableDeferred<PredictionJournal>().also(loads::add)
            if (failing) error("no database")
            if (!held) load.complete(journal())
            load.await()
        }
    )

    private fun journal() = PredictionJournal(emptyList(), emptyMap())

    @Test fun aMissIsLoadedAndReportedOnceSoTheOverlayDrawsAgainWithTheJournal() {
        held = true
        assertNull(cache.getWithoutWaiting(0L, 10L))
        assertNull("the peer's forecast of the same draw", cache.getWithoutWaiting(0L, 10L))
        assertEquals(1, loads.size)
        assertEquals(0, ready)

        val journal = journal()
        loads.single().complete(journal)
        assertEquals(1, ready)
        assertSame(journal, cache.getWithoutWaiting(0L, 10L))
        assertEquals("the redraw asks for no further one", 1, ready)
        assertEquals(1, loads.size)
    }

    @Test fun aJournalChangeLoadsAgainWithoutTheNotification() {
        cache.getWithoutWaiting(0L, 10L)
        revision++
        assertNull(cache.getWithoutWaiting(0L, 10L))
        assertEquals(2, ready)
        assertSame(loads.last().getCompleted(), cache.getWithoutWaiting(0L, 10L))
    }

    @Test fun theNotificationFillingTheCacheFirstIsReportedAndNotRepeated() = runBlocking {
        held = true
        cache.getWithoutWaiting(0L, 10L)
        held = false
        cache.get(0L, 10L)
        assertEquals(1, ready)
        loads.first().complete(journal())
        assertEquals(1, ready)
    }

    @Test fun aLoadOvertakenByAnotherChangeLeadsToOneMore() {
        held = true
        cache.getWithoutWaiting(0L, 10L)
        revision++
        loads.single().complete(journal())
        assertEquals(1, ready)

        assertNull("loaded before the change", cache.getWithoutWaiting(0L, 10L))
        loads.last().complete(journal())
        assertEquals(2, ready)
        assertSame(loads.last().getCompleted(), cache.getWithoutWaiting(0L, 10L))
        assertEquals(2, loads.size)
    }

    @Test fun nothingIsReportedWhenNoForecastMissedTheJournal() = runBlocking {
        cache.get(0L, 10L)
        now += PredictionJournalCache.MAX_AGE_MS + 1
        cache.get(0L, 10L)
        assertEquals(2, loads.size)
        assertEquals(0, ready)
    }

    @Test fun anUnchangedJournalIsReusedWithoutLoadingUntilItIsTenMinutesOld() = runBlocking {
        val journal = cache.get(0L, 10L)
        now += PredictionJournalCache.MAX_AGE_WITHOUT_WAITING_MS
        assertSame(journal, cache.getWithoutWaiting(60_000L, 60_010L))
        now += 1
        assertNull(cache.getWithoutWaiting(60_000L, 60_010L))
        assertEquals(1, ready)
    }

    @Test fun aFailedLoadIsRetriedByTheNextDrawAndAsksForNone() {
        failing = true
        assertNull(cache.getWithoutWaiting(0L, 10L))
        assertEquals(0, ready)
        failing = false
        assertNull(cache.getWithoutWaiting(0L, 10L))
        assertEquals(1, ready)
        assertEquals(2, loads.size)
    }

    // --- Range ---

    private val minute = 60_000L
    private val hour = 60L * minute
    private val baseline = 100L * hour

    private fun startOf(baselineAt: Long) = baselineAt - 36L * hour

    private fun lookUp(baselineAt: Long, horizonMinutes: Int) =
        cache.getWithoutWaiting(startOf(baselineAt), baselineAt + horizonMinutes * minute)

    @Test fun theLoadIsWiderThanTheRangeAskedForAndCoversAnyHorizon() = runBlocking {
        val journal = cache.get(startOf(baseline), baseline + 30 * minute)
        val (start, end) = ranges.single()
        assertTrue(start < startOf(baseline))
        assertTrue("the longest horizon's future entries", end >= baseline + 360 * minute)
        assertSame(journal, lookUp(baseline, 120))
        // ... and still does when the load is as old as the main thread accepts.
        now += 10 * minute
        assertSame(journal, lookUp(baseline + 10 * minute, 360))
        assertEquals(1, loads.size)
    }

    @Test fun aRangeBeyondTheLoadIsReadInTheBackgroundInsteadOfReused() {
        lookUp(baseline, 30)
        val narrow = loads.single().getCompleted()
        held = true
        // Seven hours on: the end is outside what was read, though the journal is unchanged.
        assertNull("must neither wait nor draw with part of the journal", lookUp(baseline + 7 * hour, 30))
        assertEquals(1, ready)
        loads.last().complete(journal())
        assertEquals(2, ready)
        val wide = lookUp(baseline + 7 * hour, 30)
        assertSame(loads.last().getCompleted(), wide)
        assertNotSame(narrow, wide)
        assertEquals(2, loads.size)
    }

    @Test fun anEarlierBaselineOutsideTheLoadIsNotCoveredEither() {
        lookUp(baseline, 30)
        assertNull(lookUp(baseline - 7 * hour, 30))
        assertEquals(2, loads.size)
        assertTrue(ranges.last().first <= startOf(baseline - 7 * hour))
    }

    @Test fun sensorsOnOneChartShareTheLoadWhicheverIsReadFirst() = runBlocking {
        for (first in listOf(baseline, baseline - 3 * hour)) {
            revision++
            val journal = cache.get(startOf(first), first + 120 * minute)
            assertSame(journal, lookUp(baseline, 120))
            assertSame(journal, lookUp(baseline - 3 * hour, 120))
        }
        assertEquals(2, loads.size)
        assertEquals(0, ready)
    }

    @Test fun theBackgroundChecksCoverageToo() = runBlocking {
        cache.get(startOf(baseline), baseline)
        cache.get(startOf(baseline), baseline + 7 * hour)
        assertEquals(2, loads.size)
    }

    // --- Loads that overlap ---

    @Test fun anOldLoadEndingAfterTheJournalChangedDoesNotReplaceTheNewerOne() = runBlocking {
        held = true
        val notification = async(Dispatchers.Unconfined) { cache.get(0L, 10L) }
        revision++
        assertNull(cache.getWithoutWaiting(0L, 10L))
        val (old, newer) = loads
        val newerJournal = journal()
        newer.complete(newerJournal)
        assertEquals(1, ready)

        old.complete(journal())
        assertSame("the notification draws the newer journal as well", newerJournal, notification.await())
        assertSame(newerJournal, cache.getWithoutWaiting(0L, 10L))
        assertSame(newerJournal, cache.get(0L, 10L))
        assertEquals("no further queries", 2, loads.size)
        assertEquals("no further redraws", 1, ready)
    }

    @Test fun anOldLoadEndingFirstDoesNotAnnounceTheOneStillRunning() = runBlocking {
        held = true
        val notification = async(Dispatchers.Unconfined) { cache.get(0L, 10L) }
        revision++
        cache.getWithoutWaiting(0L, 10L)
        loads[0].complete(journal())
        notification.await()
        assertEquals("nothing to redraw with yet", 0, ready)
        assertNull(cache.getWithoutWaiting(0L, 10L))

        loads[1].complete(journal())
        assertEquals(1, ready)
        assertSame(loads[1].getCompleted(), cache.getWithoutWaiting(0L, 10L))
        assertEquals(2, loads.size)
    }

    @Test fun ofOneRevisionTheLoadStartedLastStays() = runBlocking {
        held = true
        val first = async(Dispatchers.Unconfined) { cache.get(0L, 10L) }
        val second = async(Dispatchers.Unconfined) { cache.get(0L, 10L) }
        loads[1].complete(journal())
        loads[0].complete(journal())
        assertSame(second.await(), first.await())
        assertSame(second.await(), cache.getWithoutWaiting(0L, 10L))
    }
}
