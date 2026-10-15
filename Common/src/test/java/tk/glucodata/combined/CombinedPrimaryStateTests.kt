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
    @Test fun freshLowCalibratedReadingsRemainTheIndividualFallback() {
        val state = CombinedPrimaryState(listOf("a", "b"))
        val low = update(state, 0, 15f, 100f)!!
        assertEquals(15f, low.mgdl)
        assertEquals("a", low.fallbackSensorId)
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, state.result.status)
        val lone = state.update(listOf(pair(1, 15f)[0]), base + 60_000L, 20, 5)!!
        assertEquals(15f, lone.mgdl)
        assertEquals("a", lone.fallbackSensorId)
    }

    @Test fun mixedCadenceCallbacksAndMonitorTicksCannotLetThePrimaryExpire() {
        val state = CombinedPrimaryState(listOf("a", "b", "c"))
        val latest = mutableMapOf<String, DualSensorConsensus.Reading>()
        val blends = mutableSetOf<Int>()
        for (minute in 0..25) {
            for ((id, value) in listOf("a" to 100f, "b" to 105f, "c" to 110f)) {
                if (id == "b" && minute % 5 != 0) continue
                latest[id] = DualSensorConsensus.Reading(id, base + minute * 60_000L, value)
                val output = state.update(latest.values.toList(), base + minute * 60_000L, 20, 5)
                if (output?.blended == true) blends += minute
            }
            for (offset in listOf(15_000L, 30_000L, 45_000L)) {
                val now = base + minute * 60_000L + offset
                state.update(latest.values.toList(), now, 20, 5)
                assertTrue("primary expired at $minute:$offset", now - state.latest!!.timeMs <= DualSensorConsensus.MAX_AGE_MS)
            }
        }
        assertTrue("blends=$blends", blends.containsAll(listOf(10, 15, 20, 25)))
    }

    @Test fun waitingCannotPreserveAnExpiredPrimary() {
        val state = CombinedPrimaryState(listOf("a", "b"))
        update(state, 0)
        val now = base + 360_000L
        val partial = listOf(pair(6)[0], pair(0)[1])
        assertNull(state.update(partial, now, 20, 5)) // Exactly at the freshness boundary is still current.
        val output = state.update(partial, now + 1L, 20, 5)!!
        assertEquals(now, output.timeMs)
        assertEquals(100f, output.mgdl)
    }

}
