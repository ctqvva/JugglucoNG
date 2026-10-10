package tk.glucodata.drivers.nightscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.VirtualGlucoseSensorBridge

class NightscoutFollowerHistoryPagingTests {

    @Test
    fun bootstrapHasNoHistoryLimit() {
        val lowerBound = NightscoutFollowerHistoryPaging.lowerBoundMs(
            latestStoredMs = 1_800_000_000_000L - 60_000L,
            bootstrap = true,
        )

        assertEquals(null, lowerBound)
    }

    @Test
    fun incrementalFetchOverlapsLatestStoredReading() {
        val nowMs = 1_800_000_000_000L
        val latestStoredMs = nowMs - 60_000L

        val lowerBound = NightscoutFollowerHistoryPaging.lowerBoundMs(
            latestStoredMs = latestStoredMs,
            bootstrap = false,
            nowMs = nowMs,
        )

        assertEquals(latestStoredMs - 5L * 60L * 1000L, lowerBound)
    }

    @Test
    fun futureDatedStoredReadingDoesNotPushTheFetchPastNow() {
        // One reading from an uploader whose clock ran ten minutes ahead must not make every
        // later fetch ask only for readings newer than that, which the server does not have.
        val nowMs = 1_800_000_000_000L
        val lowerBound = NightscoutFollowerHistoryPaging.lowerBoundMs(
            latestStoredMs = nowMs + 10L * 60_000L,
            bootstrap = false,
            nowMs = nowMs,
        )

        assertEquals(nowMs - 5L * 60L * 1000L, lowerBound)
    }

    @Test
    fun bootstrapEndpointHasNoLowerTimestampBound() {
        val endpoint = NightscoutFollowerHistoryPaging.endpoint(
            baseUrl = "https://example.com",
            lowerBoundMs = null,
            beforeExclusiveMs = null,
        )

        assertTrue(endpoint.endsWith("/api/v1/entries/sgv.json?count=1000"))
    }

    @Test
    fun incrementalEndpointUsesTimestampBoundsAndPageCount() {
        val endpoint = NightscoutFollowerHistoryPaging.endpoint(
            baseUrl = "https://example.com",
            lowerBoundMs = 1_000L,
            beforeExclusiveMs = 5_000L,
        )

        assertTrue(endpoint.startsWith("https://example.com/api/v1/entries/sgv.json?"))
        assertTrue(endpoint.contains("count=1000"))
        assertTrue(endpoint.contains("find%5Bdate%5D%5B%24gte%5D=1000"))
        assertTrue(endpoint.contains("find%5Bdate%5D%5B%24lt%5D=5000"))
    }

    @Test
    fun bootstrapConsumesEveryPagePastPreviousPageLimit() {
        val all = (25_000L downTo 1_000L step 1_000L).toList()
        val requestedBefore = mutableListOf<Long?>()
        val consumed = mutableListOf<Long>()

        val result = NightscoutFollowerHistoryPaging.consumePages(
            lowerBoundMs = null,
            fetchPage = { before ->
                requestedBefore += before
                all.asSequence()
                    .filter { before == null || it < before }
                    .take(1)
                    .map(::reading)
                    .toList()
            },
            consumePage = { page -> consumed += page.map { it.timestampMs } },
        )

        assertEquals(all, consumed)
        assertEquals(25, result.fetchedCount)
        assertEquals(listOf(25_000L), result.newestReadings.map { it.timestampMs })
        assertEquals(26, requestedBefore.size)
        assertTrue(result.reachedEnd)
    }

    @Test
    fun pagingStopsWhenServerIgnoresBeforeFilter() {
        var requests = 0
        var consumedPages = 0
        val samePage = listOf(reading(6_000L), reading(5_000L))

        val result = NightscoutFollowerHistoryPaging.consumePages(
            lowerBoundMs = null,
            fetchPage = {
                requests += 1
                samePage
            },
            consumePage = { consumedPages += 1 },
        )

        assertEquals(2, requests)
        assertEquals(1, consumedPages)
        assertEquals(2, result.fetchedCount)
        assertEquals(listOf(6_000L, 5_000L), result.newestReadings.map { it.timestampMs })
        assertEquals(false, result.reachedEnd)
    }

    private fun reading(timestampMs: Long) =
        VirtualGlucoseSensorBridge.Reading(
            timestampMs = timestampMs,
            glucoseMgdl = 100f,
        )
}
