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
}
