package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DisplayDeltaSourceTests {
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
}
