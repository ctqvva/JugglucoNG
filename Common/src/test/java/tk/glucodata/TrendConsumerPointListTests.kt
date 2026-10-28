package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.DisplayValues

/**
 * Every trend-arrow consumer must regress over the SAME point list. Historically
 * each surface assembled its own: the dashboard hero skipped the live
 * augmentation, and the notification paths cut their Room load at the wall
 * clock while TrendEngine windows from the newest point — so whenever the newest
 * reading lagged "now" (always, by up to a full period), the lists differed by
 * one tail point. Invisible everywhere except the ±0.5 mg/dl/min flat dead zone,
 * where the nuance flipped one glyph but not the other for a full period
 * (observed live: dashboard ↗ vs notification → on an identical 134).
 *
 * DisplayTrendSource.resolveTrendPoints is the one canonical resolution now;
 * these tests pin element-wise identity (timestamps AND values, per the
 * acceptance criteria) and identical glyph decisions at the dead-zone edge.
 */
class TrendConsumerPointListTests {
    @Test fun aLiveTailKeepsOnlyItsOwnMinutesIntervalAndRecordedValue() {
        val source = GlucosePoint(60_000L, 100f, 95f).also {
            it.sensorSerial = "sensor"; it.uncertainty = GlucoseUncertainty(80f, 110f)
            it.sealedDisplayValue = 105f; it.sealedDisplayViewMode = 0
        }
        val live = snapshot(60_001L, 102f).copy(sensorId = "sensor")
        val tail = DisplayTrendSource.augmentHistory(listOf(source), live, "sensor", 0L).last()
        assertEquals(82f, tail.uncertainty!!.lower, 0f)
        assertEquals(112f, tail.uncertainty!!.upper, 0f)
        assertEquals(105f, tail.sealedDisplayValue, 0f)
        assertEquals("sensor", tail.sensorSerial)
        assertNull(DisplayTrendSource.augmentHistory(listOf(source), live.copy(timeMillis = 120_000L), "sensor", 0L).last().uncertainty)
        assertNull(DisplayTrendSource.augmentHistory(listOf(source), live.copy(sensorId = "other"), "other", 0L).last().uncertainty)
    }

    @Before
    fun registerProductionTrendProvider() {
        // Specific.start() performs this registration in the app. Local JVM
        // tests do not run application startup, so reproduce that wiring here
        // instead of exercising TrendAccess's deliberately degraded fallback.
        TrendAccess.register(TrendVelocityProvider { points, useRaw, isMmol ->
            TrendEngine.calculateTrend(points, useRaw, isMmol).velocity
        })
    }

    private val minute = 60_000L
    private val now = 1_700_000_000_000L
    private val serial = "sensor-1"

    /** Newest reading is 45 s old — the wall-clock lag the old cut tripped over. */
    private val newestTs = now - 45_000L

    /**
     * 21 Room rows, 1-min apart. The interior 20 rise at exactly 0.48 mg/dl/min;
     * the oldest row (exactly 20 min before the newest) dips below the line, so
     * including or dropping it moves the regression across the 0.5 dead-zone
     * edge — the construction that reproduced the live glyph split pre-fix.
     */
    private fun roomBase(): List<GlucosePoint> {
        val points = ArrayList<GlucosePoint>()
        points.add(GlucosePoint(newestTs - 20 * minute, 116f, 0f))
        for (k in 19 downTo 0) {
            points.add(GlucosePoint(newestTs - k * minute, 133.1f - 0.48f * k, 0f))
        }
        return points
    }

    private fun snapshot(ts: Long, value: Float) = CurrentDisplaySource.Snapshot(
        timeMillis = ts,
        rate = Float.NaN,
        sensorId = serial,
        sensorGen = 0,
        index = 0,
        viewMode = 0,
        source = "test",
        autoValue = value,
        rawValue = 0f,
        sharedDisplayValue = 0f,
        sharedMgdl = 0,
        isMmol = false,
        displayValues = DisplayValues(primaryValue = value, primaryStr = "", fullFormatted = "")
    )

    // ---- The four consumers' trend lists, as production assembles them post-fix ----

    /** Dashboard hero: full Room window + snapshot (DashboardComponents.kt). */
    private fun dashboardList(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot?) =
        DisplayTrendSource.resolveTrendPoints(base, snap, null)

    /** Main notification: 3-h chart rows + snapshot (Notify.makearrownotification). */
    private fun notificationList(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot?) =
        DisplayTrendSource.resolveTrendPoints(base, snap, serial)

