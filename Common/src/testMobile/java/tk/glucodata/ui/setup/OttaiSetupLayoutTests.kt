package tk.glucodata.ui.setup

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.R
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w1200dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OttaiSetupLayoutTests {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        val output = File("build/outputs/layout-regressions/ottai-$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("card").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun accountUsesPlainCardWithoutHeadingAtNormalAndLargeFontSizes() {
        var scale by mutableFloatStateOf(1f)
        var region by mutableStateOf(OttaiRegion.GLOBAL)
        var signedIn by mutableStateOf(false)
        var heading = ""
        var signOut = ""
        var regionLabels = emptyList<String>()
        compose.setContent {
            val context = LocalContext.current
            heading = context.getString(R.string.ottai_account_title)
            signOut = context.getString(R.string.ottai_sign_out)
            regionLabels = OttaiRegion.entries.map { context.getString(it.fullLabelRes) }
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                    Box(Modifier.width(320.dp).testTag("card")) {
                        OttaiAccountCard(
                            signedIn = signedIn,
                            accountLogin = "a.long.account@example.com",
                            accountRegion = region,
                            region = region,
                            onRegionChange = { region = it },
                            message = "",
                            form = remember { OttaiSignInForm() },
                            onSignedIn = {},
                            onSignOut = {},
                            onRegister = {},
                            scope = rememberCoroutineScope(),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(heading).assertDoesNotExist()
        capture("global-normal")
        compose.runOnIdle { scale = 2f }
        compose.onNodeWithText(heading).assertDoesNotExist()
        regionLabels.forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertFalse("Region label clipped: $label", layout.didOverflowHeight)
            assertFalse("Region label truncated: $label", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        }
        capture("global-large-font")
        compose.runOnIdle { region = OttaiRegion.CN }
        capture("china-large-font")
        compose.runOnIdle { signedIn = true }
        compose.onNodeWithText(heading).assertDoesNotExist()
        val accountBounds = compose.onNodeWithText("a.long.account@example.com").fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithText(signOut).fetchSemanticsNode().boundsInRoot
        assertTrue("Sign out must reflow below a long account", actionBounds.top >= accountBounds.bottom)
        capture("signed-in-large-font")
    }

    @Test
    fun pendingAccountRequestKeepsItsRegionAndSmsChallenge() {
        var region by mutableStateOf(OttaiRegion.CN)
        val form = OttaiSignInForm().apply {
            busy = true
            requestId = "pending-sms-challenge"
            code = "123456"
        }
        var globalLabel = ""
        compose.setContent {
            globalLabel = LocalContext.current.getString(OttaiRegion.GLOBAL.fullLabelRes)
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    OttaiAccountCard(false, "", region, region, { region = it }, "", form, {}, {}, {}, rememberCoroutineScope())
                }
            }
        }
        compose.onNodeWithText(globalLabel).performClick()
        compose.runOnIdle {
            assertEquals(OttaiRegion.CN, region)
            assertEquals("pending-sms-challenge", form.requestId)
            form.busy = false
        }
        compose.onNodeWithText(globalLabel).performClick()
        compose.runOnIdle {
            assertEquals(OttaiRegion.GLOBAL, region)
            assertEquals("", form.requestId)
            assertEquals("", form.code)
        }
    }

    @Test
    fun pendingSensorRequestDisablesTheSingleControlledIdField() {
        var enabled by mutableStateOf(true)
        var selected by mutableStateOf("")
        var continueLabel = ""
        compose.setContent {
            continueLabel = LocalContext.current.getString(R.string.continue_action)
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    OttaiSensorIdEntry(value = selected, onValueChange = { selected = it }, enabled = enabled)
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("70D07E2552DB")
        compose.runOnIdle { assertEquals("70D07E2552DB", selected); enabled = false }
        compose.onNodeWithText("70D07E2552DB").assertIsNotEnabled()
        compose.onNodeWithText(continueLabel).assertDoesNotExist()
        compose.runOnIdle { enabled = true; selected = "54DCE9C7FB72" }
        compose.onNode(hasSetTextAction()).assertTextContains("54DCE9C7FB72")
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText(continueLabel).assertDoesNotExist()
    }

    @Test
    fun connectAppearsOnlyWhenUsableAndCredentialActionsStayAvailable() {
        var canConnect by mutableStateOf(false)
        var loading by mutableStateOf(false)
        var connected = false
        var imported = false
        var exported = false
        var importLabel = ""
        var replaceLabel = ""
        var exportLabel = ""
        var statusTitle = ""
        compose.setContent {
            importLabel = LocalContext.current.getString(R.string.ottai_credentials_import)
            replaceLabel = LocalContext.current.getString(R.string.ottai_credentials_replace)
            exportLabel = LocalContext.current.getString(R.string.export)
            statusTitle = LocalContext.current.getString(R.string.ottai_state_active)
            MaterialTheme(typography = AppTypography) {
                androidx.compose.foundation.layout.Column(Modifier.width(320.dp).testTag("card")) {
                    OttaiSensorCard(
                        sensorId = "70D07E2552DB",
                        state = OttaiMaterialState.ACTIVE,
                        loading = loading,
                        signedIn = true,
                        deviceVersion = "",
                        bindingState = OttaiCloudBindingUiState.HIDDEN,
                        busy = false,
                        canConnect = canConnect,
                        connectLabel = "Connect",
                        message = "",
                        messageIsError = false,
                        onConnect = { connected = true },
                        onImport = { imported = true },
                        onSignIn = {},
                        onExport = { exported = true },
                        onSensorIdChange = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Connect").assertDoesNotExist()
        compose.onNodeWithText(statusTitle).assertDoesNotExist()
        compose.runOnIdle { canConnect = true; loading = true }
        compose.onNodeWithText("Connect").assertDoesNotExist()
        compose.onNodeWithText(importLabel).assertIsEnabled()
        compose.onNodeWithText(exportLabel).assertDoesNotExist()
        compose.runOnIdle { loading = false }
        val connect = compose.onNodeWithText("Connect").assertIsEnabled()
        val importAction = compose.onNodeWithText(replaceLabel).assertIsEnabled()
        val exportAction = compose.onNodeWithText(exportLabel).assertIsEnabled()
        val importBounds = importAction.fetchSemanticsNode().boundsInRoot
        val exportBounds = exportAction.fetchSemanticsNode().boundsInRoot
        assertEquals("Import and Export must have equal width", exportBounds.width, importBounds.width, 1f)
        assertEquals("Import and Export must have equal height", exportBounds.height, importBounds.height, 1f)
        capture("selected-sensor-credentials")
        importAction.performClick()
        exportAction.performClick()
        connect.performClick()
        compose.runOnIdle {
            assertTrue(connected)
            assertTrue(imported)
            assertTrue(exported)
        }
    }

    @Test
    fun choosingNearbySensorUpdatesTheFieldInsideTheSameSensorCard() {
        var selectedId by mutableStateOf("")
        var scrollRequest by mutableIntStateOf(0)
        var focusSelected by mutableStateOf(false)
        var continueLabel = ""
        compose.setContent {
            continueLabel = LocalContext.current.getString(R.string.continue_action)
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                androidx.compose.material3.Surface(Modifier.width(320.dp).height(640.dp).testTag("card")) {
                    OttaiSearchList(
                        selectedSensor = {
                            OttaiSensorCard(
                                sensorId = selectedId,
                                onSensorIdChange = { selectedId = it },
                                state = OttaiMaterialState.READY_TO_ACTIVATE,
                                loading = false,
                                signedIn = true,
                                deviceVersion = if (selectedId.isBlank()) "" else "V1.5.S2428.1",
                                bindingState = OttaiCloudBindingUiState.HIDDEN,
                                busy = false,
                                canConnect = selectedId.isNotBlank(),
                                connectLabel = "Connect",
                                message = "",
                                messageIsError = false,
                                onConnect = {}, onImport = {}, onSignIn = {},
                                onExport = if (selectedId.isBlank()) null else ({}),
                            )
                        },
                        scrollRequest = scrollRequest,
                        focusSelectedSensor = focusSelected,
                        deviceCount = 1,
                        header = { androidx.compose.material3.Text("Searching for sensors…") },
                        devices = {
                            item {
                                androidx.compose.material3.TextButton(onClick = { selectedId = "54DCE9C7FB72" }) {
                                    androidx.compose.material3.Text("Nearby Ottai")
                                }
                            }
                        },
                        footer = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Connect").assertDoesNotExist()
        compose.onNodeWithText(continueLabel).assertDoesNotExist()
        compose.onNodeWithText("Nearby Ottai").performClick()
        compose.onNodeWithText("Searching for sensors…").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("54DCE9C7FB72")
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("Connect").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(continueLabel).assertDoesNotExist()
        capture("single-sensor-card")
        compose.runOnIdle { focusSelected = true; scrollRequest += 1 }
        compose.onNodeWithText("Connect").assertIsDisplayed()
        compose.runOnIdle { focusSelected = false; scrollRequest += 1 }
        compose.onNodeWithText("Searching for sensors…").assertIsDisplayed()
    }

    @Test
    fun pageImportUsesTheAvailableWidthWithoutAnExtraInset() {
        var label = ""
        compose.setContent {
            label = LocalContext.current.getString(R.string.ottai_import_file)
            MaterialTheme {
                Box(Modifier.width(320.dp).testTag("page")) {
                    OttaiImportButton(onClick = {}, enabled = true)
                }
            }
        }
        val page = compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        assertEquals(page.left, action.left, 1f)
        assertEquals(page.right, action.right, 1f)
    }

}
