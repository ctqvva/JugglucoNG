package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import tk.glucodata.data.prediction.analysis.ActualGlucose
import tk.glucodata.data.prediction.analysis.AdaptivePredictionStore
import tk.glucodata.data.prediction.analysis.ForecastLane
import tk.glucodata.data.prediction.analysis.ForecastModel
import tk.glucodata.data.prediction.analysis.ForecastMoment
import tk.glucodata.data.prediction.analysis.ForecastValues
import tk.glucodata.data.prediction.analysis.PredictionAccuracy
import tk.glucodata.data.prediction.analysis.PredictionAnalysisSnapshot
import tk.glucodata.data.prediction.analysis.RecordedForecastEntity

/**
 * The analysis surfaces drawn from a synthetic recording: they must show their figures, and
 * the PNGs under build/outputs/prediction-analysis are how a reviewer sees them without a
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w411dp-h1400dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PredictionAnalysisScreensTests {
    @get:Rule val compose = createComposeRule()

    private val minute = 60_000L
    private val start = 1_790_000_000_000L

    /** A gently waving glucose line, one reading a minute for three days. */
    private fun glucose(t: Long): Float {
        val m = (t - start) / minute.toDouble()
        return (135 + 45 * sin(2 * PI * m / 260) + 15 * sin(2 * PI * m / 61)).toFloat()
    }

    private val readingTimes = generateSequence(start) { it + minute }.takeWhile { it <= start + 3 * 24 * 60 * minute }.toList()
    private val actual = ActualGlucose(mapOf("SENSOR" to ActualGlucose.SensorLanes(
        readingTimes.toLongArray(), mapOf(ForecastLane.AUTO to FloatArray(readingTimes.size) { glucose(readingTimes[it]) }),
    )))

    /** A standard forecast that overshoots the trend and an adaptive one that follows it more closely. */
    private fun forecast(origin: Long, model: ForecastModel, steps: Int = 24): RecordedForecastEntity {
        val baseline = glucose(origin)
        val slope = (glucose(origin) - glucose(origin - 10 * minute)) / 10f
        val values = FloatArray(steps) { k ->
            val m = (k + 1) * 5
            when (model) {
                ForecastModel.STANDARD -> baseline + slope * m * kotlin.math.exp(-m / 70f) * 1.6f
                ForecastModel.ADAPTIVE -> baseline + (glucose(origin + m * minute) - baseline) * 0.75f
            }
        }
        return RecordedForecastEntity(
            originTime = origin, recordedAt = origin, sensorSerial = "SENSOR", lane = ForecastLane.AUTO,
            model = model.storageId, modelVersion = 1, configHash = 0L, shown = model == ForecastModel.STANDARD,
            fallback = false, baselineMgdl = baseline, stepMinutes = 5, valuesMgdl = ForecastValues.encode(values),
            trendMgdlPerMin = slope, plannedEntries = 0, journalEntries = 0,
        )
    }

    private val origins = generateSequence(start + 60 * minute) { it + 5 * minute }
        .takeWhile { it <= start + 3 * 24 * 60 * minute - 130 * minute }.toList()
    private val forecasts = origins.flatMap { listOf(forecast(it, ForecastModel.STANDARD), forecast(it, ForecastModel.ADAPTIVE)) }

    private fun capture(name: String) {
        val output = File("build/outputs/prediction-analysis/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Frame(content: @androidx.compose.runtime.Composable () -> Unit) {
        JugglucoTheme(themeMode = ThemeMode.DARK) {
            // A Surface, like the Scaffold the real screens sit in, so text gets its content colour.
            androidx.compose.material3.Surface(Modifier.fillMaxSize().testTag("screen"), color = MaterialTheme.colorScheme.surface) { content() }
        }
    }

    @Test
    fun accuracyScreenShowsTheRecordedFigures() {
        val report = PredictionAccuracy.report(forecasts, actual, zone = ZoneOffset.UTC)
        val snapshot = PredictionAnalysisSnapshot(report, storedForecasts = forecasts.size, storedBytes = 1_400_000L)
        compose.setContent {
            Frame {
                PredictionAnalysisContent(
                    snapshot = snapshot, periodDays = 30, horizonMinutes = 30, recording = true, adaptiveShown = false,
                    isMmol = false, onPeriodSelected = {}, onHorizonSelected = {}, onTurnOn = {},
                    onOpenPastForecasts = {}, onClear = {},
                )
            }
        }
        compose.onNodeWithText("Typical error").assertIsDisplayed()
        compose.onNodeWithText("Compared with no change").assertIsDisplayed()
        compose.onAllNodesWithText("Adaptive forecast").onFirst().assertIsDisplayed()
        capture("accuracy")
    }

    @Test
    fun accuracyScreenWithoutRecordingsOffersToTurnItOn() {
        val empty = PredictionAnalysisSnapshot(PredictionAccuracy.report(emptyList(), actual), 0, 0L)
        compose.setContent {
            Frame {
                PredictionAnalysisContent(
                    snapshot = empty, periodDays = 30, horizonMinutes = 30, recording = false, adaptiveShown = false,
                    isMmol = false, onPeriodSelected = {}, onHorizonSelected = {}, onTurnOn = {},
                    onOpenPastForecasts = {}, onClear = {},
                )
            }
        }
        compose.onNodeWithText("No forecasts recorded yet").assertIsDisplayed()
        compose.onNodeWithText("Turn on").assertIsDisplayed()
        capture("accuracy-empty")
    }

    @Test
    fun inspectorDrawsAForecastAgainstWhatFollowed() {
        val origin = origins[origins.size / 2]
        val (times, values) = actual.readings("SENSOR", ForecastLane.AUTO, origin - 60 * minute, origin + 120 * minute)
        val moment = ForecastMoment(
            originTime = origin, sensorSerial = "SENSOR", lane = ForecastLane.AUTO,
            standard = forecast(origin, ForecastModel.STANDARD), adaptive = forecast(origin, ForecastModel.ADAPTIVE),
            actualTimes = times, actualValues = values, actual = actual,
        )
        compose.setContent {
            Frame {
                PredictionInspectorContent(
                    origins = origins, selected = origins.size / 2, moment = moment, isMmol = false,
                    targetLowMgdl = 70f, targetHighMgdl = 180f, onSelect = {},
                )
            }
        }
        compose.onAllNodesWithText("Actual").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Standard forecast · On the chart").assertIsDisplayed()
        capture("past-forecast")
    }

    @Test
    fun shortForecastStillShowsLaterActualReadings() {
        val origin = origins[origins.size / 2]
        val short = forecast(origin, ForecastModel.STANDARD, steps = 6)
        val (times, values) = actual.readings("SENSOR", ForecastLane.AUTO, origin - 60 * minute, origin + 30 * minute)
        val moment = ForecastMoment(origin, "SENSOR", ForecastLane.AUTO, short, null, times, values, actual)
        compose.setContent {
            Frame {
                PredictionInspectorContent(
                    origins = listOf(origin), selected = 0, moment = moment, isMmol = false,
                    targetLowMgdl = 70f, targetHighMgdl = 180f, onSelect = {},
                )
            }
        }
        compose.onNodeWithText("No reading").assertDoesNotExist()
        compose.onNodeWithText("Not yet").assertDoesNotExist()
        for (h in listOf(60, 120)) {
            compose.onAllNodesWithText(tk.glucodata.ui.util.GlucoseFormatter.formatFromMgDl(
                actual.valueAt("SENSOR", ForecastLane.AUTO, origin + h * minute), false,
            )).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun settingsGroupShowsRecordingAccuracyAndAdaptive() {
        compose.setContent {
            Frame {
                Box(Modifier.padding(16.dp)) {
                    PredictionAnalysisSettingsGroup(
                        analysisEnabled = true, onAnalysisEnabledChange = {},
                        accuracySummary = "Off by 18 mg/dL on average at 30 min", onOpenAccuracy = {},
                        adaptiveEnabled = false, adaptiveStatus = AdaptivePredictionStore.Status.Ready(14f, start),
                        onAdaptiveEnabledChange = {}, predictionEnabled = true,
                    )
                }
            }
        }
        compose.onNodeWithText("Record forecasts").assertIsDisplayed()
        compose.onNodeWithText("Adaptive prediction").assertIsDisplayed()
        compose.onNodeWithText("Experimental · learned from 14 days of readings").assertIsDisplayed()
        capture("settings")
    }
}
