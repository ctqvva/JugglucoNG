package tk.glucodata.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.Applic
import tk.glucodata.ui.JugglucoTheme
import tk.glucodata.ui.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33], application = AdaptiveSheetHeightTests.TestApplication::class,
    qualifiers = "en-w411dp-h1000dp-night-xxhdpi")
class AdaptiveSheetHeightTests {
    class TestApplication : Applic() { override fun onCreate() {} }
    @get:Rule val compose = createComposeRule()

    private fun height() = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot.height
    private fun settle() {
        compose.mainClock.advanceTimeBy(2500)
        compose.waitForIdle()
    }

    @Test
    fun transientBottomInsetsDoNotBecomePermanentContentHeight() {
        var bottomInset by mutableIntStateOf(0)
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                StableModalBottomSheet(
                    modifier = Modifier.testTag("sheet"),
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                    adaptiveHeight = true,
                    // Models the dismissal frame where IME visibility has cleared but its
                    // animated padding still fills the viewport. No food layout changes.
                    contentWindowInsets = { WindowInsets(bottom = bottomInset) },
                ) {
                    Box(Modifier.fillMaxWidth()
                        .animateSheetContentSize(isImeVisible = bottomInset > 0)
                        .height(420.dp))
                }
            }
        }
        settle()
        val compact = height()
        compose.runOnIdle { bottomInset = 2000 }
        settle()
        assertTrue(height() > compact + 100f)
        compose.runOnIdle { bottomInset = 0 }
        settle()
        assertTrue("inset dismissal must restore $compact, got ${height()}", height() <= compact + 2f)
    }

    @Test
    fun tallContentCanShrinkWhileBottomInsetsAreStillPresent() {
        var bottomInset by mutableIntStateOf(0)
        var contentHeight by mutableStateOf(420.dp)
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                StableModalBottomSheet(
                    modifier = Modifier.testTag("sheet"),
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                    adaptiveHeight = true,
                    contentWindowInsets = { WindowInsets(bottom = bottomInset) },
                ) {
                    Box(Modifier.fillMaxWidth()
                        .animateSheetContentSize(isImeVisible = bottomInset > 0)
                        .height(contentHeight))
                }
            }
        }
        settle()
        val compact = height()
        compose.runOnIdle { contentHeight = 1400.dp }
        settle()
        assertTrue(height() > compact + 100f)
        compose.runOnIdle { bottomInset = 2000; contentHeight = 420.dp }
        settle()
        compose.runOnIdle { bottomInset = 0 }
        settle()
        assertTrue("short form must recover after tall content and IME: ${height()}", height() <= compact + 2f)
    }

    @Test
    fun keyboardDismissalDoesNotPullTallSheetDownAndBackUp() {
        var bottomInset by mutableIntStateOf(1300)
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                StableModalBottomSheet(
                    modifier = Modifier.testTag("sheet"),
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismissRequest = {},
                    adaptiveHeight = true,
                    contentWindowInsets = { WindowInsets(bottom = bottomInset) },
                ) {
                    Box(Modifier.fillMaxWidth()
                        .animateSheetContentSize(isImeVisible = bottomInset > 0)
                        .height(1400.dp))
                }
            }
        }
        settle()
        val expanded = height()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { bottomInset = 0 }
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("tall sheet must stay at $expanded while IME closes, got ${height()}", height() >= expanded - 2f)
        }
    }
}
