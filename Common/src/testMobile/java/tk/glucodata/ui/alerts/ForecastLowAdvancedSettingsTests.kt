package tk.glucodata.ui.alerts

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.R
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDefaults
import tk.glucodata.alerts.AlertType
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-rUS-w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForecastLowAdvancedSettingsTests {
    @get:Rule val compose = createComposeRule()

    private fun label(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show(type: AlertType, dark: Boolean, onChange: (AlertConfig) -> Unit = {}) {
        compose.setContent {
            val advanced = remember { mutableStateOf(false) }
            var config by remember {
                mutableStateOf(AlertDefaults.defaultConfig(type, false).copy(
                    enabled = true, soundEnabled = false, vibrationEnabled = false
                ))
            }
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
                typography = AppTypography
            ) {
                CompositionLocalProvider(LocalAlertsAdvancedOpen provides advanced) {
                    Surface {
                        Box(Modifier.size(420.dp, 900.dp).testTag("screen")) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                AlertSettingsExpanded(config, false, { config = it; onChange(it) }, {}, {})
                            }
                        }
                    }
                }
            }
        }
    }

    private fun verifyPlacement(dark: Boolean) {
        var saved: AlertConfig? = null
        show(AlertType.PRE_LOW, dark) { saved = it }
        val evidence = label(R.string.pre_low_evidence_label)
        compose.onNodeWithText(evidence).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.advanced)).performScrollTo().performClick()
        compose.onNodeWithText(evidence).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.pre_low_evidence_description)).assertIsDisplayed()
        val intervalBounds = compose.onNodeWithText(label(R.string.rearm_min_interval_label))
            .fetchSemanticsNode().boundsInRoot
        val evidenceBounds = compose.onNodeWithText(evidence).fetchSemanticsNode().boundsInRoot
        assertTrue("Evidence belongs below the minimum repeat interval", intervalBounds.bottom <= evidenceBounds.top)
        compose.onNodeWithText(evidence).performClick()
        assertTrue("The row must save the evidence switch", saved?.preLowEvidenceEnabled == true)
        val output = File("build/outputs/layout-regressions/forecast-low-advanced-${if (dark) "dark" else "light"}.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun evidenceIsInsideAdvancedBelowRepeatIntervalInLightTheme() = verifyPlacement(false)
    @Test fun evidenceIsInsideAdvancedBelowRepeatIntervalInDarkTheme() = verifyPlacement(true)

    @Test fun forecastHighDoesNotOfferLowEvidenceSetting() {
        show(AlertType.PRE_HIGH, false)
        compose.onNodeWithText(label(R.string.advanced)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.pre_low_evidence_label)).assertDoesNotExist()
    }
}
