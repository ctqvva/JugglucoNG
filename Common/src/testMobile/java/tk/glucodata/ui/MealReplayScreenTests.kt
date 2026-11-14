package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.data.prediction.GlucosePredictionPoint
import tk.glucodata.data.prediction.PredictionModelParameters
import tk.glucodata.data.prediction.analysis.MealReplay
import tk.glucodata.ui.viewmodel.MealReplayState
import tk.glucodata.ui.viewmodel.ReplayBlock
import tk.glucodata.ui.viewmodel.ReplayedMeal

/**
 * Past meals drawn from synthetic replays. The PNGs under build/outputs/meal-replay are how a
 * reviewer sees the screen without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w411dp-h1400dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MealReplayScreenTests {
    @get:Rule val compose = createComposeRule()

    private val minute = 60_000L
    private val mealAt = 1_790_000_000_000L

    private fun rise(m: Int, peak: Float) = 115f + peak * sin(PI * m.coerceIn(0, 240) / 240.0).toFloat() - 0.08f * m.coerceAtLeast(0)

    private fun replay(peak: Float, endOffset: Float): MealReplay.Replay {
        val points = (0..72).map { k ->
            val m = k * 5
            val v = rise(m, peak) + endOffset * (m / 360f)
            GlucosePredictionPoint(mealAt - 3 * minute + m * minute, v, 1f)
        }
        val actual = rise(240, 70f)
        return MealReplay.Replay(points, points[48].value, actual)
    }

    private val readings = (-12..72).map { k -> mealAt + k * 5 * minute to rise(k * 5, 70f) }
    private val saved = PredictionModelParameters(10f, 54f)
    private val trial = PredictionModelParameters(8.5f, 54f)

    private fun state(withTrial: Boolean): MealReplayState {
        val meal = ReplayedMeal(
            meal = MealReplay.Meal(mealAt, 45f),
            blockStart = 0,
            insulinUnits = 4.5f,
            readingsMgdl = readings,
            saved = replay(peak = 40f, endOffset = -30f),
            trial = if (withTrial) replay(peak = 62f, endOffset = -8f) else null,
        )
        return MealReplayState(
            meals = listOf(meal, meal.copy(meal = MealReplay.Meal(mealAt + 86_400_000L, 30f))),
            blocks = listOf(
                ReplayBlock(
                    startMinute = 0, endMinute = 24 * 60 - 1, saved = saved,
                    trial = if (withTrial) trial else saved, scoredMeals = 24,
                    savedMissMgdl = 38f, trialMissMgdl = if (withTrial) 29f else 38f, flatMissMgdl = 43f,
                ),
            ),
        )
    }

    private fun capture(name: String) {
        val output = File("build/outputs/meal-replay/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private var retried = 0

    private fun show(state: MealReplayState?) {
        compose.setContent {
            JugglucoTheme(themeMode = ThemeMode.DARK) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize().testTag("screen"), color = MaterialTheme.colorScheme.surface) {
                    MealReplayContent(
                        state = state, selected = 0, isMmol = false, targetLowMgdl = 70f, targetHighMgdl = 180f,
                        onSelect = {}, onCarbRatio = { _, _ -> }, onSensitivity = { _, _ -> }, onReset = {}, onRetry = { retried++ },
                    )
                }
            }
        }
    }

    @Test
    fun aBlockShowsItsMissesAndTheMealItsCurves() {
        show(state(withTrial = true))
        compose.onNodeWithText("Average miss 4 hours after a meal").assertIsDisplayed()
        compose.onNodeWithText("38").assertIsDisplayed()
        compose.onNodeWithText("29").assertIsDisplayed()
        compose.onNodeWithText("8.5 g/U").assertIsDisplayed()
        compose.onNodeWithText("Reset").assertIsEnabled()
        compose.onAllNodesWithText("Trial values").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Reading at 4 hours").assertIsDisplayed()
        capture("past-meals-trial")
    }

    @Test
    fun withoutATrialThereIsNothingToReset() {
        show(state(withTrial = false))
        compose.onNodeWithText("Reset").assertIsNotEnabled()
        capture("past-meals")
    }

    @Test
    fun withNoMealsItSaysWhatAMealNeeds() {
        show(MealReplayState(emptyList(), emptyList()))
        compose.onNodeWithText("No meals to replay yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFailedLoadSaysSoAndOffersToTryAgain() {
        show(MealReplayState(emptyList(), emptyList(), failed = true))
        compose.onNodeWithText("Couldn't read your history or journal").assertIsDisplayed()
        compose.onNodeWithText("No meals to replay yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }
}
