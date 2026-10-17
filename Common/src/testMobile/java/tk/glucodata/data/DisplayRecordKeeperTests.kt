package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ui.GlucosePoint

/**
 * "Keep displayed values" records every minute as it arrives, not only the
 * minutes that were on screen. The 2026-10-18 AiDEX report: a raw calibration
 * moved every minute nobody had looked at, and scrolling over them froze a
 * calibrated stretch (10:39–12:10) between uncalibrated ones.
 */
class DisplayRecordKeeperTests {

    private companion object {
        const val NOW = 1_792_000_020_000L // on a minute
        const val MINUTE = ReadingDisplay.MINUTE_MS
        const val GRACE = ReadingDisplay.DISPLAY_SEAL_GRACE_MS
        const val SENSOR = "X-222227KUFY"
    }

    @Test
    fun withoutAWatermarkTheWholeStoreIsRecorded() {
        assertEquals(0L, DisplayRecordKeeper.startTimeFor(0L, null, NOW))
    }

    @Test
    fun aPassCoversTheGraceWindowEvenWithNothingNew() {
        assertEquals(NOW - GRACE - MINUTE, DisplayRecordKeeper.startTimeFor(NOW - 5_000L, null, NOW))
        assertEquals(
            NOW - GRACE - MINUTE,
            DisplayRecordKeeper.startTimeFor(NOW - 5_000L, NOW - 10 * MINUTE, NOW),
        )
    }

    @Test
    fun aLateBackfillIsRecordedFromItsOldestReading() {
        val backfilled = NOW - 6 * GRACE
        assertEquals(backfilled, DisplayRecordKeeper.startTimeFor(NOW - 5_000L, backfilled, NOW))
    }

    private fun point(minute: Int, sealed: Float? = null) = GlucosePoint(
        value = 100f + minute,
        time = "",
        timestamp = NOW + minute * MINUTE + 8_000L,
        rawValue = 80f + minute,
        sensorSerial = SENSOR,
        sealedDisplayValue = sealed,
    )

    @Test
    fun everyUnsealedMinuteIsRecordedInItsSensorsLane() {
        val points = listOf(point(0), point(0).copy(timestamp = NOW + 30_000L), point(1, sealed = 150f), point(2))
        val raw = HistoryRepository.minutesToRecord(
            points,
            viewModeOf = { 1 },
            valueOf = { p, mode -> if (mode == 1) p.rawValue + 30f else p.value },
        )
        assertEquals(listOf(NOW, NOW + 2 * MINUTE), raw.map { it.minuteMs })
        assertEquals(listOf(110f, 112f), raw.map { it.displayMgdl })
        assertEquals(setOf(1), raw.map { it.viewMode }.toSet())
    }

    @Test
    fun aMinuteWithNothingToDrawIsLeftAlone() {
        val none = HistoryRepository.minutesToRecord(listOf(point(0)), viewModeOf = { 1 }, valueOf = { _, _ -> null })
        assertEquals(emptyList<HistoryRepository.PresentedMinute>(), none)
    }
}
