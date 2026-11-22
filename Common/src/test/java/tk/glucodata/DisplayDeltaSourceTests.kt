package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.After
import java.util.Locale

class DisplayDeltaSourceTests {
    @After fun resetCalibration() { CalibrationAccess.unregisterForTests() }

    private val now = 1_800_000_000_000L
    private val timeout = 330_000L
    private fun point(minutesAgo: Int, auto: Float, raw: Float = auto, sensor: String? = null) =
        GlucosePoint(now - minutesAgo * 60_000L, auto, raw).also { it.sensorSerial = sensor }
    private val history get() = listOf(point(5, 100f, 140f), point(0, 110f, 120f))

    private fun text(
        points: List<GlucosePoint> = history,
        enabled: Boolean = true,
        shownAt: Long = now,
        sensor: String? = "sensor",
        mode: Int = 0,
        mmol: Boolean = false,
        interval: Int = 5,
        clock: Long = now
    ) = DisplayDeltaSource.format(enabled, points, sensor, shownAt, mode, mmol, interval, clock, timeout)

    @Test fun disabledOrUnknownSensorNeverShowsDelta() {
        assertEquals("", text(enabled = false))
        assertEquals("", text(sensor = null))
        assertEquals("", text(sensor = ""))
        assertEquals("", DisplayDeltaSource.resolve(false, null, 5))
    }

    @Test fun staleFutureAndMissingReadingsNeverShowDelta() {
        assertEquals("", text(clock = now + timeout + 1))
        assertEquals("", text(clock = now - 1))
        assertEquals("", text(shownAt = 0L))
        assertEquals("", text(points = emptyList()))
        assertEquals("", text(points = listOf(point(0, 110f))))
        assertEquals("+10", text(clock = now + timeout))
    }

    @Test fun usesSelectedLaneAndConfiguredInterval() {
        assertEquals("+10", text())
        assertEquals("+10", text(mode = 2))
        assertEquals("−20", text(mode = 1))
        assertEquals("−20", text(mode = 3))
        assertEquals("+2", text(interval = 1))
    }

    @Test fun missingRawLaneDoesNotBorrowAutoDelta() {
        assertEquals("", text(points = listOf(point(5, 100f, 0f), point(0, 110f, 0f)), mode = 1))
        assertEquals("", text(points = listOf(point(5, 100f, Float.NaN), point(0, 110f, 120f)), mode = 3))
    }

