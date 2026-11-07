package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.ui.setup.AiDexKeyManagementScreen
import tk.glucodata.ui.setup.AiDexKeySensor
import tk.glucodata.ui.setup.aiDexKeySensorFromIdentity
import tk.glucodata.ui.setup.mergeAiDexKeySensors
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import tk.glucodata.ui.setup.rememberWizardUiMetrics
import tk.glucodata.ui.theme.AppTypography
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "ru-w400dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiDexKeyManagementTests {
    @get:Rule val compose = createComposeRule()

    private fun show(available: Boolean = true, sensors: List<AiDexKeySensor> = emptyList()) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Surface {
                    AiDexKeyManagementScreen(rememberWizardUiMetrics(), "", false, {}, {},
                        bloodControlAvailable = available, sensors = sensors)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun russianSwitchShowsBloodControlFormWithoutAccountOrClose() {
        show()
        compose.onNodeWithText("AideX CN").assertIsDisplayed().assertIsOn()
        compose.onNodeWithText("BloodControl API").performClick().assertIsOn()
        compose.onNodeWithText("Запросить ключ").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("SMS", substring = true).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("22222FXYT3")
        compose.onNodeWithText("Запросить ключ").assertIsEnabled()
        compose.onNodeWithText("Закрыть").assertDoesNotExist()
        capture("aidex-bloodcontrol-ru.png")
        compose.onNodeWithText("AideX CN").performClick()
        compose.onNodeWithText("Запросить ключ").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "en-w400dp-h820dp-mdpi")
    fun englishKeepsCnAndHasNoSourceSwitchOrCloseButton() {
        show()
        compose.onNodeWithText("AideX CN").assertDoesNotExist()
        compose.onNodeWithText("BloodControl API").assertDoesNotExist()
        compose.onNodeWithText("Close").assertDoesNotExist()
        compose.onAllNodesWithText("SMS code")[0].performScrollTo().assertIsDisplayed()
    }

    @Test fun russianBuildWithoutCredentialsDoesNotOfferBloodControl() {
        show(available = false)
        compose.onNodeWithText("AideX CN").assertDoesNotExist()
        compose.onNodeWithText("BloodControl API").assertDoesNotExist()
        compose.onNodeWithText("Запросить ключ").assertDoesNotExist()
        compose.onNodeWithText("Закрыть").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "ru-w640dp-h360dp-mdpi")
    fun landscapeBloodControlActionCanScrollIntoView() {
        show()
        compose.onNodeWithText("BloodControl API").performClick()
        compose.onNodeWithText("Запросить ключ").performScrollTo().assertIsDisplayed()
    }

    @Test fun connectedSensorPrefillsAndTappingNearbyOnlyChangesTheSerial() {
        show(sensors = listOf(
            AiDexKeySensor("X-22222G7SVJ", "", "AA:BB:CC:DD:EE:01", connected = true),
            AiDexKeySensor("F-22222FY2M8", "AiDEX F-22222FY2M8", "AA:BB:CC:DD:EE:02"),
        ))
        compose.onNodeWithText("BloodControl API").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("22222G7SVJ")
        compose.onNodeWithText("22222FY2M8", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("22222FY2M8")
        compose.onNodeWithText("Запросить ключ").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Ключ сопряжения отсутствует").assertExists()
        capture("aidex-bloodcontrol-sensors-ru.png")
    }

    @Test fun discoveryDoesNotOverwriteAnEditedOrClearedSerial() {
        val sensors = mutableStateOf(emptyList<AiDexKeySensor>())
        compose.setContent {
            MaterialTheme {
                AiDexKeyManagementScreen(rememberWizardUiMetrics(), "", false, {}, {},
                    bloodControlAvailable = true, sensors = sensors.value)
            }
        }
        compose.onNodeWithText("BloodControl API").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("22222FXYT3")
        compose.runOnIdle { sensors.value = listOf(AiDexKeySensor("22222G7SVJ", "", "", true)) }
        compose.onNode(hasSetTextAction()).assertTextContains("22222FXYT3")
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.runOnIdle { sensors.value += AiDexKeySensor("22222FY2M8", "", "") }
        compose.onNode(hasSetTextAction()).assertTextContains("")
        compose.onNodeWithText("Запросить ключ").assertIsNotEnabled()
    }

    @Test fun pickerDeduplicatesSerialsAndNeverUsesAMacFallbackAsASerial() {
        val address = "AA:BB:CC:DD:EE:FF"
        assertNull(aiDexKeySensorFromIdentity("X-AABBCCDDEEFF", "X-AABBCCDDEEFF", address, true))
        assertEquals("22222G7SVJ", aiDexKeySensorFromIdentity("X-22222G7SVJ", "", address, true)?.serial)
        val discovered = aiDexKeySensorFromIdentity("X-AABBCCDDEEFF", "AiDEX F-22222G7SVJ", address, false)!!
        val connected = discovered.copy(serial = "X-22222G7SVJ", connected = true)
        assertEquals(listOf(connected.copy(serial = "22222G7SVJ")), mergeAiDexKeySensors(listOf(discovered, connected)))
    }

    @Test @Config(qualifiers = "ru-w640dp-h360dp-mdpi")
    fun manySensorsLeaveTheFormReachableInLandscape() {
        show(sensors = (0..11).map { AiDexKeySensor("22222TEST$it", "AiDEX", "") })
        compose.onNodeWithText("BloodControl API").performClick()
        compose.onNodeWithText("Запросить ключ").performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "Common").isDirectory }
        val output = File(root, ".local/bloodcontrol/$name").apply { requireNotNull(parentFile).mkdirs() }
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
