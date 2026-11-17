package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LegacyWidgetFrameTests {
    private val now = 1_800_000_000_000L
    private val history = listOf(GlucosePoint(now - 5 * 60_000L, 100f), GlucosePoint(now, 110f))

    @Test fun oneLoadSuppliesTheSnapshotAndUnmodifiedMeasuredDeltaForTheWholeWidgetBatch() {
        val shown = deltaSnapshot(at = now, auto = 80f)
        val augmented = DisplayTrendSource.augmentHistory(history, shown, "sensor", 0L)
        var loads = 0
        val frame = WidgetDisplaySource.resolveWidgetFrame(true, 5, { now }, 330_000L) {
            loads++
            CurrentDisplaySource.ResolvedReading(shown, augmented, history)
        }
        assertEquals(1, loads)
        assertSame(shown, frame.snapshot)
        assertEquals("+10", frame.deltaText)
        assertEquals(110f, history.last().value, 0f)
    }

    @Test fun missingOrStaleFramesNeverProvideADelta() {
        assertEquals("", WidgetDisplaySource.resolveWidgetFrame(true, 5, { now }, 330_000L) { null }.deltaText)
        val reading = CurrentDisplaySource.ResolvedReading(deltaSnapshot(at = now), history, history)
        assertEquals("", WidgetDisplaySource.resolveWidgetFrame(true, 5, { now + 330_001L }, 330_000L) { reading }.deltaText)
        assertEquals("", WidgetDisplaySource.resolveWidgetFrame(false, 5, { now }, 330_000L) { reading }.deltaText)
        val withoutMeasuredHistory = CurrentDisplaySource.ResolvedReading(deltaSnapshot(at = now), history)
        assertEquals("", WidgetDisplaySource.resolveWidgetFrame(true, 5, { now }, 330_000L) { withoutMeasuredHistory }.deltaText)
    }

    @Test fun currentResolutionRetainsTheMeasuredInputSeparatelyFromItsSmoothedSeries() {
        val measured = (0..10).map { minute ->
            GlucosePoint(now - (10 - minute) * 60_000L, if (minute == 10) 130f else 100f)
        }
        val resolved = requireNotNull(CurrentDisplaySource.resolveReading(
            current = null, recentPoints = measured, historyStart = now - 25 * 60_000L,
            viewMode = 0, isMmol = false,
            smoothingMode = CurrentDisplaySource.SmoothingMode(true, 5, false),
            sensorId = "sensor", nowMillis = now
        ))
        assertSame(measured, resolved.measuredPoints)
        assertEquals(130f, resolved.measuredPoints.last().value, 0f)
        org.junit.Assert.assertTrue(resolved.points.last().value < resolved.measuredPoints.last().value)
    }
}
