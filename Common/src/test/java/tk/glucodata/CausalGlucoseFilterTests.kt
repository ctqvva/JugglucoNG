package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The properties that make a live estimate honest: it uses only the past, it is a function of a
 * bounded window (so the live path and a later backfill agree), a lone spike does not move it, a
 * real fall is followed all the way down, and a hole restarts it instead of being bridged.
 */
class CausalGlucoseFilterTests {

    private val minute = 60_000L

    private fun times(count: Int, stepMs: Long = minute, startAt: Long = 0L) =
        LongArray(count) { startAt + it * stepMs }

    private fun estimate(t: LongArray, v: DoubleArray, at: Int = t.lastIndex) =
        CausalGlucoseFilter.estimateAt(t, v, at)!!

    @Test
    fun flatSeriesEstimatesItsOwnValue() {
        val t = times(30)
        val v = DoubleArray(30) { 120.0 }
        val e = estimate(t, v)
        assertEquals(120.0, e.mgdl, 1e-6)
        assertEquals(0.0, e.rateMgdlPerMin, 1e-6)
    }

    @Test
    fun linearRiseIsTrackedWithoutLagAfterSettling() {
        val t = times(40)
        val v = DoubleArray(40) { 100.0 + it * 1.0 }
        val e = estimate(t, v)
        assertEquals(139.0, e.mgdl, 2.0)
        assertEquals(1.0, e.rateMgdlPerMin, 0.3)
    }

    @Test
    fun futureSamplesNeverChangeAnEstimate() {
        val t = times(40)
        val v = DoubleArray(40) { 100.0 + (it % 7) * 3.0 }
        val at = 25
        val full = estimate(t, v, at)
        val truncated = CausalGlucoseFilter.estimateAt(t.copyOf(at + 1), v.copyOf(at + 1), at)!!
        assertEquals(truncated.mgdl, full.mgdl, 0.0)
        assertEquals(truncated.rateMgdlPerMin, full.rateMgdlPerMin, 0.0)
    }

    @Test
    fun samplesOlderThanTheWindowNeverChangeAnEstimate() {
        val count = 120 // two hours of one-minute samples, window is one hour
        val t = times(count)
        val a = DoubleArray(count) { 100.0 + (it % 5) * 2.0 }
        val b = a.copyOf().also { for (i in 0 until 50) it[i] = 300.0 }
        assertEquals(estimate(t, a).mgdl, estimate(t, b).mgdl, 0.0)
    }

    @Test
    fun aLoneSpikeBarelyMovesTheEstimate() {
        val t = times(31)
        val v = DoubleArray(31) { 100.0 }.also { it[29] = 160.0 }
        assertTrue(estimate(t, v, 29).mgdl < 110.0)
        assertTrue(abs(estimate(t, v, 30).mgdl - 100.0) < 5.0)
    }

    @Test
    fun aSustainedFallReachesTheLowValues() {
        val flat = 20
        val fall = 30
        val t = times(flat + fall)
        val v = DoubleArray(flat + fall) { i -> if (i < flat) 120.0 else 120.0 - (i - flat + 1) * 2.0 }
        val e = estimate(t, v)
        assertEquals(60.0, e.mgdl, 5.0)
        assertTrue(e.rateMgdlPerMin < -1.5)
    }

    @Test
    fun aHoleRestartsTheFilterAtTheFirstSampleAfterIt() {
        val before = times(20)
        val after = longArrayOf(before.last() + 30 * minute)
        val t = before + after
        val v = DoubleArray(20) { 100.0 } + doubleArrayOf(200.0)
        val e = estimate(t, v)
        assertEquals(200.0, e.mgdl, 0.0)
        assertEquals(0.0, e.rateMgdlPerMin, 0.0)
    }

    @Test
    fun invalidSamplesAreSkippedAndAnInvalidTargetHasNoEstimate() {
        val t = times(10)
        val v = DoubleArray(10) { 110.0 }.also { it[5] = 0.0; it[6] = Double.NaN }
        assertEquals(110.0, estimate(t, v).mgdl, 1e-6)
        assertNull(CausalGlucoseFilter.estimateAt(t, v, 5))
    }

    @Test
    fun laneIsUnitAgnostic() {
        val t = times(40)
        val mgdl = FloatArray(40) { 100f + (it % 6) * 4f }
        val mmol = FloatArray(40) { mgdl[it] / 18.0182f }
        val idx = (0 until 40).toList()
        val laneMgdl = CausalGlucoseFilter.filterLane(idx, { t[it] }, { mgdl[it] }, mgdlPerUnit = 1f)
        val laneMmol = CausalGlucoseFilter.filterLane(idx, { t[it] }, { mmol[it] }, mgdlPerUnit = 18.0182f)
        for (i in idx) assertEquals(laneMgdl[i] / 18.0182f, laneMmol[i], 1e-3f)
    }

    @Test
    fun laneKeepsAnInvalidPointInvalid() {
        val t = times(5)
        val v = floatArrayOf(100f, 100f, 0f, 100f, 100f)
        val lane = CausalGlucoseFilter.filterLane((0 until 5).toList(), { t[it] }, { v[it] }, 1f)
        assertEquals(0f, lane[2], 0f)
    }

    @Test
    fun aSourceChangeRestartsTheFilterInsteadOfCarryingTheOldLevel() {
        val t = times(12)
        val v = DoubleArray(12) { if (it < 6) 100.0 else 200.0 }
        val source = List(12) { if (it < 6) "A" else "B" }
        val lane = CausalGlucoseFilter.filterLane(
            (0 until 12).toList(), { t[it] }, { v[it].toFloat() }, 1f,
            sameSource = { a, b -> source[a] == source[b] },
        )
        assertEquals(200f, lane[11], 1e-3f)
    }
}
