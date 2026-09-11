package tk.glucodata.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucosePoint

/**
 * The resolved chart is decided once. These pin the three decisions: the
 * value, the look, and the runs.
 */
class HistoryChartModelBuilderTests {

    private companion object {
        const val MINUTE = MainSensorOwnership.MINUTE_MS
        const val NOW = 1_800_000_000_000L
        val SEALED_START = NOW - 3L * 60L * MINUTE
    }

    private fun point(ts: Long, value: Float, serial: String, sealed: Float = Float.NaN) =
        GlucosePoint(ts, value, value).also { it.sensorSerial = serial; it.sealedDisplayValue = sealed }

    private fun series(id: String, primary: Boolean, vararg points: GlucosePoint) =
        HistoryChartModelBuilder.SeriesInput(id, primary, viewMode = 0, colorArgb = 0, points = points.toList())

    private val noCalibration = HistoryChartModelBuilder.Calibration { _, _, _, _ -> null }
    private val plusOne = HistoryChartModelBuilder.Calibration { v, _, _, _ -> v + 1f }

    @Test
    fun aRecordedValueWinsOverTheCalibration() {
        val p = point(NOW, 5f, "A", sealed = 7f)
        assertEquals(7f, HistoryChartModelBuilder.resolveValue(p, false, "A", plusOne)!!, 0.001f)
    }

    @Test
    fun withoutARecordTheCalibrationAppliesAndWithoutThatTheOwnValueStands() {
        val p = point(NOW, 5f, "A")
        assertEquals(6f, HistoryChartModelBuilder.resolveValue(p, false, "A", plusOne)!!, 0.001f)
        assertEquals(5f, HistoryChartModelBuilder.resolveValue(p, false, "A", noCalibration)!!, 0.001f)
    }

    @Test
    fun aPointWithNothingInTheLaneIsSkippedNotDrawnAsZero() {
        val p = GlucosePoint(NOW, 0f, 0f)
        assertNull(HistoryChartModelBuilder.resolveValue(p, false, "A", noCalibration))
    }

    @Test
    fun whereTheRecordIsSilentThePrimaryIsMainAndPeersAreSecondary() {
        val model = HistoryChartModelBuilder.build(
            listOf(
                series("A", primary = true, point(NOW, 5f, "A"), point(NOW + MINUTE, 5f, "A")),
                series("B", primary = false, point(NOW, 6f, "B"), point(NOW + MINUTE, 6f, "B")),
            ),
            MainSensorOwnership.NONE,
            noCalibration,
        )
        assertEquals(listOf(ChartLook.MAIN), model.primary!!.runs.map { it.look })
        assertEquals(listOf(ChartLook.SECONDARY), model.peers.single().runs.map { it.look })
    }

    @Test
    fun aSealedMinuteOwnedByThePeerSwapsTheLooksThere() {
        // The user swapped to A; B was main when the first two minutes were shown.
        val owned = mapOf(
            MainSensorOwnership.minuteOf(SEALED_START) to "B",
            MainSensorOwnership.minuteOf(SEALED_START + MINUTE) to "B",
        )
        val ownership = MainSensorOwnership(owned, NOW)
        val a = series(
            "A", primary = true,
            point(SEALED_START, 5f, "A"),
            point(SEALED_START + MINUTE, 5f, "A"),
            point(SEALED_START + 2 * MINUTE, 5f, "A"),
        )
        val b = series(
            "B", primary = false,
            point(SEALED_START, 6f, "B"),
            point(SEALED_START + MINUTE, 6f, "B"),
            point(SEALED_START + 2 * MINUTE, 6f, "B"),
        )
        val model = HistoryChartModelBuilder.build(listOf(a, b), ownership, noCalibration)

        assertEquals(listOf(ChartLook.SECONDARY, ChartLook.MAIN), model.primary!!.runs.map { it.look })
        assertEquals(listOf(ChartLook.MAIN, ChartLook.SECONDARY), model.peers.single().runs.map { it.look })
    }

