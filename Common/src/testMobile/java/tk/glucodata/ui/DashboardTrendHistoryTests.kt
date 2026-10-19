package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.TrendEngine

/**
 * The dashboard row arrows regress over [buildTrendHistory], not over the 10 readings the
 * card actually draws.
 *
 * TrendEngine picks its window from the sensor's cadence, so the number of points it wants
 * is a property of the sensor, not of the UI. Handing [ReadingRow] the displayed list capped
 * that at 10 regardless: enough for a 5-minute sensor (~5 points for a 20 minute window),
 * short for a 1-minute one (~12 points for a ~11.7 minute window). The dashboard row then
 * regressed over a different window than the history screen, which passes a whole day
 * section — the same reading drawing a rising arrow in one place and a falling one in the
 * other.
 *
 * These tests pin the supply guarantee rather than a specific arrow direction: the exact
 * disagreement depends on the window formula, but "never serve the estimator less than it
 * asked for" holds whatever that formula is.
 */
class DashboardTrendHistoryTests {

    private val nowMillis = 1_700_000_000_000L

    /** Oldest-first, one reading a minute — the shape [buildDisplayReadings] consumes. */
    private fun oneMinuteCadence(count: Int = 40): List<GlucosePoint> =
        (count - 1 downTo 0).map { minutesAgo ->
            GlucosePoint(
                value = 100f + minutesAgo * 0.4f,
                time = "",
                timestamp = nowMillis - minutesAgo * 60_000L
            )
        }

    private fun windowMinutesFor(points: List<GlucosePoint>): Double =
        TrendEngine.trendWindowMillis(points) / 60_000.0

    private fun spanMinutes(points: List<GlucosePoint>): Double =
        (points.first().timestamp - points.last().timestamp) / 60_000.0

    @Test
    fun trendHistoryCoversTheWindowTheEngineAsksFor() {
        val trendHistory = buildTrendHistory(oneMinuteCadence())
        val wanted = windowMinutesFor(trendHistory)
        assertTrue(
            "trend history spans ${spanMinutes(trendHistory)} min but the engine wants $wanted",
            spanMinutes(trendHistory) >= wanted
        )
    }

    @Test
    fun theDisplayedTenReadingsWouldNotHaveCoveredIt() {
        // Pins why buildTrendHistory exists at all. If a future window formula makes 10
        // points sufficient at 1-minute cadence this fails, and the extra list can go.
        val consumerHistory = oneMinuteCadence()
        val displayed = buildDisplayReadings(consumerHistory, limit = 10)
        val wanted = windowMinutesFor(buildTrendHistory(consumerHistory))
        assertTrue(
            "10 displayed readings span ${spanMinutes(displayed)} min, engine wants $wanted",
            spanMinutes(displayed) < wanted
        )
    }

    @Test
    fun displayedReadingsStayAnExactPrefixOfTrendHistory() {
        // ReadingRow slices with history.drop(index) using the *display* index, so the rows
        // drawn must line up with the head of the trend list or every row but the first
        // regresses over the wrong window.
        val consumerHistory = oneMinuteCadence()
        val displayed = buildDisplayReadings(consumerHistory, limit = 10)
        val trendHistory = buildTrendHistory(consumerHistory)

        assertTrue(trendHistory.size > displayed.size)
        displayed.forEachIndexed { index, point ->
            assertEquals(
                "row $index must be the same reading in both lists",
                point.timestamp,
                trendHistory[index].timestamp
            )
        }
    }

    @Test
    fun trendHistoryNeverExceedsWhatTheEngineCanRead() {
        // TrendEngine caps at 30 samples; carrying more is dead weight in a remember block.
        assertTrue(buildTrendHistory(oneMinuteCadence(count = 200)).size <= 30)
    }

    @Test
    fun fiveMinuteSensorsAreUnaffectedByTheChange() {
        // The starvation was always a fast-cadence problem. A 5-minute sensor's 20 minute
        // window needs ~5 points, so the old 10-point list was already sufficient and this
        // fix must not have moved its arrow at all.
        val fiveMinute = (39 downTo 0).map { i ->
            GlucosePoint(value = 100f + i * 0.4f, time = "", timestamp = nowMillis - i * 5 * 60_000L)
        }
        val displayed = buildDisplayReadings(fiveMinute, limit = 10)
        assertTrue(spanMinutes(displayed) >= windowMinutesFor(buildTrendHistory(fiveMinute)))
    }
}
