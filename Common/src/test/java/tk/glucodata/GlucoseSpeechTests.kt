package tk.glucodata

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tk.glucodata.ui.DisplayValueResolver
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SpeechTestApplication::class)
class GlucoseSpeechTests {
    private fun context(language: String = "en"): Context {
        val app = RuntimeEnvironment.getApplication() as Context
        val config = Configuration(app.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return app.createConfigurationContext(config)
    }

    @Test fun speaksAllFiveRequestedDirectionsWithoutArrowGlyphs() {
        val context = context()
        for ((value, rate, expected) in listOf(
            Triple("6.0", 0f, "6.0, stable"),
            Triple("6.0", -1f, "6.0, decreasing"),
            Triple("6.6", 1f, "6.6, increasing"),
            Triple("7.6", 2f, "7.6, rising quickly"),
            Triple("5.2", -2f, "5.2, falling quickly"),
        )) assertEquals(expected, GlucoseSpeech.format(context, value, rate))
    }

    @Test fun speechMatchesTheArrowDeadZoneAndVerticalThresholds() {
        val context = context()
        for (rate in listOf(-0.5f, -0.01f, 0f, 0.01f, 0.5f)) {
            assertEquals("6.0, stable", GlucoseSpeech.format(context, "6.0", rate))
        }
        assertEquals("6.0, increasing", GlucoseSpeech.format(context, "6.0", 0.501f))
        assertEquals("6.0, decreasing", GlucoseSpeech.format(context, "6.0", -0.501f))
        assertEquals("6.0, increasing", GlucoseSpeech.format(context, "6.0", 1.999f))
        assertEquals("6.0, decreasing", GlucoseSpeech.format(context, "6.0", -1.999f))
        assertEquals("6.0, rising quickly", GlucoseSpeech.format(context, "6.0", 3.5f))
        assertEquals("6.0, falling quickly", GlucoseSpeech.format(context, "6.0", -3.5f))
    }

    @Test fun unavailableTrendDoesNotInventStableDirection() {
        for (rate in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals("6.0", GlucoseSpeech.format(context(), "6.0", rate))
        }
    }

    @Test fun snapshotUsesItsDisplayedPrimaryLaneAndSameMgdlRateInEitherGlucoseUnit() {
        for (mmol in listOf(true, false)) {
            val values = DisplayValueResolver.resolve(
                autoValue = if (mmol) 7.1f else 128f,
                rawValue = if (mmol) 5.4f else 97f,
                calibratedValue = if (mmol) 6.0f else 108f,
                viewMode = 3,
                isMmol = mmol,
            )
            val reading = CurrentDisplaySource.Snapshot(
                timeMillis = 1_700_000_000_000L, rate = -2f, sensorId = "speech-test",
                sensorGen = 0, index = 0, viewMode = 3, source = "test",
                autoValue = values.primaryValue, rawValue = 0f,
                sharedDisplayValue = values.primaryValue, sharedMgdl = 108,
                isMmol = mmol, displayValues = values,
            )
            assertEquals(if (mmol) "6.0, falling quickly" else "108, falling quickly",
                GlucoseSpeech.format(context(), reading))
        }
    }

    @Test fun translatedDirectionKeepsSpeechSafeDecimalFormatting() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("6.0, schnell steigend", GlucoseSpeech.format(context("de"),
                DisplayValueResolver.formatForSpeech(6f, true), 2f))
            assertEquals("6.0, быстро снижается", GlucoseSpeech.format(context("ru"), "6.0", -2f))
        } finally {
            Locale.setDefault(original)
        }
    }
}
