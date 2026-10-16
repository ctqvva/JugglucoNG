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

    private fun staggeredPair(minute: Int, secondValue: Float = 110f) = pair(minute, 100f, secondValue)
        .mapIndexed { index, reading -> if (index == 1) reading.copy(timeMs = reading.timeMs + 30_000L) else reading }

    @Test fun staggeredFiveMinuteUpdatesKeepPairedEvidenceForTheWarning() {
        for (leadingIndex in 0..1) {
            val engine = DualSensorConsensus()
            var readings = staggeredPair(0, 150f)
            assertFalse(engine.evaluate(readings, base + 30_000L, 20, 5).warningDue)
            for (minute in listOf(5, 10, 15)) {
                val completed = staggeredPair(minute, 150f)
                readings = readings.toMutableList().also { it[leadingIndex] = completed[leadingIndex] }
                repeat(2) { tick ->
                    val waiting = engine.evaluate(readings, completed[leadingIndex].timeMs + tick * 15_000L, 20, 5)
                    assertEquals(DualSensorConsensus.Status.WAITING_FOR_PEER, waiting.status)
                    assertNull(waiting.estimateMgdl)
                    assertFalse(waiting.warningDue)
                }
                readings = completed
                assertTrue("Warning after completed pair, leading sensor $leadingIndex, minute $minute",
                    engine.evaluate(readings, readings.maxOf { it.timeMs } + 30_000L, 20, 5).warningDue)
            }
        }
    }

    @Test fun staggeredFiveMinuteUpdatesLearnAndHideTheEstimateUntilThePeerArrives() {
        for (leadingIndex in 0..1) {
            val engine = DualSensorConsensus()
            var readings = staggeredPair(0)
            engine.evaluate(readings, base + 30_000L, 20, 5)
            for (minute in listOf(5, 10, 15)) {
                val completed = staggeredPair(minute)
                readings = readings.toMutableList().also { it[leadingIndex] = completed[leadingIndex] }
                repeat(2) { tick ->
                    assertNull(engine.evaluate(readings, completed[leadingIndex].timeMs + tick * 15_000L, 20, 5).estimateMgdl)
                }
                readings = completed
                val result = engine.evaluate(readings, readings.maxOf { it.timeMs } + 30_000L, 20, 5)
                if (minute < 10) assertNull(result.estimateMgdl)
                else assertEquals(105f, result.estimateMgdl!!, 0.001f)
            }
        }
    }

    @Test fun aPartialUpdateWithinTheTimingLimitCannotProveRecoveryOrProvideAnEstimate() {
        val engine = DualSensorConsensus()
        engine.evaluate(pair(0, 100f, 150f), base, 20, 1)
        val partial = listOf(pair(1, 150f, 150f)[0], pair(0, 100f, 150f)[1])
        val waiting = engine.evaluate(partial, base + 60_000L, 20, 1)
        assertEquals(DualSensorConsensus.Status.WAITING_FOR_PEER, waiting.status)
        assertNull(waiting.differencePercent)
        val completed = listOf(partial[0], partial[1].copy(timeMs = base + 90_000L, mgdl = 225f))
        assertTrue(engine.evaluate(completed, base + 90_000L, 20, 1).warningDue)

        engine.reset()
        for (minute in 0..2) evaluate(engine, minute)
        val updating = listOf(pair(3, 105f)[0], pair(2)[1])
        assertNull(engine.evaluate(updating, base + 180_000L, 20, 5).estimateMgdl)
        assertNotNull(evaluate(engine, 3, 105f).estimateMgdl)
    }

    @Test fun waitingForPeerExpiresWithoutBridgingAMissedCycle() {
        val engine = DualSensorConsensus()
        val readings = staggeredPair(0, 150f)
        engine.evaluate(readings, base + 30_000L, 20, 5)
        val pending = listOf(staggeredPair(5, 150f)[0], readings[1])
        assertEquals(DualSensorConsensus.Status.WAITING_FOR_PEER,
            engine.evaluate(pending, base + 300_000L, 20, 5).status)
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE,
            engine.evaluate(pending, base + 390_001L, 20, 5).status)
        assertFalse(engine.evaluate(staggeredPair(10, 150f), base + 630_000L, 20, 5).warningDue)
    }

    @Test fun incompletePairsCannotHideInvalidationOfAcceptedEvidence() {
        for (change in listOf<(DualSensorConsensus.Reading) -> DualSensorConsensus.Reading>(
            { it.copy(sensorId = "c") }, { it.copy(lane = 1) },
            { it.copy(mgdl = 151f) }, { it.copy(timeMs = it.timeMs - 1) },
            { it.copy(mgdl = Float.NaN) }
        )) {
            val engine = DualSensorConsensus()
            val previous = staggeredPair(0, 150f)
            engine.evaluate(previous, base + 30_000L, 20, 5)
            val pending = listOf(staggeredPair(5, 150f)[0], change(previous[1]))
            assertEquals(DualSensorConsensus.Status.UNAVAILABLE,
                engine.evaluate(pending, base + 300_000L, 20, 5).status)
            assertFalse(engine.evaluate(staggeredPair(5, 150f), base + 330_000L, 20, 5).warningDue)
        }
    }

    @Test fun twoNewButSkewedSamplesResetRatherThanResumeAnOldRun() {
        val engine = DualSensorConsensus()
        engine.evaluate(staggeredPair(0, 150f), base + 30_000L, 20, 5)
        val input = listOf(staggeredPair(5, 150f)[0], staggeredPair(3, 150f)[1])
        assertEquals(DualSensorConsensus.Status.UNAVAILABLE,
            engine.evaluate(input, base + 300_000L, 20, 5).status)
        assertFalse(engine.evaluate(staggeredPair(5, 150f), base + 330_000L, 20, 5).warningDue)
    }

    @Test fun percentageSettingsAreBounded() {
        assertEquals(10, sanitizeDisagreementPercent(-1))
        assertEquals(50, sanitizeDisagreementPercent(999))
    }
}
