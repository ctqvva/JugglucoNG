package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test

class SensorGroupConsensusTests {
    private val base = 1_800_000_000_000L
    private fun readings(minute: Int, count: Int = 3) = (0 until count).map {
        DualSensorConsensus.Reading("sensor-$it", base + minute * 60_000L, 100f + it * 5)
    }

    @Test fun threeAndFourSensorsLearnWithoutDiscardingAnyContributor() {
        for (count in 3..4) {
            val engine = SensorGroupConsensus()
            for (minute in 0..2) {
                val result = engine.evaluate(readings(minute, count), base + minute * 60_000L, 20, 5)
                if (minute < 2) assertNull(result.estimateMgdl)
                else assertEquals(100f + (count - 1) * 2.5f, result.estimateMgdl!!, 0.001f)
            }
        }
    }

    @Test fun everyContributorMustAdvanceBeforeAnotherGroupEstimate() {
        val engine = SensorGroupConsensus()
        for (minute in 0..2) engine.evaluate(readings(minute), base + minute * 60_000L, 20, 5)
        val previous = readings(2)
        val next = readings(7)
        for (advanced in 1..2) {
            val result = engine.evaluate(next.take(advanced) + previous.drop(advanced), base + 420_000L, 20, 5)
            assertEquals(DualSensorConsensus.Status.WAITING_FOR_PEER, result.status)
            assertNull(result.estimateMgdl)
        }
        assertEquals(105f, engine.evaluate(next, base + 420_000L, 20, 5).estimateMgdl!!, 0.001f)
    }

    @Test fun oneDisagreeingSensorWithholdsTheBlendAndCanRaiseTheWarning() {
        val engine = SensorGroupConsensus()
        for (minute in 0..5) {
            val samples = readings(minute).mapIndexed { i, reading -> if (i == 2) reading.copy(mgdl = 170f) else reading }
            val result = engine.evaluate(samples, base + minute * 60_000L, 20, 5)
            assertEquals(DualSensorConsensus.Status.DISAGREEING, result.status)
            assertNull(result.estimateMgdl)
            assertEquals(minute == 5, result.warningDue)
            assertEquals("sensor-0", result.first!!.sensorId)
            assertEquals("sensor-2", result.second!!.sensorId)
        }
    }

    @Test fun staleMissingAndDuplicateGroupsCannotRetainEvidence() {
        val engine = SensorGroupConsensus()
        for (minute in 0..2) engine.evaluate(readings(minute), base + minute * 60_000L, 20, 5)
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, engine.evaluate(readings(2), base + 480_001L, 20, 5).status)
        assertNull(engine.evaluate(readings(9), base + 540_000L, 20, 5).estimateMgdl)
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, engine.evaluate(readings(9) + readings(9).first(), base + 540_000L, 20, 5).status)
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, engine.evaluate(readings(9).take(1), base + 540_000L, 20, 5).status)
    }
    @Test fun mixedCadencesCompleteGroupObservationsInEveryCallbackOrder() {
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) {
            val engine = SensorGroupConsensus()
            val latest = mutableMapOf<Int, DualSensorConsensus.Reading>()
            for (minute in 0..20) {
                var completed = false
                for (index in order) {
                    if (index == 1 && minute % 5 != 0) continue
                    latest[index] = readings(minute)[index]
                    val result = engine.evaluate(latest.values.toList(), base + minute * 60_000L, 20, 5)
                    if (result.estimateMgdl != null) completed = true
                }
                if (minute >= 10 && minute % 5 == 0) assertTrue("order=$order minute=$minute", completed)
                repeat(3) { tick -> engine.evaluate(latest.values.toList(), base + minute * 60_000L + (tick + 1) * 15_000L, 20, 5) }
            }
        }
    }

    @Test fun mixedCadenceDisagreementStillAccumulatesCompletedGroupEvidence() {
        val engine = SensorGroupConsensus()
        val latest = mutableMapOf<Int, DualSensorConsensus.Reading>()
        var warned = false
        for (minute in 0..15) for (index in 0..2) {
            if (index == 1 && minute % 5 != 0) continue
            latest[index] = readings(minute)[index].let { if (index == 1) it.copy(mgdl = 170f) else it }
            if (engine.evaluate(latest.values.toList(), base + minute * 60_000L, 20, 5).warningDue) warned = true
        }
        assertTrue(warned)
    }

    @Test fun aRejectedGroupCannotLeaveHalfAdvancedPairEngines() {
        val engine = SensorGroupConsensus()
        engine.evaluate(readings(0), base, 20, 5)
        val skewed = listOf(readings(3)[0], readings(1)[1], readings(3)[2])
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE, engine.evaluate(skewed, base + 180_000L, 20, 5).status)
        for (minute in 4..6) engine.evaluate(readings(minute), base + minute * 60_000L, 20, 5)
        assertNotNull(engine.evaluate(readings(6), base + 360_000L, 20, 5).estimateMgdl)
    }

}
