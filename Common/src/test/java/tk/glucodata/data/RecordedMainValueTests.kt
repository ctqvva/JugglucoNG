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
     * The guarantee is only as good as the absence of a write that could break
     * it, so this asserts on the DAO's source rather than on behaviour: no
     * REPLACE, no @Update, no targeted UPDATE statement against the table.
     */
    @Test
    fun noWritePathCanOverwriteARecordedMainValue() {
        val dao = File("src/mobile/java/tk/glucodata/data/ReadingDisplayDao.kt").readText()

        assertTrue("seal must ignore minutes that already have a record",
            dao.contains("OnConflictStrategy.IGNORE"))
        assertFalse("a REPLACE write would silently move a recorded value",
            dao.contains("OnConflictStrategy.REPLACE"))
        assertFalse("an @Update would silently move a recorded value",
            dao.contains("@Update"))
        assertFalse("an UPDATE statement would silently move a recorded value",
            dao.contains("UPDATE reading_display"))
    }

    /**
     * The write side must not be gated on a calibration being active, or a store
     * with calibration off records nothing and the setting protects nothing.
     */
    @Test
    fun theSealPassRecordsEvenWhenNoCalibrationApplies() {
        val repo = File("src/mobile/java/tk/glucodata/data/HistoryRepository.kt").readText()
        val pass = repo.substringAfter("suspend fun sealDueMainValues")
            .substringBefore("\n    private fun displayKey")

        assertTrue("the pass must fall back to the sensor's own value",
            pass.contains("} else {\n                        base\n                    }"))
        assertFalse("the pass must not bail out when no calibration is active",
            pass.contains("if (!CalibrationManager.hasActiveCalibration"))
    }
}
