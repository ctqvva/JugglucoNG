package tk.glucodata.alerts

import org.junit.Assert.*
import org.junit.Test

class DualSensorConsensusTests {
    private val base = 1_000_000L
    private fun pair(minute: Int, a: Float = 100f, b: Float = 110f) = listOf(
        DualSensorConsensus.Reading("a", base + minute * 60_000L, a),
        DualSensorConsensus.Reading("b", base + minute * 60_000L, b)
    )
    private fun evaluate(engine: DualSensorConsensus, minute: Int, a: Float = 100f, b: Float = 110f) =
        engine.evaluate(pair(minute, a, b), base + minute * 60_000L, 20, 5)

    @Test fun disabledByDefaultAndNoGlucoseThresholdInEitherUnit() {
        for (mmol in listOf(false, true)) {
            val config = AlertDefaults.defaultConfig(AlertType.SENSOR_DISAGREEMENT, mmol)
            assertFalse(config.enabled)
            assertFalse(config.experimentalEstimateEnabled)
            assertNull(config.threshold)
            assertEquals(20, config.disagreementPercent)
            assertEquals(5, config.durationMinutes)
        }
    }

    @Test fun learnsBeforeProducingAnEstimateWithoutDelayingCurrentValues() {
        val engine = DualSensorConsensus()
        assertNull(evaluate(engine, 0).estimateMgdl)
        assertNull(evaluate(engine, 1).estimateMgdl)
        val result = evaluate(engine, 2, 130f, 140f)
        assertEquals(135f, result.estimateMgdl!!, 0.001f)
        assertEquals(DualSensorConsensus.Status.COMPARABLE, result.status)
    }

    @Test fun symmetricDifferenceDoesNotDependOnPrimaryOrder() {
        val readings = pair(0, 80f, 120f)
        val a = DualSensorConsensus().evaluate(readings, base, 20, 5)
        val b = DualSensorConsensus().evaluate(readings.reversed(), base, 20, 5)
        assertEquals(40f, a.differencePercent!!, 0.001f)
        assertEquals(a, b)
    }

    @Test fun timerCannotTurnOnePairIntoPersistentDisagreement() {
        val engine = DualSensorConsensus()
        val readings = pair(0, 100f, 150f)
        repeat(4) { assertFalse(engine.evaluate(readings, base + it * 60_000L, 20, 1).warningDue) }
    }

    @Test fun requiresFreshPairedEvidenceForTheWholeDuration() {
        val engine = DualSensorConsensus()
        for (minute in 0..4) assertFalse(evaluate(engine, minute, 100f, 150f).warningDue)
        val result = evaluate(engine, 5, 100f, 150f)
        assertTrue(result.warningDue)
        assertNull(result.estimateMgdl)
        assertEquals(DualSensorConsensus.Status.DISAGREEING, result.status)
    }

    @Test fun aSingleAdvancingSensorCannotCompleteTheDuration() {
        val engine = DualSensorConsensus()
        engine.evaluate(listOf(pair(0, 100f, 150f)[0], pair(1, 100f, 150f)[1]), base + 60_000L, 20, 1)
        val result = engine.evaluate(pair(1, 100f, 150f), base + 60_000L, 20, 1)
        assertFalse(result.warningDue)
    }

    @Test fun recoveryRestartsPersistenceAndImmediatelyWithholdsADisagreeingEstimate() {
        val engine = DualSensorConsensus()
        for (minute in 0..5) evaluate(engine, minute, 100f, 150f)
        assertFalse(evaluate(engine, 6).warningDue)
        assertFalse(evaluate(engine, 7, 100f, 150f).warningDue)
        assertNull(evaluate(engine, 8, 100f, 150f).estimateMgdl)
    }

