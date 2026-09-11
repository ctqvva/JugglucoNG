package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The guarantee: the number the dashboard showed as the main value is recorded
 * once and cannot afterwards be changed by anything.
 */
class RecordedMainValueTests {

    private companion object {
        const val MINUTE = ReadingDisplay.MINUTE_MS
        const val GRACE = ReadingDisplay.DISPLAY_SEAL_GRACE_MS
        const val NOW = 1_800_000_000_000L
    }

    private fun record(timestamp: Long, serial: String = "sensor-a") = ReadingDisplay(
        timestamp = ReadingDisplay.minuteOf(timestamp),
        sensorSerial = serial,
        displayMgdl = 120f,
        viewMode = 0,
        calibrationFingerprint = 1L,
        recordedAt = NOW,
    )

    @Test
    fun theKeyIsTheMinuteAloneSoOneMinuteHasOneMainValue() {
        // Two sensors reporting in the same minute — 29% of a real two-sensor
        // timeline — must resolve to one record, or the dashboard's main value
        // is not the thing being recorded.
        val a = record(NOW - 5 * MINUTE + 12_345L, serial = "70D07E2552DB")
        val b = record(NOW - 5 * MINUTE + 54_321L, serial = "D76C7BB368A3")
        assertEquals(a.timestamp, b.timestamp)
    }

    @Test
    fun sealingIsMeasuredFromTheReadingNotFromWhenTheRowWasWritten() {
        // Written just now, but describing a minute from yesterday: it is
        // history the moment it is recorded. Measuring from recordedAt made the
        // boundary depend on when a background pass happened to run.
        val old = ReadingDisplay(
            timestamp = ReadingDisplay.minuteOf(NOW - 24L * 60L * MINUTE),
            sensorSerial = "sensor-a",
            displayMgdl = 120f,
            viewMode = 0,
            calibrationFingerprint = 1L,
            recordedAt = NOW,
        )
        assertTrue(old.isSealedAt(NOW))
    }

    @Test
    fun theGraceWindowBoundaryFallsOnTheReadingsOwnAge() {
        assertFalse(record(NOW - GRACE + MINUTE).isSealedAt(NOW))
        assertTrue(record(NOW - GRACE - MINUTE).isSealedAt(NOW))
    }

    @Test
    fun aZeroOrNegativeRecordIsNotUsableSoItCannotBeDrawnAsARealValue() {
        assertFalse(record(NOW - GRACE - MINUTE).copy(displayMgdl = 0f).isUsable)
        assertFalse(record(NOW - GRACE - MINUTE).copy(displayMgdl = Float.NaN).isUsable)
        assertTrue(record(NOW - GRACE - MINUTE).isUsable)
    }

    /**
     * The guarantee is only as good as the writes that exist, so this asserts on
     * the DAO's source: exactly two, and the one that can change a row refuses
     * in SQL to touch a sealed one.
     */
    @Test
    fun noWritePathCanMoveASealedMainValue() {
        val dao = File("src/mobile/java/tk/glucodata/data/ReadingDisplayDao.kt").readText()

        assertTrue(
            "recording a new minute must ignore one that already has a record",
            dao.contains("OnConflictStrategy.IGNORE"),
        )
        assertFalse(
            "a REPLACE write would silently move a recorded value",
            dao.contains("OnConflictStrategy.REPLACE"),
        )
        assertFalse(
            "an @Update would move a sealed row on any caller's say-so",
            dao.contains("@Update"),
        )
        // The revision path exists, but the seal is the database's rule rather
        // than the caller's: a wrong horizon can only fail to revise a live
        // minute, never rewrite a sealed one.
        assertTrue(
            "revision must be bounded in SQL to minutes inside the grace window",
            dao.contains("WHERE timestamp = :timestamp AND timestamp > :sealHorizon"),
        )
        assertEquals(
            "exactly one UPDATE statement against the table",
            1,
            Regex("UPDATE reading_display").findAll(dao).count(),
        )
    }

    /**
     * Presentation is the only writer.
     *
     * A background pass would have to replay HistoryDisplayMerge to decide who
     * owned a past minute, and that decision is not reproducible: the merge ranks
     * sensors by how recently each last read and builds coverage from whatever
     * readings exist at query time, so one later reading changes the answer for
     * last week. Such a row is plausible and unfalsifiable, which is worse than
     * no row at all.
     */
    @Test
    fun nothingButPresentationWritesARecord() {
        val repo = File("src/mobile/java/tk/glucodata/data/HistoryRepository.kt").readText()

        assertTrue(
            "the presentation path is the writer",
            repo.contains("suspend fun recordPresentedMinutes"),
        )
        assertFalse(
            "no background pass may seal minutes from stored readings",
            repo.contains("fun sealDueMainValues"),
        )
    }

    /**
     * Recording must not be gated on a calibration being active, or a store with
     * calibration off records nothing and the setting protects nothing.
     */
    @Test
    fun aMinuteIsRecordedWhetherOrNotACalibrationApplies() {
        val repo = File("src/mobile/java/tk/glucodata/data/HistoryRepository.kt").readText()
        val writer = repo.substringAfter("suspend fun recordPresentedMinutes")
            .substringBefore("\n    /**")

        assertFalse(
            "the writer must not bail out when no calibration is active",
            writer.contains("hasActiveCalibration"),
        )
        assertTrue(
            "it is gated on the user's setting and nothing else",
            writer.contains("shouldFreezeDisplayedValues"),
        )
    }

    /**
     * Only what was on screen may be recorded — the chart holds far more than it
     * draws, and a minute it merely queried was presented to nobody.
     */
    @Test
    fun onlyVisibleMinutesAreOfferedForRecording() {
        val recorder = File("src/mobile/java/tk/glucodata/ui/PresentedMinuteRecorder.kt").readText()
        val chart = File("src/mobile/java/tk/glucodata/ui/DashboardChart.kt").readText()

        assertTrue(
            "a fling past a stretch is not a presentation",
            recorder.contains("SETTLE_MS"),
        )
        assertTrue(
            "the recorder waits for the viewport to settle before writing",
            chart.contains("delay(PresentedMinuteRecorder.SETTLE_MS)"),
        )
        assertTrue(
            "only points inside the drawn viewport are offered",
            chart.contains("if (point.timestamp !in viewportStart..viewportEnd) return@forEachIndexed"),
        )
        assertTrue(
            "a backgrounded chart presents nothing",
            chart.contains("if (!isResumed || renderData.isEmpty()) return@LaunchedEffect"),
        )
    }
}
