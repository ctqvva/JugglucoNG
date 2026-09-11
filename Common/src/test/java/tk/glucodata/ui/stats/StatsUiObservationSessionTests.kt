package tk.glucodata.ui.stats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StatsUiObservationSessionTests {
    private class Fixture : AutoCloseable {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var starts = 0
        var stops = 0
        var cancellations = 0
        val observers = mutableListOf<Job>()
        val session = StatsUiObservationSession(parent, ::start, { stops++ })

        private fun start() {
            starts++
            observers += checkNotNull(session.scope).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    cancellations++
                }
            }
        }

        override fun close() { parent.cancel() }
    }

    @Test fun noConsumerMeansNoObservationScope() = Fixture().use { f ->
        assertNull(f.session.scope)
        assertEquals(0, f.starts)
    }

    @Test fun overlappingScreensShareOneSessionUntilLastConsumerStops() = Fixture().use { f ->
        val dashboard = Any()
        val stats = Any()
        f.session.acquire(dashboard)
        f.session.acquire(stats)
        f.session.release(dashboard)
        assertEquals(1, f.starts)
        assertTrue(f.observers.single().isActive)
        assertEquals(0, f.stops)
        f.session.release(stats)
        runBlocking { f.observers.single().join() }
        assertNull(f.session.scope)
        assertEquals(1, f.stops)
        assertEquals(1, f.cancellations)
    }

    @Test fun resumeCreatesFreshScopeAndIgnoresLateReleaseFromOldScreen() = Fixture().use { f ->
        val old = Any()
        val current = Any()
        f.session.acquire(old)
        val oldScope = f.session.scope
        f.session.release(old)
        f.session.acquire(current)
        f.session.release(old)
        assertNotSame(oldScope, f.session.scope)
        assertEquals(2, f.starts)
        assertTrue(f.observers.last().isActive)
        assertFalse(f.observers.first().isActive)
    }

    @Test fun duplicateLifecycleEventsDoNotLeakConsumers() = Fixture().use { f ->
        val screen = Any()
        f.session.acquire(screen)
        f.session.acquire(screen)
        f.session.release(screen)
        f.session.release(screen)
        assertNull(f.session.scope)
        assertEquals(1, f.starts)
        assertEquals(1, f.stops)
    }

    @Test fun stoppingUiDoesNotCancelIndependentWork() = Fixture().use { f ->
        val report = f.parent.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        val screen = Any()
        f.session.acquire(screen)
        f.session.release(screen)
        assertTrue(report.isActive)
        assertTrue(f.parent.coroutineContext[Job]!!.isActive)
    }

    @Test fun viewModelScopeCancellationAlsoStopsObservers() = Fixture().use { f ->
        f.session.acquire(Any())
        f.parent.cancel()
        runBlocking { f.observers.single().join() }
        assertEquals(1, f.cancellations)
    }
}