    @Test fun staleFutureSkewedInvalidAndDuplicateSensorsAreUnavailable() {
        val readings = pair(0)
        val invalid = listOf(
            emptyList(), readings.take(1), readings + readings[0].copy(sensorId = "c"),
            listOf(readings[0], readings[1].copy(sensorId = "a")),
            readings.map { it.copy(mgdl = Float.NaN) },
            readings.map { it.copy(mgdl = Float.POSITIVE_INFINITY) },
            readings.map { it.copy(mgdl = 0f) }, readings.map { it.copy(mgdl = Float.MAX_VALUE) }, readings.map { it.copy(timeMs = 0) },
            listOf(readings[0].copy(timeMs = base - 60_001), readings[1]),
            readings.map { it.copy(timeMs = base + 1) }
        )
        for (input in invalid) assertEquals(DualSensorConsensus.Status.UNAVAILABLE,
            DualSensorConsensus().evaluate(input, base, 20, 5).status)
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE,
            DualSensorConsensus().evaluate(readings, base + 360_001, 20, 5).status)
    }

    @Test fun anOutageCannotCountTowardPersistenceOrLearning() {
        val engine = DualSensorConsensus()
        evaluate(engine, 0, 100f, 150f)
        assertFalse(evaluate(engine, 10, 100f, 150f).warningDue)
        assertNull(evaluate(engine, 11).estimateMgdl)
    }

    @Test fun pairAndLaneChangesResetLearnedHistory() {
        for (change in listOf<(DualSensorConsensus.Reading) -> DualSensorConsensus.Reading>(
            { it.copy(sensorId = "c") }, { it.copy(lane = 1) })) {
            val engine = DualSensorConsensus()
            for (minute in 0..2) evaluate(engine, minute)
            val input = pair(3).toMutableList().also { it[1] = change(it[1]) }
            assertNull(engine.evaluate(input, base + 180_000, 20, 5).estimateMgdl)
        }
    }

    @Test fun correctedOrBackwardsTimestampsResetHistory() {
        for (input in listOf(pair(2, 101f, 110f), pair(1))) {
            val engine = DualSensorConsensus()
            for (minute in 0..2) evaluate(engine, minute)
            assertNull(engine.evaluate(input, base + 120_000, 20, 5).estimateMgdl)
        }
    }

    @Test fun repeatedSamplesNeverSatisfyLearning() {
        val engine = DualSensorConsensus()
        repeat(10) { assertNull(engine.evaluate(pair(0), base + it * 10_000L, 20, 5).estimateMgdl) }
    }

    @Test fun quieterStreamGetsMoreWeightButCannotExcludeTheOther() {
        val engine = DualSensorConsensus()
        var result = evaluate(engine, 0, 105f, 100f)
        for (minute in 1..10) result = evaluate(engine, minute, 105f, if (minute % 2 == 0) 110f else 100f)
        assertTrue(result.estimateMgdl!! < 107.5f)
        assertTrue(result.estimateMgdl!! >= 106.25f)
        assertTrue(result.estimateMgdl!! <= 110f)
    }

    @Test fun steadyBiasIsNotAutomaticallyCorrectedAndEstimateStaysBetweenInputs() {
        val engine = DualSensorConsensus()
        for (minute in 0..10) {
            val result = evaluate(engine, minute, 100f, 120f)
            result.estimateMgdl?.let { assertEquals(110f, it, 0.001f) }
        }
    }

    @Test fun explicitResetDiscardsAllEvidence() {
        val engine = DualSensorConsensus()
        for (minute in 0..5) evaluate(engine, minute, 100f, 150f)
        engine.reset()
        assertFalse(evaluate(engine, 6, 100f, 150f).warningDue)
    }

    @Test fun fiveMinuteSensorsCanAccumulateEvidenceAndLearnBetweenUpdates() {
        val engine = DualSensorConsensus()
        evaluate(engine, 0)
        assertEquals(DualSensorConsensus.Status.LEARNING,
            engine.evaluate(pair(0), base + 4 * 60_000L, 20, 5).status)
        evaluate(engine, 5)
        assertNotNull(evaluate(engine, 10).estimateMgdl)
        engine.reset()
        evaluate(engine, 0, 100f, 150f)
        assertTrue(evaluate(engine, 5, 100f, 150f).warningDue)
    }

    @Test fun percentageSettingsAreBounded() {
        assertEquals(10, sanitizeDisagreementPercent(-1))
        assertEquals(50, sanitizeDisagreementPercent(999))
    }
}
