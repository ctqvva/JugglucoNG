package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DataSmoothing

/**
 * The Δ states a movement, so it has to read the reading the way the rest of the app
 * reasons with it.
 *
 * It used to be computed from the stored series while the notification, the trend arrow
 * and the FALLING_FAST / RISING_FAST alarms were computed from the smoothed one, so a row
 * could state a fall that nothing else on the phone agreed with (issue #187). Smoothing
 * here is deliberate — it is what stops one wild sample from deciding an arrow — and
 * "smooth only graph" is the way to opt out of it.
 */
class ReadingDeltaSmoothingTests {

    private val nowMillis = 1_700_000_000_000L
    private val minute = 60_000L

    /** Oldest-first, one reading a minute, newest at [nowMillis]. */
    private fun series(values: List<Float>): List<GlucosePoint> =
        values.mapIndexed { index, value ->
            GlucosePoint(
                value = value,
                time = "",
                timestamp = nowMillis - (values.lastIndex - index) * minute
            )
        }

    /** Flat, then one wild sample as the newest reading — the case smoothing exists for. */
    private val spikeAtTheEdge = series(listOf(100f, 100f, 100f, 100f, 100f, 100f, 130f))

    private fun deltaRate(history: List<GlucosePoint>): Float =
        readingDeltas(
            listOf(history.last().timestamp),
            history,
            isMmol = false,
            deltaIntervalMinutes = 5
        ).single()!!.rateMgdlPerMinute

    /**
     * The dashboard builds its own series; CurrentDisplaySource builds the one behind the
     * notification and the alarms. Same window in, same numbers out, or the two disagree
     * about the same reading again.
     */
    @Test
    fun theEvaluationSeriesIsTheTransformTheNotificationAndAlarmsAlreadyUse() {
        val window = 5
        val dashboard = buildSmoothedConsumerHistory(spikeAtTheEdge, window, collapseChunks = false)
        val currentDisplay = DataSmoothing.smoothNativePoints(
            spikeAtTheEdge.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) },
            window,
            false
        )

        assertEquals(currentDisplay.size, dashboard.size)
        dashboard.forEachIndexed { index, point ->
            assertEquals(currentDisplay[index].timestamp, point.timestamp)
            assertEquals(currentDisplay[index].value, point.value, 0.0001f)
        }
    }

    /** The setting has to actually arrive: a Δ that ignored it was the whole defect. */
    @Test
    fun theSmoothingSettingReachesTheDelta() {
        val measured = deltaRate(spikeAtTheEdge)
        val evaluated = deltaRate(
            buildSmoothedConsumerHistory(spikeAtTheEdge, 5, collapseChunks = false)
        )

        assertTrue("the spike still moves the Δ", evaluated > 0f)
        assertTrue("but it no longer decides it alone: $evaluated !< $measured", evaluated < measured)
    }

    /** The escape hatch: with "smooth only graph" the Δ is the measured reading again. */
    @Test
    fun smoothOnlyGraphLeavesTheDeltaOnTheMeasuredReading() {
        val window = DataSmoothing.localSmoothingMinutes(
            smoothingMinutes = 5,
            graphOnly = true,
            exchangeOutputsOnly = false
        )
        assertEquals(0, window)

        val series = buildSmoothedConsumerHistory(spikeAtTheEdge, window, collapseChunks = false)
        assertEquals(deltaRate(spikeAtTheEdge), deltaRate(series), 0.0001f)
    }

    /** Smoothing off is smoothing off, whatever the other two switches say. */
    @Test
    fun aDisabledWindowLeavesTheDeltaOnTheMeasuredReading() {
        val series = buildSmoothedConsumerHistory(spikeAtTheEdge, 0, collapseChunks = false)
        assertEquals(deltaRate(spikeAtTheEdge), deltaRate(series), 0.0001f)
    }
}
