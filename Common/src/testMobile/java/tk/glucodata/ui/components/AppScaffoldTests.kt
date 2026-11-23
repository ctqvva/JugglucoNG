package tk.glucodata.ui.components

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The bar of a pushed screen marks itself off from content exactly while some is under it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w411dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppScaffoldTests {
    @get:Rule val compose = createComposeRule()

    private val first = ScrollState(0)
    private val second = ScrollState(0)
    private var step by mutableIntStateOf(0)
    private var rest = 0
    private var raised = 0

    private fun show(registered: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                rest = MaterialTheme.colorScheme.background.toArgb()
                raised = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                AppScaffold(
                    topBar = { AppTopBar(title = "", onNavigateBack = null, modifier = Modifier.testTag("bar")) },
                ) { padding ->
                    val scroll = if (step == 0) first else second
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(if (registered) scroll.scrollsUnderTopBar() else scroll)
                    ) {
                        Box(Modifier.fillMaxWidth().height(3000.dp))
                    }
                }
            }
        }
    }

    private fun barColor(): Int {
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("bar").captureToImage().toPixelMap()
        return Color(pixels[pixels.width / 2, pixels.height / 2].value).toArgb()
    }

    private fun scroll(state: ScrollState, to: Int) {
        compose.runOnIdle { runBlocking { state.scrollTo(to) } }
    }

    @Test
    fun barTakesItsContainerOnlyWhileContentIsUnderIt() {
        show()
        assertEquals(rest, barColor())
        // A jump, as a search result makes: no drag, so no nested scroll to count.
        scroll(first, 400)
        assertEquals(raised, barColor())
        scroll(first, 0)
        assertEquals(rest, barColor())
    }

    @Test
    fun barFollowsTheStepOnScreen() {
        show()
        scroll(first, 400)
        assertEquals(raised, barColor())
        compose.runOnIdle { step = 1 }
        assertEquals(rest, barColor())
        compose.runOnIdle { step = 0 }
        assertEquals(raised, barColor())
    }

    @Test
    fun barStaysPutWhenAListPinnedBelowSomethingElseScrolls() {
        show(registered = false)
        scroll(first, 400)
        assertEquals(rest, barColor())
    }

    @Test
    fun barNeverScrollsAway() {
        show()
        val before = compose.onNodeWithTag("bar").getBoundsInRoot()
        scroll(first, 2000)
        assertEquals(before, compose.onNodeWithTag("bar").getBoundsInRoot())
    }
}
