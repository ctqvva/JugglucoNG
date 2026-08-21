package tk.glucodata.alerts

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.AlertDeliveryPolicy

/** The quiet window's pure rules: it always ends, and a silenced episode has a start. */
class QuietWindowTests {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60L * 1000L
    private val minute = 60_000L

    @Test
    fun untilIEndItIsTwentyFourHoursNoMore() {
        assertEquals(now + QuietWindow.MAX_DURATION_MS, QuietWindow.cappedUntil(now + QuietWindow.MAX_DURATION_MS, now))
        assertEquals(now + 24 * hour, QuietWindow.cappedUntil(now + 48 * hour, now))
        assertEquals(now + 24 * hour, QuietWindow.cappedUntil(Long.MAX_VALUE, now))
    }

    @Test
    fun shorterRequestsAreKeptAndPastOnesClampToNow() {
        assertEquals(now + 2 * hour, QuietWindow.cappedUntil(now + 2 * hour, now))
        assertEquals(now, QuietWindow.cappedUntil(now - hour, now))
    }

    @Test
    fun anEndTimeTheClockHasPassedIsOver() {
        assertEquals(0L, QuietWindow.effectiveUntil(now - 1, now))
        assertEquals(0L, QuietWindow.effectiveUntil(now, now))
        assertEquals(now + 1, QuietWindow.effectiveUntil(now + 1, now))
        assertEquals(0L, QuietWindow.effectiveUntil(0L, now))
    }

    @Test
    fun aPickedTimeOfDayMeansTodayIfAheadElseTomorrowAndNeverPastTheCap() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 10)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val tenOClock = cal.timeInMillis

        val later = QuietWindow.untilForTimeOfDay(12, 30, tenOClock)
        assertEquals(tenOClock + 2 * hour + 30 * minute, later)

        val earlier = QuietWindow.untilForTimeOfDay(9, 0, tenOClock)
        assertTrue("tomorrow 09:00 lies ahead", earlier > tenOClock)
        assertTrue("and within the cap", earlier <= tenOClock + QuietWindow.MAX_DURATION_MS)

        // Exactly now means tomorrow, and tomorrow-at-the-same-time is the cap.
        val sameTime = QuietWindow.untilForTimeOfDay(10, 0, tenOClock)
        assertTrue(sameTime > tenOClock)
        assertTrue(sameTime <= tenOClock + QuietWindow.MAX_DURATION_MS)
    }

    @Test
    fun modeAndMinutesAreSanitized() {
        assertEquals(AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, QuietWindow.parseMode(null))
        assertEquals(AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, QuietWindow.parseMode("notification_only"))
        assertEquals(QuietWindow.DEFAULT_BREAKTHROUGH_MINUTES, QuietWindow.sanitizeBreakthroughMinutes(0))
        assertEquals(QuietWindow.DEFAULT_BREAKTHROUGH_MINUTES, QuietWindow.sanitizeBreakthroughMinutes(120))
        assertEquals(15, QuietWindow.sanitizeBreakthroughMinutes(15))
        assertEquals(QuietWindow.DEFAULT_MINUTES, QuietWindow.sanitizeDefaultMinutes(0))
        assertEquals(QuietWindow.DEFAULT_MINUTES, QuietWindow.sanitizeDefaultMinutes(25 * 60))
        assertEquals(180, QuietWindow.sanitizeDefaultMinutes(180))
    }

    @Test
    fun aSilencedEpisodeKeepsItsStartAcrossDeliveries() {
        val tracker = SilencedEpisodeTracker(staleMs = 30 * minute)
        assertEquals(now, tracker.note(kind = 0, nowMs = now))
        assertEquals(now, tracker.note(kind = 0, nowMs = now + 5 * minute))
        assertEquals(now, tracker.note(kind = 0, nowMs = now + 12 * minute))
        assertTrue(tracker.has(0))
    }

    @Test
    fun aDeliveryAfterTheStaleGapStartsANewEpisode() {
        val tracker = SilencedEpisodeTracker(staleMs = 30 * minute)
        tracker.note(0, now)
        tracker.note(0, now + 10 * minute)
        // 35 minutes after the last silenced delivery: the old episode was over.
        assertEquals(now + 45 * minute, tracker.note(0, now + 45 * minute))
    }

    @Test
    fun aDeliveryExactlyAtTheStaleGapStartsANewEpisodeUnlessRefreshed() {
        // The scheduled breakthrough check comes round at N minutes; with N at the
        // stale gap the redelivery it causes would otherwise begin a new episode and
        // never break through. refresh() is what the check does first.
        val tracker = SilencedEpisodeTracker(staleMs = 30 * minute)
        tracker.note(0, now)
        assertEquals(now + 30 * minute, tracker.note(0, now + 30 * minute))

        val refreshed = SilencedEpisodeTracker(staleMs = 30 * minute)
        refreshed.note(0, now)
        refreshed.refresh(0, now + 30 * minute)
        assertEquals(now, refreshed.note(0, now + 30 * minute + 200))
        // refresh never creates an episode.
        refreshed.refresh(7, now)
        assertFalse(refreshed.has(7))
    }

    @Test
    fun customAlertsHaveTheirOwnEpisodeKey() {
        assertEquals(1000, QuietWindow.customEpisodeKind(0))
        assertEquals(1001, QuietWindow.customEpisodeKind(1))
        val tracker = SilencedEpisodeTracker(staleMs = 30 * minute)
        tracker.note(0, now)
        assertEquals(now + minute, tracker.note(QuietWindow.customEpisodeKind(0), now + minute))
        assertEquals(now, tracker.note(0, now + 2 * minute))
    }

    @Test
    fun kindsAreTrackedApartAndClearedApart() {
        val tracker = SilencedEpisodeTracker(staleMs = 30 * minute)
        tracker.note(0, now)
        assertEquals(now + minute, tracker.note(1, now + minute))
        tracker.clear(0)
        assertFalse(tracker.has(0))
        assertTrue(tracker.has(1))
        tracker.clearAll()
        assertFalse(tracker.has(1))
        // After a clear the next delivery is a fresh start, whenever it comes.
        assertEquals(now + 2 * minute, tracker.note(0, now + 2 * minute))
    }
}
