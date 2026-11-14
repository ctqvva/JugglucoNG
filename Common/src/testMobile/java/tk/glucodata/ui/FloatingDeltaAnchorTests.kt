package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.deltaSnapshot
import tk.glucodata.ui.overlay.OverlayReadings
import tk.glucodata.ui.overlay.overlayDeltaText
import tk.glucodata.ui.overlay.overlayDisplayPoint

class FloatingDeltaAnchorTests {
    private val now = 1_800_000_000_000L
    private val shown = deltaSnapshot(at = now - 4 * 60_000L)
    private val frame = OverlayReadings("sensor", shown, emptyList(), "+3", 5)

    @Test fun aFreshCompletedBucketKeepsItsDeltaWhileHistoryHasNewerReadings() {
        val historyTail = GlucosePoint(value = 120f, time = "", timestamp = now)
        assertEquals(now, overlayDisplayPoint(historyTail, shown.timeMillis, now)!!.timestamp)
        assertEquals("+3", overlayDeltaText(frame, true, "sensor", 5, now))
    }

    @Test fun freshnessIsCheckedAtTheSnapshotActuallyShownRatherThanTheHistoryTail() {
        assertEquals("+3", overlayDeltaText(frame, true, "sensor", 5, shown.timeMillis + 330_000L))
        assertEquals("", overlayDeltaText(frame, true, "sensor", 5, shown.timeMillis + 330_001L))
        assertEquals("", overlayDeltaText(frame, true, "sensor", 5, shown.timeMillis - 1L))
    }

    @Test fun aDisabledChangedIntervalOrChangedSelectionCannotReuseTheOldFrame() {
        assertEquals("", overlayDeltaText(frame, false, "sensor", 5, now))
        assertEquals("", overlayDeltaText(frame, true, "sensor", 1, now))
        assertEquals("", overlayDeltaText(frame, true, "other", 5, now))
        assertEquals("", overlayDeltaText(frame, true, null, 5, now))
    }
}
