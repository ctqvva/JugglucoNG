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
            compose.onAllNodes(isDialog()).onLast().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun failedTestRequiresConfirmationBeforeDiscard() = failedDiscard(1f)
    @Test fun largeFontFailureAndDiscardConfirmationRemainUsable() = failedDiscard(2f)

    private fun failedDiscard(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        var discarded = 0
        var dismissed = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                    OttaiFirmwareStatusDialog(OttaiFirmwareState(stage = OttaiFirmwareStage.FAILED,
                        firmware = "E1.1.4(V1.8.S2600.1),STDRD", errorCode = "calibration_missing"), true,
                        onInstall = { error("No install from failure") }, onPrepare = { error("No preparation from failure") },
                        onDiscard = { done -> discarded++; done(Result.success(Unit)) }, onDismiss = { dismissed++ })
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_discard)).assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(text(R.string.ottai_ota_title)).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(scale, layouts.single().layoutInput.density.fontScale, 0f)
        capture(if (scale == 1f) "recovery-failed" else "recovery-failed-large-font")
        compose.onNodeWithText(text(R.string.ottai_ota_discard)).performClick()
        compose.onNodeWithText(text(R.string.ottai_ota_discard_confirm)).assertIsDisplayed()
        assertEquals(0, discarded)
        capture(if (scale == 1f) "recovery-discard-confirm" else "recovery-discard-confirm-large-font")
        compose.onAllNodesWithText(text(R.string.ottai_ota_discard)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, discarded); assertEquals(1, dismissed) }
    }

    @Test fun verifiedFreshFirmwareWaitsForASeparatePreparationConfirmation() {
        var prepared = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                OttaiFirmwareStatusDialog(OttaiFirmwareState(stage = OttaiFirmwareStage.PREPARATION_READY,
                    firmware = "V1.8.0", canPrepareParameters = true), true,
                    onInstall = { error("Verified target cannot be flashed again") },
                    onPrepare = { prepared++ }, onDiscard = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_finish_preparation)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_install)).assertDoesNotExist()
        assertEquals(0, prepared)
        capture("recovery-awaiting-preparation")
        compose.onNodeWithText(text(R.string.ottai_ota_parameters)).performClick()
        assertEquals(0, prepared)
        compose.onNodeWithText(text(R.string.ok)).performClick()
        compose.runOnIdle { assertEquals(1, prepared) }
    }

    @Test fun unreadableRecoveryOffersOnlyConfirmedDiscardAndClose() {
        var discarded = 0
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareModeDialog(true, onMode = { error("No new experiment with a pending journal") },
                    onDiscard = { done -> discarded++; done(Result.success(Unit)) }, onDismiss = { dismissed++ })
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_fresh_mode)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_started_mode)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_discard)).performClick()
        assertEquals(0, discarded)
        compose.onAllNodesWithText(text(R.string.ottai_ota_discard)).onLast().performClick()
        compose.runOnIdle { assertEquals(1, discarded); assertEquals(1, dismissed) }
    }

    @Test fun freshExperimentsRemainAvailableWithoutAPendingJournal() {
        var selected: OttaiFirmwareMode? = null
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareModeDialog(false, onMode = { selected = it }, onDiscard = { error("No recovery") }, onDismiss = {})
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_fresh_mode)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_started_mode)).performClick()
        compose.runOnIdle { assertEquals(OttaiFirmwareMode.STARTED_SENSOR, selected) }
        compose.onNodeWithText(text(R.string.ottai_ota_discard)).assertDoesNotExist()
    }

    @Test fun noOfferExposesAnExplicitCloudAuthRecheck() = cloudRecheck(1f)
    @Test fun largeFontCloudAuthRecheckAndCloseRemainVisible() = cloudRecheck(2f)

    private fun cloudRecheck(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        var checked = 0
        var closed = 0
        var stage by mutableStateOf(OttaiFirmwareStage.NO_UPDATE)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                OttaiFirmwareStatusDialog(OttaiFirmwareState(stage = stage, mode = OttaiFirmwareMode.STARTED_SENSOR,
                    firmware = "E1.2.6(V1.7.SH2616.1),STDRD"), false,
                    onInstall = { error("No automatic installation") }, onPrepare = { error("No preparation") },
                    onDiscard = {}, onDismiss = { closed++ }, onCloudRecheck = {
                        checked++
                        stage = OttaiFirmwareStage.CLOUD_AUTHENTICATING
                    })
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.close)).assertIsDisplayed()
        capture(if (scale == 1f) "cloud-recheck" else "cloud-recheck-large-font")
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).performClick()
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_authenticating)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.close)).performClick()
        compose.runOnIdle { assertEquals(1, checked); assertEquals(1, closed) }
    }

    @Test fun pendingRecoveryDoesNotExposeANewCloudRecheck() {
        compose.setContent {
            MaterialTheme {
                OttaiFirmwareStatusDialog(OttaiFirmwareState(stage = OttaiFirmwareStage.READY, recovery = true), true,
                    onInstall = {}, onPrepare = {}, onDiscard = {}, onDismiss = {},
                    onCloudRecheck = { error("Recovery must use the saved package") })
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru-w400dp-h800dp-mdpi")
    fun russianLargeFontFreshActionsRemainVisible() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                OttaiFirmwareStatusDialog(OttaiFirmwareState(stage = OttaiFirmwareStage.READY,
                    canPrepareParameters = true), false, onInstall = {}, onPrepare = {}, onDiscard = {},
                    onDismiss = {}, onCloudRecheck = {})
            }
        }
        compose.onNodeWithText(text(R.string.ottai_ota_install)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_cloud_recheck)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ottai_ota_parameters)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.close)).assertIsDisplayed()
        capture("cloud-recheck-fresh-russian-large-font")
    }

    @Test fun blockedConnectionExplainsRecoveryAndDiscardReleasesOnlyThatBlock() {
        val id = "AABBCCDDEEFF"
        val file = File(context.filesDir, "ottai-ota/$id.bin.bak")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1)) // Even an unreadable journal must be discardable.
        assertEquals(text(R.string.ottai_ota_pending), ottaiConnectFailureMessage(context, id))
        assertNull(OttaiFirmwareSession.pendingMode(context, id, "AA:BB:CC:DD:EE:FF"))
        OttaiFirmwareSession.discardPendingTransfer(context, id)
        assertEquals(text(R.string.ottai_connect_failed), ottaiConnectFailureMessage(context, id))
    }
}
