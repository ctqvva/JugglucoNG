package tk.glucodata.share.lumiflex

import org.junit.Assert.*
import org.junit.Test

class LumiflexCurrentDeliveryTests {
    @Test fun aFailedLivePublishIsRetriedWithTheSameReadingAndRate() {
        val now = 1_790_000_000_000L
        val rows = listOf(LumiflexFollowReading(now - 60_000, 100f), LumiflexFollowReading(now, 102f))
        var attempts = 0
        var observedRate = Float.NaN
        val delivery = LumiflexCurrentDelivery { row, rate ->
            attempts++; observedRate = rate
            assertEquals(rows.last(), row)
            if (attempts == 1) throw IllegalStateException("publication failed")
        }
        assertThrows(IllegalStateException::class.java) { delivery.deliver(rows, now) }
        assertNull(delivery.snapshot)
        delivery.deliver(rows, now)
        assertEquals(2, attempts)
        assertEquals(2f, observedRate, 0f)
        assertEquals(rows.last(), delivery.snapshot!!.reading)
        delivery.deliver(rows, now)
        assertEquals(2, attempts)
    }

    @Test fun staleHistoryUpdatesTheSnapshotWithoutGeneratingLiveAlerts() {
        val now = 1_790_000_000_000L
        val stale = LumiflexFollowReading(now - 60 * 60_000L, 101f)
        val delivery = LumiflexCurrentDelivery { _, _ -> fail("stale live alert") }
        delivery.deliver(listOf(stale), now)
        assertEquals(stale, delivery.snapshot!!.reading)
    }
}
