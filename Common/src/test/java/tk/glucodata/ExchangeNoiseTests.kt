package tk.glucodata

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.logic.TrendEngineNoiseProvider
import tk.glucodata.logic.TrendEngineVelocityProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExchangeNoiseTests {
    private val sensor = "X-EXCHANGE-NOISE"
    private val now = System.currentTimeMillis()
    private val minute = 60_000L

    @Before fun register() {
        CalibrationAccess.unregisterForTests()
        NoiseAccess.register(TrendEngineNoiseProvider)
        TrendAccess.register(TrendEngineVelocityProvider)
    }

    @After fun cleanup() { CalibrationAccess.unregisterForTests() }

    private fun points(rawJitter: Boolean = false, scale: Float = 1f): List<GlucosePoint> =
        (4 downTo 0).map { index ->
            GlucosePoint(now - index * 5 * minute, 100f / scale,
                (if (rawJitter && index % 2 == 1) 120f else 100f) / scale)
        }

    private fun reading(
        history: List<GlucosePoint>,
        mode: Int = 0,
        live: CurrentGlucoseSource.Snapshot? = null,
        smoothing: CurrentDisplaySource.SmoothingMode = CurrentDisplaySource.MEASURED,
        mmol: Boolean = false,
    ) = requireNotNull(CurrentDisplaySource.resolveReading(
        live, history, now - 25 * minute, mode, mmol, smoothing, sensor,
        preferIncomingSample = true, nowMillis = now, preserveSameMinuteRaw = true
    ))

    private fun payload(reading: CurrentDisplaySource.ResolvedReading) =
        ExchangeGlucosePayload.fromReading(reading, sensor, 100.0, 0f, now, 0, "100")

    @Test fun noiseFollowsRawPrimaryInBothRawModes() {
        val history = points(rawJitter = true)
        for (mode in 0..3) {
            val captured = reading(history, mode)
            val payload = payload(captured)
            val expected = TrendEngineNoiseProvider.noise(history, mode == 1 || mode == 3, false)!!
            assertEquals(expected.varianceMgdl2, payload.noise.varianceMgdl2, 0.001f)
            assertEquals(if (mode == 1 || mode == 3) 3 else 1, payload.noise.nightscoutLevel)
        }
    }

    @Test fun calibrationSlopeScalesNoiseVarianceAndTransmittedPrimary() {
        val history = points(rawJitter = true)
        val uncalibrated = payload(reading(history, 1))
        CalibrationAccess.register(object : CalibrationProvider {
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) = isRawMode
            override fun getCalibratedValue(value: Float, timestamp: Long, isRawMode: Boolean,
                emitDiagnostics: Boolean, sensorId: String?) = 2f * value + 10f
        })
        val calibrated = payload(reading(history, 1))
        assertEquals(210, calibrated.primaryMgdl)
        assertEquals(4f * uncalibrated.noise.varianceMgdl2, calibrated.noise.varianceMgdl2, 0.01f)
    }

    @Test fun pendingLiveReadingIsIncludedAtItsOwnTimeWithoutLocalCollapse() {
        val history = points().dropLast(1)
        val live = CurrentGlucoseSource.Snapshot(now, "140", 140f, 100f,
            rate = 0f, sensorId = sensor, sensorGen = 0, index = 0, source = "test")
        val captured = reading(history, live = live)
        val series = DisplayTrendSource.exchangePoints(captured)
        assertEquals(now, captured.snapshot.timeMillis)
        assertEquals(140f, series.last().value, 0f)
        val payload = payload(captured)
        assertEquals(140, payload.primaryMgdl)
        assertTrue(payload.noise.varianceMgdl2 > 10f)
    }

    @Test fun fewerLocalReadingsCannotCollapseTheExchangeTail() {
        val history = points().dropLast(1)
        val live = CurrentGlucoseSource.Snapshot(now, "140", 140f, 100f,
            rate = 0f, sensorId = sensor, sensorGen = 0, index = 0, source = "test")
        val targets = DataSmoothing.Targets(chart = false, current = true, sent = false,
            fewerCurrent = true)
        val localMode = CurrentDisplaySource.localSmoothingMode(10, targets)
        assertTrue(localMode.collapseChunks)
        val exchangeMode = CurrentDisplaySource.exchangeSmoothingMode(10, targets)
        assertFalse(exchangeMode.collapseChunks)
        assertFalse(exchangeMode.smoothAllData)
        val captured = reading(history, live = live, smoothing = exchangeMode)
        assertEquals(now, captured.snapshot.timeMillis)
        assertEquals(140, payload(captured).primaryMgdl)
        assertTrue(payload(captured).noise.varianceMgdl2 > 10f)
    }

    @Test fun neighboringPersistedMinuteCannotReplaceTheLiveValue() {
        val history = points().dropLast(1) + GlucosePoint(now - 30_000L, 100f, 100f)
        val live = CurrentGlucoseSource.Snapshot(now, "115", 115f, 100f,
            rate = 0f, sensorId = sensor, sensorGen = 0, index = 0, source = "test")
        val captured = reading(history, live = live)
        assertEquals(115f, captured.snapshot.primaryValue, 0f)
        assertEquals(115f, DisplayTrendSource.exchangePoints(captured).last().value, 0f)
    }

    @Test fun nativeCacheWithoutRawUsesTheSameMinutesHistoryRawOnce() {
        val minuteStart = now / minute * minute
        val live = CurrentGlucoseSource.Snapshot(minuteStart + 30_000L, "100", 100f, Float.NaN,
            rate = 0f, sensorId = sensor, sensorGen = 0, index = 0, source = "native")
        val history = points().dropLast(1) + GlucosePoint(minuteStart, 100f, 140f)
        for (mode in 0..3) {
            val captured = reading(history, mode, live)
            assertEquals(live.timeMillis, captured.snapshot.timeMillis)
            assertEquals(140f, captured.snapshot.rawValue, 0f)
            assertEquals(if (mode == 1 || mode == 3) 140 else 100, payload(captured).primaryMgdl)
            val series = DisplayTrendSource.exchangePoints(captured)
            assertEquals(5, series.size)
            assertEquals(live.timeMillis, series.last().timestamp)
        }
    }

    @Test fun aPreviousMinutesRawCannotReplaceMissingLiveRaw() {
        val minuteStart = now / minute * minute
        val live = CurrentGlucoseSource.Snapshot(minuteStart + 10_000L, "100", 100f, Float.NaN,
            rate = 0f, sensorId = sensor, sensorGen = 0, index = 0, source = "native")
        // Only 30 seconds apart, but these are different sensor minutes.
        val history = points().dropLast(1) + GlucosePoint(minuteStart - 20_000L, 100f, 140f)
        for (mode in listOf(1, 3)) {
            val captured = reading(history, mode, live)
            assertFalse(captured.snapshot.rawValue.isFinite())
            assertEquals(100, payload(captured).primaryMgdl)
        }
    }

    @Test fun newerHistoryCannotLeakIntoAnOlderPayload() {
        val history = points() + GlucosePoint(now + minute, 250f, 250f)
        val live = CurrentGlucoseSource.Snapshot(now, "100", 100f, 100f,
            rate = 0f, sensorId = sensor, sensorGen = 0, index = 0, source = "test")
        val captured = reading(history, live = live)
        val series = DisplayTrendSource.exchangePoints(captured)
        assertEquals(now, series.last().timestamp)
        assertEquals(5, series.size)
        assertEquals(0f, payload(captured).noise.varianceMgdl2, 0.001f)
    }

    @Test fun noiseUsesExchangeSmoothingForEveryPoint() {
        val history = points(rawJitter = true).map { GlucosePoint(it.timestamp, it.rawValue, it.rawValue) }
        val mode = CurrentDisplaySource.SmoothingMode(true, 10, false)
        val captured = reading(history, smoothing = mode)
        val series = DisplayTrendSource.exchangePoints(captured)
        assertEquals(captured.snapshot.primaryValue, series.last().value, 0f)
        assertEquals(captured.points.map { it.value }, series.map { it.value })
        val expected = TrendEngineNoiseProvider.noise(captured.points, false, false)!!
        assertEquals(expected.varianceMgdl2, payload(captured).noise.varianceMgdl2, 0.001f)
    }

    @Test fun importedCalibrationRemainsTheAuthoritativeTail() {
        val live = CurrentGlucoseSource.Snapshot(now, "100", 100f, 100f,
            calibratedNumericValue = 130f, rate = 0f, sensorId = sensor,
            sensorGen = 0, index = 0, source = "test")
        val captured = reading(points(), live = live)
        val series = DisplayTrendSource.exchangePoints(captured)
        assertEquals(130f, series.last().value, 0f)
        assertEquals(130, payload(captured).primaryMgdl)
        assertTrue(payload(captured).noise.varianceMgdl2 > 10f)
    }

    @Test fun mmolAndMgdlPublishTheSameVariance() {
        val mgdl = payload(reading(points(true), 1))
        val mmol = payload(reading(points(true, 18.0182f), 1, mmol = true))
        assertEquals(mgdl.noise.varianceMgdl2, mmol.noise.varianceMgdl2, 0.01f)
        assertEquals(mgdl.primaryMgdl, mmol.primaryMgdl)
    }

    @Test fun staleOrFarFuturePayloadHasNoNoiseButRetainsItsOwnTrendSeries() {
        for (offset in listOf(-10 * minute, 10 * minute)) {
            val shifted = points().map { GlucosePoint(it.timestamp + offset, it.value, it.rawValue) }
            val captured = reading(shifted)
            assertEquals(now + offset, DisplayTrendSource.exchangePoints(captured).last().timestamp)
            assertNull(payload(captured).noise)
            val series = DisplayTrendSource.exchangePoints(captured)
            assertEquals(0f, BroadcastTrendRate.computed(series, false, 7f), 0.001f)
        }
    }

    @Test fun serializingPayloadNeverRequeriesNoiseProvider() {
        val captured = reading(points(true), 1)
        val payload = payload(captured)
        NoiseAccess.register { _, _, _ -> error("serializer must use captured noise") }
        val extras = SendLikexDrip.mkGlucosebundle(payload, 0L)
        assertEquals(payload.primaryMgdl.toDouble(), extras.getDouble("com.eveningoutpost.dexdrip.Extras.BgEstimate"), 0.0)
        assertEquals(payload.timeMillis, extras.getLong("com.eveningoutpost.dexdrip.Extras.Time"))
        assertEquals(payload.noise.varianceMgdl2.toDouble(), extras.getDouble("com.eveningoutpost.dexdrip.Extras.Noise"), 0.0)
    }
}
