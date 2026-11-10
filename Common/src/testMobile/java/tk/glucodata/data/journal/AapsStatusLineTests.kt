package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.data.journal.AapsStatusLine.Observed
import tk.glucodata.data.journal.AapsStatusLine.Segment

/** The temp basal AAPS puts at the head of the status line it broadcasts for xDrip. */
class AapsStatusLineTests {

    @Test
    fun anAbsoluteRateLeadsTheLine() {
        assertEquals(Observed.Rate(0.85f), AapsStatusLine.parse("0.85 U/h 2.09U(2.88|-0.79) -1.20 17g"))
    }

    @Test
    fun aLocalisedRateWithADecimalCommaAndAWordUnit() {
        assertEquals(Observed.Rate(1.4f), AapsStatusLine.parse("1,40 ед/ч 2,09U(2,88|-0,79) -1,20 17g"))
        assertEquals(Observed.Rate(0f), AapsStatusLine.parse("0,00 IE/h 0,31IE 0g"))
    }

    @Test
    fun aDisabledLoopLineComesFirst() {
        assertEquals(Observed.Rate(0.5f), AapsStatusLine.parse("Disabled loop\n0.50 U/h 1.00U 0g"))
    }

    /** No temp basal running: the line opens with IOB, whose unit is glued to the number. */
    @Test
    fun aLineWithoutATempBasalStartsWithIob() {
        assertEquals(Observed.None, AapsStatusLine.parse("2,09U(2,88|-0,79) -1,20 17g"))
        assertEquals(Observed.None, AapsStatusLine.parse("2.09U 17g"))
        assertEquals(Observed.None, AapsStatusLine.parse(""))
    }

    @Test
    fun aPercentPumpIsRecognisedButHasNoRate() {
        assertEquals(Observed.Percent, AapsStatusLine.parse("120% 2.09U 17g"))
    }

    private val t0 = 1_700_000_000_000L
    private fun minutes(m: Int) = t0 + m * 60_000L

    @Test
    fun theFirstRateOpensASegment() {
        val step = AapsStatusLine.advance(null, Observed.Rate(0.8f), minutes(0))
        assertNull(step.closed)
        assertEquals(Segment(minutes(0), 0.8f, minutes(0)), step.current)
    }

    @Test
    fun theSameRateExtendsTheSegment() {
        val running = Segment(minutes(0), 0.8f, minutes(5))
        val step = AapsStatusLine.advance(running, Observed.Rate(0.8f), minutes(10))
        assertNull(step.closed)
        assertEquals(Segment(minutes(0), 0.8f, minutes(10)), step.current)
    }

    /** A new rate ends the old segment where the new one starts, so the bars meet. */
    @Test
    fun aNewRateClosesTheOldSegmentAtItsStart() {
        val running = Segment(minutes(0), 0.8f, minutes(5))
        val step = AapsStatusLine.advance(running, Observed.Rate(1.2f), minutes(10))
        assertEquals(Segment(minutes(0), 0.8f, minutes(10)), step.closed)
        assertEquals(Segment(minutes(10), 1.2f, minutes(10)), step.current)
    }

    @Test
    fun noTempBasalClosesTheSegment() {
        val running = Segment(minutes(0), 0.8f, minutes(5))
        val step = AapsStatusLine.advance(running, Observed.None, minutes(10))
        assertEquals(Segment(minutes(0), 0.8f, minutes(10)), step.closed)
        assertNull(step.current)
    }

    /** Juggluco missed the updates in between: nothing says the rate ran through the gap. */
    @Test
    fun aLongSilenceLeavesTheSegmentWhereItWasLastSeen() {
        val running = Segment(minutes(0), 0.8f, minutes(5))
        val step = AapsStatusLine.advance(running, Observed.Rate(0.8f), minutes(60))
        assertNull(step.closed)
        assertEquals(Segment(minutes(60), 0.8f, minutes(60)), step.current)
    }

    @Test
    fun theStoredDurationRoundsUpToAWholeMinute() {
        assertEquals(1, Segment(minutes(0), 0.8f, minutes(0)).durationMinutes)
        assertEquals(6, Segment(minutes(0), 0.8f, minutes(5) + 1).durationMinutes)
    }
}
