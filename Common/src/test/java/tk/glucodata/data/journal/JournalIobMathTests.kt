package tk.glucodata.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared IOB/COB arithmetic, exercised through the entry points the watch
 * will use (plan §4 category W, D1).
 *
 * The phone's own suite is the oracle that moving this code did not change the
 * phone's numbers — `JournalIobCalculatorTests` and `OutboundApiInsulinSnapshotTests`
 * compile and pass untouched against the delegates. What those cannot do is call
 * the shared object with the watch's inputs, so this pins the contract the watch
 * depends on: a dose is `(timestamp, units, curve points)`, and carbs are
 * `(timestamp, grams, stated absorption window)`.
 *
 * The carb floor is here because moving it exposed that nothing tested it: an
 * entry whose profile-derived window is shorter than 30 minutes is clamped up to
 * 30, and without this test that clamp could change silently.
 */
class JournalIobMathTests {

    private val at = 1_700_000_000_000L
    private fun minutes(count: Long) = at + count * 60_000L

    /** Activity ramps 0 -> 1 over 30 min, back to 0 at 60. */
    private fun triangle() = listOf(
        IobCurvePoint(0, 0f),
        IobCurvePoint(30, 1f),
        IobCurvePoint(60, 0f),
    )

    @Test
    fun aFullDoseIsOnBoardAtInjectionAndGoneAtTheEndOfItsCurve() {
        val dose = IobDose(at, 10f, triangle())
        assertEquals(10f, JournalIobMath.compute(listOf(dose), at).iobUnits, 1e-4f)
        assertEquals(0f, JournalIobMath.compute(listOf(dose), minutes(60)).iobUnits, 1e-4f)
    }

    @Test
    fun iobOnlyEverFalls() {
        val dose = IobDose(at, 10f, triangle())
        var previous = Float.MAX_VALUE
        for (minute in 0L..60L) {
            val iob = JournalIobMath.compute(listOf(dose), minutes(minute)).iobUnits
            assertTrue("IOB rose at minute $minute", iob <= previous + 1e-4f)
            previous = iob
        }
    }

    @Test
    fun effectiveIobNeverExceedsIobAndPeaksWithTheCurve() {
        val dose = IobDose(at, 10f, triangle())
        for (minute in 0L..90L) {
            val result = JournalIobMath.compute(listOf(dose), minutes(minute))
            assertTrue(
                "eIOB above IOB at minute $minute",
                result.eiobUnits <= result.iobUnits + 1e-4f,
            )
        }
        val peak = JournalIobMath.compute(listOf(dose), minutes(30))
        assertEquals(peak.iobUnits, peak.eiobUnits, 1e-4f)
    }

    @Test
    fun aDoseWithNoUsableCurveCountsForNothing() {
        // A v1 journal payload carries no curves at all; the dose is still logged,
        // it just cannot say how much of it is left.
        val dose = IobDose(at, 10f, emptyList())
        assertEquals(0f, JournalIobMath.compute(listOf(dose), at).iobUnits, 1e-4f)
    }

    @Test
    fun aReadingFromBeforeTheInjectionContributesNothing() {
        val dose = IobDose(minutes(10), 10f, triangle())
        assertEquals(0f, JournalIobMath.compute(listOf(dose), at).iobUnits, 1e-4f)
    }

    @Test
    fun freshCarbsAreAllActiveAndFullyElapsedOnesAreNone() {
        val entry = CarbEntry(at, 30f, absorptionMinutes = 60f)
        val rate = { _: Long -> 35f }
        assertEquals(30f, JournalIobMath.activeCarbsGrams(listOf(entry), at, rate), 1e-4f)
        assertEquals(15f, JournalIobMath.activeCarbsGrams(listOf(entry), minutes(30), rate), 1e-4f)
        assertEquals(0f, JournalIobMath.activeCarbsGrams(listOf(entry), minutes(90), rate), 1e-4f)
    }

    @Test
    fun carbsWithoutAStatedWindowUseTheProfileAndItsThirtyMinuteFloor() {
        // 5 g at 35 g/h is under 8.6 minutes of absorption, which is faster than
        // any carb entry should be counted as, so the window is floored at 30.
        val entry = CarbEntry(at, 5f)
        val rate = { _: Long -> 35f }
        assertEquals(2.5f, JournalIobMath.activeCarbsGrams(listOf(entry), minutes(15), rate), 1e-4f)
        // The profile is asked per entry, not once: it is time-dependent.
        val asked = mutableListOf<Long>()
        JournalIobMath.activeCarbsGrams(listOf(CarbEntry(minutes(20), 5f)), at) { timestamp ->
            asked += timestamp
            35f
        }
        assertEquals(listOf(minutes(20)), asked)
    }

    @Test
    fun unusableCarbEntriesAreIgnoredRatherThanCountedAsZeroAbsorbed() {
        val rate = { _: Long -> 35f }
        val entries = listOf(
            CarbEntry(at, 0f, absorptionMinutes = 60f),
            CarbEntry(at, Float.NaN, absorptionMinutes = 60f),
            CarbEntry(at, -5f, absorptionMinutes = 60f),
        )
        assertEquals(0f, JournalIobMath.activeCarbsGrams(entries, minutes(10), rate), 1e-4f)
    }
}
