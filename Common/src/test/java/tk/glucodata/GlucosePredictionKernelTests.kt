package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.prediction.GlucosePredictionKernel
import tk.glucodata.data.prediction.GlucosePredictionKernel.Sample
import tk.glucodata.data.prediction.GlucoseTreatmentCurves

/**
 * The forward simulation, now shared with the watch. The treatment term is
 * supplied by the caller, so these pin the kernel's own behaviour: it must
 * start from the newest reading, honour the horizon, and let a modelled
 * treatment move the forecast in the right direction.
 */
class GlucosePredictionKernelTests {

    private val minute = 60_000L

    private fun flat(value: Float, count: Int = 12, startAt: Long = 0L) =
        (0 until count).map { Sample(startAt + it * minute, value) }

    private fun simulate(
        history: List<Sample>,
        momentum: Boolean = false,
        horizon: Int = 120,
        step: Int = 5,
        delta: (Long) -> Float = { 0f },
    ) = GlucosePredictionKernel.simulate(
        history = history,
        isMmol = false,
        trendMomentumEnabled = momentum,
        horizonMinutes = horizon,
        stepMinutes = step,
        targetLow = 70f,
        targetHigh = 180f,
        journalDeltaAt = delta,
    )

    @Test
    fun tooLittleHistoryPredictsNothing() {
        assertTrue(simulate(emptyList()).isEmpty())
        assertTrue(simulate(flat(120f, count = 1)).isEmpty())
    }

    @Test
    fun theForecastStartsAtTheNewestReading() {
        val history = flat(120f)
        val result = simulate(history)
        assertEquals(history.last().timestamp, result.first().timestamp)
        assertEquals(120f, result.first().value, 1e-3f)
        assertEquals(1f, result.first().confidence, 1e-6f)
    }

    @Test
    fun theHorizonAndStepDecideTheSpan() {
        val history = flat(120f)
        val result = simulate(history, horizon = 60, step = 5)
        val span = result.last().timestamp - result.first().timestamp
        assertEquals(60L * minute, span)
        assertEquals(1 + 12, result.size)
    }

    @Test
    fun confidenceDecaysAcrossTheHorizon() {
        val result = simulate(flat(120f))
        val confidences = result.map { it.confidence }
        assertTrue(confidences.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(confidences.last() < confidences.first())
    }

    @Test
    fun aRisingSeriesWithMomentumForecastsHigher() {
        val rising = (0 until 12).map { Sample(it * minute, 100f + it * 2f) }
        val withMomentum = simulate(rising, momentum = true).last().unclampedValue
        val without = simulate(rising, momentum = false).last().unclampedValue
        assertTrue("$withMomentum should exceed $without", withMomentum > without)
    }

    @Test
    fun aModelledCarbRisePushesTheForecastUp() {
        val history = flat(120f)
        val baseline = history.last().timestamp
        val plain = simulate(history).last().unclampedValue
        val withCarbs = simulate(history) { at ->
            GlucoseTreatmentCurves.carbRise(
                grams = 40f,
                startMillis = baseline,
                baselineMillis = baseline,
                atMillis = at,
                absorptionMinutes = 90f,
                carbRatioGramsPerUnit = 10f,
                sensitivityDisplay = 54f,
            )
        }.last().unclampedValue
        assertTrue("$withCarbs should exceed $plain", withCarbs > plain)
    }

    @Test
    fun aModelledInsulinDosePushesTheForecastDown() {
        val history = flat(200f)
        val baseline = history.last().timestamp
        val minutes = intArrayOf(0, 30, 60, 120, 180, 240)
        val activity = floatArrayOf(0f, 1f, 1.4f, 0.8f, 0.3f, 0f)
        val plain = simulate(history).last().unclampedValue
        val withInsulin = simulate(history) { at ->
            val future = GlucoseTreatmentCurves.cumulativeCurveFraction(minutes, activity, baseline, at)
            -(4f * 54f * future)
        }.last().unclampedValue
        assertTrue("$withInsulin should be under $plain", withInsulin < plain)
    }

    @Test
    fun theDrawnValueSaturatesButTheDoseValueDoesNot() {
        // A large dose forecasts below the display floor. The drawn value clamps
        // so the curve stays on the chart; the unclamped one must not, or two
        // very different doses suggest the same correction.
        val history = flat(120f)
        val huge = simulate(history) { -400f }.last()
        assertEquals(18f, huge.value, 1e-3f)
        assertTrue("unclamped ${huge.unclampedValue} should stay below the floor", huge.unclampedValue < 18f)
    }

    @Test
    fun curveFractionIsBoundedAndMonotonic() {
        val minutes = intArrayOf(0, 30, 60, 120, 240)
        val activity = floatArrayOf(0f, 1f, 1.4f, 0.6f, 0f)
        val at = { m: Long -> GlucoseTreatmentCurves.cumulativeCurveFraction(minutes, activity, 0L, m * minute) }
        assertEquals(0f, at(0), 1e-6f)
        assertEquals(1f, at(240), 1e-3f)
        assertEquals(1f, at(600), 1e-3f)
        assertTrue((0L..240L step 20).map(at).zipWithNext().all { (a, b) -> b >= a - 1e-6f })
    }

    @Test
    fun aCurveTooShortToIntegrateContributesNothing() {
        assertEquals(0f, GlucoseTreatmentCurves.cumulativeCurveFraction(intArrayOf(0), floatArrayOf(1f), 0L, minute), 1e-6f)
        assertEquals(0f, GlucoseTreatmentCurves.cumulativeCurveFraction(IntArray(0), FloatArray(0), 0L, minute), 1e-6f)
    }
}
