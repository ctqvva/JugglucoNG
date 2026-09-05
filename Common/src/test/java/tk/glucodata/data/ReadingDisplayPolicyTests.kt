package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee: a value the user has already read does not move because a
 * setting changed.
 */
class ReadingDisplayPolicyTests {
    private companion object {
        const val MINUTE_MS = 60L * 1000L
        const val NOW = 1_700_000_000_000L
        const val GRACE_MS = ReadingDisplay.DISPLAY_SEAL_GRACE_MS
    }

    private fun record(
        displayMgdl: Float = 120f,
        recordedAt: Long = NOW - GRACE_MS - MINUTE_MS,
        viewMode: Int = 0
    ) = ReadingDisplay(
        sensorSerial = "sensor-a",
        timestamp = recordedAt,
        displayMgdl = displayMgdl,
        viewMode = viewMode,
        calibrationFingerprint = 1L,
        recordedAt = recordedAt
    )

    @Test
    fun aSealedReadingKeepsItsRecordedValueWhenCalibrationChanges() {
        // 120 was shown an hour ago; the calibration now in force would say 140.
        assertEquals(
            120f,
            ReadingDisplayPolicy.displayMgdl(
                recorded = record(displayMgdl = 120f),
                derivedMgdl = 140f,
                nowMs = NOW,
                freezeEnabled = true
            ),
            0.001f
        )
    }

    @Test
    fun aReadingStillInsideTheGraceWindowFollowsTheNewCalibration() {
        // A fingerstick entered ten minutes after the fact should still correct
        // the line around it.
        assertEquals(
            140f,
            ReadingDisplayPolicy.displayMgdl(
                recorded = record(displayMgdl = 120f, recordedAt = NOW - 10L * MINUTE_MS),
                derivedMgdl = 140f,
                nowMs = NOW,
                freezeEnabled = true
            ),
            0.001f
        )
    }

    @Test
    fun withTheSettingOffNothingIsFrozen() {
        assertEquals(
            140f,
            ReadingDisplayPolicy.displayMgdl(
                recorded = record(displayMgdl = 120f),
                derivedMgdl = 140f,
                nowMs = NOW,
                freezeEnabled = false
            ),
            0.001f
        )
    }

    @Test
    fun aReadingWithNoRecordDerivesNormally() {
        assertEquals(
            140f,
            ReadingDisplayPolicy.displayMgdl(
                recorded = null,
                derivedMgdl = 140f,
                nowMs = NOW,
                freezeEnabled = true
            ),
            0.001f
        )
    }

    @Test
    fun anUnusableRecordIsIgnoredRatherThanDrawnAsZero() {
        assertEquals(
            140f,
            ReadingDisplayPolicy.displayMgdl(
                recorded = record(displayMgdl = 0f),
                derivedMgdl = 140f,
                nowMs = NOW,
                freezeEnabled = true
            ),
            0.001f
        )
    }

    @Test
    fun recordingIsRefusedForSealedRowsAndAllowedForTheRest() {
        assertTrue("no record yet", ReadingDisplayPolicy.shouldRecord(null, NOW, freezeEnabled = true))
        assertTrue(
            "still settling",
            ReadingDisplayPolicy.shouldRecord(
                record(recordedAt = NOW - 10L * MINUTE_MS), NOW, freezeEnabled = true
            )
        )
        assertFalse(
            "sealed",
            ReadingDisplayPolicy.shouldRecord(record(), NOW, freezeEnabled = true)
        )
        assertFalse(
            "setting off",
            ReadingDisplayPolicy.shouldRecord(null, NOW, freezeEnabled = false)
        )
    }

    /**
     * The compounding bug, stated as a property.
     *
     * The old rewrite read `value`, calibrated it, and wrote the result back to
     * `value` — so running it twice calibrated an already-calibrated number.
     * Here the derivation always starts from the immutable sensor value, and a
     * sealed record refuses further writes, so repeating a pass converges
     * instead of drifting.
     */
    @Test
    fun replayingTheSamePassDoesNotWalkTheValue() {
        val sensorValueMgdl = 100f
        val calibrate = { v: Float -> v * 1.2f }

        var stored: ReadingDisplay? = null
        var now = NOW
        repeat(5) {
            if (ReadingDisplayPolicy.shouldRecord(stored, now, freezeEnabled = true)) {
                stored = record(
                    displayMgdl = calibrate(sensorValueMgdl),
                    recordedAt = now
                )
            }
            now += 10L * MINUTE_MS
        }

        // Five passes, one derivation: 120, not 100 * 1.2^5 = 248.8.
        assertEquals(120f, stored!!.displayMgdl, 0.001f)
    }
}
