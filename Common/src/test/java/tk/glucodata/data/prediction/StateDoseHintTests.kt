package tk.glucodata.data.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.GlucosePoint

/**
 * The field case the state-based hint exists for: three readings minutes apart, the old
 * forecast-endpoint suggestion flipping from "carbs: 2 g" to "insulin: 0.2 U" in twenty
 * seconds, both amounts below anything anyone can act on.
 */
class StateDoseHintTests {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val maxAge = 15 * minute

    private val parameters = PredictionModelParameters(
        carbRatioGramsPerUnit = 10f,
        insulinSensitivityMgDlPerUnit = 54f
    )

    private val fieldParameters = PredictionModelParameters(
        carbRatioGramsPerUnit = 12f,
        insulinSensitivityMgDlPerUnit = 42f
    )

    /** Readings every five minutes over [spanMinutes], ending now at [endValue]. */
    private fun history(
        endValue: Float,
        slopePerMinute: Float,
        spanMinutes: Int = 30
    ): List<GlucosePoint> {
        val steps = spanMinutes / 5
        return (steps downTo 0).map { stepsAgo ->
            val minutesAgo = stepsAgo * 5
            GlucosePoint(
                value = endValue - slopePerMinute * minutesAgo,
                time = "",
                timestamp = now - minutesAgo * minute
            )
        }
    }

    /** The reported turn: an older fall followed by the nine one-minute field readings. */
    private fun reversalHistory(oldestValue: Float = 185f): List<GlucosePoint> {
        val earlierFall = (30 downTo 9).map { minutesAgo ->
            val fractionFromOldest = (30 - minutesAgo) / 21f
            GlucosePoint(
                value = oldestValue + (147f - oldestValue) * fractionFromOldest,
                time = "",
                timestamp = now - minutesAgo * minute
            )
        }
        val recentValues = listOf(147f, 147f, 146f, 143f, 142f, 148f, 152f, 155f, 154f)
        return earlierFall + recentValues.mapIndexed { index, value ->
            GlucosePoint(
                value = value,
                time = "",
                timestamp = now - (8 - index) * minute
            )
        }
    }

    private fun hint(
        history: List<GlucosePoint>,
        iobUnits: Float,
        eiobUnits: Float,
        unit: String = "mg/dL",
        targetHigh: Float = 180f,
        doseTargetMgDl: Float = 90f,
        parameters: PredictionModelParameters = this.parameters,
        horizonMinutes: Int = 45,
        correctInRange: Boolean = true,
        nowMillis: Long = now
    ) = StateDoseHintCalculator.calculate(
        history = history,
        unit = unit,
        targetHighDisplay = targetHigh,
        doseTargetMgDl = doseTargetMgDl,
        iobUnits = iobUnits,
        eiobUnits = eiobUnits,
        parameters = parameters,
        horizonMinutes = horizonMinutes,
        correctInRange = correctInRange,
        nowMillis = nowMillis,
        maxReadingAgeMillis = maxAge
    )

    private fun evaluation(
        history: List<GlucosePoint>,
        iobUnits: Float = 1.8f,
        nowMillis: Long = now
    ) = StateDoseHintCalculator.evaluate(
        history = history,
        unit = "mg/dL",
        targetHighDisplay = 180f,
        doseTargetMgDl = 105f,
        iobUnits = iobUnits,
        eiobUnits = 0.6f,
        parameters = fieldParameters,
        horizonMinutes = 45,
        correctInRange = true,
        nowMillis = nowMillis,
        maxReadingAgeMillis = maxAge
    )

    @Test
    fun theFieldCaseSaysNothingAndNeverChangesSign() {
        // 22:51 — 211 mg/dL rising, IoB 4.6 U at eIoB 2.5 U. The gap to the target is
        // 121 mg/dL, which 4.6 U already more than covers, so there is nothing to add.
        assertNull(hint(history(endValue = 211f, slopePerMinute = 0.4f), iobUnits = 4.6f, eiobUnits = 2.5f))
        // 22:52 — twenty seconds later, 215 and still rising. Same answer, where the old
        // suggestion swung from "carbs: 2 g" to "insulin: 0.2 U".
        assertNull(hint(history(endValue = 215f, slopePerMinute = 0.5f), iobUnits = 4.5f, eiobUnits = 2.5f))
    }

