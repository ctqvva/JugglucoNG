package tk.glucodata.chart

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.GlucosePoint

/**
 * Equivalence with the dashboard's former in-chart semantics, restated here as
 * an oracle so the old path could be deleted with evidence rather than hope.
 *
 * The oracle is the rule set DashboardChart used to apply per frame:
 *
 * - value: `sealedDisplayValue` if usable, else the lane's calibration when one
 *   applies, else the lane's own value (`CalibratedValueResolver.valueAt`);
 * - look: `MainSensorOwnership.isMainAt` per point, null keeping the default
 *   (`ownershipRanges`);
 * - lanes: `drawRaw`/`drawAuto` with `hideRawSource`/`hideAutoSource`, minus
 *   whichever lane is already the main line;
 * - runs: broken where consecutive points are further apart than the gap.
 *
 * Each scenario runs the oracle point by point and compares against the
 * model's runs flattened back to (timestamp, value, look).
 */
class HistoryChartModelParityTests {

    private companion object {
        const val MINUTE = MainSensorOwnership.MINUTE_MS
        const val NOW = 1_800_000_000_000L
        const val GAP = 16L * MINUTE
    }

    private data class Resolved(val timestamp: Long, val value: Float, val look: ChartLook)

    private fun point(ts: Long, auto: Float, raw: Float, serial: String, sealed: Float = Float.NaN, sealedMode: Int = -1) =
        GlucosePoint(ts, auto, raw).also {
            it.sensorSerial = serial; it.sealedDisplayValue = sealed; it.sealedDisplayViewMode = sealedMode
        }

    /**
     * The dashboard's former per-point value rule, plus the one deliberate
     * change since: a record applies only to the lane it was shown on.
     */
    private fun oracleValue(p: GlucosePoint, isRaw: Boolean, cal: ((Float, Long) -> Float)?): Float? {
        val recordedIsRaw = p.sealedDisplayViewMode == 1 || p.sealedDisplayViewMode == 3
        val laneMatches = p.sealedDisplayViewMode < 0 || recordedIsRaw == isRaw
        if (!p.sealedDisplayValue.isNaN() && p.sealedDisplayValue > 0.1f && laneMatches) return p.sealedDisplayValue
        val base = if (isRaw) p.rawValue else p.value
        if (base.isNaN() || base <= 0.1f) return null
        return cal?.invoke(base, p.timestamp) ?: base
    }

    /** The dashboard's former per-point look rule. */
    private fun oracleLook(o: MainSensorOwnership, serial: String, ts: Long, isPrimary: Boolean): ChartLook =
        when (o.isMainAt(serial, ts)) {
            true -> ChartLook.MAIN
            false -> ChartLook.SECONDARY
            null -> if (isPrimary) ChartLook.MAIN else ChartLook.SECONDARY
        }

    private fun oracle(
        points: List<GlucosePoint>, serial: String, isPrimary: Boolean, viewMode: Int,
        o: MainSensorOwnership, cal: ((Float, Long) -> Float)?,
    ): List<Resolved> = points.mapNotNull { p ->
        val v = oracleValue(p, viewMode == 1 || viewMode == 3, cal) ?: return@mapNotNull null
        Resolved(p.timestamp, v, oracleLook(o, serial, p.timestamp, isPrimary))
    }

    private fun flatten(series: ChartSeriesModel): List<Resolved> {
        // Boundary points are shared between runs; the oracle sees each point
        // once, so collapse the duplicate at a look change and keep the look the
        // point *enters* with (the run it started in).
        val out = ArrayList<Resolved>()
        series.runs.forEach { run ->
            run.points.forEach { p ->
                if (out.lastOrNull()?.timestamp != p.timestamp) out.add(Resolved(p.timestamp, p.value, run.look))
            }
        }
        return out
    }

    private fun assertParity(
        points: List<GlucosePoint>, serial: String, isPrimary: Boolean, viewMode: Int,
        o: MainSensorOwnership, cal: ((Float, Long) -> Float)?, hasCalibration: Boolean = cal != null,
    ) {
        val model = HistoryChartModelBuilder.build(
            listOf(HistoryChartModelBuilder.SeriesInput(serial, isPrimary, viewMode, 0, points)),
            o,
            HistoryChartModelBuilder.Calibration { v, ts, _, _ -> cal?.invoke(v, ts) },
            hasCalibration = hasCalibration,
            gapThresholdMs = GAP,
        )
        assertEquals(oracle(points, serial, isPrimary, viewMode, o, cal), flatten(model.series.single()))
    }

    private fun sealedSpan(from: Int, to: Int, owner: String) =
        (from until to).associate { MainSensorOwnership.minuteOf(NOW - (200 - it) * MINUTE) to owner }

    private fun ts(i: Int) = NOW - (200 - i) * MINUTE

