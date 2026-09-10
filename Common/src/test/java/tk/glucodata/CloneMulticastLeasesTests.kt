package tk.glucodata

import org.junit.Assert.*
import org.junit.Test

class CloneMulticastLeasesTests {
    @Test fun listenersShareLockUntilLastSessionStops() {
        var acquisitions = 0
        var releases = 0
        val leases = CloneMulticastLeases({ acquisitions++; true }, { releases++ })
        assertTrue(leases.acquire())
        assertTrue(leases.acquire())
        assertEquals(1, acquisitions)
        leases.release()
        assertEquals(0, releases)
        leases.release()
        assertEquals(1, releases)
        leases.release()
        assertEquals(1, releases)
        assertTrue(leases.acquire())
        assertEquals(2, acquisitions)
        leases.release()
        assertEquals(2, releases)
    }

    @Test fun failedAcquisitionCanBeRetriedWithoutLeakingALease() {
        var available = false
        var releases = 0
        val leases = CloneMulticastLeases({ available }, { releases++ })
        assertFalse(leases.acquire())
        leases.release()
        assertEquals(0, releases)
        available = true
        assertTrue(leases.acquire())
        leases.release()
        assertEquals(1, releases)
    }

    @Test fun concurrentSessionLifetimesBalance() {
        var acquisitions = 0
        var releases = 0
        val leases = CloneMulticastLeases({ acquisitions++; true }, { releases++ })
        val threads = List(8) {
            Thread { repeat(100) { assertTrue(leases.acquire()); leases.release() } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(acquisitions > 0)
        assertEquals(acquisitions, releases)
    }
}
