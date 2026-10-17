package tk.glucodata.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucosePoint

/**
 * The scrub cursor marks the lines that were drawn. These pin that its dots
 * and tooltip values come from the resolved model — calibration, record and
 * lane visibility included — and not from the sensor's own point.
 */
class ChartCursorTests {

    private companion object {
        const val MINUTE = MainSensorOwnership.MINUTE_MS
        const val NOW = 1_800_000_000_000L // minute-aligned
        const val RAW_MODE = 1
    }

    private fun point(ts: Long, auto: Float, raw: Float, serial: String, sealed: Float = Float.NaN) =
        GlucosePoint(ts, auto, raw).also { it.sensorSerial = serial; it.sealedDisplayValue = sealed }

    private fun series(id: String, primary: Boolean, viewMode: Int, hasCalibration: Boolean, vararg points: GlucosePoint) =
        HistoryChartModelBuilder.SeriesInput(
            id, primary, viewMode, colorArgb = 0, points = points.toList(), hasCalibration = hasCalibration,
        )

    /** Calibrates only sensor B's raw lane: 2.0 raw reads as 4.6. */
    private val calibratesPeerRaw = HistoryChartModelBuilder.Calibration { v, _, isRaw, sensorId ->
        if (isRaw && sensorId == "B") v + 2.6f else null
    }

    private fun primaryA() = series(
        "A", primary = true, viewMode = RAW_MODE, hasCalibration = false,
        point(NOW, 3.6f, 3.8f, "A"), point(NOW + MINUTE, 3.6f, 3.8f, "A"),
    )

    private fun calibratedRawPeerB() = series(
        "B", primary = false, viewMode = RAW_MODE, hasCalibration = true,
        point(NOW, 4.0f, 2.0f, "B"), point(NOW + MINUTE, 4.0f, 2.0f, "B"),
    )

    @Test
    fun aCalibratedRawPeerIsMarkedOnItsCalibratedLineNotItsHiddenSource() {
        // The reported case: an AiDEX raw peer with a calibration, source values
        // hidden. The line draws 4.6; the old dot and tooltip read raw 2.0.
        val model = HistoryChartModelBuilder.build(
            listOf(primaryA(), calibratedRawPeerB()), MainSensorOwnership.NONE, calibratesPeerRaw,
            hideInitialWhenCalibrated = true,
        )
        val cursor = model.peers.single().cursorAt(NOW)!!
        assertEquals(4.6f, cursor.line!!.value, 0.001f)
        assertTrue("hidden source must not be marked", cursor.lanes.isEmpty())
    }

    @Test
    fun aShownSourceLaneIsMarkedWithItsOwnValueBesideTheCalibratedLine() {
        val model = HistoryChartModelBuilder.build(
            listOf(primaryA(), calibratedRawPeerB()), MainSensorOwnership.NONE, calibratesPeerRaw,
            hideInitialWhenCalibrated = false,
        )
        val cursor = model.peers.single().cursorAt(NOW)!!
        assertEquals(4.6f, cursor.line!!.value, 0.001f)
        assertEquals(listOf(ChartLaneValue(ChartLaneKind.RAW, 2.0f)), cursor.lanes)
    }

    @Test
    fun aRecordedPrimaryMinuteMarksTheRecordAndNoUndrawnSourceLane() {
        // A record differing from the raw value with no calibration used to
        // draw a second dot on a raw line that was never stroked.
        val a = series(
            "A", primary = true, viewMode = RAW_MODE, hasCalibration = false,
            point(NOW, 5f, 5f, "A", sealed = 6f), point(NOW + MINUTE, 5f, 5f, "A"),
        )
        val model = HistoryChartModelBuilder.build(
            listOf(a), MainSensorOwnership.NONE, HistoryChartModelBuilder.Calibration { _, _, _, _ -> null },
        )
        val cursor = model.primary!!.cursorAt(NOW)!!
        assertEquals(6f, cursor.line!!.value, 0.001f)
        assertEquals(ChartLook.MAIN, cursor.line!!.look)
        assertTrue(cursor.lanes.isEmpty())
    }

    @Test
    fun theCalibrationPreviewIsNotMarked() {
        val a = series(
            "A", primary = true, viewMode = 0, hasCalibration = true,
            point(NOW, 5f, 5f, "A", sealed = 5f), point(NOW + MINUTE, 5f, 5f, "A", sealed = 5f),
        )
        val model = HistoryChartModelBuilder.build(
            listOf(a), MainSensorOwnership.NONE, HistoryChartModelBuilder.Calibration { v, _, _, _ -> v + 1f },
            hasCalibration = true, hideInitialWhenCalibrated = true,
        )
        assertTrue(model.primary!!.secondaryLanes.any { it.kind == ChartLaneKind.CALIBRATION_PREVIEW })
        val cursor = model.primary!!.cursorAt(NOW)!!
        assertEquals(5f, cursor.line!!.value, 0.001f)
        assertTrue(cursor.lanes.none { it.kind == ChartLaneKind.CALIBRATION_PREVIEW })
    }

    @Test
    fun aPeerThatOwnsTheMinuteIsMarkedWithTheMainLook() {
        val ownership = MainSensorOwnership(mapOf(MainSensorOwnership.minuteOf(NOW) to "B"), NOW + 60 * MINUTE)
        val model = HistoryChartModelBuilder.build(
            listOf(primaryA(), calibratedRawPeerB()), ownership, calibratesPeerRaw,
        )
        assertEquals(ChartLook.MAIN, model.peers.single().cursorAt(NOW)!!.line!!.look)
        assertEquals(ChartLook.SECONDARY, model.primary!!.cursorAt(NOW)!!.line!!.look)
    }

    @Test
    fun aSeriesWithNothingInTheCursorsMinuteIsNotMarked() {
        val model = HistoryChartModelBuilder.build(
            listOf(primaryA(), calibratedRawPeerB()), MainSensorOwnership.NONE, calibratesPeerRaw,
        )
        assertNull(model.peers.single().cursorAt(NOW + 5 * MINUTE))
    }

    @Test
    fun theNearestReadingInsideTheMinuteIsTakenAndNeverOneFromTheNeighbouringMinute() {
        val ts = listOf(NOW - 1_000L, NOW + 10_000L, NOW + 50_000L, NOW + MINUTE + 1_000L)
        assertEquals(NOW + 50_000L, nearestInMinuteOf(ts, NOW + 40_000L) { it })
        assertEquals(NOW + 10_000L, nearestInMinuteOf(ts, NOW + 2_000L) { it })
        assertEquals(NOW + 50_000L, nearestInMinuteOf(ts, NOW + 59_000L) { it })
        assertEquals(NOW + 10_000L, nearestInMinuteOf(ts, NOW + 10_000L) { it })
        // Equidistant: the later reading, as the minute buckets always preferred.
        assertEquals(NOW + 50_000L, nearestInMinuteOf(ts, NOW + 30_000L) { it })
        assertNull(nearestInMinuteOf(listOf(NOW - 1_000L, NOW + MINUTE), NOW + 30_000L) { it })
    }
}
