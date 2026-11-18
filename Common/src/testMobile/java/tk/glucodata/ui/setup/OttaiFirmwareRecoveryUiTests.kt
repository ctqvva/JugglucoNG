package tk.glucodata.ui.setup

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
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
    private fun capture(name: String, dialog: Boolean = false) {
        val output = File("build/outputs/layout-regressions/ottai-$name.png")
        output.parentFile?.mkdirs()
        val node = if (dialog) compose.onNode(isDialog()) else compose.onNodeWithTag("panel")
        output.outputStream().use {
            node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun failedTestRequiresConfirmationBeforeDiscard() = failedDiscard(1f)
    @Test fun largeFontFailureAndDiscardConfirmationRemainUsable() = failedDiscard(2f)

    private fun failedDiscard(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        var discarded = 0
        var left = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                rememberOttaiFirmwareContent(OttaiFirmwareState(stage = OttaiFirmwareStage.FAILED,
                    firmware = "E1.1.4(V1.8.S2600.1),STDRD", errorCode = "calibration_missing"), true,
                    onInstall = { error("No install from failure") }, onPrepare = { error("No preparation from failure") },
                    onDiscard = { done -> discarded++; done(Result.success(Unit)) }, onLeave = { left++ },
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("panel")).invoke()
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.ottai_ota_discard)).performScrollTo().assertIsDisplayed()
        capture("recovery-failed-$scale")
        compose.onNodeWithText(text(R.string.ottai_ota_discard)).performClick()
        compose.onNodeWithText(text(R.string.ottai_ota_discard_confirm)).assertIsDisplayed()
        assertEquals(0, discarded)
        capture("recovery-discard-confirm-$scale", dialog = true)
        compose.onAllNodesWithText(text(R.string.ottai_ota_discard)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, discarded); assertEquals(1, left) }
    }

    @Test fun verifiedFreshFirmwareWaitsForASeparatePreparationConfirmation() {
        var prepared = 0
        var stage by mutableStateOf(OttaiFirmwareStage.PREPARATION_READY)
        compose.setContent {
            MaterialTheme {
                rememberOttaiFirmwareContent(OttaiFirmwareState(stage = stage, firmware = "V1.8.0", canPrepareParameters = true), true,
                    onInstall = { error("Verified target cannot be flashed again") },
                    onPrepare = { prepared++; stage = OttaiFirmwareStage.PREPARING },
                    onDiscard = {}, onLeave = {}).invoke()
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.ottai_ota_finish_preparation)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_install)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_parameters)).performClick()
        assertEquals(0, prepared)
        compose.onNodeWithText(text(R.string.ok)).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.runOnIdle { assertEquals(1, prepared); stage = OttaiFirmwareStage.COMPLETE }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun unreadableRecoveryOffersOnlyConfirmedDiscard() {
        var discarded = 0
        var left = 0
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareModePicker(true, onMode = { error("No experiment with a pending journal") },
                    onDiscard = { done -> discarded++; done(Result.success(Unit)) }, onDiscarded = { left++ })
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.ottai_ota_fresh_mode)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_started_mode)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_discard)).performClick()
        assertEquals(0, discarded)
        compose.onAllNodesWithText(text(R.string.ottai_ota_discard)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, discarded); assertEquals(1, left) }
    }

    @Test fun freshExperimentsOpenWithoutADialog() {
        var selected: OttaiFirmwareMode? = null
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareModePicker(false, onMode = { selected = it },
                    onDiscard = { error("No recovery") }, onDiscarded = {})
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.ottai_ota_fresh_mode)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_started_mode)).performClick()
        compose.runOnIdle { assertEquals(OttaiFirmwareMode.STARTED_SENSOR, selected) }
    }

    @Test fun noOfferExposesAnExplicitCloudAuthRecheck() = cloudRecheck(1f)
    @Test fun largeFontCloudAuthRecheckRemainsVisible() = cloudRecheck(2f)

    private fun cloudRecheck(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        var checked = 0
        var stage by mutableStateOf(OttaiFirmwareStage.NO_UPDATE)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                rememberOttaiFirmwareContent(OttaiFirmwareState(stage = stage, mode = OttaiFirmwareMode.STARTED_SENSOR,
                    firmware = "E1.2.6(V1.7.SH2616.1),STDRD"), false,
                    onInstall = { error("No automatic installation") }, onPrepare = { error("No preparation") },
                    onDiscard = {}, onLeave = {}, onCloudRecheck = {
                        checked++
                        stage = OttaiFirmwareStage.CLOUD_AUTHENTICATING
                    }, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("panel")).invoke()
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).performScrollTo().assertIsDisplayed()
        capture("cloud-recheck-$scale")
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).performClick()
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_authenticating)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, checked) }
    }

    @Test fun pendingRecoveryDoesNotExposeANewCloudRecheck() {
        compose.setContent {
            MaterialTheme {
                rememberOttaiFirmwareContent(OttaiFirmwareState(stage = OttaiFirmwareStage.READY, recovery = true), true,
                    onInstall = {}, onPrepare = {}, onDiscard = {}, onLeave = {},
                    onCloudRecheck = { error("Recovery must use the saved package") }).invoke()
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru-w400dp-h800dp-mdpi")
    fun russianLargeFontFreshActionsRemainVisible() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                rememberOttaiFirmwareContent(OttaiFirmwareState(stage = OttaiFirmwareStage.READY,
                    canPrepareParameters = true), false, onInstall = {}, onPrepare = {}, onDiscard = {},
                    onLeave = {}, onCloudRecheck = {},
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("panel")).invoke()
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.ottai_ota_install)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_parameters)).assertIsDisplayed()
        capture("firmware-actions-russian-large-font")
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

    @Test fun onlyAnExplicitInstallOpensTheTransferDialog() {
        var installed = 0
        var stage by mutableStateOf(OttaiFirmwareStage.READY)
        compose.setContent {
            MaterialTheme {
                rememberOttaiFirmwareContent(OttaiFirmwareState(stage = stage, firmware = "V1.7", target = "V1.8",
                    totalBytes = 1_024, canPrepareParameters = stage == OttaiFirmwareStage.PREPARATION_READY), false,
                    onInstall = { installed++; stage = OttaiFirmwareStage.INSTALLING },
                    onPrepare = {}, onDiscard = {}, onLeave = {}).invoke()
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.ottai_ota_install)).performClick()
        assertEquals(0, installed)
        compose.onNodeWithText(text(R.string.ottai_ota_confirm)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ok)).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.ok)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, installed); stage = OttaiFirmwareStage.RECONNECTING }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.runOnIdle { stage = OttaiFirmwareStage.PREPARATION_READY }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.ottai_ota_parameters)).assertIsDisplayed()
    }
}
