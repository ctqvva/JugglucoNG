package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import tk.glucodata.ui.components.AppTopBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
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
import tk.glucodata.drivers.aidex.AiDexTestKeystore
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.native.protocol.AiDexPairingMaterialRegistry
import tk.glucodata.ui.setup.AiDexSavedSensorsSection
import tk.glucodata.ui.setup.AiDexSavedKeySensor
import tk.glucodata.ui.setup.AiDexSavedSensorsScreen
import tk.glucodata.ui.setup.AiDexKeyManagementState
import tk.glucodata.ui.util.BleDeviceScanner
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.ui.setup.rememberWizardUiMetrics
import tk.glucodata.ui.theme.AppTypography
import java.io.File
import tk.glucodata.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "ru-w400dp-h820dp-mdpi", shadows = [AiDexTestKeystore::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiDexKeyManagementTests {
    @get:Rule val compose = createComposeRule()

    @Before fun clearKeys() {
        RuntimeEnvironment.getApplication().getSharedPreferences("aidex_cn_provisioning", 0).edit().clear().commit()
        AiDexPairingMaterialRegistry.clearForTests()
    }

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
        compose.onNodeWithText("BloodControl API").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("Запросить ключ").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("SMS", substring = true).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("22222FXYT3")
        compose.onNodeWithText("Запросить ключ").assertIsEnabled()
        compose.onNodeWithText("Закрыть").assertDoesNotExist()
        capture("aidex-bloodcontrol-ru.png")
        compose.onNodeWithText("AideX CN").performScrollTo().performClick()
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
        compose.onNodeWithText("BloodControl API").performScrollTo().performClick()
        compose.onNodeWithText("Запросить ключ").performScrollTo().assertIsDisplayed()
    }

    @Test fun connectedSensorPrefillsAndTappingNearbyOnlyChangesTheSerial() {
        show(sensors = listOf(
            AiDexKeySensor("X-22222G7SVJ", "", "AA:BB:CC:DD:EE:01", connected = true),
            AiDexKeySensor("F-22222FY2M8", "AiDEX F-22222FY2M8", "AA:BB:CC:DD:EE:02"),
        ))
        compose.onNodeWithText("BloodControl API").performScrollTo().performClick()
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
        compose.onNodeWithText("BloodControl API").performScrollTo().performClick()
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
        compose.onNodeWithText("BloodControl API").performScrollTo().performClick()
        compose.onNodeWithText("Запросить ключ").performScrollTo().assertIsDisplayed()
    }

    @Test fun fSensorFormCannotConnectBeforeKeysAreSavedAndThenUsesTheSelectedAddress() {
        val context = RuntimeEnvironment.getApplication()
        val sensor = AiDexKeySensor("22222G7SVJ", "AiDEX F-22222G7SVJ", "AA:BB:CC:DD:EE:01")
        var connected: AiDexKeySensor? = null
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Surface {
                    AiDexKeyManagementScreen(rememberWizardUiMetrics(), sensor.serial, false, {}, {},
                        bloodControlAvailable = true, sensors = listOf(sensor), requiredSerial = sensor.serial,
                        onConnect = { connected = it })
                }
            }
        }
        val connect = context.getString(R.string.ottai_connect_saved)
        compose.onNodeWithText("BloodControl API").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertTextContains(sensor.serial)
        compose.onNodeWithText(context.getString(R.string.aidex_keys_before_connect)).assertExists()
        compose.onNodeWithText(connect).performScrollTo().assertIsNotEnabled()
        assertNull(connected)
        assertTrue(AiDexProvisioningStore.saveAndInstall(context, sensor.serial, ByteArray(16), ByteArray(16)))
        // Exercise the existing explicit-request saved-key path, without any network call.
        compose.onNodeWithText("Запросить ключ").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(connect).fetchSemanticsNodes()
                .any { SemanticsProperties.Disabled !in it.config }
        }
        compose.onNodeWithText("Запросить ключ").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.export)).assertExists().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.ottai_credentials_replace)).assertExists().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.ottai_select)).assertDoesNotExist()
        capture("aidex-saved-sensors-ru.png")
        compose.onNodeWithText(connect).performScrollTo().performClick()
        assertEquals(sensor, connected)
    }

    @Test fun savedSensorsReuseOttaiActionsAndKeepManagedKeysProtected() {
        val context = RuntimeEnvironment.getApplication()
        var selected = ""
        var exported = ""
        var deleted = ""
        val sensors = listOf(AiDexSavedKeySensor("22222FXYT3", true, true),
            AiDexSavedKeySensor("22222FY2M8", false, false))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Surface {
                    AiDexSavedSensorsSection(sensors, true, { selected = it }, { exported = it }, { deleted = it })
                }
            }
        }
        compose.onAllNodesWithText(context.getString(R.string.ottai_select))[0].performClick()
        assertEquals("22222FXYT3", selected)
        compose.onAllNodesWithText(context.getString(R.string.export))[1].performClick()
        assertEquals("22222FY2M8", exported)
        compose.onAllNodesWithContentDescription(context.getString(R.string.ottai_delete_saved)).assertCountEquals(1)
        compose.onNodeWithContentDescription(context.getString(R.string.ottai_delete_saved)).performClick()
        assertEquals("22222FY2M8", deleted)
    }

    @Test fun topSavedLinkOpensASeparatePageAndSelectionKeepsTheProviderAndFormState() {
        val context = RuntimeEnvironment.getApplication()
        val first = "22222FXYT3"
        val second = "22222FY2M8"
        for (serial in listOf(first, second)) {
            assertTrue(AiDexProvisioningStore.saveAndInstall(context, serial, ByteArray(16), ByteArray(16)))
        }
        val form = AiDexKeyManagementState(first)
        form.password.value = "in-memory-draft"
        val savedPage = mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Scaffold(topBar = {
                    AppTopBar(
                        title = context.getString(if (savedPage.value) R.string.ottai_saved_sensors_title else R.string.aidex_setup_title),
                        onNavigateBack = { savedPage.value = false },
                        navigationContentDescription = "Back",
                    )
                }) { padding ->
                    Box(Modifier.padding(padding)) {
                    if (savedPage.value) {
                        AiDexSavedSensorsScreen(rememberWizardUiMetrics(), emptyList(), form.serialInput.value,
                            onSelect = { form.selectSerial(it); savedPage.value = false })
                    } else {
                        AiDexKeyManagementScreen(rememberWizardUiMetrics(), "", false, {}, {},
                            bloodControlAvailable = true, formState = form,
                            onOpenSavedSensors = { savedPage.value = true })
                    }
                    }
                }
            }
        }
        val savedTitle = context.getString(R.string.ottai_saved_sensors_title)
        val select = context.getString(R.string.ottai_select)
        compose.onNodeWithText("BloodControl API").performScrollTo().performClick()
        compose.onNodeWithText(select).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ottai_saved_section_phone)).assertDoesNotExist()
        compose.onNodeWithText(savedTitle).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("BloodControl API").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.waitUntil(5000) { compose.onAllNodesWithText(select).fetchSemanticsNodes().size == 2 }
        capture("aidex-saved-page-ru.png")
        compose.onAllNodesWithText(select)[1].performClick()
        compose.onNodeWithText("BloodControl API").assertIsOn()
        compose.onNode(hasSetTextAction()).assertTextContains(second)
        compose.onNodeWithText(context.getString(R.string.export)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.ottai_credentials_replace)).assertIsEnabled()
        assertEquals("in-memory-draft", form.password.value)
        compose.onNodeWithText(select).assertDoesNotExist()
        capture("aidex-retrieval-inline-keys-ru.png")
    }

    @Test fun animatedScanLineTracksTheScanStateAndFailureIsVisible() {
        val scanning = mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Surface {
                AiDexKeyManagementScreen(rememberWizardUiMetrics(), "", false, {}, {},
                    bloodControlAvailable = true, searching = scanning.value,
                    scanError = if (scanning.value) null else BleDeviceScanner.ScanStartError.BluetoothDisabled)
                }
            }
        }
        compose.onNodeWithTag("aidex-key-scan-progress").assertIsDisplayed()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.aidex_searching_sensors)).assertExists()
        compose.mainClock.advanceTimeBy(800)
        capture("aidex-retrieval-scanning-ru.png")
        compose.runOnIdle { scanning.value = false }
        compose.onNodeWithTag("aidex-key-scan-progress").assertDoesNotExist()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.bluetooth_is_turned_off)).assertIsDisplayed()
    }

    @Test fun reselectingTheSameSerialRefreshesInlineControlsAfterSavedKeysChange() {
        val context = RuntimeEnvironment.getApplication()
        val serial = "22222FY2M8"
        assertTrue(AiDexProvisioningStore.saveAndInstall(context, serial, ByteArray(16), ByteArray(16)))
        val form = AiDexKeyManagementState(serial)
        form.bloodControlSelected.value = true
        compose.setContent {
            MaterialTheme {
                AiDexKeyManagementScreen(rememberWizardUiMetrics(), "", false, {}, {},
                    bloodControlAvailable = true, formState = form)
            }
        }
        val export = context.getString(R.string.export)
        compose.waitUntil(5000) { compose.onAllNodesWithText(export).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(AiDexProvisioningStore.removeSaved(context, serial))
        // AnimatedContent can retain the form during a quick visit to the saved page.
        compose.runOnIdle { form.selectSerial(serial) }
        compose.waitUntil(5000) { compose.onAllNodesWithText(export).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(context.getString(R.string.ottai_credentials_import)).assertExists()
        compose.onNode(hasSetTextAction()).assertTextContains(serial)
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