    @Test
    fun fallingTowardTheTargetSuggestsCarbsWithTheTimeItHappens() {
        val result = hint(
            history = history(endValue = 154f, slopePerMinute = -1.53125f),
            iobUnits = 1.8f,
            eiobUnits = 0.6f,
            doseTargetMgDl = 105f,
            parameters = fieldParameters
        )
        assertNotNull(result)
        assertEquals(StateDoseHintKind.CARBS, result!!.kind)
        // The measured fall reaches 105 in 32 minutes and projects to 85.1 at 45 minutes.
        // That 19.9 mg/dL gap is 5.7 g at this profile, rounded up to 6. Adding the 1.8 U
        // again would double-count its observed effect and inflate the answer to 28 g.
        assertEquals(6f, result.amount, 0.001f)
        assertEquals(32, result.minutesAhead)
        assertEquals(105f, result.targetMgDl, 0.001f)
    }

    @Test
    fun freshIncompleteRefreshCanKeepTheLastCompatibleHint() {
        val stableHistory = history(endValue = 154f, slopePerMinute = -1.53125f)
        val first = evaluation(stableHistory)
        assertTrue(first is StateDoseHintEvaluation.Complete)
        val complete = first as StateDoseHintEvaluation.Complete
        assertNotNull(complete.hint)
        val retained = StateDoseHintDisplaySnapshot(
            hint = complete.hint!!,
            latestTimestamp = complete.latestTimestamp,
            sensorSerial = complete.sensorSerial
        )

        val middle = evaluation(stableHistory.takeLast(2))
        assertTrue(middle is StateDoseHintEvaluation.Incomplete)
        assertTrue(
            StateDoseHintContinuity.canRetain(
                previous = retained,
                incomplete = middle as StateDoseHintEvaluation.Incomplete,
                nowMillis = now,
                maxReadingAgeMillis = maxAge
            )
        )

        val restored = evaluation(stableHistory)
        assertTrue(restored is StateDoseHintEvaluation.Complete)
        assertEquals(complete.hint, (restored as StateDoseHintEvaluation.Complete).hint)
    }

    @Test
    fun completeNoHintClearsInsteadOfUsingContinuity() {
        val result = evaluation(history(endValue = 105f, slopePerMinute = 0f))
        assertTrue(result is StateDoseHintEvaluation.Complete)
        assertNull((result as StateDoseHintEvaluation.Complete).hint)
    }

    @Test
    fun incompleteRefreshCannotCarryAHintAcrossSensorsOrStaleData() {
        val retained = StateDoseHintDisplaySnapshot(
            hint = StateDoseHint(StateDoseHintKind.CARBS, 10f, 105f, 20),
            latestTimestamp = now - minute,
            sensorSerial = "sensor-a"
        )
        assertFalse(
            StateDoseHintContinuity.canRetain(
                previous = retained,
                incomplete = StateDoseHintEvaluation.Incomplete(now, "sensor-b"),
                nowMillis = now,
                maxReadingAgeMillis = maxAge
            )
        )
        assertFalse(
            StateDoseHintContinuity.canRetain(
                previous = retained,
                incomplete = StateDoseHintEvaluation.Incomplete(now - maxAge - minute, "sensor-a"),
                nowMillis = now,
                maxReadingAgeMillis = maxAge
            )
        )
        assertFalse(
            StateDoseHintContinuity.canRetain(
                previous = retained,
                incomplete = StateDoseHintEvaluation.Incomplete(now - (2 * minute), "sensor-a"),
                nowMillis = now,
                maxReadingAgeMillis = maxAge
            )
        )
    }

    @Test
    fun theReportedFiveMinuteRiseNeverSuggestsCarbs() {
        assertNull(
            hint(
                history = reversalHistory(),
                iobUnits = 1.8f,
                eiobUnits = 0.8f,
                doseTargetMgDl = 105f,
                parameters = fieldParameters
            )
        )
    }

    @Test
    fun theRecentTurnCarriesMoreWeightThanTheOlderFall() {
        val slope = TrendEngine.recencyWeightedSlope(reversalHistory().asReversed()) {
            it.value.toDouble()
        }
        assertNotNull(slope)
        // The plain 30-minute fit is -1.33 mg/dL/min. Recency weighting reads this turn as
        // near-flat instead, above the -0.3 boundary that would enter the carb branch.
        assertTrue(slope!! > -0.3f)
    }

