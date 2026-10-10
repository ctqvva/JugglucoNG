package tk.glucodata.drivers.nightscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The order of one follower refresh. The value on screen used to wait behind treatments,
 * finger sticks and devicestatus downloading one after another; these pin that it no longer
 * does, and that a refresh given up on mid-flight cannot publish over its replacement.
 */
class NightscoutFollowerRefreshSequenceTests {

    private val steps = mutableListOf<String>()

    private fun run(
        current: () -> Boolean = { true },
        enrich: () -> Unit = { steps += "enrich" },
        onEnrichFailure: (Throwable) -> Unit = {},
    ): NightscoutFollowerRefreshSequence.Outcome =
        NightscoutFollowerRefreshSequence.run(
            fetch = { steps += "fetch"; listOf(142f) },
            publish = { readings -> steps += "publish:${readings.single()}" },
            enrich = enrich,
            isCurrent = current,
            onEnrichFailure = onEnrichFailure,
        )

    @Test
    fun readingsArePublishedBeforeEnrichmentStarts() {
        val outcome = run()

        assertEquals(NightscoutFollowerRefreshSequence.Outcome.COMPLETED, outcome)
        assertEquals(listOf("fetch", "publish:142.0", "enrich"), steps)
    }

    @Test
    fun failingEnrichmentDoesNotFailARefreshThatAlreadyPublished() {
        // Otherwise a treatments endpoint that errors would put the follower into backoff and
        // show "sync failed" right after it delivered a fresh value.
        var reported: Throwable? = null
        val outcome = run(
            enrich = { throw IllegalStateException("treatments HTTP 500") },
            onEnrichFailure = { reported = it },
        )

        assertEquals(NightscoutFollowerRefreshSequence.Outcome.COMPLETED, outcome)
        assertEquals(listOf("fetch", "publish:142.0"), steps)
        assertEquals("treatments HTTP 500", reported?.message)
    }

    @Test
    fun refreshAbandonedDuringFetchPublishesNothing() {
        val outcome = run(current = { "fetch" !in steps })

        assertEquals(NightscoutFollowerRefreshSequence.Outcome.SUPERSEDED, outcome)
        assertEquals(listOf("fetch"), steps)
    }

    @Test
    fun refreshAbandonedAfterPublishingSkipsEnrichment() {
        val outcome = run(current = { steps.none { it.startsWith("publish") } })

        assertEquals(NightscoutFollowerRefreshSequence.Outcome.SUPERSEDED, outcome)
        assertEquals(listOf("fetch", "publish:142.0"), steps)
    }

    @Test
    fun refreshAbandonedDuringEnrichmentDoesNotReschedule() {
        // SUPERSEDED is what keeps the stale refresh from booking the next poll over the one
        // its replacement booked.
        val outcome = run(current = { "enrich" !in steps })

        assertEquals(NightscoutFollowerRefreshSequence.Outcome.SUPERSEDED, outcome)
        assertTrue("enrich" in steps)
    }

    @Test
    fun fetchFailurePropagatesWithoutPublishing() {
        try {
            NightscoutFollowerRefreshSequence.run(
                fetch = { throw IllegalStateException("entries HTTP 401") },
                publish = { _: Unit -> fail("published without readings") },
                enrich = { fail("enriched without readings") },
                isCurrent = { true },
            )
            fail("fetch failure was swallowed")
        } catch (expected: IllegalStateException) {
            assertEquals("entries HTTP 401", expected.message)
        }
    }
}
