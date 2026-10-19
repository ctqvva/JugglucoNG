package tk.glucodata

import org.junit.Assert.*
import org.junit.Test

class CloneRecoveryWakeTests {
    @Test fun acquisitionIsBoundedAndReleaseIsIdempotent() {
        var timeout = 0L
        var releases = 0
        val leases = CloneRecoveryWakeLeases({ 100L }) { value ->
            timeout = value
            val end: () -> Unit = { releases++; Unit }
            end
        }
        val token = leases.acquire()
        assertTrue(token > 0)
        assertEquals(30_000L, timeout)
        leases.release(token)
        leases.release(token)
        assertEquals(1, releases)
    }

    @Test fun concurrentHostsAndStaleTokensCannotReleaseEachOther() {
        var acquisitions = 0
        val released = mutableListOf<Int>()
        val leases = CloneRecoveryWakeLeases({ 0L }) { _ ->
            val id = ++acquisitions
            val end: () -> Unit = { released.add(id); Unit }
            end
        }
        val first = leases.acquire()
        val second = leases.acquire()
        assertNotEquals(first, second)
        leases.release(first)
        leases.release(first)
        assertEquals(listOf(1), released)
        leases.release(second)
        assertEquals(listOf(1, 2), released)
    }

    @Test fun disableReleasesEveryLeaseAndBlocksLateAcquisition() {
        var acquired = 0
        var released = 0
        val leases = CloneRecoveryWakeLeases({ 0L }) { _ ->
            acquired++
            val end: () -> Unit = { released++; Unit }
            end
        }
        val old = leases.acquire()
        leases.acquire()
        leases.setEnabled(false)
        assertEquals(2, released)
        assertEquals(0L, leases.acquire())
        assertEquals(2, acquired)
        leases.setEnabled(true)
        val next = leases.acquire()
        leases.release(old)
        assertEquals(2, released)
        leases.release(next)
        assertEquals(3, released)
    }

    @Test fun expiredTokensArePrunedWithoutChangingOtherDeadlines() {
        var time = 0L
        var releases = 0
        val requestedTimeouts = mutableListOf<Long>()
        val leases = CloneRecoveryWakeLeases({ time }) { timeout ->
            requestedTimeouts.add(timeout)
            val end: () -> Unit = { releases++; Unit }
            end
        }
        val expired = leases.acquire()
        time = 29_000
        leases.acquire()
        time = 30_000
        leases.acquire()
        assertEquals(1, releases)
        leases.release(expired)
        assertEquals(1, releases)
        assertEquals(listOf(30_000L, 30_000L, 30_000L), requestedTimeouts)
        leases.releaseAll()
        assertEquals(3, releases)
    }

    @Test fun failedAcquisitionHasNoLeaseToRelease() {
        val leases = CloneRecoveryWakeLeases({ 0L }) { null }
        assertEquals(0L, leases.acquire())
        leases.release(0)
        leases.releaseAll()
    }
}