    @Test
    fun aRecentRiseVetoesCarbsEvenWhenTheOlderFallStillDominates() {
        // The final five-minute delta is +11. The deliberately steeper older fall keeps the
        // long fit negative, proving that the recent-rise guard is independent of that fit.
        assertNull(
            hint(
                history = reversalHistory(oldestValue = 250f),
                iobUnits = 1.8f,
                eiobUnits = 0.8f,
                doseTargetMgDl = 105f,
                parameters = fieldParameters
            )
        )
    }

    @Test
    fun oneNoisyReadingDoesNotEraseAClearFall() {
        val clearFall = history(endValue = 150f, slopePerMinute = -2f)
        val noisyNewest = clearFall.dropLast(1) + GlucosePoint(
            value = 154f,
            time = "",
            timestamp = now
        )
        val result = hint(
            history = noisyNewest,
            iobUnits = 1.8f,
            eiobUnits = 0.8f,
            doseTargetMgDl = 105f,
            parameters = fieldParameters
        )
        assertNotNull(result)
        assertEquals(StateDoseHintKind.CARBS, result!!.kind)
        assertTrue(result.amount >= 5f)
    }

    @Test
    fun highAndNotComingDownSuggestsInsulinComputedOnTheCurrentValue() {
        val result = hint(
            history = history(endValue = 300f, slopePerMinute = 0.5f),
            iobUnits = 1.0f,
            eiobUnits = 0.5f
        )
        assertNotNull(result)
        assertEquals(StateDoseHintKind.INSULIN, result!!.kind)
        // (300 - 90) / 54 = 3.89 U, minus the whole 1.0 U on board, rounded down to the
        // pen's half unit. Computed on 300, not on where the curve says it is going.
        assertEquals(2.5f, result.amount, 0.001f)
        assertNull(result.minutesAhead)
    }

    @Test
    fun insulinAlreadyOnBoardCoveringTheGapSaysNothing() {
        assertNull(
            hint(
                history = history(endValue = 300f, slopePerMinute = 0.5f),
                iobUnits = 4.0f,
                eiobUnits = 1.5f
            )
        )
    }

    @Test
    fun nothingActingYetIsAnOrdinaryCorrectionAndNotThisHintsBusiness() {
        assertNull(
            hint(
                history = history(endValue = 300f, slopePerMinute = 0.5f),
                iobUnits = 1.0f,
                eiobUnits = 0f
            )
        )
    }

    @Test
    fun aStateHoveringAroundTheTargetStaysSilent() {
        // Either side of the target, inside the band, is where the old suggestion changed
        // sign on sensor noise.
        assertNull(hint(history(endValue = 95f, slopePerMinute = -0.5f), iobUnits = 2f, eiobUnits = 1f))
        assertNull(hint(history(endValue = 85f, slopePerMinute = -0.5f), iobUnits = 2f, eiobUnits = 1f))
    }

    @Test
    fun aModelProfileWithoutASensitivitySaysNothing() {
        assertNull(
            hint(
                history = history(endValue = 300f, slopePerMinute = 0.5f),
                iobUnits = 1.0f,
                eiobUnits = 0.5f,
                parameters = PredictionModelParameters(
                    carbRatioGramsPerUnit = 10f,
                    insulinSensitivityMgDlPerUnit = 0f
                )
            )
        )
    }

    @Test
    fun lessThanAPenStepIsNotShown() {
        // (200 - 90) / 54 = 2.04 U against 1.8 U on board: 0.24 U left, which no pen
        // delivers, so nothing is shown rather than a number nobody can act on.
        assertNull(
            hint(
                history = history(endValue = 200f, slopePerMinute = 0.5f),
                iobUnits = 1.8f,
                eiobUnits = 0.8f
            )
        )
    }

    @Test
    fun lessThanAMeaningfulCarbPortionIsNotShown() {
        // Crossing the target inside the horizon, but only just, and with almost nothing
        // on board: the arithmetic gives a single gram, which is not a portion.
        assertNull(
            hint(
                history = history(endValue = 115f, slopePerMinute = -0.6f),
                iobUnits = 0.05f,
                eiobUnits = 0.02f
            )
        )
    }

    @Test
    fun withoutInsulinOnBoardTheHintStaysOut() {
        assertNull(hint(history(endValue = 150f, slopePerMinute = -1.5f), iobUnits = 0f, eiobUnits = 0f))
    }

    @Test
    fun aStaleReadingSaysNothingAboutNow() {
        assertNull(
            hint(
                history = history(endValue = 150f, slopePerMinute = -1.5f),
                iobUnits = 1.0f,
                eiobUnits = 0.6f,
                nowMillis = now + 20 * minute
            )
        )
    }

