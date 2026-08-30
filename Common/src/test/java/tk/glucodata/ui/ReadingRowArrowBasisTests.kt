package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.TrendEngine

/**
 * A reading row states one movement twice: as a number and as an arrow. It used to state it
 * from two different windows — the arrow regressed over the trend engine's window, the Δ
 * covered the configured interval — so during a reversal a row could read "Δ −5" beside an
 * arrow pointing up. Both were right about their own question; together they were wrong.
 */
class ReadingRowArrowBasisTests {

    private val nowMillis = 1_700_000_000_000L
    private val minute = 60_000L

    /** Oldest-first, as the pipeline hands history over. */
    private fun history(vararg valuesNewestLast: Pair<Long, Float>): List<GlucosePoint> =
        valuesNewestLast.map { (minutesAgo, value) ->
            GlucosePoint(value = value, time = "", timestamp = nowMillis - minutesAgo * minute)
        }.sortedBy { it.timestamp }

    private fun regressed(velocity: Float) =
        TrendEngine.TrendResult(TrendEngine.stateFor(velocity), velocity, 0f, 1f, 0f)

    /** The field case: a fall that the 20-minute regression still reads as a climb. */
    @Test
    fun theArrowFollowsTheDeltaBesideIt() {
        // Climbed for a quarter of an hour, then turned: the regression is still positive
        // while the last five minutes are clearly down.
        val points = history(
            20L to 200f, 15L to 220f, 10L to 240f, 5L to 256f, 0L to 235f,
        )
        val deltas = readingDeltas(listOf(nowMillis), points, isMmol = false, deltaIntervalMinutes = 5)

        val delta = deltas.single()
        assertTrue("the row states a fall", delta!!.text.startsWith("−"))
        // Same movement as a rate: 21 mg/dL down over five minutes.
        assertEquals(-21f / 5f, delta.rateMgdlPerMinute, 0.001f)

        val climbing = regressed(velocity = 2.4f)
        val shown = trendResultForDisplayedDelta(climbing, delta.rateMgdlPerMinute)

        assertTrue("the arrow may not point up beside a fall", shown.velocity < 0f)
        assertEquals(TrendEngine.TrendState.DoubleDown, shown.state)
    }

    /** Without a Δ for the row there is no second claim, so the regression stands. */
    @Test
    fun withoutADeltaTheRegressionIsKept() {
        val climbing = regressed(velocity = 2.4f)

        assertSame(climbing, trendResultForDisplayedDelta(climbing, null))
        assertSame(climbing, trendResultForDisplayedDelta(climbing, Float.NaN))
    }

    /** The reported field case: the hero regresses down while its displayed Δ is positive. */
    @Test
    fun theHeroArrowFollowsItsDisplayedPositiveDelta() {
        val points = history(5L to 207.9f, 0L to 214f)
        val delta = readingDeltas(
            listOf(nowMillis),
            points,
            isMmol = false,
            deltaIntervalMinutes = 5,
        ).single()!!

        assertEquals("+6.1", delta.text)
        val shown = trendResultForDisplayedDelta(regressed(velocity = -2.4f), delta.rateMgdlPerMinute)

        assertEquals(1.22f, shown.velocity, 0.001f)
        assertEquals(TrendEngine.TrendState.SingleUp, shown.state)
    }

    @Test
    fun theRateIsMilligramsPerMinuteWhateverTheScreenShows() {
        // 1.1 mmol/L over five minutes is 19.8 mg/dL over five minutes.
        val points = history(5L to 8.0f, 0L to 9.1f)

        val mmol = readingDeltas(listOf(nowMillis), points, isMmol = true, deltaIntervalMinutes = 5).single()

        assertEquals(1.1f * tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL / 5f, mmol!!.rateMgdlPerMinute, 0.05f)
        assertTrue("the text stays in the unit on screen", mmol.text.contains("1"))
    }

    /** A one-minute interval is a rate over one minute, not over five. */
    @Test
    fun theIntervalSettingIsPartOfTheRate() {
        val points = history(2L to 100f, 1L to 104f, 0L to 108f)

        val perMinute = readingDeltas(listOf(nowMillis), points, isMmol = false, deltaIntervalMinutes = 1).single()

        assertEquals(4f, perMinute!!.rateMgdlPerMinute, 0.001f)
    }

    /** The texts are the same ones as before; only their source is now shared. */
    @Test
    fun theTextsAreUnchangedByCarryingARate() {
        val points = history(20L to 200f, 15L to 220f, 10L to 240f, 5L to 256f, 0L to 235f)
        val anchors = listOf(nowMillis, nowMillis - 5 * minute, nowMillis - 10 * minute)

        val texts = readingDeltaTexts(anchors, points, isMmol = false, deltaIntervalMinutes = 5)
        val fromDeltas = readingDeltas(anchors, points, isMmol = false, deltaIntervalMinutes = 5).map { it?.text }

        assertEquals(texts, fromDeltas)
    }
}
