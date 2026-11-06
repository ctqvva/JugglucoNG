package tk.glucodata.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
import tk.glucodata.R
import tk.glucodata.share.lumiflex.FollowerInfo
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "ru-w460dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumiflexCloudFollowersUiTests {
    @get:Rule val compose = createComposeRule()
    private val row = FollowerInfo(userAuthorizationId = "a1", readerAlias = "Мама", readerUserName = "79991234567",
        normalPushState = 1, emergePushState = 1)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                Box(Modifier.size(448.dp, 860.dp).background(MaterialTheme.colorScheme.surface).testTag("screen")) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { block() }
                }
            }
        }
    }
    private fun capture(name: String, tag: String = "screen") {
        val output = File("build/outputs/layout-regressions/lumiflex-followers-$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use {
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun invitationStaysInTheOpenWhileMultipleFollowersAreCollapsed() {
        var selected: FollowerInfo? = null
        content {
            LumiflexCloudFollowersContent(LumiflexFollowersUiState(followers = listOf(row,
                row.copy(userAuthorizationId = "a2", readerAlias = "", readerUserName = "family+reader@example.org"))),
                actions = LumiflexFollowerActions(edit = { selected = it }))
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithText("Отправить приглашение").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(0)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Мама"))
        compose.onNodeWithText("+7 (999) 123-45-67").assertIsDisplayed()
        compose.onAllNodesWithText("family+reader@example.org").assertCountEquals(1)
        capture("multiple-collapsed-ru")
        compose.onNodeWithText("Мама").performClick()
        assertEquals(row, selected)
    }

    @Test fun inviteUsesSharedPhoneFormattingForPasteDeletionAndSubmission() {
        var current by mutableStateOf(LumiflexFollowersUiState())
        var invited = ""
        content {
            LumiflexInviteForm(current, LumiflexFollowerActions(
                account = { current = current.copy(account = it) }, alias = { current = current.copy(alias = it) },
                invite = { invited = current.account },
            ))
        }
        compose.onNodeWithText("+7", useUnmergedTree = true).assertIsDisplayed()
        val phone = compose.onAllNodes(hasSetTextAction())[0]
        phone.performTextInput("9991234567")
        compose.waitUntil { current.account == "79991234567" }
        phone.performTextReplacement("+7 (999) 123-45-67")
        compose.waitUntil { current.account == "79991234567" }
        phone.performKeyInput { pressKey(Key.Backspace) }
        compose.waitUntil { current.account == "7999123456" }
        compose.onNodeWithText("Отправить приглашение").assertIsNotEnabled()
        phone.performTextInput("7")
        compose.waitUntil { current.account == "79991234567" }
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("Мама")
        capture("invite-ru", "follower-invite")
        compose.onNodeWithText("Отправить приглашение").performClick()
        assertEquals("79991234567", invited)
    }


    @Test fun singleFollowerUsesTheApiDestinationEditorPattern() {
        content {
            LumiflexCloudFollowersContent(LumiflexFollowersUiState(followers = listOf(row), editor = row))
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("Пригласить подписчика").assertIsDisplayed()
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Обычные уведомления"))
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(4)
        capture("single-expanded-ru")
        compose.onNodeWithText("Сохранить").performScrollTo().assertIsNotEnabled()
    }

    @Test fun emailAliasAndFailedInviteRemainEditable() {
        var current by mutableStateOf(LumiflexFollowersUiState(emailMode = true,
            account = "family+reader@example.org", alias = "Мама", inviteError = R.string.lumiflex_status_invite_failed))
        content {
            LumiflexInviteForm(current, LumiflexFollowerActions(account = { current = current.copy(account = it) }))
        }
        compose.onNodeWithText("family+reader@example.org").assertExists()
        compose.onNodeWithText("Не удалось пригласить").assertIsDisplayed()
        compose.onNodeWithText("Отправить приглашение").assertIsEnabled()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("family+new@example.org")
        assertEquals("family+new@example.org", current.account)
        capture("invite-error-ru", "follower-invite")
    }

    @Test fun removalRequiresAnExplicitConfirmation() {
        var removed = 0
        content {
            LumiflexCloudFollowersContent(LumiflexFollowersUiState(followers = listOf(row), editor = row),
                actions = LumiflexFollowerActions(remove = { removed++ }))
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithContentDescription("Назад").assertIsDisplayed()
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Сохранить"))
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        capture("details-ru")
        compose.onNodeWithContentDescription("Удалить подписчика").performScrollTo().performClick()
        assertEquals(0, removed)
        compose.onNodeWithText("Подписчик «Мама» потеряет доступ к вашим показаниям.").assertIsDisplayed()
        compose.onNode(hasText("Удалить подписчика") and hasClickAction() and hasAnyAncestor(hasTestTag("follower-remove-confirm"))).performClick()
        assertEquals(1, removed)
    }

    @Test fun cancellingDirtyEditorKeepsDraftUntilDiscardIsConfirmed() {
        var dismissed = false
        content {
            val draft = row.copy(readerAlias = "Семья", normalPushState = 0)
            LumiflexCloudFollowersContent(LumiflexFollowersUiState(followers = listOf(row), editor = draft),
                actions = LumiflexFollowerActions(dismissEditor = { dismissed = true }))
        }
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Мама"))
        compose.onNodeWithText("Мама").performClick()
        compose.onNodeWithText("Отменить изменения?").assertIsDisplayed()
        assertFalse(dismissed)
        compose.onNode(hasText("Отменить") and hasClickAction() and hasAnyAncestor(hasTestTag("follower-discard-confirm"))).performClick()
        assertFalse(dismissed)
        compose.onNodeWithText("Семья").assertExists()
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Сохранить"))
        compose.onNodeWithText("Сохранить").assertIsEnabled()
    }

    @Test fun failedLoadingOffersRetryWithoutPretendingTheListIsEmpty() {
        var retried = false
        content {
            LumiflexCloudFollowersContent(LumiflexFollowersUiState(loadFailed = true),
                actions = LumiflexFollowerActions(refresh = { retried = true }))
        }
        compose.onNodeWithText("Подписчиков пока нет").assertDoesNotExist()
        compose.onNodeWithText("Не удалось загрузить подписчиков").assertIsDisplayed()
        compose.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
        capture("load-error-ru")
    }

    @Test fun narrowLargeTextEditorKeepsAllControlsAndActionsReachable() {
        var current by mutableStateOf(LumiflexFollowersUiState(followers = listOf(row), editor = row))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = AppTypography) {
                    Box(Modifier.size(320.dp, 900.dp).background(MaterialTheme.colorScheme.surface).testTag("screen")) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            LumiflexCloudFollowersContent(current,
                                actions = LumiflexFollowerActions(editChanged = { current = current.copy(editor = it) }))
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Опасно низкая глюкоза"))
        compose.onNodeWithText("Опасно низкая глюкоза").assertIsDisplayed().performClick()
        assertEquals(0, current.editor!!.emergePushState)
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Сохранить"))
        compose.onNodeWithText("Сохранить").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Удалить подписчика").performScrollTo().assertIsDisplayed()
        capture("narrow-large-text-ru")
    }
    @Test fun apiDestinationControlsKeepTheirInsetsTouchTargetsAndHeaderRemoval() {
        content { LumiflexCloudFollowersContent(LumiflexFollowersUiState(followers = listOf(row), editor = row)) }
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Обычные уведомления"))
        val control = compose.onNode(hasText("Обычные уведомления") and hasClickAction())
        assertTrue(control.fetchSemanticsNode().boundsInRoot.height >= 64f)
        val field = compose.onNode(hasSetTextAction() and hasText("Мама")).fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("Обычные уведомления", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(field.left + 16f, label.left, 0.5f)
        control.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        capture("controls-pressed-ru")
        control.performTouchInput { up() }
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Сохранить"))
        val save = compose.onNodeWithText("Сохранить").fetchSemanticsNode().boundsInRoot
        assertEquals(field.width, save.width, 0.5f)
        assertTrue(save.height >= 56f)
        capture("controls-actions-ru")
        compose.onNodeWithContentDescription("Удалить подписчика").performScrollTo()
        val remove = compose.onNode(hasContentDescription("Удалить подписчика") and hasClickAction()).fetchSemanticsNode()
        val touch = remove.touchBoundsInRoot
        assertTrue("touch=$touch layout=${remove.boundsInRoot}", touch.width >= 48f && touch.height >= 48f)
    }

    @Test fun deletionFailureRemainsVisibleWhenTheAffectedFollowerIsFarBelowTheInvitationForm() {
        val rows = (1..20).map { row.copy(userAuthorizationId = "a$it", readerAlias = "Person $it") }
        var state by mutableStateOf(LumiflexFollowersUiState(followers = rows))
        content { LumiflexCloudFollowersContent(state, actions = LumiflexFollowerActions(
            remove = { state = state.copy(removeError = R.string.lumiflex_status_remove_failed) })) }
        compose.onNodeWithTag("followers-list").performScrollToNode(hasText("Person 20"))
        compose.onNode(hasContentDescription("Удалить подписчика") and hasClickAction() and
            hasAnyAncestor(hasTestTag("follower-a20"))).performClick()
        compose.onNode(hasText("Удалить подписчика") and hasClickAction() and
            hasAnyAncestor(hasTestTag("follower-remove-confirm"))).performClick()
        compose.onNodeWithText("Не удалось удалить").assertIsDisplayed()
        compose.onNodeWithText("Person 20").assertIsDisplayed()
    }

}
