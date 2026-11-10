package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tk.glucodata.ui.overlay.nextOverlayFreshnessCheckDelay
import tk.glucodata.ui.overlay.overlayDisplayPoint

/**
 * The floating overlay only recomposes on new data, so its own clock is the only
 * thing that can blank a reading once the sensor goes quiet (#554). Every layout
 * (pill, side and top island) draws value and arrow from [overlayDisplayPoint], so
 * these tests replay the overlay's clock against it: shown while fresh, gone at the
 * timeout, back with the next reading. The composable itself can't be rendered
 * here: it calls into JNI (Natives) while composing.
 */
class FloatingOverlayFreshnessTests {
    private val start = 1_700_000_000_000L
    private val window = 330_000L

    private fun point(at: Long) = GlucosePoint(value = 6.1f, time = "", timestamp = at)

    /** Walk the overlay's clock from [from] until it stops; returns where it stopped. */
    private fun runClock(latestMillis: Long, from: Long): Long {
        var now = from
        while (true) {
            val wait = nextOverlayFreshnessCheckDelay(latestMillis, now, window) ?: return now
            now += wait
        }
    }

    private fun shown(latest: GlucosePoint?, now: Long, snapshotMillis: Long = 0L) =
        overlayDisplayPoint(latest, snapshotMillis, now, window)

    @Test
    fun aFreshReadingIsShown() {
        val reading = point(start)
        assertSame(reading, shown(reading, start + 60_000L))
    }

    @Test
    fun theClockBlanksTheReadingJustPastTheTimeoutThenStops() {
        val reading = point(start)
        val stoppedAt = runClock(reading.timestamp, start + 10_000L)
        assertEquals(start + window + 1L, stoppedAt)
        assertSame(reading, shown(reading, stoppedAt - 1L))
        assertNull(shown(reading, stoppedAt))
    }

    @Test
    fun aNewReadingAfterStalenessIsShownAgain() {
        val old = point(start)
        val stoppedAt = runClock(old.timestamp, start)
        assertNull(shown(old, stoppedAt))

        val next = point(stoppedAt + 120_000L)
        assertSame(next, shown(next, next.timestamp))
        assertEquals(15_000L, nextOverlayFreshnessCheckDelay(next.timestamp, next.timestamp, window))
    }

    @Test
    fun aFresherCurrentSnapshotKeepsAnOlderHistoryTailVisible() {
        val tail = point(start)
        val now = start + window + 60_000L
        assertNull(shown(tail, now))
        assertSame(tail, shown(tail, now, snapshotMillis = start + 120_000L))
    }

    @Test
    fun noReadingMeansNoDataAndNoClock() {
        assertNull(shown(null, start))
        assertNull(nextOverlayFreshnessCheckDelay(0L, start, window))
    }

    @Test
    fun aReadingAlreadyStaleOnStartDoesNotStartTheClock() {
        val reading = point(start - window - 60_000L)
        assertNull(shown(reading, start))
        assertNull(nextOverlayFreshnessCheckDelay(reading.timestamp, start, window))
    }
}