    @Test
    fun recordedThenCalibratedThenOwnPreferenceMatchesAcrossAMixedSeries() {
        // Records alternate between lanes, so every view mode below meets both
        // a matching and a mismatching record.
        val points = (0 until 60).map { i ->
            val sealed = if (i % 7 == 0) 4f + i * 0.01f else Float.NaN
            val mode = if (i % 14 == 0) 0 else 1
            point(ts(i), 5f + i * 0.02f, 6f + i * 0.02f, "A", sealed, sealedMode = mode)
        }
        val cal: (Float, Long) -> Float = { v, _ -> v * 1.1f }
        assertParity(points, "A", true, 0, MainSensorOwnership.NONE, cal)
        assertParity(points, "A", true, 1, MainSensorOwnership.NONE, cal)
        assertParity(points, "A", true, 2, MainSensorOwnership.NONE, cal)
        assertParity(points, "A", true, 3, MainSensorOwnership.NONE, cal)
        assertParity(points, "A", true, 0, MainSensorOwnership.NONE, null)
    }

    @Test
    fun ownershipBoundariesInsideASealedStretchMatch() {
        // A owns 0..20 and 40..60; B owns 20..40; everything is sealed.
        val owned = sealedSpan(0, 20, "A") + sealedSpan(20, 40, "B") + sealedSpan(40, 60, "A")
        val o = MainSensorOwnership(owned, NOW)
        val a = (0 until 60).map { point(ts(it), 5f, 5f, "A") }
        val b = (0 until 60).map { point(ts(it), 6f, 6f, "B") }
        assertParity(a, "A", true, 0, o, null)
        assertParity(b, "B", false, 0, o, null)
    }

    @Test
    fun theGraceWindowEdgeMatches() {
        // Recorded owner is B for every minute, including the last hour; inside
        // the window the record must be ignored by both.
        val owned = (0 until 200).associate { MainSensorOwnership.minuteOf(ts(it)) to "B" }
        val o = MainSensorOwnership(owned, NOW)
        val a = (0 until 200).map { point(ts(it), 5f, 5f, "A") }
        assertParity(a, "A", true, 0, o, null)
        val flat = flatten(
            HistoryChartModelBuilder.build(
                listOf(HistoryChartModelBuilder.SeriesInput("A", true, 0, 0, a)),
                o,
                HistoryChartModelBuilder.Calibration { _, _, _, _ -> null },
            ).series.single()
        )
        // Sanity on the edge itself: the last minute inside the window is MAIN by default.
        assertEquals(ChartLook.MAIN, flat.last().look)
        assertEquals(ChartLook.SECONDARY, flat.first().look)
    }

    @Test
    fun gapsBreakRunsWhereTheOracleWouldNotJoin() {
        val points = listOf(
            point(ts(0), 5f, 5f, "A"), point(ts(1), 5f, 5f, "A"),
            point(ts(1) + GAP + MINUTE, 5f, 5f, "A"), point(ts(1) + GAP + 2 * MINUTE, 5f, 5f, "A"),
        )
        val model = HistoryChartModelBuilder.build(
            listOf(HistoryChartModelBuilder.SeriesInput("A", true, 0, 0, points)),
            MainSensorOwnership.NONE,
            HistoryChartModelBuilder.Calibration { _, _, _, _ -> null },
            gapThresholdMs = GAP,
        )
        assertEquals(2, model.primary!!.runs.size)
        assertParity(points, "A", true, 0, MainSensorOwnership.NONE, null)
    }

    @Test
    fun secondaryLanesMatchTheDashboardsDrawRawDrawAutoMinusTheMainLane() {
        fun oracleLanes(viewMode: Int, hasCal: Boolean, hide: Boolean): List<ChartLaneKind> {
            val isRaw = viewMode == 1 || viewMode == 3
            val hideRaw = hasCal && hide && isRaw
            val hideAuto = hasCal && hide && !isRaw
            val drawRaw = !hideRaw && (viewMode == 1 || viewMode == 2 || viewMode == 3)
            val drawAuto = !hideAuto && (viewMode == 0 || viewMode == 2 || viewMode == 3)
            // Without a calibration the primary lane is the main line itself.
            val rawIsMain = !hasCal && isRaw
            val autoIsMain = !hasCal && !isRaw
            return listOfNotNull(
                ChartLaneKind.RAW.takeIf { drawRaw && !rawIsMain },
                ChartLaneKind.AUTO.takeIf { drawAuto && !autoIsMain },
            )
        }
        for (viewMode in 0..3) for (hasCal in listOf(false, true)) for (hide in listOf(false, true)) {
            assertEquals(
                "viewMode=$viewMode hasCal=$hasCal hide=$hide",
                oracleLanes(viewMode, hasCal, hide),
                HistoryChartModelBuilder.secondaryLaneKinds(viewMode, hasCal, hide),
            )
        }
    }
}
