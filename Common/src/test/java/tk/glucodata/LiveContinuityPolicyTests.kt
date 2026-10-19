package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveContinuityPolicyTests {
    @Test
    fun `no continuity sync for first reading`() {
        val decision = LiveContinuityPolicy.decideContinuitySync(
            previousReadingMs = 0L,
            currentReadingMs = 1_000L,
            bucketSizeMs = 60_000L,
            maxMissingBuckets = 10,
        )

        assertFalse(decision.shouldRequestContinuitySync)
    }

    @Test
    fun `no continuity sync for normal one minute cadence with jitter`() {
        val decision = LiveContinuityPolicy.decideContinuitySync(
            previousReadingMs = 1_000L,
            currentReadingMs = 64_000L,
            bucketSizeMs = 60_000L,
            maxMissingBuckets = 10,
        )

        assertFalse(decision.shouldRequestContinuitySync)
        assertEquals(0, decision.missingBuckets)
    }

    @Test
    fun `continuity sync for one missing sample bucket`() {
        val decision = LiveContinuityPolicy.decideContinuitySync(
            previousReadingMs = 1_000L,
            currentReadingMs = 121_000L,
            bucketSizeMs = 60_000L,
            maxMissingBuckets = 10,
        )

        assertTrue(decision.shouldRequestContinuitySync)
        assertEquals(1, decision.missingBuckets)
    }

    @Test
    fun `no continuity sync for duplicate or out of order sample`() {
        val duplicate = LiveContinuityPolicy.decideContinuitySync(
            previousReadingMs = 121_000L,
            currentReadingMs = 121_000L,
            bucketSizeMs = 60_000L,
            maxMissingBuckets = 10,
        )
        val outOfOrder = LiveContinuityPolicy.decideContinuitySync(
            previousReadingMs = 121_000L,
            currentReadingMs = 61_000L,
            bucketSizeMs = 60_000L,
            maxMissingBuckets = 10,
        )

        assertFalse(duplicate.shouldRequestContinuitySync)
        assertFalse(outOfOrder.shouldRequestContinuitySync)
    }

    @Test
    fun `no continuity sync for long reconnect scale gap`() {
        val decision = LiveContinuityPolicy.decideContinuitySync(
            previousReadingMs = 1_000L,
            currentReadingMs = 1_201_000L,
            bucketSizeMs = 60_000L,
            maxMissingBuckets = 10,
        )

        assertFalse(decision.shouldRequestContinuitySync)
        assertEquals(19, decision.missingBuckets)
    }
}
