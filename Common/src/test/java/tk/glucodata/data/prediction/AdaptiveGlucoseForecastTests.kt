package tk.glucodata.data.prediction

import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive forecast's contract: it learns only from the past, needs two days before it
 * answers, never reads across a sensor change, survives a save and load, and on data with a
 * learnable pattern does better than assuming no change.
 */
class AdaptiveGlucoseForecastTests {

    private val utc = TimeZone.getTimeZone("UTC")
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val start = 1_790_000_000_000L

    /**
     * Five-minute readings of a mean-reverting process with momentum: glucose drifts toward 130
     * and a move tends to continue for a while. A forecaster that learns those two facts beats
     * "no change"; one that cannot learn does not.
     */
    private fun history(days: Double, seed: Int = 1, sensorChangeAt: Long? = null): AdaptiveHistory {
        val rng = Random(seed)
        val n = (days * 24 * 12).toInt()
        val times = LongArray(n) { start + it * 5 * minute }
        val values = FloatArray(n)
        var g = 130.0
        var v = 0.0
        for (i in 0 until n) {
            v = 0.85 * v + 0.02 * (130.0 - g) + rng.nextDouble(-2.5, 2.5) + 1.5 * sin(2 * PI * i / 288.0)
            g += v
            values[i] = g.toFloat().coerceIn(40f, 400f)
        }
        val sensors = times.map { if (sensorChangeAt != null && it >= sensorChangeAt) "B" else "A" }
        return AdaptiveHistory.of(times, values, sensors)
    }

    @Test
    fun noModelBeforeTwoDaysOfHistory() {
        val h = history(days = 1.5)
        assertNull(AdaptiveGlucoseForecast.fit(h, h.times.last(), utc))
        assertNotNull(AdaptiveGlucoseForecast.fit(history(days = 2.5), start + (2.5 * day).toLong(), utc))
    }

    @Test
    fun itLearnsOnlyFromOutcomesBeforeNow() {
        val now = start + 5 * day
        val past = history(days = 6.0)
        // Same past, a different future after `now`: the fitted model must be identical.
        val n = past.size
        val alteredValues = FloatArray(n) { if (past.times[it] <= now) past.mgdl[it] else past.mgdl[it] * 0.5f + 60f }
        val altered = AdaptiveHistory(past.times, alteredValues, past.segment)
        val a = AdaptiveGlucoseForecast.fit(past, now, utc)!!
        val b = AdaptiveGlucoseForecast.fit(altered, now, utc)!!
        assertEquals(a.encode(), b.encode())
    }

    @Test
    fun itBeatsNoChangeOnALearnablePattern() {
        val train = history(days = 10.0, seed = 3)
        val now = train.times.last()
        val model = AdaptiveGlucoseForecast.fit(train, now, utc)!!
        assertTrue("holdout checked on 10 days", model.holdoutRmse.isFinite())
        assertTrue(model.reliable)

        // Score on fresh data from the same process, never seen in training.
        val test = history(days = 3.0, seed = 9)
        val k30 = model.horizons.indexOf(30)
        var sseModel = 0.0
        var ssePersistence = 0.0
        var n = 0
        for (i in 24 until test.size - 6) {
            val t = test.times[i]
            val deltas = AdaptiveGlucoseForecast.forecastDeltas(model, test, t, utc) ?: continue
            val actual = test.mgdl[i + 6] - test.mgdl[i]
            sseModel += (deltas[k30] - actual).let { it * it }
            ssePersistence += actual.toDouble() * actual
            n++
        }
        assertTrue(n > 500)
        assertTrue("model ${sqrt(sseModel / n)} vs no change ${sqrt(ssePersistence / n)}", sseModel < ssePersistence * 0.9)
    }

    @Test
    fun featuresNeverReachAcrossASensorChange() {
        val change = start + 3 * day
        val h = history(days = 4.0, sensorChangeAt = change)
        val model = AdaptiveGlucoseForecast.fit(h, start + 4 * day - 10 * minute, utc)!!
        // Within the new sensor's first hour the past hour belongs to the old sensor: no answer.
        assertNull(AdaptiveGlucoseForecast.forecastDeltas(model, h, change + 30 * minute, utc))
        // After an hour on the new sensor, it answers again.
        assertNotNull(AdaptiveGlucoseForecast.forecastDeltas(model, h, change + 65 * minute, utc))
    }

    @Test
    fun aGapLeavesNoFeaturesUntilAnHourOfReadingsIsBack() {
        val h = history(days = 3.0)
        val gapStart = start + 2 * day
        val keep = h.times.indices.filter { h.times[it] !in gapStart..(gapStart + 3 * 60 * minute) }
        val gapped = AdaptiveHistory(
            LongArray(keep.size) { h.times[keep[it]] }, FloatArray(keep.size) { h.mgdl[keep[it]] }, IntArray(keep.size) { h.segment[keep[it]] },
        )
        val model = AdaptiveGlucoseForecast.fit(history(days = 3.0), start + 3 * day - 10 * minute, utc)!!
        val firstAfter = gapStart + 3 * 60 * minute + 5 * minute
        assertNull(AdaptiveGlucoseForecast.forecastDeltas(model, gapped, firstAfter, utc))
        assertNotNull(AdaptiveGlucoseForecast.forecastDeltas(model, gapped, firstAfter + 60 * minute, utc))
    }

    @Test
    fun aHoleInsideTheHourIsCaughtEvenWhenEverySpotLookupLands() {
        // Readings at t−60 and from t−35 on: the 5/15/30/60-minute lookups all find values, but
        // 25 minutes of the hour are missing, so the features must not be formed.
        val h = history(days = 3.0)
        val t = h.times[h.size - 40]
        val keep = h.times.indices.filter { h.times[it] <= t - 60 * minute || h.times[it] >= t - 35 * minute }
        val holed = AdaptiveHistory(
            LongArray(keep.size) { h.times[keep[it]] }, FloatArray(keep.size) { h.mgdl[keep[it]] }, IntArray(keep.size) { h.segment[keep[it]] },
        )
        val model = AdaptiveGlucoseForecast.fit(h, h.times.last(), utc)!!
        assertNotNull(AdaptiveGlucoseForecast.forecastDeltas(model, h, t, utc))
        assertNull(AdaptiveGlucoseForecast.forecastDeltas(model, holed, t, utc))
    }

    @Test
    fun aSavedModelLoadsBackTheSame() {
        val model = AdaptiveGlucoseForecast.fit(history(days = 5.0), start + 5 * day - 10 * minute, utc)!!
        val loaded = AdaptiveForecastModel.decode(model.encode())!!
        assertEquals(model.encode(), loaded.encode())
        val h = history(days = 5.0)
        val t = h.times[h.size - 30]
        assertEquals(
            AdaptiveGlucoseForecast.forecastDeltas(model, h, t, utc)!!.toList(),
            AdaptiveGlucoseForecast.forecastDeltas(loaded, h, t, utc)!!.toList(),
        )
        assertNull("another version is not loaded", AdaptiveForecastModel.decode(model.encode().replaceFirst("1|", "99|")))
        assertNull(AdaptiveForecastModel.decode("garbage"))
    }
}
