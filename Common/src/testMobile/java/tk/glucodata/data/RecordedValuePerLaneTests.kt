package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.PredictionHistorySource
import tk.glucodata.ui.buildDisplayHistoryForPrediction

/**
 * Each main line keeps its own record of a minute: rows, exports and
 * predictions take the record of the lane they show, as the chart's main line
 * and the statistics do, and never the other line's.
 *
 * With one record per minute, #499 gated it by the lane selected now (a
 * raw/auto switch re-derived every minute first shown on the other line, and a
 * calibration moved exactly those), and #652 took it in either lane (the two
 * lines were stitched together wherever the user had looked in each). Both
 * were on a 2026-10-17 AiDEX store, where raw and auto sit ~1.2 mmol/L apart.
 */
class RecordedValuePerLaneTests {

    private companion object {
        const val TS = 1_800_000_000_000L
        const val SENSOR = "X-222227KUFY"
        const val AUTO_STOCK = 68f
        const val RAW_STOCK = 31.4f
        const val RECORDED_AUTO = 127f
        const val RECORDED_RAW = 105f
    }

    private fun point(auto: Float? = RECORDED_AUTO, raw: Float? = RECORDED_RAW) = GlucosePoint(
        value = AUTO_STOCK,
        time = "",
        timestamp = TS,
        rawValue = RAW_STOCK,
        sensorSerial = SENSOR,
        sealedAutoLaneValue = auto,
        sealedRawLaneValue = raw,
    )

    @Test
    fun everyViewModeTakesItsOwnLinesRecordInExportsRowsAndPredictions() {
        val point = point()
        for (requestedMode in 0..3) {
            val raw = requestedMode == 1 || requestedMode == 3
            val expected = if (raw) RECORDED_RAW else RECORDED_AUTO
            for (isMmol in listOf(false, true)) {
                val exported = ExportCalibration.calibratedMgDl(
                    autoMgDl = AUTO_STOCK,
                    rawMgDl = RAW_STOCK,
                    timestamp = TS,
                    sensorId = SENSOR,
                    viewMode = requestedMode,
                    isMmol = isMmol,
                    sealedAutoMgDl = RECORDED_AUTO,
                    sealedRawMgDl = RECORDED_RAW,
                )
                assertEquals("mode=$requestedMode mmol=$isMmol", expected, exported ?: Float.NaN, 0.01f)
            }
            assertEquals(expected, tk.glucodata.ui.SealedGlucoseValue.calibratedFor(point, raw, SENSOR))
            val prediction = buildDisplayHistoryForPrediction(
                listOf(point),
                if (raw) PredictionHistorySource.CALIBRATED_RAW else PredictionHistorySource.CALIBRATED_AUTO,
            ).single()
            assertEquals(expected, prediction.value, 0.001f)
        }
    }

    @Test
    fun theOtherLinesRecordIsNotTaken() {
        // Shown on the auto line only. The raw line was never shown for this
        // minute, so a raw-mode export must not hand out the auto line's number.
        val autoOnly = point(raw = null)
        for (rawMode in listOf(1, 3)) {
            val exported = ExportCalibration.calibratedDisplayValue(
                autoDisplayValue = AUTO_STOCK,
                rawDisplayValue = Float.NaN,
                timestamp = TS,
                sensorId = SENSOR,
                viewMode = rawMode,
                sealedAutoLaneValue = autoOnly.sealedAutoLaneValue,
                sealedRawLaneValue = autoOnly.sealedRawLaneValue,
            )
            assertEquals(null, exported)
        }
        val prediction = buildDisplayHistoryForPrediction(listOf(autoOnly), PredictionHistorySource.RAW).single()
        assertEquals(RAW_STOCK, prediction.value, 0.001f)
    }
}
