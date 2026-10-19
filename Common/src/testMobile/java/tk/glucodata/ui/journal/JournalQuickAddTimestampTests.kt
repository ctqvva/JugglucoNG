package tk.glucodata.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test

class JournalQuickAddTimestampTests {

    private val now = 1_700_000_000_000L

    @Test
    fun quickAddWithoutSelectionSeedsWallClockNow() {
        // Regression: a stale history tail (e.g. T-6 min after resume, before the
        // data flows re-emit) must never seed the entry; only "now" may.
        assertEquals(now, journalQuickAddTimestamp(selectedPointTimestamp = null, nowMillis = now))
    }

    @Test
    fun quickAddWithSelectionSeedsSelectedPoint() {
        val selected = now - 45L * 60L * 1000L
        assertEquals(
            selected,
            journalQuickAddTimestamp(selectedPointTimestamp = selected, nowMillis = now)
        )
    }

    @Test
    fun quickAddAlwaysNowIgnoresSelection() {
        val selected = now - 45L * 60L * 1000L
        assertEquals(
            now,
            journalQuickAddTimestamp(selectedPointTimestamp = selected, nowMillis = now, alwaysNow = true)
        )
    }

    @Test
    fun quickAddAlwaysNowWithoutSelectionSeedsWallClockNow() {
        assertEquals(
            now,
            journalQuickAddTimestamp(selectedPointTimestamp = null, nowMillis = now, alwaysNow = true)
        )
    }
}
