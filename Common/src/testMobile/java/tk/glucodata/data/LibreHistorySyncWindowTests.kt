package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibreHistorySyncWindowTests {

    private companion object {
        const val NOW_SEC = 1_800_000_000L
        const val LIVE_START = NOW_SEC - 5 * 60 // the live sync's few minutes
    }

    private fun ms(sec: Long) = sec * 1000L

    @Test
    fun aSensorWithoutHistoryKeepsTheRequestedWindow() {
        assertEquals(LIVE_START, LibreHistorySyncWindow.startSec(LIVE_START, emptySet(), null, false))
    }

    @Test
    fun newlyArrivedHistoryPullsTheLiveSyncBackToItsStreamReading() {
        val old = ms(NOW_SEC - 31 * 60)
        val arrived = ms(NOW_SEC - 16 * 60)
        val start = LibreHistorySyncWindow.startSec(LIVE_START, setOf(old, arrived), known = setOf(old), backfilled = true)
        assertEquals(NOW_SEC - 16 * 60 - LibreHistorySyncWindow.PAIRING_SLACK_SEC, start)
    }

    @Test
    fun anNfcScanReachesBackToItsEarliestNewValue() {
        val known = setOf(ms(NOW_SEC - 16 * 60))
        val scanned = (1..32).map { ms(NOW_SEC - it * 15 * 60) }.toSet() + known
        val start = LibreHistorySyncWindow.startSec(LIVE_START, scanned, known, backfilled = true)
        assertEquals(NOW_SEC - 32 * 15 * 60 - LibreHistorySyncWindow.PAIRING_SLACK_SEC, start)
    }

    @Test
    fun nothingNewKeepsTheRequestedWindow() {
        val history = setOf(ms(NOW_SEC - 16 * 60))
        assertEquals(LIVE_START, LibreHistorySyncWindow.startSec(LIVE_START, history, history, backfilled = true))
    }

    @Test
    fun theFirstSyncAfterTheUpgradeBackfillsTheWholeSessionOnce() {
        val history = setOf(ms(NOW_SEC - 10 * 86_400), ms(NOW_SEC - 16 * 60))
        assertEquals(
            NOW_SEC - 10 * 86_400 - LibreHistorySyncWindow.PAIRING_SLACK_SEC,
            LibreHistorySyncWindow.startSec(LIVE_START, history, known = null, backfilled = false),
        )
        assertEquals(LIVE_START, LibreHistorySyncWindow.startSec(LIVE_START, history, known = null, backfilled = true))
    }

    @Test
    fun anEarlierRequestIsNeverNarrowed() {
        val history = setOf(ms(NOW_SEC - 16 * 60))
        assertEquals(0L, LibreHistorySyncWindow.startSec(0L, history, known = emptySet(), backfilled = true))
    }
}
