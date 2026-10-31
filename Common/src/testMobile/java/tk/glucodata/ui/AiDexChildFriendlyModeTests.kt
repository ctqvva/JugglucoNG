package tk.glucodata.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w600dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiDexChildFriendlyModeTests {
    @get:Rule val compose = createComposeRule()

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test fun protectionEnabledOnOpenSheetHidesCalibrationAndBlocksStockFromDisablingIt() {
        val protected = mutableStateOf(false)
        val changes = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                AiDexAlgorithmSheet("test", 9, hideCalibration = protected.value,
                    onSelect = { changes += it }, onDismiss = {})
            }
        }
        compose.onNodeWithText(text(R.string.calibration)).assertExists()
        compose.runOnIdle { protected.value = true }
        compose.onNodeWithText(text(R.string.calibration)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.aidex_stock_calibration)).assertIsNotEnabled().performClick()
        compose.onNodeWithText(text(R.string.sibionics_uncertainty_ribbon)).assertExists()
        compose.runOnIdle { assertEquals(emptyList<Int>(), changes) }

        compose.runOnIdle { protected.value = false }
        compose.onNodeWithText(text(R.string.calibration)).performClick()
        compose.runOnIdle { assertEquals(listOf(8), changes) }
    }

    @Test fun calibrationProtectionStillAllowsAlgorithmChangesThatKeepCalibrationDisabled() {
        val changes = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                AiDexAlgorithmSheet("test", 0, hideCalibration = true,
                    onSelect = { changes += it }, onDismiss = {})
            }
        }
        compose.onNodeWithText(text(R.string.sibionics_adaptive_v2)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.calibration)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.aidex_stock_calibration)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(8, 0), changes) }
    }
}
