package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import tk.glucodata.share.lumiflex.LumiflexCloudSettings
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "ru-w460dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumiflexCloudSettingsUiTests {
    @get:Rule val compose = createComposeRule()

    private val signedIn = LumiflexCloudUiState(
        account = "79991234567", password = "savedPassword",
        session = LumiflexCloudSettings.Session("79991234567", "fixture-token", "u1", 1),
        enabled = true,
    )

    private fun capture(name: String) {
        val output = File("build/outputs/layout-regressions/lumiflex-$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun signedInAccountHasSyncActionsAndNoRedundantLoginForm() {
        var syncClicks = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Box(Modifier.size(420.dp, 1_000.dp).testTag("screen")) {
                    LumiflexCloudSettingsContent(signedIn, onSync = { syncClicks++ })
                }
            }
        }
        compose.onNodeWithText("79991234567").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Войти").assertDoesNotExist()
        compose.onNodeWithText("Ожидание показаний").assertExists()
        capture("signed-in-ru")
        compose.onNodeWithText("Отправить сейчас").performScrollTo().performClick()
        assertEquals(1, syncClicks)
    }

    @Test
    fun expiredSessionKeepsEnteredPasswordAndOffersAccountCreation() {
        var current by mutableStateOf(signedIn.copy(
            session = LumiflexCloudSettings.Session("79991234567", "", "", 2),
            sessionExpired = true, editing = true,
        ))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Box(Modifier.size(420.dp, 1_000.dp).testTag("screen")) {
                    LumiflexCloudSettingsContent(current, onPassword = { current = current.copy(password = it) })
                }
            }
        }
        compose.onNodeWithText("Сеанс истёк · войдите снова").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithContentDescription("Показать пароль").performClick()
        compose.onNodeWithText("savedPassword").assertExists()
        compose.onNodeWithText("Создать аккаунт").performScrollTo().assertIsEnabled()
        capture("expired-ru")
    }

    @Test
    fun verifiedPhoneResumesPasswordSetupWithoutRequestingAnotherSms() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Box(Modifier.size(420.dp, 1_000.dp).testTag("screen")) {
                    LumiflexCloudSettingsContent(signedIn.copy(passwordSetupRequired = true))
                }
            }
        }
        compose.onNodeWithText("Телефон подтверждён · завершите настройку").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("Получить код из СМС").assertDoesNotExist()
        compose.onNodeWithText("Войти").assertDoesNotExist()
        capture("password-setup-ru")
    }

    @Test
    fun registrationShowsCodeEntryAndDisablesResendingDuringCooldown() {
        var verified = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Box(Modifier.size(420.dp, 1_000.dp).testTag("screen")) {
                    LumiflexCloudSettingsContent(LumiflexCloudUiState(
                        registration = true, phone = "+7 999 123-45-67", code = "123456", codeSent = true, resendSeconds = 55,
                    ), onVerifyCode = { verified = true })
                }
            }
        }
        compose.onNodeWithText("Повторить через 55 с").assertIsNotEnabled()
        compose.onNodeWithText("Подтвердить номер").performScrollTo().performClick()
        assertTrue(verified)
        capture("registration-ru")
    }

    @Test
    fun narrowScreenWithLargeRussianTextKeepsAccountAndSyncActionsReachable() {
        var signOut = false
        var synced = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                    Box(Modifier.size(320.dp, 900.dp).testTag("screen")) {
                        LumiflexCloudSettingsContent(signedIn.copy(sync = LumiflexCloudSettings.SyncStatus(
                            state = LumiflexCloudSettings.SyncState.WAITING_FOR_SENSOR, pending = 42,
                        )), onSync = { synced = true }, onSignOut = { signOut = true })
                    }
                }
            }
        }
        compose.onNodeWithText("Выйти").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(signOut)
        compose.onNodeWithText("Отправить сейчас").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(synced)
        capture("narrow-large-text-ru")
    }
}
