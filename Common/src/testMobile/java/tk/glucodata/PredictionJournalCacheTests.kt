package tk.glucodata

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PredictionJournalCacheTests {
    private var revision = 1L
    private var now = 1_000_000L
    private var ready = 0
    private var failing = false
    private val loads = mutableListOf<CompletableDeferred<PredictionJournal>>()
    private var held = false

    private val cache = PredictionJournalCache(
        scope = CoroutineScope(Dispatchers.Unconfined),
        revision = { revision },
        clock = { now },
        onMissedLoadReady = { ready++ },
        load = { _, _ ->
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
}