    @Test
    fun aFallThatOnlyReachesTheTargetAfterTheHorizonSaysNothingYet() {
        // 150 falling 0.5 mg/dL per minute crosses 90 in two hours, well past the horizon.
        assertNull(hint(history(endValue = 150f, slopePerMinute = -0.5f), iobUnits = 1.0f, eiobUnits = 0.6f))
    }

    @Test
    fun aRiseTooShortToJudgeSaysNothing() {
        assertNull(
            hint(
                history = history(endValue = 300f, slopePerMinute = 0.5f, spanMinutes = 15),
                iobUnits = 1.0f,
                eiobUnits = 0.5f
            )
        )
    }

    @Test
    fun elevatedFlatAndNothingLeftInFlightSuggestsACorrection() {
        // 171 mg/dL, dose target 90, in-range high 180, flat, 0.3 U on board. Nothing is
        // going to move that on its own.
        val result = hint(
            history = history(endValue = 171f, slopePerMinute = 0f),
            iobUnits = 0.3f,
            eiobUnits = 0.1f
        )
        assertNotNull(result)
        assertEquals(StateDoseHintKind.INSULIN, result!!.kind)
        // (171 - 90) / 54 = 1.5 U, minus the whole 0.3 U on board = 1.2, rounded DOWN to
        // the pen's half unit. 1.5 would be rounding to the nearest, which hands out
        // insulin the arithmetic did not ask for.
        assertEquals(1.0f, result.amount, 0.001f)
        assertNull(result.minutesAhead)
    }

    @Test
    fun theSameStateWithEnoughOnBoardSaysNothing() {
        assertNull(
            hint(
                history = history(endValue = 171f, slopePerMinute = 0f),
                iobUnits = 2.0f,
                eiobUnits = 0.8f
            )
        )
    }

    @Test
    fun justAboveTheTargetIsNotWorthADose() {
        // 105 never gets past the hysteresis band; 112 does, and is then stopped by the
        // margin the in-range case starts at.
        assertNull(hint(history(endValue = 105f, slopePerMinute = 0f), iobUnits = 0.1f, eiobUnits = 0.05f))
        assertNull(hint(history(endValue = 112f, slopePerMinute = 0f), iobUnits = 0.1f, eiobUnits = 0.05f))
    }

    @Test
    fun aboveTheRangeStaysWithTheRuleItAlreadyHad() {
        // 200 is above the in-range high, so the existing rule owns it, and that rule wants
        // insulin already acting. The in-range case must not pick the reading up instead.
        assertNull(
            hint(
                history = history(endValue = 200f, slopePerMinute = 0f),
                iobUnits = 0.3f,
                eiobUnits = 0f
            )
        )
    }

    @Test
    fun anElevatedButFallingValueIsNotCorrected() {
        assertNull(
            hint(
                history = history(endValue = 171f, slopePerMinute = -0.6f),
                iobUnits = 0.3f,
                eiobUnits = 0.1f
            )
        )
    }

    @Test
    fun withTheInRangeSwitchOffOnlyThatCaseGoesQuiet() {
        assertNull(
            hint(
                history = history(endValue = 171f, slopePerMinute = 0f),
                iobUnits = 0.3f,
                eiobUnits = 0.1f,
                correctInRange = false
            )
        )
        // The carb branch is untouched by that switch.
        val carbs = hint(
            history = history(endValue = 154f, slopePerMinute = -1.53125f),
            iobUnits = 1.8f,
            eiobUnits = 0.6f,
            doseTargetMgDl = 105f,
            parameters = fieldParameters,
            correctInRange = false
        )
        assertNotNull(carbs)
        assertEquals(StateDoseHintKind.CARBS, carbs!!.kind)
        assertEquals(6f, carbs.amount, 0.001f)
    }

    @Test
    fun mmolReadingsAreComparedInMgDl() {
        // 16.7 mmol/L is 300 mg/dL; same case, same answer as the mg/dL one.
        val result = hint(
            history = history(endValue = 16.65f, slopePerMinute = 0.0277f),
            iobUnits = 1.0f,
            eiobUnits = 0.5f,
            unit = "mmol/L",
            targetHigh = 10f
        )
        assertNotNull(result)
        assertEquals(StateDoseHintKind.INSULIN, result!!.kind)
        assertEquals(2.5f, result.amount, 0.001f)
    }
}