    @Test fun invalidSamplesAndGapsCannotProduceDelta() {
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("", text(points = listOf(point(5, 100f), point(0, invalid))))
            assertEquals("", text(points = listOf(point(5, invalid), point(0, 110f))))
        }
        assertEquals("", text(points = listOf(point(21, 100f), point(0, 110f))))
        assertEquals("", text(points = listOf(point(1, 100f), point(0, 110f))))
    }

    @Test fun deltaEndsExactlyAtShownReadingAndIgnoresNewerStoredValues() {
        val points = listOf(point(10, 100f), point(5, 110f), point(0, 50f))
        assertEquals("+10", text(points = points, shownAt = now - 5 * 60_000L))
        assertEquals("", text(points = points, shownAt = now - 60_000L))
    }

    @Test fun nearDuplicatesDoNotReplaceIntervalPartner() {
        assertEquals("+10", text(points = history + GlucosePoint(now - 1, 109f)))
    }

    @Test fun explicitForeignSensorCannotSupplyAnchorOrPartner() {
        assertEquals("", text(points = listOf(point(5, 100f, sensor = "other"), point(0, 110f))))
        assertEquals("", text(points = listOf(point(5, 100f), point(0, 110f, sensor = "other"))))
        assertEquals("+10", text(points = listOf(point(5, 100f, sensor = "sensor"), point(0, 110f, sensor = "sensor"))))
    }

    @Test fun mmolValuesStayInDisplayUnitsAndUseLocale() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("+0,5", text(points = listOf(point(5, 5f), point(0, 5.5f)), mmol = true))
        } finally {
            Locale.setDefault(oldLocale)
        }
    }
    @Test fun bothPairValuesUseTheSelectedLanesPerPointCalibration() {
        val calls = mutableListOf<Triple<Long, Boolean, String?>>()
        CalibrationAccess.register(object : CalibrationProvider {
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) = true
            override fun getCalibratedValue(value: Float, timestamp: Long, isRawMode: Boolean,
                emitDiagnostics: Boolean, sensorId: String?): Float {
                calls += Triple(timestamp, isRawMode, sensorId)
                return value * (if (isRawMode) 2f else 3f)
            }
        })
        assertEquals("−40", text(mode = 1))
        assertEquals(setOf(now, now - 5 * 60_000L), calls.map { it.first }.toSet())
        assertEquals(true, calls.all { it.second && it.third == "sensor" })
        calls.clear()
        assertEquals("+30", text(mode = 0))
        assertEquals(true, calls.all { !it.second && it.third == "sensor" })
        assertEquals(GlucoseDelta.format(1f, true), text(points = listOf(point(5, 5f), point(0, 5.5f)), mode = 1, mmol = true))
    }

    @Test fun integratedCalibrationIsNotAppliedTwiceAndInvalidCorrectionsFollowTheDisplayFallback() {
        var integrated = true
        CalibrationAccess.register(object : CalibrationProvider {
            override fun shouldOverwriteSensorValues() = integrated
            override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?) = true
            override fun getCalibratedValue(value: Float, timestamp: Long, isRawMode: Boolean,
                emitDiagnostics: Boolean, sensorId: String?) = Float.NaN
        })
        assertEquals("−20", text(mode = 1))
        integrated = false
        assertEquals("−20", text(mode = 1))
    }

    @Test fun resolveLoadsMeasuredHistoryInsteadOfMixingInTheSmoothedWidgetTail() {
        val at = System.currentTimeMillis()
        val shown = deltaSnapshot(at, auto = 80f)
        val measured = listOf(GlucosePoint(at - 5 * 60_000L, 100f), GlucosePoint(at, 110f))
        val chart = DisplayTrendSource.augmentHistory(measured, shown, "sensor", 0L)
        assertEquals(80f, chart.last().value, 0f)
        val result = DisplayDeltaSource.resolveResult(shown, 5, { at }) { start, mmol, sensor ->
            assertEquals(at - 21 * 60_000L, start)
            assertEquals(false, mmol)
            assertEquals("sensor", sensor)
            measured
        }
        assertEquals("+10", result?.text)
        assertEquals(110f, measured.last().value, 0f)
    }

    @Test fun aReadingThatExpiresDuringTheHistoryLoadCannotPublishADelta() {
        var clock = now
        val result = DisplayDeltaSource.resolveResult(deltaSnapshot(at = now), 5, { clock }) { _, _, _ ->
            clock = now + timeout + 1
            history
        }
        assertEquals(null, result)
    }

    @Test fun aMinuteAlignedMeasurementMatchesATwentyFiveSecondLiveOffset() {
        val shownAt = now + 25_000L
        assertEquals("+10", text(shownAt = shownAt, clock = shownAt))
        assertEquals("−20", text(shownAt = shownAt, clock = shownAt, mode = 1))
    }

    @Test fun matchingNeverBorrowsANeighboringMinuteEvenInsideTheDisplayTolerance() {
        val neighbor = GlucosePoint(now + 60_000L, 180f, 200f)
        assertEquals("", text(points = listOf(point(5, 100f), neighbor), shownAt = now + 25_000L, clock = now + 25_000L))
        assertEquals("", text(points = listOf(point(5, 100f), GlucosePoint(now - 1L, 110f)),
            shownAt = now + 25_000L, clock = now + 25_000L))
        assertEquals("+10", text(points = history + neighbor, shownAt = now + 25_000L, clock = now + 25_000L))
    }

    @Test fun ambiguousUnalignedArrivalsAndInvalidExactSamplesCannotBorrowTheRoundedRow() {
        assertEquals("", text(points = history + GlucosePoint(now + 10_000L, 180f),
            shownAt = now + 25_000L, clock = now + 25_000L))
        assertEquals("", text(points = history + GlucosePoint(now + 25_000L, Float.NaN),
            shownAt = now + 25_000L, clock = now + 25_000L))
    }

    @Test fun exactMeasuredTimestampsWinOverMinuteFlooring() {
        val points = history + GlucosePoint(now + 25_000L, 130f, 150f)
        val expected = GlucoseDelta.format(GlucoseDelta.delta(now + 25_000L, 130f, now - 5 * 60_000L, 100f, 5), false)
        assertEquals(expected, text(points = points, shownAt = now + 25_000L, clock = now + 25_000L))
    }

    @Test fun aTolerantMatchDoesNotExtendTheMeasuredReadingsFreshness() {
        assertEquals("", text(shownAt = now + 25_000L, clock = now + timeout + 1L))
    }

}
