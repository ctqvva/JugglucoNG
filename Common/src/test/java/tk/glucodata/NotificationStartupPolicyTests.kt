package tk.glucodata

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.NotificationStartupPolicy.RestoreOutcome

/**
 * Portable JVM tests for startup freshness classification. These call the real
 * policy code that the startup restore uses (same class, no mirrors): a non-null
 * snapshot does not imply freshness, so the timestamp actually about to be
 * rendered decides. Header-timestamp application is covered against the real
 * builder code by NotificationStartupRestoreTests.
 */
class NotificationStartupPolicyTests {
    private val now = 1_700_000_000_000L
    private val window = 330_000L

    private fun classify(sensorPresent: Boolean, currentMillis: Long, historyMillis: Long) =
        NotificationStartupPolicy.classifyRestore(sensorPresent, currentMillis, historyMillis, now, window)

    @Test fun freshRenderedReadingRestoresAsFresh() {
        assertEquals(RestoreOutcome.FRESH_READING, classify(true, now - 60_000L, now - 60_000L))
    }

    @Test fun freshnessBoundaryIsInclusive() {
        assertEquals(RestoreOutcome.FRESH_READING, classify(true, now - window, now - window))
        assertEquals(RestoreOutcome.STALE_READING, classify(true, now - window - 1L, now - window - 1L))
    }

    @Test fun sixMinuteOldRenderedReadingIsStaleNotFresh() {
        // The resolver window is 330s but history fallbacks come from a wider
        // trend window; 6 minutes already exceeds freshness.
        assertEquals(RestoreOutcome.STALE_READING, classify(true, now - 360_000L, now - 360_000L))
    }

    @Test fun fifteenAndTwentyFourMinuteOldReadingsAreStale() {
        assertEquals(RestoreOutcome.STALE_READING, classify(true, now - 15 * 60_000L, now - 15 * 60_000L))
        assertEquals(RestoreOutcome.STALE_READING, classify(true, now - 24 * 60_000L, now - 24 * 60_000L))
    }

    @Test fun latestTimestampDecidesWhenNothingNewerRendered() {
        assertEquals(RestoreOutcome.FRESH_READING, classify(true, 0L, now - 60_000L))
        assertEquals(RestoreOutcome.STALE_READING, classify(true, 0L, now - 400_000L))
    }

    @Test fun sensorPresentWithNoDataWaits() {
        assertEquals(RestoreOutcome.AWAITING_DATA, classify(true, 0L, 0L))
    }

    @Test fun absentSensorReportsNoSensorEvenWithData() {
        assertEquals(RestoreOutcome.NO_SENSOR, classify(false, now - 60_000L, now - 60_000L))
        assertEquals(RestoreOutcome.NO_SENSOR, classify(false, 0L, 0L))
    }
}
