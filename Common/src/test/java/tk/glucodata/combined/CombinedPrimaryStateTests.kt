package tk.glucodata.combined

import org.junit.Assert.*
import org.junit.Test
import tk.glucodata.alerts.DualSensorConsensus

class CombinedPrimaryStateTests {
    private val base = 2_000_000_000L
    private fun pair(minute: Int, a: Float = 100f, b: Float = 110f) = listOf(
        DualSensorConsensus.Reading("a", base + minute * 60_000L, a),
        DualSensorConsensus.Reading("b", base + minute * 60_000L, b))
    private fun update(state: CombinedPrimaryState, minute: Int, a: Float = 100f, b: Float = 110f) =
        state.update(pair(minute, a, b), base + minute * 60_000L, 20, 5)

    @Test fun learnsUsingTheFirstInputThenProducesOneCombinedPrimary() {
        val state = CombinedPrimaryState(listOf("a", "b"))
        assertEquals(100f, update(state, 0)!!.mgdl)
        assertFalse(state.latest!!.blended)
        update(state, 1)
        assertEquals(105f, update(state, 2)!!.mgdl, 0.001f)
        assertTrue(state.latest!!.blended)
        assertNull(state.latest!!.fallbackSensorId)
        assertTrue(state.latest!!.rate.isNaN())
        assertEquals(1f, update(state, 3, 101f, 111f)!!.rate, 0.001f)
    }

    @Test fun nonPrimaryInputCompletesThePairWithoutPublishingAnIntermediateValue() {
        val state = CombinedPrimaryState(listOf("a", "b"))
        for (minute in 0..2) update(state, minute)
        val old = state.latest
        val partial = listOf(pair(7, 110f)[0], pair(2)[1])
        assertNull(state.update(partial, base + 420_000L, 20, 5))
        assertSame(old, state.latest)
        val completed = listOf(partial[0], pair(7, b = 120f)[1].copy(timeMs = base + 450_000L))
        val output = state.update(completed, base + 450_000L, 20, 5)!!
        assertTrue(output.blended)
        assertEquals(base + 420_000L, output.timeMs)
        assertNull(state.update(completed, base + 460_000L, 20, 5))
    }

    @Test fun disagreementUsesOneFreshSourceAndResetsTheRateAcrossCompositionChanges() {
        val state = CombinedPrimaryState(listOf("a", "b"))
        for (minute in 0..2) update(state, minute)
        val output = update(state, 3, 100f, 160f)!!
        assertEquals(100f, output.mgdl)
        assertFalse(output.blended)
        assertEquals("a", output.fallbackSensorId)
        assertTrue(output.rate.isNaN())
    }

    @Test fun staleFirstInputUsesTheSecondAndNoFreshInputProducesNoNewOutput() {
        val state = CombinedPrimaryState(listOf("a", "b"))
        val initial = pair(0)
        state.update(initial, base, 20, 5)
        val lone = listOf(initial[0], pair(10, b = 125f)[1])
        val output = state.update(lone, base + 600_000L, 20, 5)!!
        assertEquals(125f, output.mgdl)
        assertEquals("b", output.fallbackSensorId)
        assertTrue(output.rate.isNaN())
        assertNull(state.update(lone, base + 1_000_000L, 20, 5))
    }

    @Test fun futureOrUnrelatedSourcesNeverBecomeFallbacks() {
        val state = CombinedPrimaryState(listOf("a", "b"))
        assertNull(state.update(pair(1), base, 20, 5))
        assertNull(state.update(pair(0).map { it.copy(sensorId = "c") }, base, 20, 5))
    }

    @Test fun restoredOutputCannotBeBackdatedOrRepublished() {
        val state = CombinedPrimaryState(listOf("a", "b"))
        val stored = CombinedPrimaryState.Output(base + 120_000L, 105f, 0f, true)
        state.restore(stored)
        assertNull(update(state, 0))
        assertNull(update(state, 1))
        assertNull(update(state, 2))
        assertSame(stored, state.latest)
        assertTrue(update(state, 3)!!.blended)
    }

    @Test fun recentNearbyCalibrationAnchorInfluencesTheBlendWithoutOverridingItsBounds() {
        fun estimate(ageMs: Long, reference: Float): Float {
            val state = CombinedPrimaryState(listOf("a", "b"))
            for (minute in 0..2) state.update(pair(minute).map {
                if (it.sensorId == "a") it.copy(calibrationTimeMs = base - ageMs, calibrationReferenceMgdl = reference) else it
            }, base + minute * 60_000L, 20, 5)
            return state.latest!!.mgdl
        }
        val recent = estimate(0, 100f)
        assertTrue(recent in 102.5f..105f)
        assertTrue(recent < estimate(24 * 60 * 60_000L, 100f))
        assertTrue(recent < estimate(0, 300f))
        assertEquals(105f, estimate(-600_000L, 100f), 0.001f)
    }

    @Test fun calibrationChangesRestartLearningWithoutRewritingTheLastPublishedRecord() {
        val state = CombinedPrimaryState(listOf("a", "b"))
        for (minute in 0..2) update(state, minute)
        val previous = state.latest
        state.resetLearning()
        assertSame(previous, state.latest)
        assertFalse(update(state, 3)!!.blended)
    }
}