    /** Alarm notification: rows since now-2×window + snapshot. */
    private fun alarmList(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot?): List<GlucosePoint> {
        val startT = now - 2 * DisplayTrendSource.TREND_WINDOW_MS
        return DisplayTrendSource.resolveTrendPoints(base.filter { it.timestamp >= startT }, snap, serial)
    }

    /** GlucosePoint has no value equals — compare the fields the regression reads. */
    private fun triples(points: List<GlucosePoint>) =
        points.map { Triple(it.timestamp, it.value, it.rawValue) }

    // ---- Acceptance 1: element-wise list identity, timestamps AND values ----

    @Test
    fun allConsumersResolveTheIdenticalPointList() {
        val base = roomBase()
        val snap = snapshot(newestTs, 133.1f)

        val dashboard = triples(dashboardList(base, snap))
        val notification = triples(notificationList(base, snap))
        val alarm = triples(alarmList(base, snap))

        assertEquals(dashboard, notification)
        assertEquals(dashboard, alarm)
        // The tail row exactly one window before the newest point stays in for everyone.
        assertEquals(21, dashboard.size)
        assertEquals(newestTs - 20 * minute, dashboard.first().first)
    }

    @Test
    fun listsStayIdenticalWhenRoomLagsTheLiveReading() {
        // The newest reading exists only as the live snapshot, not yet persisted.
        val base = roomBase().dropLast(1)
        val snap = snapshot(newestTs, 133.1f)

        val dashboard = triples(dashboardList(base, snap))
        val notification = triples(notificationList(base, snap))
        val alarm = triples(alarmList(base, snap))

        assertEquals(dashboard, notification)
        assertEquals(dashboard, alarm)
        // The live point is appended for every consumer, dashboard included.
        assertEquals(newestTs, dashboard.last().first)
        assertEquals(133.1f, dashboard.last().second)
    }

    // ---- Acceptance 2: identical glyph decision at the dead-zone edge ----

    @Test
    fun glyphDecisionIsIdenticalAtTheFlatDeadZoneEdge() {
        val base = roomBase()
        val snap = snapshot(newestTs, 133.1f)

        // Dashboard hero (DashboardComponents.kt): canonical list into the engine.
        val dashboardResult = TrendEngine.calculateTrend(dashboardList(base, snap), useRaw = false, isMmol = false)

        // Main notification and alarm notification (Notify.java).
        val notificationVelocity =
            DisplayTrendSource.resolveArrowRate(notificationList(base, snap), snap, 0, false, Float.NaN)
        val alarmVelocity =
            DisplayTrendSource.resolveArrowRate(alarmList(base, snap), snap, 0, false, Float.NaN)

        // Broadcast (BroadcastTrendRate.java): the same canonical list; the snapshot is
        // deliberately not handed to resolveArrowRate so a too-thin history falls back
        // to the caller's native rate instead of the snapshot's own.
        val broadcastVelocity =
            DisplayTrendSource.resolveArrowRate(alarmList(base, snap), null, 0, false, Float.NaN)

        assertEquals(dashboardResult.velocity, notificationVelocity, 1e-6f)
        assertEquals(dashboardResult.velocity, alarmVelocity, 1e-6f)
        assertEquals(dashboardResult.velocity, broadcastVelocity, 1e-6f)

        // Identical velocity means one glyph decision: every consumer sits on the
        // same side of the ±0.5 dead zone, whatever the estimator reads for the
        // fixture. Pre-fix, the one-tail-point list difference put the dashboard
        // and the notification on opposite sides here.
        fun deadZoneSide(v: Float) = when {
            v > 0.5f -> 1
            v < -0.5f -> -1
            else -> 0
        }
        assertEquals(deadZoneSide(dashboardResult.velocity), deadZoneSide(notificationVelocity))
        assertEquals(deadZoneSide(dashboardResult.velocity), deadZoneSide(alarmVelocity))
        assertEquals(deadZoneSide(dashboardResult.velocity), deadZoneSide(broadcastVelocity))
    }

    @Test
    fun subDeadZoneDriftReadsFlatOnEveryConsumer() {
        // Interior slope 0.48 with no tail dip: inside the dead zone for everyone.
        val base = roomBase().drop(1)
        val snap = snapshot(newestTs, 133.1f)

        val dashboardResult = TrendEngine.calculateTrend(dashboardList(base, snap), useRaw = false, isMmol = false)
        val notificationVelocity =
            DisplayTrendSource.resolveArrowRate(notificationList(base, snap), snap, 0, false, Float.NaN)

        assertEquals(dashboardResult.velocity, notificationVelocity, 1e-6f)
        assertEquals(TrendEngine.TrendState.Flat, dashboardResult.state)
        assertTrue("must stay inside the dead zone", kotlin.math.abs(notificationVelocity) <= 0.5f)
    }
}
