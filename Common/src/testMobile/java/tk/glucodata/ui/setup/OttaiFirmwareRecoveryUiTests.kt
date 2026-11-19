package tk.glucodata.ui.setup

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.R
import tk.glucodata.drivers.ottai.*
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OttaiFirmwareRecoveryUiTests {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun text(id: Int) = context.getString(id)
    private fun capture(name: String) {
        val output = File("build/outputs/layout-regressions/ottai-$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun modeChoicesAndManagedSensorUseACompactDialog() {
        var managed by mutableStateOf(false)
        var state by mutableStateOf<OttaiFirmwareState?>(null)
        var selected: OttaiFirmwareMode? = null
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                OttaiFirmwareDialogContent(state = state, managed = managed, onMode = {
                    selected = it
                    state = OttaiFirmwareState(mode = it)
                },
                    onDiscard = {}, onLeave = {})
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        val dialogId = compose.onNode(isDialog()).fetchSemanticsNode().id
        compose.onNodeWithText(text(R.string.ottai_ota_started_mode)).assertIsDisplayed()
        capture("firmware-mode-dialog")
        compose.onNodeWithText(text(R.string.ottai_ota_started_mode)).performClick()
        compose.onNodeWithText(text(R.string.connecting)).assertIsDisplayed()
        assertEquals(dialogId, compose.onNode(isDialog()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertEquals(OttaiFirmwareMode.STARTED_SENSOR, selected)
            managed = true
            state = null
        }
        compose.onNodeWithText(text(R.string.ottai_ota_started_mode)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_disconnect_first)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.close)).assertIsDisplayed()
        capture("firmware-managed-dialog")
    }

    @Test fun failedCheckAndDiscardConfirmationShareOneDialog() = confirmDiscard(1f)
    @Test fun largeFontDiscardConfirmationRemainsUsable() = confirmDiscard(2f)

    private fun confirmDiscard(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        var discarded = 0
        var left = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                OttaiFirmwareDialogContent(OttaiFirmwareState(stage = OttaiFirmwareStage.FAILED,
                    firmware = "E1.1.4(V1.8.S2600.1),STDRD", errorCode = "calibration_missing"), pending = true,
                    onInstall = { error("No install from failure") }, onPrepare = { error("No preparation from failure") },
                    onDiscard = { done -> discarded++; done(Result.success(Unit)) }, onLeave = { left++ })
            }
        }
        compose.onNodeWithText("calibration_missing", substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_discard)).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.ottai_ota_discard_confirm)).performScrollTo().assertIsDisplayed()
        assertEquals(0, discarded)
        capture("firmware-discard-dialog-$scale")
        compose.onAllNodesWithText(text(R.string.ottai_ota_discard)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, discarded); assertEquals(1, left) }
    }

    @Test fun pendingRecoveryRequiresAnExplicitRetry() {
        var selected: OttaiFirmwareMode? = null
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareDialogContent(pending = true, recoveryMode = OttaiFirmwareMode.STARTED_SENSOR,
                    onMode = { selected = it }, onDiscard = {}, onLeave = {})
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_fresh_mode)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_started_mode)).assertDoesNotExist()
        assertNull(selected)
        compose.onNodeWithText(text(R.string.ottai_retry)).performClick()
        compose.runOnIdle { assertEquals(OttaiFirmwareMode.STARTED_SENSOR, selected) }
    }

    @Test fun unreadableRecoveryOffersDiscardWithoutTryingToConnect() {
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareDialogContent(pending = true, onMode = { error("Unreadable recovery") },
                    onDiscard = {}, onLeave = {})
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_fresh_mode)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_retry)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_discard)).assertIsDisplayed()
    }

    @Test fun noOfferExposesAnExplicitCloudAuthRecheck() {
        var checked = 0
        var stage by mutableStateOf(OttaiFirmwareStage.NO_UPDATE)
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareDialogContent(OttaiFirmwareState(stage = stage, mode = OttaiFirmwareMode.STARTED_SENSOR),
                    onDiscard = {}, onLeave = {}, onCloudRecheck = {
                        checked++
                        stage = OttaiFirmwareStage.CLOUD_AUTHENTICATING
                    })
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).performClick()
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_authenticating)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, checked) }
    }

    @Test fun pendingRecoveryDoesNotExposeANewCloudRecheck() {
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareDialogContent(OttaiFirmwareState(stage = OttaiFirmwareStage.READY, recovery = true),
                    pending = true, onDiscard = {}, onLeave = {},
                    onCloudRecheck = { error("Recovery must use the saved package") })
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru-w400dp-h800dp-mdpi")
    fun russianLargeFontFreshActionsRemainReachable() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                OttaiFirmwareDialogContent(OttaiFirmwareState(stage = OttaiFirmwareStage.READY,
                    firmware = "E1.2.3(V1.7.SH2542.1),STDRD", target = "E1.2.7(V1.7.SH2640.3)",
                    canPrepareParameters = true), onDiscard = {}, onLeave = {})
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_install)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_parameters)).performScrollTo().assertIsDisplayed()
        capture("firmware-actions-dialog-russian-large-font")
    }

    @Test fun blockedConnectionShowsPendingRecoveryAndDiscardReleasesTheBlock() {
        val id = "AABBCCDDEEFF"
        val file = File(context.filesDir, "ottai-ota/$id.bin.bak")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1))
        assertEquals(text(R.string.ottai_ota_pending), ottaiConnectFailureMessage(context, id))
        assertNull(OttaiFirmwareSession.pendingMode(context, id, "AA:BB:CC:DD:EE:FF"))
        OttaiFirmwareSession.discardPendingTransfer(context, id)
        assertEquals(text(R.string.status_connection_failed), ottaiConnectFailureMessage(context, id))
    }

    @Test fun installAndFactoryWritesStayInOneDialogAndRequireSeparateConfirmation() {
        var installed = 0
        var prepared = 0
        var stage by mutableStateOf(OttaiFirmwareStage.READY)
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareDialogContent(OttaiFirmwareState(stage = stage, firmware = "V1.7", target = "V1.8",
                    totalBytes = 1_024, canPrepareParameters = stage == OttaiFirmwareStage.PREPARATION_READY),
                    onInstall = { installed++; stage = OttaiFirmwareStage.INSTALLING },
                    onPrepare = { prepared++; stage = OttaiFirmwareStage.PREPARING },
                    onDiscard = {}, onLeave = {})
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_install)).performClick()
        assertEquals(0, installed)
        compose.onNodeWithText(text(R.string.ottai_ota_confirm)).assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onAllNodesWithText(text(R.string.ottai_ota_install)).onLast().performClick()
        compose.onNodeWithText(text(R.string.close)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, installed); stage = OttaiFirmwareStage.RECONNECTING }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.runOnIdle { stage = OttaiFirmwareStage.PREPARATION_READY }
        compose.onNodeWithText(text(R.string.ottai_ota_install)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_parameters)).performClick()
        assertEquals(0, prepared)
        compose.onAllNodesWithText(text(R.string.ottai_ota_parameters)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, prepared); stage = OttaiFirmwareStage.COMPLETE }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.close)).assertIsDisplayed()
    }
}