    @Test
    fun aChangeOfLookSharesItsBoundaryPointSoTheLineStaysContinuous() {
        val owned = mapOf(MainSensorOwnership.minuteOf(SEALED_START) to "B")
        val a = series(
            "A", primary = true,
            point(SEALED_START, 5f, "A"),
            point(SEALED_START + MINUTE, 5f, "A"),
        )
        val model = HistoryChartModelBuilder.build(listOf(a), MainSensorOwnership(owned, NOW), noCalibration)
        val runs = model.primary!!.runs
        assertEquals(2, runs.size)
        assertEquals(runs[0].points.last(), runs[1].points.first())
    }

    @Test
    fun theCalibrationPreviewExistsOnlyWhereTheRecordDisagreesWithTheLiveCalibration() {
        // Minutes 0 and 1 are recorded at 5 while the calibration says 6; minute
        // 2 has no record and simply draws the calibration. Only the first two
        // have anything to preview.
        val a = series(
            "A", primary = true,
            point(NOW, 5f, "A", sealed = 5f),
            point(NOW + MINUTE, 5f, "A", sealed = 5f),
            point(NOW + 2 * MINUTE, 5f, "A"),
        )
        val model = HistoryChartModelBuilder.build(listOf(a), MainSensorOwnership.NONE, plusOne, hasCalibration = true)
        val preview = model.primary!!.secondaryLanes.single { it.kind == ChartLaneKind.CALIBRATION_PREVIEW }
        val previewed = preview.runs.flatMap { it.points }
        assertEquals(listOf(NOW, NOW + MINUTE), previewed.map { it.timestamp })
        assertEquals(6f, previewed[0].value, 0.001f)
    }

    @Test
    fun noPreviewLaneWhenTheRecordAndTheCalibrationAgree() {
        val a = series("A", primary = true, point(NOW, 5f, "A", sealed = 6f), point(NOW + MINUTE, 5f, "A", sealed = 6f))
        val model = HistoryChartModelBuilder.build(listOf(a), MainSensorOwnership.NONE, plusOne, hasCalibration = true)
        assertTrue(model.primary!!.secondaryLanes.none { it.kind == ChartLaneKind.CALIBRATION_PREVIEW })
    }

    @Test
    fun aPeerInADualViewModeKeepsItsOtherLane() {
        // Sibionics as a peer in auto+raw: its main run is the auto lane, and
        // its raw signal is a lane beside it — it must not vanish because it is
        // not the primary.
        val b = HistoryChartModelBuilder.SeriesInput(
            sensorId = "B", isPrimary = false, viewMode = 2, colorArgb = 0,
            points = listOf(
                GlucosePoint(NOW, 5f, 2.8f).also { it.sensorSerial = "B" },
                GlucosePoint(NOW + MINUTE, 5.1f, 2.9f).also { it.sensorSerial = "B" },
            ),
        )
        val model = HistoryChartModelBuilder.build(listOf(b), MainSensorOwnership.NONE, noCalibration)
        val peer = model.peers.single()
        assertEquals(listOf(5f, 5.1f), peer.runs.flatMap { it.points }.map { it.value })
        val raw = peer.secondaryLanes.single { it.kind == ChartLaneKind.RAW }
        assertEquals(listOf(2.8f, 2.9f), raw.runs.flatMap { it.points }.map { it.value })
    }

    @Test
    fun aGapBreaksTheRunWithoutSharingAPoint() {
        val a = series(
            "A", primary = true,
            point(NOW, 5f, "A"),
            point(NOW + 2 * MINUTE, 5f, "A"),
        )
        val model = HistoryChartModelBuilder.build(listOf(a), MainSensorOwnership.NONE, noCalibration, gapThresholdMs = MINUTE)
        val runs = model.primary!!.runs
        assertEquals(2, runs.size)
        assertTrue(runs[0].points.last() != runs[1].points.first())
    }
}
