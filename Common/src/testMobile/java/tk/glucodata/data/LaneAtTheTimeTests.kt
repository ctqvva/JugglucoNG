package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.DisplayLaneHistory.Switch
import tk.glucodata.ui.GlucosePoint

/**
 * "Keep displayed values": a raw/auto switch must not change the main line
 * (#479 — "a stable lane for the main value readings that would not be
 * rewritten by calibration, switching auto/raw, or even switching sensors").
 *
 * The 2026-10-17 AiDEX reports (raw ~1.2 mmol/L below auto): after enabling the
 * freeze and switching lanes, minutes that had not been on screen yet followed
 * the new lane while the ones drawn before kept the old one, so the line jumped
 * at the edges of whatever had been looked at. Per-lane records (withdrawn)
 * instead showed the other lane's line in full, so the switch rewrote the
 * chart and the statistics (TIR 99% → 85%).
 */
class LaneAtTheTimeTests {

    private companion object {
        const val SENSOR = "X-222227KUFY"
        const val MINUTE = ReadingDisplay.MINUTE_MS
        const val T0 = 1_792_000_000_000L - 1_792_000_000_000L % MINUTE
        const val AUTO = 100f
        const val RAW = 78f
        const val GRACE = ReadingDisplay.DISPLAY_SEAL_GRACE_MS
    }

    /** A switch made when minute [m] left the grace window. */
    private fun sealedAt(m: Int): Long = T0 + m * MINUTE + GRACE

    private fun point(minute: Int, sealed: Float? = null, sealedMode: Int? = null) = GlucosePoint(
        value = AUTO + minute,
        time = "",
        timestamp = T0 + minute * MINUTE + 20_000L,
        rawValue = RAW + minute,
        sensorSerial = SENSOR,
        sealedDisplayValue = sealed,
        sealedDisplayViewMode = sealedMode,
    )

    /** The stored value of the lane, as with no calibration. */
    private fun laneValue(point: GlucosePoint, viewMode: Int): Float? =
        if (viewMode == 1 || viewMode == 3) point.rawValue else point.value

    private fun apply(points: List<GlucosePoint>, log: List<Switch>) =
        LaneAtTheTime.apply(points, { log }, ::laneValue)

    /** What the main line draws: the record, else the lane selected now. */
    private fun mainLine(points: List<GlucosePoint>, isRawMode: Boolean) =
        points.map { it.sealedDisplayValue ?: if (isRawMode) it.rawValue else it.value }

    @Test
    fun switchingToRawLeavesTheWholePastOnAuto() {
        // Auto until minute 60, then raw. Minutes 30..44 had been on screen in
        // auto and were recorded; the rest of the past never was.
        val log = listOf(Switch(sealedAt(60), 0, 1))
        val stored = (0 until 70).map { m ->
            if (m in 30..44) point(m, sealed = AUTO + m, sealedMode = 0) else point(m)
        }

        val shown = mainLine(apply(stored, log), isRawMode = true)

        (0 until 60).forEach { m -> assertEquals("minute $m", AUTO + m, shown[m], 0f) }
        (60 until 70).forEach { m -> assertEquals("minute $m", RAW + m, shown[m], 0f) }
    }

    @Test
    fun aSwitchCarriesTheGraceHourWithIt() {
        // Switched to raw at minute 120: the hour before it was still settling,
        // so it follows raw like everything after; what had sealed stays auto.
        val log = listOf(Switch(T0 + 120 * MINUTE, 0, 1))
        val stored = (0..120).map(::point)

        val shown = mainLine(apply(stored, log), isRawMode = true)

        (0 until 60).forEach { m -> assertEquals("minute $m", AUTO + m, shown[m], 0f) }
        (60..120).forEach { m -> assertEquals("minute $m", RAW + m, shown[m], 0f) }
        assertTrue(LaneAtTheTime.recordable(log, T0 + 90 * MINUTE, viewMode = 1))
        assertFalse(LaneAtTheTime.recordable(log, T0 + 30 * MINUTE, viewMode = 1))
    }

    @Test
    fun switchingBackRestoresTheSameLine() {
        val log = listOf(Switch(sealedAt(20), 0, 1), Switch(sealedAt(40), 1, 0))
        val stored = (0 until 50).map(::point)

        val inAuto = mainLine(apply(stored, log), isRawMode = false)

        (0 until 20).forEach { m -> assertEquals(AUTO + m, inAuto[m], 0f) }
        (20 until 40).forEach { m -> assertEquals("raw was on then", RAW + m, inAuto[m], 0f) }
        (40 until 50).forEach { m -> assertEquals(AUTO + m, inAuto[m], 0f) }
    }

    @Test
    fun aRecordedMinuteKeepsItsRecord() {
        val log = listOf(Switch(sealedAt(60), 0, 1))
        val recorded = point(5, sealed = 140f, sealedMode = 1)
        val out = apply(listOf(recorded), log).single()
        assertEquals(140f, out.sealedDisplayValue!!, 0f)
        assertEquals(1, out.sealedDisplayViewMode)
    }

    @Test
    fun theLaneValueTravelsAsTheRecordWithItsMode() {
        val log = listOf(Switch(sealedAt(60), 3, 0))
        val out = apply(listOf(point(5)), log).single()
        assertEquals(RAW + 5, out.sealedDisplayValue!!, 0f)
        assertEquals(3, out.sealedDisplayViewMode)
    }

    @Test
    fun withoutASwitchNothingChanges() {
        val stored = (0 until 10).map(::point)
        assertSame(stored, apply(stored, emptyList()))
        assertSame(stored, apply(stored, listOf(Switch(sealedAt(-1), 0, 1))))
    }

    @Test
    fun aLaneWithNothingToDrawFallsBackToTheLiveLine() {
        val log = listOf(Switch(sealedAt(60), 1, 0))
        val noRaw = point(5).copy(rawValue = Float.NaN)
        assertNull(apply(listOf(noRaw), log).single().sealedDisplayValue)
    }

    @Test
    fun aMinuteFromBeforeTheSwitchIsRecordedOnlyInTheLaneItWasOn() {
        val switchAt = sealedAt(60) + 30_000L
        val log = listOf(Switch(switchAt, 0, 1))

        assertTrue(LaneAtTheTime.recordable(log, T0 + 10 * MINUTE, viewMode = 0))
        assertTrue(LaneAtTheTime.recordable(log, T0 + 10 * MINUTE, viewMode = 2))
        assertFalse(
            "a frame drawn in raw before the history caught up",
            LaneAtTheTime.recordable(log, T0 + 10 * MINUTE, viewMode = 1),
        )
        // The minute the switch happened in holds readings of both lanes.
        assertTrue(LaneAtTheTime.recordable(log, T0 + 60 * MINUTE, viewMode = 0))
        assertTrue(LaneAtTheTime.recordable(log, T0 + 60 * MINUTE, viewMode = 1))
        assertTrue(LaneAtTheTime.recordable(log, T0 + 61 * MINUTE, viewMode = 1))
        assertTrue(LaneAtTheTime.recordable(emptyList(), T0, viewMode = 3))
    }
}
