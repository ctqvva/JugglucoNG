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
        // From zero, not the earliest history: older NFC trend rows can predate it.
        assertEquals(0L, LibreHistorySyncWindow.startSec(LIVE_START, history, known = null, backfilled = false))
    }

    @Test
    fun aNewProcessCatchesUpOnHistoryTheLastOneStoredButNeverSynced() {
        // The last process stored a scan's 8 hours of history and died before syncing.
        val newest = NOW_SEC - 3 * 86_400 // and the app then stayed closed for days
        val history = setOf(ms(newest - 7 * 3600), ms(newest))
        assertEquals(
            newest - LibreHistorySyncWindow.SENSOR_HISTORY_SPAN_SEC - LibreHistorySyncWindow.PAIRING_SLACK_SEC,
            LibreHistorySyncWindow.startSec(LIVE_START, history, known = null, backfilled = true),
        )
    }

    private fun row(timestampMs: Long, value: Float = 120f, raw: Float = 0f) =
        HistoryReading(timestamp = timestampMs, sensorSerial = "3MH0000ABC", value = value, rawValue = raw, rate = null)

    @Test
    fun anOldHistoryRowWhoseValueMovedToTheNextMinutesStreamReadingIsLeftBehind() {
        // History for minute id 41 at 12:15:55; its stream reading came at
        // 12:16:02, and id 40's (12:15:02) was missed, so 12:15 gets no row.
        val minute = ms(NOW_SEC - NOW_SEC % 60 - 30 * 60)
        val history = minute + 55_000
        val stream = minute + 62_000
        val stored = listOf(row(history, value = 100f), row(stream))
        val exported = setOf(minute + 60_000) // 12:16:02 {stream, history}
        assertEquals(
            listOf(history),
            LibreHistorySyncWindow.rowsLeftBehind(stored, exported, setOf(history), exportStartMs = 0L).map { it.timestamp },
        )
    }

    @Test
    fun aHistoryRowTheExportStillWritesIsKept() {
        val history = ms(NOW_SEC - 30 * 60)
        val stored = listOf(row(history, value = 0f, raw = 100f))
        val exported = setOf(ReadingDisplay.minuteOf(history))
        assertEquals(emptyList<HistoryReading>(), LibreHistorySyncWindow.rowsLeftBehind(stored, exported, setOf(history), 0L))
    }

    @Test
    fun onlyRowsInsideTheExportWindowAndAtHistoryTimesAreLeftBehind() {
        val history = ms(NOW_SEC - 30 * 60)
        val streamOnly = history + 120_000
        val stored = listOf(row(history), row(streamOnly))
        // Before the window: this export says nothing about it.
        assertEquals(emptyList<HistoryReading>(), LibreHistorySyncWindow.rowsLeftBehind(stored, emptySet(), setOf(history), history))
        // A stream row is never a history row, exported or not.
        assertEquals(listOf(history), LibreHistorySyncWindow.rowsLeftBehind(stored, emptySet(), setOf(history), 0L).map { it.timestamp })
    }

    @Test
    fun anOldNfcTrendRowTheExportNoLongerWritesIsLeftBehind() {
        val trend = ms(NOW_SEC - 40 * 60) + 7_000 // Raw-only: uncompensated trend minute
        val history = ms(NOW_SEC - 30 * 60)
        val stored = listOf(row(trend, value = 0f, raw = 95f), row(history, value = 0f, raw = 100f))
        val exported = setOf(ReadingDisplay.minuteOf(history)) // covered history: {0, history}
        assertEquals(
            listOf(trend),
            LibreHistorySyncWindow.rowsLeftBehind(stored, exported, setOf(history), 0L).map { it.timestamp },
        )
    }

    @Test
    fun anEarlierRequestIsNeverNarrowed() {
        val history = setOf(ms(NOW_SEC - 16 * 60))
        assertEquals(0L, LibreHistorySyncWindow.startSec(0L, history, known = emptySet(), backfilled = true))
    }
}
