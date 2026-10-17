package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #130: live outputs apply software calibration on a copy at emit time, so
 * file exports have to apply the SAME projection or a CSV can show a hypo that never
 * appeared on screen or in Nightscout.
 *
 * These cover the decisions [ExportCalibration] makes before it touches
 * `CalibrationManager` — which stored lane is the base, that a recorded displayed
 * value always wins, and the unit round-trip. The projection call itself is not
 * covered here (it needs a calibration database, as it did before this file existed);
 * the math behind it is covered by `SeriesCalibratorTests`, and the mode matching by
 * `CalibrationManagerPolicyExtraTests`.
 */
class ExportCalibrationProjectionTests {

    /** viewMode 1 and 3 mean the primary lane is the raw signal, not auto. */
    @Test
    fun baseComesFromTheRawLaneInRawMode() {
        for (viewMode in intArrayOf(1, 3)) {
            assertEquals(
                "raw lane must be the base in viewMode $viewMode",
                11.0f,
                ExportCalibration.laneBaseValue(autoDisplayValue = 6.0f, rawDisplayValue = 11.0f, viewMode = viewMode)!!,
                0.0f
            )
        }
    }

    @Test
    fun baseComesFromTheAutoLaneOtherwise() {
        for (viewMode in intArrayOf(0, 2)) {
            assertEquals(
                "auto lane must be the base in viewMode $viewMode",
                6.0f,
                ExportCalibration.laneBaseValue(autoDisplayValue = 6.0f, rawDisplayValue = 11.0f, viewMode = viewMode)!!,
                0.0f
            )
        }
    }

    /** The other lane being unusable must not matter — only the chosen lane is read. */
    @Test
    fun theOtherLaneIsNeverConsulted() {
        assertEquals(
            6.0f,
            ExportCalibration.laneBaseValue(autoDisplayValue = 6.0f, rawDisplayValue = Float.NaN, viewMode = 0)!!,
            0.0f
        )
    }

    @Test
    fun anUnusableBaseYieldsNoProjection() {
        for (bad in floatArrayOf(Float.NaN, 0f, -3f, 0.05f)) {
            assertNull(
                ExportCalibration.laneBaseValue(autoDisplayValue = bad, rawDisplayValue = 11f, viewMode = 0)
            )
        }
    }

    /**
     * The KDoc's central promise: an export never recomputes a value the user was
     * shown a different number for, so a recorded displayed value wins outright —
     * including when the stored lanes cannot produce one.
     */
    @Test
    fun aRecordedDisplayedValueWinsOutright() {
        assertEquals(
            9.1f,
            ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = 6.0f,
                rawDisplayValue = 11.0f,
                timestamp = 1_700_000_000_000L,
                sensorId = "sn-1",
                viewMode = 0,
                sealedDisplayValue = 9.1f
            )!!,
            0.0f
        )
        // Even with nothing usable stored: the shown value is the only defensible one.
        assertEquals(
            9.1f,
            ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = Float.NaN,
                rawDisplayValue = 0f,
                timestamp = 1_700_000_000_000L,
                sensorId = "sn-1",
                viewMode = 1,
                sealedDisplayValue = 9.1f
            )!!,
            0.0f
        )
    }

    /** Nothing usable and nothing recorded: no column, rather than a fabricated one. */
    @Test
    fun noProjectionWhenNeitherTheBaseNorTheRecordCanBeUsed() {
        assertNull(
            ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = 0f,
                rawDisplayValue = Float.NaN,
                timestamp = 1_700_000_000_000L,
                sensorId = "sn-1",
                viewMode = 0,
                sealedDisplayValue = null
            )
        )
    }

    /**
     * A wrong unit in a JSON export is silent, so the mg/dL round-trip is pinned in
     * both directions rather than against a hard-coded number.
     */
    @Test
    fun theMgDlPathReturnsMgDlInBothUnits() {
        for (isMmol in booleanArrayOf(false, true)) {
            assertEquals(
                100f,
                ExportCalibration.calibratedMgDl(
                    autoMgDl = 0f,
                    rawMgDl = 0f,
                    timestamp = 1_700_000_000_000L,
                    sensorId = "sn-1",
                    viewMode = 0,
                    isMmol = isMmol,
                    sealedMgDl = 100f
                )!!,
                0.01f
            )
        }
    }

    @Test
    fun theMgDlPathIsEmptyWhenNothingApplies() {
        assertNull(
            ExportCalibration.calibratedMgDl(
                autoMgDl = 0f,
                rawMgDl = Float.NaN,
                timestamp = 1_700_000_000_000L,
                sensorId = "sn-1",
                viewMode = 0,
                isMmol = true
            )
        )
    }
}
