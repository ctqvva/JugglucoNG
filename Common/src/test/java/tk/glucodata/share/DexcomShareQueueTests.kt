package tk.glucodata.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomShareQueueTests {
    private val minute = 60_000L
    private val base = 1_760_000_000_000L

    private fun egv(timeMs: Long, mgdl: Int = 120, trend: Int = 4) = ShareEgv(timeMs, mgdl, trend)

    @Test
    fun liveReadingsOneMinuteApartAreThinnedToOnePerFourAndAHalfMinutes() {
        val queue = DexcomShareQueue()
        val accepted = (0..10).mapNotNull { index ->
            val time = base + index * minute
            if (queue.offerLive("s1", egv(time), time)) time else null
        }

        assertEquals(listOf(base, base + 5 * minute, base + 10 * minute), accepted)
    }

    @Test
    fun backfillFillsAGapAtFiveMinuteSpacing() {
        val queue = DexcomShareQueue(enabledAtMs = base - 60 * minute)
        assertTrue(queue.offerLive("s1", egv(base), base))

        val gap = (1..6).map { egv(base - it * 5 * minute) }
        assertEquals(6, queue.offerBackfill("s1", gap, base))

        assertEquals(
            gap.map { it.timeMs }.reversed() + base,
            queue.pending(base).map { it.timeMs },
        )
    }

    @Test
    fun backfillForAnotherSensorIsIgnored() {
        val queue = DexcomShareQueue()
        assertTrue(queue.offerLive("s1", egv(base), base))

        assertEquals(0, queue.offerBackfill("s2", (1..3).map { egv(base - it * 5 * minute) }, base))
        assertEquals(1, queue.pending(base).size)
    }

    @Test
    fun backfillOlderThanTheEnableMomentIsIgnored() {
        val queue = DexcomShareQueue(enabledAtMs = base)
        assertTrue(queue.offerLive("s1", egv(base), base))

        assertEquals(
            0,
            queue.offerBackfill("s1", listOf(egv(base - minute), egv(base - 5 * minute)), base),
        )
    }

    @Test
    fun readingsOlderThanTheWindowAreDroppedAndFutureReadingsRejected() {
        val queue = DexcomShareQueue(enabledAtMs = base - 48 * 60 * minute)

        assertFalse(queue.offerLive("s1", egv(base - 25 * 60 * minute), base))
        assertTrue(queue.offerLive("s1", egv(base - 23 * 60 * minute), base))
        assertFalse(queue.offerLive("s1", egv(base + 2 * minute), base))
        assertTrue(queue.offerLive("s1", egv(base - minute), base))

        assertEquals(
            listOf(base - 23 * 60 * minute, base - minute),
            queue.pending(base).map { it.timeMs },
        )
    }

    @Test
    fun pendingDropsEntriesThatHaveLeftTheWindow() {
        val queue = DexcomShareQueue()
        assertTrue(queue.offerLive("s1", egv(base), base))

        assertTrue(queue.pending(base + 23 * 60 * minute).isNotEmpty())
        assertTrue(queue.pending(base + 25 * 60 * minute).isEmpty())
    }

    @Test
    fun overflowDropsTheOldestWaitingReading() {
        val queue = DexcomShareQueue(maxPending = 3)
        val times = (0..3).map { base + it * 5 * minute }
        times.forEach { time -> assertTrue(queue.offerLive("s1", egv(time), time)) }

        assertEquals(times.drop(1), queue.pending(times.last()).map { it.timeMs })
    }

    @Test
    fun deliveredAndDroppedTimestampsThinLaterReadings() {
        val queue = DexcomShareQueue()
        assertTrue(queue.offerLive("s1", egv(base), base))
        assertTrue(queue.offerLive("s1", egv(base + 5 * minute), base + 5 * minute))

        queue.markDelivered(listOf(egv(base)))
        assertEquals(listOf(base + 5 * minute), queue.pending(base + 5 * minute).map { it.timeMs })

        assertFalse(queue.offerLive("s1", egv(base + minute), base + minute))

        queue.drop(listOf(egv(base + 5 * minute)))
        assertEquals(emptyList<ShareEgv>(), queue.pending(base + 5 * minute))
        assertFalse(queue.offerLive("s1", egv(base + 5 * minute), base + 5 * minute))
    }

    @Test
    fun encodeDecodeRoundTripsTheQueue() {
        val queue = DexcomShareQueue(enabledAtMs = base - minute)
        assertTrue(queue.offerLive("s1", egv(base), base))
        assertTrue(queue.offerLive("s1", egv(base + 5 * minute), base + 5 * minute))
        queue.markDelivered(listOf(egv(base)))

        val restored = DexcomShareQueue.decode(queue.encode())

        assertEquals("s1", restored.liveSensorId())
        assertEquals(
            listOf(base + 5 * minute),
            restored.pending(base + 5 * minute).map { it.timeMs },
        )
        assertFalse(restored.offerLive("s1", egv(base + minute), base + minute))
        assertEquals(0, restored.offerBackfill("s1", listOf(egv(base - 2 * minute)), base))
    }

    @Test
    fun theHistoryRowOfTheLiveMomentWaitsAndTheLiveReadingWins() {
        val queue = DexcomShareQueue(enabledAtMs = base - minute)
        assertTrue(queue.offerLive("s1", egv(base), base))

        // The history row is stored seconds before the same reading goes out live, with no trend.
        val historyRow = egv(base + 5 * minute, mgdl = 130, trend = 0)
        assertEquals(0, queue.offerBackfill("s1", listOf(historyRow), base + 5 * minute))
        assertEquals(1, queue.heldCount())

        val live = egv(base + 5 * minute + 10_000L, mgdl = 131, trend = 4)
        assertTrue(queue.offerLive("s1", live, base + 5 * minute + 10_000L))

        assertEquals(listOf(egv(base), live), queue.pending(base + 5 * minute + 10_000L))
        assertEquals(0, queue.heldCount())
    }

    @Test
    fun aReconnectGapStoredBeforeTheLiveReadingIsReleasedByIt() {
        val queue = DexcomShareQueue(enabledAtMs = base - minute)
        assertTrue(queue.offerLive("s1", egv(base), base))

        // Disconnected for 30 minutes; the sensor's history lands before its first live reading.
        val reconnect = base + 35 * minute
        val gap = (1..6).map { egv(base + it * 5 * minute) }
        // Rows older than the hold limit cannot be the live reading's moment and go at once;
        // the last ten minutes wait for the live reading.
        assertEquals(4, queue.offerBackfill("s1", gap, reconnect))
        assertEquals(2, queue.heldCount())

        assertTrue(queue.offerLive("s1", egv(reconnect), reconnect))

        assertEquals(
            listOf(base) + gap.map { it.timeMs } + reconnect,
            queue.pending(reconnect).map { it.timeMs },
        )
    }

    @Test
    fun heldHistoryIsReleasedWhenNoLiveReadingPassesIt() {
        val queue = DexcomShareQueue(enabledAtMs = base - minute)
        assertTrue(queue.offerLive("s1", egv(base), base))
        assertEquals(0, queue.offerBackfill("s1", listOf(egv(base + 5 * minute)), base + 5 * minute))

        assertEquals(listOf(base), queue.pending(base + 10 * minute).map { it.timeMs })
        assertEquals(
            listOf(base, base + 5 * minute),
            queue.pending(base + 16 * minute).map { it.timeMs },
        )
    }

    @Test
    fun anotherLiveSensorDropsTheHeldHistory() {
        val queue = DexcomShareQueue(enabledAtMs = base - minute)
        assertTrue(queue.offerLive("s1", egv(base), base))
        queue.offerBackfill("s1", listOf(egv(base + 5 * minute)), base + 5 * minute)

        assertTrue(queue.offerLive("s2", egv(base + 6 * minute), base + 6 * minute))

        assertEquals(0, queue.heldCount())
        assertEquals(listOf(base, base + 6 * minute), queue.pending(base + 30 * minute).map { it.timeMs })
    }

    @Test
    fun heldHistoryAndTheLastLiveMomentSurviveARestart() {
        val queue = DexcomShareQueue(enabledAtMs = base - minute)
        assertTrue(queue.offerLive("s1", egv(base), base))
        queue.offerBackfill("s1", listOf(egv(base + 5 * minute)), base + 5 * minute)

        val restored = DexcomShareQueue.decode(queue.encode())

        assertEquals(1, restored.heldCount())
        // Still newer than the restored last live moment, so it is held, not queued.
        assertEquals(0, restored.offerBackfill("s1", listOf(egv(base + 10 * minute)), base + 10 * minute))
        assertTrue(restored.offerLive("s1", egv(base + 11 * minute), base + 11 * minute))
        assertEquals(
            listOf(base, base + 5 * minute, base + 11 * minute),
            restored.pending(base + 11 * minute).map { it.timeMs },
        )
    }

    @Test
    fun decodingGarbageYieldsAnEmptyQueue() {
        val restored = DexcomShareQueue.decode("not json")

        assertEquals(null, restored.liveSensorId())
        assertTrue(restored.pending(base).isEmpty())
    }
}
