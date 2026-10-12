package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.PredictionHistorySource
import tk.glucodata.ui.buildDisplayHistoryForPrediction

/**
 * A recorded main value is a fact about a minute *and a lane*. The chart
 * builder has always resolved the other lane as if nothing was recorded
 * (recordAppliesToLane); every other sealed consumer used to take the record
 * whichever lane it was resolving, so a raw-line record ended up as the
 * "calibrated" auto value in rows, stats and exports — and a view-mode toggle
 * froze the other lane's numbers into the still-unsealed hour.
 */
class SealedLaneGateTests {

    private companion object {
        const val TS = 1_800_000_000_000L
        const val SENSOR = "X-222227KT3T"
        const val AUTO_STOCK = 68f
        const val RAW_STOCK = 31.4f
    }

    private fun point(sealed: Float?, sealedMode: Int?) = GlucosePoint(
        value = AUTO_STOCK,
        time = "",
        timestamp = TS,
        rawValue = RAW_STOCK,
        sensorSerial = SENSOR,
        sealedDisplayValue = sealed,
        sealedDisplayViewMode = sealedMode
    )

    @Test
    fun exportTakesARecordShownOnTheSameLane() {
        assertEquals(
            127f,
            ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = AUTO_STOCK,
                rawDisplayValue = RAW_STOCK,
                timestamp = TS,
                sensorId = SENSOR,
                viewMode = 0,
                sealedDisplayValue = 127f,
                sealedDisplayViewMode = 0
            ) ?: Float.NaN,
            0.001f
        )
        assertEquals(
            33f,
            ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = AUTO_STOCK,
                rawDisplayValue = RAW_STOCK,
                timestamp = TS,
                sensorId = SENSOR,
                viewMode = 1,
                sealedDisplayValue = 33f,
                sealedDisplayViewMode = 3
            ) ?: Float.NaN,
            0.001f
        )
    }

    @Test
    fun exportIgnoresARecordShownOnTheOtherLane() {
        // Raw-line record resolved on the auto lane: nothing calibrated applies
        // on a JVM without a calibration engine, so the answer is null rather
        // than the other lane's number.
        assertNull(
            ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = AUTO_STOCK,
                rawDisplayValue = RAW_STOCK,
                timestamp = TS,
                sensorId = SENSOR,
                viewMode = 0,
                sealedDisplayValue = RAW_STOCK,
                sealedDisplayViewMode = 1
            )
        )
        assertNull(
            ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = AUTO_STOCK,
                rawDisplayValue = RAW_STOCK,
                timestamp = TS,
                sensorId = SENSOR,
                viewMode = 1,
                sealedDisplayValue = 127f,
                sealedDisplayViewMode = 2
            )
        )
    }

    @Test
    fun exportKeepsARecordFromBeforeTheLaneWasKept() {
        assertEquals(
            127f,
            ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = AUTO_STOCK,
                rawDisplayValue = RAW_STOCK,
                timestamp = TS,
                sensorId = SENSOR,
                viewMode = 0,
                sealedDisplayValue = 127f,
                sealedDisplayViewMode = null
            ) ?: Float.NaN,
            0.001f
        )
    }

    @Test
    fun sealedHelperTakesARecordShownOnTheSameLane() {
        assertEquals(
            127f,
            tk.glucodata.ui.SealedGlucoseValue.calibratedFor(
                point(sealed = 127f, sealedMode = 0),
                isRawMode = false,
                sensorId = SENSOR
            ) ?: Float.NaN,
            0.001f
        )
    }

    @Test
    fun sealedHelperIgnoresARecordShownOnTheOtherLane() {
        assertNull(
            tk.glucodata.ui.SealedGlucoseValue.calibratedFor(
                point(sealed = RAW_STOCK, sealedMode = 1),
                isRawMode = false,
                sensorId = SENSOR
            )
        )
    }

    @Test
    fun allViewModesGateExportsRowsAndPredictionsByTheSameLane() {
        for (recordedMode in 0..3) {
            val point = point(sealed = 127f, sealedMode = recordedMode)
            for (requestedMode in 0..3) {
                val raw = requestedMode == 1 || requestedMode == 3
                val sameLane = (recordedMode and 1) == (requestedMode and 1)
                val expected = if (sameLane) 127f else null
                for (isMmol in listOf(false, true)) {
                    val exported = ExportCalibration.calibratedMgDl(
                        autoMgDl = AUTO_STOCK,
                        rawMgDl = RAW_STOCK,
                        timestamp = TS,
                        sensorId = SENSOR,
                        viewMode = requestedMode,
                        isMmol = isMmol,
                        sealedMgDl = 127f,
                        sealedViewMode = recordedMode,
                    )
                    if (expected == null) assertNull(exported)
                    else assertEquals(expected, exported ?: Float.NaN, 0.001f)
                }
                assertEquals(expected, tk.glucodata.ui.SealedGlucoseValue.calibratedFor(point, raw, SENSOR))
                val prediction = buildDisplayHistoryForPrediction(
                    listOf(point),
                    if (raw) PredictionHistorySource.CALIBRATED_RAW else PredictionHistorySource.CALIBRATED_AUTO,
                ).single()
                assertEquals(expected ?: if (raw) RAW_STOCK else AUTO_STOCK, prediction.value, 0.001f)
            }
        }
    }
}
