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
        GlucosePoint(ts, value, value).also { it.sensorSerial = serial; it.sealedAutoLaneValue = sealed }

    private fun series(id: String, primary: Boolean, vararg points: GlucosePoint) =
        HistoryChartModelBuilder.SeriesInput(id, primary, viewMode = 0, colorArgb = 0, points = points.toList())

    private val noCalibration = HistoryChartModelBuilder.Calibration { _, _, _, _ -> null }
    private val plusOne = HistoryChartModelBuilder.Calibration { v, _, _, _ -> v + 1f }

    @Test
    fun aRecordHoldsOnlyTheLineItWasShownOn() {
        // Shown on the raw line (9.5). The auto line was never shown for this
        // minute, so it draws live — it must not borrow the raw line's number.
        val p = GlucosePoint(NOW, 5f, 9f).also { it.sensorSerial = "A"; it.sealedRawLaneValue = 9.5f }
        assertEquals(9.5f, HistoryChartModelBuilder.resolveValue(p, isRawMode = true, "A", plusOne)!!, 0.001f)
        assertEquals(6f, HistoryChartModelBuilder.resolveValue(p, isRawMode = false, "A", plusOne)!!, 0.001f)
        val q = GlucosePoint(NOW, 5f, 9f).also { it.sensorSerial = "A"; it.sealedAutoLaneValue = 5.5f }
        assertEquals(5.5f, HistoryChartModelBuilder.resolveValue(q, isRawMode = false, "A", plusOne)!!, 0.001f)
        assertEquals(10f, HistoryChartModelBuilder.resolveValue(q, isRawMode = true, "A", plusOne)!!, 0.001f)
    }

    @Test
    fun aLaneSwitchShowsEachLinesOwnRecordAndSwitchingBackRestoresIt() {
        // Both lines recorded for the same minutes; a raw calibration added since.
        fun recorded(ts: Long) = GlucosePoint(ts, 66f, 72f).also {
            it.sensorSerial = "A"; it.sealedAutoLaneValue = 66f; it.sealedRawLaneValue = 70f
        }
        fun build(viewMode: Int) = HistoryChartModelBuilder.build(
            listOf(
                HistoryChartModelBuilder.SeriesInput(
                    "A", isPrimary = true, viewMode = viewMode, colorArgb = 0,
                    points = listOf(recorded(NOW), recorded(NOW + MINUTE)),
                )
            ),
            MainSensorOwnership.NONE, plusOne, hasCalibration = true,
        )
        fun mainValues(model: HistoryChartModel) = model.primary!!.runs.flatMap { it.points }.map { it.value }
        fun previewValues(model: HistoryChartModel) = model.primary!!.secondaryLanes
            .single { it.kind == ChartLaneKind.CALIBRATION_PREVIEW }.runs.flatMap { it.points }.map { it.value }

        val raw = build(viewMode = 1)
        assertEquals(listOf(70f, 70f), mainValues(raw))
        assertEquals("today's raw calibration, beside the record", listOf(73f, 73f), previewValues(raw))
        val auto = build(viewMode = 0)
        assertEquals(listOf(66f, 66f), mainValues(auto))
        assertEquals(listOf(67f, 67f), previewValues(auto))
        assertEquals(mainValues(raw), mainValues(build(viewMode = 3)))
        assertEquals(mainValues(auto), mainValues(build(viewMode = 2)))
    }

    @Test
    fun theOtherLinesRecordsNeverStitchIntoThisOne() {
        // The 2026-10-17 store after a fresh start: the first stretch was first
        // shown on auto, the second on raw. With one record per minute both
        // lanes drew auto then raw — a step at the boundary in either lane.
        val points = (0 until 6).map { i ->
            GlucosePoint(NOW + i * MINUTE, 120f, 100f).also {
                it.sensorSerial = "A"
                if (i < 3) it.sealedAutoLaneValue = 120f else it.sealedRawLaneValue = 100f
            }
        }
        fun mainValues(viewMode: Int) = HistoryChartModelBuilder.build(
            listOf(HistoryChartModelBuilder.SeriesInput("A", true, viewMode, 0, points)),
            MainSensorOwnership.NONE, noCalibration,
        ).primary!!.runs.flatMap { it.points }.map { it.value }
        assertEquals(List(6) { 120f }, mainValues(0))
        assertEquals(List(6) { 100f }, mainValues(1))
    }

    @Test
    fun aRecordThatOutlivedItsCalibrationStillShowsTheLiveValueBesideIt() {
        // Recorded at 13 under a calibration that has since been deleted: the
        // main line keeps 13, and the sensor's own 5 is previewed even though no
        // calibration applies any more.
        val a = series("A", primary = true, point(NOW, 5f, "A", sealed = 13f), point(NOW + MINUTE, 5f, "A", sealed = 13f))
        val model = HistoryChartModelBuilder.build(listOf(a), MainSensorOwnership.NONE, noCalibration)
        assertEquals(listOf(13f, 13f), model.primary!!.runs.flatMap { it.points }.map { it.value })
        val preview = model.primary!!.secondaryLanes.single { it.kind == ChartLaneKind.CALIBRATION_PREVIEW }
        assertEquals(listOf(5f, 5f), preview.runs.flatMap { it.points }.map { it.value })
    }

    @Test
    fun noPreviewWithoutARecord() {
        val a = series("A", primary = true, point(NOW, 5f, "A"), point(NOW + MINUTE, 5f, "A"))
        val model = HistoryChartModelBuilder.build(listOf(a), MainSensorOwnership.NONE, noCalibration)
        assertTrue(model.primary!!.secondaryLanes.none { it.kind == ChartLaneKind.CALIBRATION_PREVIEW })
    }

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
    fun hiddenSourceStaysHiddenWhenCalibratedSensorBecomesAPeer() {
        // Ottai auto and AiDex raw are single-lane modes. The hide preference
        // must survive switching either calibrated sensor behind an uncalibrated one.
        for (mode in listOf(0, 1)) {
            val calibrated = series("calibrated", true,
                point(NOW, 5f, "calibrated"), point(NOW + MINUTE, 5f, "calibrated")
            ).copy(viewMode = mode, hasCalibration = true)
            val uncalibrated = series("other", false,
                point(NOW, 8f, "other"), point(NOW + MINUTE, 8f, "other")
            )
            val calibration = HistoryChartModelBuilder.Calibration { value, _, _, sensor ->
                if (sensor == "calibrated") value + 1f else null
            }
            for (calibratedIsPrimary in listOf(true, false)) {
                for (hideSource in listOf(true, false)) {
                    val model = HistoryChartModelBuilder.build(
                        listOf(calibrated.copy(isPrimary = calibratedIsPrimary),
                            uncalibrated.copy(isPrimary = !calibratedIsPrimary)),
                        MainSensorOwnership.NONE, calibration,
                        hasCalibration = calibratedIsPrimary,
                        hideInitialWhenCalibrated = hideSource,
                    )
                    val trace = model.series.single { it.sensorId == "calibrated" }
                    assertEquals(listOf(6f, 6f), trace.runs.flatMap { it.points }.map { it.value })
                    assertEquals(if (hideSource) 0 else 1, trace.secondaryLanes.size)
                    if (!hideSource) {
                        assertEquals(listOf(5f, 5f), trace.secondaryLanes.single().runs.flatMap { it.points }.map { it.value })
                    }
                    val other = model.series.single { it.sensorId == "other" }
                    assertTrue(other.secondaryLanes.isEmpty())
                    assertEquals(listOf(8f, 8f), other.runs.flatMap { it.points }.map { it.value })
                }
            }
        }
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
    @Test
    fun mergedHistoryUsesEachReadingsCalibrationAndRecordedOwner() {
        val old = point(SEALED_START, 100f, "A")
        val current = point(SEALED_START + MINUTE, 200f, "B")
        val ownership = MainSensorOwnership(mapOf(SEALED_START to "A"), NOW)
        val calibration = HistoryChartModelBuilder.Calibration { value, _, _, sensor ->
            value + if (sensor == "A") 10f else 20f
        }
        val result = HistoryChartModelBuilder.build(
            listOf(series("B", true, old, current)), ownership, calibration,
        ).primary!!
        assertEquals(110f, result.valueAt(old.timestamp)!!, 0.001f)
        assertEquals(220f, result.valueAt(current.timestamp)!!, 0.001f)
        assertEquals(ChartLook.MAIN, result.runs.first().look)
    }

    @Test
    fun mergedHistoryDoesNotJoinDifferentSensorsEvenWithinTheGapThreshold() {
        val result = HistoryChartModelBuilder.build(
            listOf(series("B", true, point(NOW, 100f, "A"), point(NOW + MINUTE, 200f, "B"))),
            MainSensorOwnership.NONE, noCalibration,
        ).primary!!
        assertEquals(2, result.runs.size)
        assertEquals(listOf(1, 1), result.runs.map { it.points.size })
    }

}
