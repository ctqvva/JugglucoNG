package tk.glucodata.data.prediction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.GlucosePoint

/**
 * The hint is on by default, so the profile it computes from has to be named rather than
 * assumed. This is the decision behind that notice: shown once while no profile has been
 * saved, never again afterwards, and never in place of the suggestion.
 *
 * Reading the preference itself is left to the device, as everywhere else in these tests.
 * `getBoolean(key, true)` is what flips the default, and an installation that switched the
 * hint off has written `false` under that key, so it keeps its answer.
 */
class StateDoseHintProfileNoticeTests {

    @Test
    fun aHintComputedFromAnUnsavedProfileAsksOnce() {
        assertTrue(
            StateDoseHintCalculator.profileNoticeDue(
                hintPresent = true,
                modelProfileSaved = false,
                noticeAcknowledged = false
            )
        )
    }

    @Test
    fun onceAnsweredItNeverComesBack() {
        // The second suggestion, and every one after it, drags no notice along with it.
        assertFalse(
            StateDoseHintCalculator.profileNoticeDue(
                hintPresent = true,
                modelProfileSaved = false,
                noticeAcknowledged = true
            )
        )
    }

    @Test
    fun aSavedProfileNeverAsks() {
        assertFalse(
            StateDoseHintCalculator.profileNoticeDue(
                hintPresent = true,
                modelProfileSaved = true,
                noticeAcknowledged = false
            )
        )
    }

    @Test
    fun nothingToSuggestMeansNothingToAskAbout() {
        assertFalse(
            StateDoseHintCalculator.profileNoticeDue(
                hintPresent = false,
                modelProfileSaved = false,
                noticeAcknowledged = false
            )
        )
    }

    @Test
    fun theSuggestionIsStillComputedFromTheBuiltInProfile() {
        // The point of the notice: it says what the numbers came from, it does not withhold
        // them. Same falling case as StateDoseHintTests, with the built-in defaults.
        val now = 1_800_000_000_000L
        val history = (6 downTo 0).map { stepsAgo ->
            val minutesAgo = stepsAgo * 5
            GlucosePoint(
                value = 150f + 1.5f * minutesAgo,
                time = "",
                timestamp = now - minutesAgo * 60_000L
            )
        }
        val hint = StateDoseHintCalculator.calculate(
            history = history,
            unit = "mg/dL",
            targetHighDisplay = 180f,
            doseTargetMgDl = DoseTarget.DEFAULT_MGDL,
            iobUnits = 1.0f,
            eiobUnits = 0.6f,
            parameters = PredictionModelParameters(
                carbRatioGramsPerUnit = PredictionModelProfileStore.DEFAULT_CARB_RATIO_GRAMS_PER_UNIT,
                insulinSensitivityMgDlPerUnit =
                    PredictionModelProfileStore.DEFAULT_INSULIN_SENSITIVITY_MGDL_PER_UNIT
            ),
            horizonMinutes = StateDoseHintCalculator.HORIZON_MINUTES_DEFAULT,
            nowMillis = now,
            maxReadingAgeMillis = 15 * 60_000L
        )
        assertNotNull(hint)
    }
}
