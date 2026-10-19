package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRefreshPolicyTests {
    @Test fun actualReadingTimestampControlsFreshnessAndDeadline() {
        val reading = 1_000_000L
        val timeout = 330_000L
        assertTrue(NotificationRefreshPolicy.isFresh(reading, reading + timeout, timeout))
        assertFalse(NotificationRefreshPolicy.isFresh(reading, reading + timeout + 1L, timeout))
        assertEquals(1L, NotificationRefreshPolicy.deadlineDelayMs(reading, reading + timeout, timeout))
        assertEquals(0L, NotificationRefreshPolicy.deadlineDelayMs(reading, reading + timeout + 1L, timeout))
        assertTrue("clock rollback does not make a reading stale", NotificationRefreshPolicy.isFresh(reading, reading - 1L, timeout))
    }

    @Test fun repeatedDataRequestsCannotPostponePendingRefresh() {
        val first = NotificationRefreshPolicy.boundedDebounceAt(0L, 10_000L, 1_000L)
        assertEquals(11_000L, first)
        assertEquals(first, NotificationRefreshPolicy.boundedDebounceAt(first, 10_900L, 1_000L))
        assertEquals("an overdue callback still owns the slot until it runs",
            first, NotificationRefreshPolicy.boundedDebounceAt(first, 11_001L, 1_000L))
        assertEquals(12_001L, NotificationRefreshPolicy.boundedDebounceAt(0L, 11_001L, 1_000L))
    }

    @Test fun invalidReadingHasNoDeadline() {
        assertEquals(0L, NotificationRefreshPolicy.deadlineDelayMs(0L, 10L, 330_000L))
        assertFalse(NotificationRefreshPolicy.isFresh(0L, 10L, 330_000L))
    }

    @Test fun statusRefreshSharesFirstDeadlineWinsCoalescing() {
        // Data and status changes share the same bounded trailing debounce (separate
        // pending slots in Notify): while a refresh is pending, continuous events must
        // not postpone it; once it fires, a new event arms a new deadline.
        val first = NotificationRefreshPolicy.boundedDebounceAt(0L, 20_000L, 1_000L)
        assertEquals(21_000L, first)
        assertEquals("pending status requests coalesce without moving the deadline",
            first, NotificationRefreshPolicy.boundedDebounceAt(first, 20_900L, 1_000L))
        assertEquals(first, NotificationRefreshPolicy.boundedDebounceAt(first, 21_001L, 1_000L))
        assertEquals(22_001L, NotificationRefreshPolicy.boundedDebounceAt(0L, 21_001L, 1_000L))
        assertEquals("non-positive delays fire immediately without going backwards",
            20_000L, NotificationRefreshPolicy.boundedDebounceAt(0L, 20_000L, -5L))
    }

    @Test fun retainedDisplayMustMatchSensorUnitsAndViewMode() {
        assertTrue(NotificationRefreshPolicy.matchesRetainedDisplay(
            "sensor-1", "sensor-1", true, true, 3, 3))
        assertFalse(NotificationRefreshPolicy.matchesRetainedDisplay(
            null, "sensor-1", true, true, 3, 3))
        assertFalse(NotificationRefreshPolicy.matchesRetainedDisplay(
            "sensor-2", "sensor-1", true, true, 3, 3))
        assertFalse(NotificationRefreshPolicy.matchesRetainedDisplay(
            "sensor-1", "sensor-1", true, false, 3, 3))
        assertFalse(NotificationRefreshPolicy.matchesRetainedDisplay(
            "sensor-1", "sensor-1", true, true, 1, 3))
    }

    @Test fun oldServiceCannotCancelReplacementWork() {
        val old = Any()
        val replacement = Any()
        assertFalse(NotificationRefreshPolicy.mayCancelForOwner(old, replacement))
        assertTrue(NotificationRefreshPolicy.mayCancelForOwner(replacement, replacement))
        assertTrue(NotificationRefreshPolicy.mayCancelForOwner(null, replacement))
    }

    @Test fun alertwatchOngoingReadingsStayOnPinnedLifecyclePath() {
        assertTrue(NotificationRefreshPolicy.shouldPinOngoingGlucose(
            false, true, false, true))
        assertTrue(NotificationRefreshPolicy.shouldPinOngoingGlucose(
            false, true, true, false))
        assertFalse(NotificationRefreshPolicy.shouldPinOngoingGlucose(
            true, true, false, true))
        assertFalse(NotificationRefreshPolicy.shouldPinOngoingGlucose(
            false, false, false, true))
    }

    @Test fun failedPublicationGetsOnlyOneRetryPerGeneration() {
        assertTrue(NotificationRefreshPolicy.shouldSchedulePublicationRetry(0L, 7L))
        assertFalse(NotificationRefreshPolicy.shouldSchedulePublicationRetry(7L, 7L))
        assertFalse(NotificationRefreshPolicy.shouldSchedulePublicationRetry(0L, 0L))
        assertTrue(NotificationRefreshPolicy.shouldSchedulePublicationRetry(7L, 8L))
    }
}
