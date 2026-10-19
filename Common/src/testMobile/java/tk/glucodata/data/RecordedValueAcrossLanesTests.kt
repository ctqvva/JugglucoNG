package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.PredictionHistorySource
import tk.glucodata.ui.buildDisplayHistoryForPrediction

/**
 * A recorded main value is the number the user was shown for a minute,
 * whichever lane the main line was drawing then. Rows, exports and predictions
 * take it in every lane, as the chart's main line and the statistics do.
 *
 * #499 gated it by the lane selected now. On a 2026-10-17 AiDEX store in raw
 * mode that re-derived every minute recorded on the auto line, so a raw/auto
 * switch rewrote the statistics, and a raw calibration added afterwards moved
 * exactly those minutes (TIR 44% → 52%) while the raw-recorded ones held.
 */
class RecordedValueAcrossLanesTests {

    private companion object {
        const val TS = 1_800_000_000_000L
        const val SENSOR = "X-222227KUFY"
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
    fun exportTakesTheRecordInEitherLane() {
        for ((viewMode, recordedMode) in listOf(0 to 0, 1 to 3, 0 to 1, 1 to 2)) {
            assertEquals(
                "viewMode=$viewMode recordedMode=$recordedMode",
                127f,
                ExportCalibration.calibratedDisplayValue(
                    autoDisplayValue = AUTO_STOCK,
                    rawDisplayValue = RAW_STOCK,
                    timestamp = TS,
                    sensorId = SENSOR,
                    viewMode = viewMode,
                    sealedDisplayValue = 127f,
                    sealedDisplayViewMode = recordedMode
                ) ?: Float.NaN,
                0.001f
            )
        }
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
    fun rowsTakeARecordShownOnTheOtherLane() {
        assertEquals(
            66f,
            tk.glucodata.ui.SealedGlucoseValue.calibratedFor(
                point(sealed = 66f, sealedMode = 0),
                isRawMode = true,
                sensorId = SENSOR
            ) ?: Float.NaN,
            0.001f
        )
    }

    @Test
    fun everyViewModeTakesTheRecordInExportsRowsAndPredictions() {
        for (recordedMode in 0..3) {
            val point = point(sealed = 127f, sealedMode = recordedMode)
            for (requestedMode in 0..3) {
                val raw = requestedMode == 1 || requestedMode == 3
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
                    assertEquals(127f, exported ?: Float.NaN, 0.01f)
                }
                assertEquals(127f, tk.glucodata.ui.SealedGlucoseValue.calibratedFor(point, raw, SENSOR))
                val prediction = buildDisplayHistoryForPrediction(
                    listOf(point),
                    if (raw) PredictionHistorySource.CALIBRATED_RAW else PredictionHistorySource.CALIBRATED_AUTO,
                ).single()
                assertEquals(127f, prediction.value, 0.001f)
            }
        }
    }
}
