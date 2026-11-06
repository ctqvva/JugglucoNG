package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
        var toggled: Boolean? = null
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Box(Modifier.size(420.dp, 1_000.dp).testTag("screen")) {
                    LumiflexCloudSettingsContent(signedIn, onSync = { syncClicks++ }, onEnabled = { toggled = it })
                }
            }
        }
        compose.onNodeWithText("+7 (999) 123-45-67").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Войти").assertDoesNotExist()
        compose.onNodeWithText("Ожидание показаний").assertExists()
        assertEquals(compose.onNodeWithText("Ожидание показаний").fetchSemanticsNode().boundsInRoot.left,
            compose.onNodeWithText("+7 (999) 123-45-67").fetchSemanticsNode().boundsInRoot.left)
        val master = compose.onNode(isToggleable()).assertIsOn()
        assertTrue(master.fetchSemanticsNode().boundsInRoot.bottom <
            compose.onNodeWithText("+7 (999) 123-45-67").fetchSemanticsNode().boundsInRoot.top)
        master.performClick()
        assertEquals(false, toggled)
        compose.onNodeWithContentDescription("Действия с аккаунтом").assertDoesNotExist()
        compose.onNodeWithText("Изменить данные входа").assertDoesNotExist()
        for (label in listOf("Выйти", "Отправить сейчас", "Привязать сенсор")) {
            compose.onNodeWithText(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        }
        capture("signed-in-ru")
        compose.onNodeWithText("Отправить сейчас").performScrollTo().performClick()
        assertEquals(1, syncClicks)
    }

    @Test
    fun syncedAccountShowsBoundSensorAndDirectLogout() {
        var signOut = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Box(Modifier.size(420.dp, 1_000.dp).testTag("screen")) {
                    LumiflexCloudSettingsContent(signedIn.copy(sync = LumiflexCloudSettings.SyncStatus(
                        state = LumiflexCloudSettings.SyncState.SYNCED,
                        lastSuccessMs = 1_793_918_000_000, boundSensors = listOf("222227KUFY"),
                    )), onSignOut = { signOut = true })
                }
            }
        }
        compose.onNodeWithText("X-222227KUFY").assertIsDisplayed()
        compose.onNodeWithText("Привязать сенсор").assertDoesNotExist()
        compose.onNodeWithText("Отправить сейчас").assertIsDisplayed()
        capture("synced-ru")
        compose.onNodeWithText("Выйти").performClick()
        assertTrue(signOut)
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

    @Test
    fun registrationHasAFixedCountryPrefixAndNormalizesTypedAndPastedNumbers() {
        var current by mutableStateOf(LumiflexCloudUiState(registration = true))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Box(Modifier.size(420.dp, 1_000.dp).testTag("screen")) {
                    LumiflexCloudSettingsContent(current, onPhone = { current = current.copy(phone = it) })
                }
            }
        }
        compose.onNodeWithText("+7", useUnmergedTree = true).assertIsDisplayed()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("9991234567")
        compose.waitUntil { current.phone == "79991234567" }
        field.performTextReplacement("+7 (999) 123-45-67")
        compose.waitUntil { current.phone == "79991234567" }
        field.performKeyInput { pressKey(Key.Backspace) }
        compose.waitUntil { current.phone == "7999123456" }
        field.performTextInput("7")
        compose.waitUntil { current.phone == "79991234567" }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Получить код из СМС").assertIsEnabled()
        compose.onNodeWithText("Укажите российский номер", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Вход здесь разлогинивает", substring = true).assertDoesNotExist()
        capture("phone-entry-ru")
    }
}
