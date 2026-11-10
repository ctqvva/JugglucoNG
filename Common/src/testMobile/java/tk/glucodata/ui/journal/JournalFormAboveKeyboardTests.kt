package tk.glucodata.ui.journal

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h900dp")
class JournalFormAboveKeyboardTests {
    @get:Rule
    val compose = createComposeRule()

    private var viewport by mutableStateOf(800.dp)
    private var keyboardUp by mutableStateOf(false)
    private var fieldHeight by mutableStateOf(56.dp)
    private lateinit var listState: LazyListState

    private fun showForm(above: Dp = 300.dp, below: Dp = 200.dp) {
        compose.setContent {
            listState = rememberLazyListState()
            val focus = remember { JournalFormFocus() }
            FormAboveKeyboardEffect(listState, focus) { keyboardUp }
            Box(Modifier.height(viewport)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    item { Spacer(Modifier.height(above).fillMaxWidth().testTag("above")) }
                    item(key = "field") {
                        TextField(
                            value = "",
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = fieldHeight)
                                .onFocusChanged { focus.onRowFocusChanged("field", it) }
                                .testTag("field")
                        )
                    }
                    item { Spacer(Modifier.height(below).fillMaxWidth()) }
                    item { Spacer(Modifier.height(48.dp).fillMaxWidth().testTag("save")) }
                }
            }
        }
    }

    private fun focusField() {
        compose.onNodeWithTag("field").requestFocus()
        compose.waitForIdle()
    }

    private fun openKeyboard(leaving: Dp = 500.dp) {
        keyboardUp = true
        // A step at a time, like the inset does.
        var height = viewport
        while (height > leaving) {
            height = maxOf(height - 50.dp, leaving)
            viewport = height
            compose.waitForIdle()
        }
    }

    private fun closeKeyboard() {
        viewport = 800.dp
        compose.waitForIdle()
        keyboardUp = false
        compose.waitForIdle()
    }

    private fun scrollHome() {
        scrollTo(0)
    }

    private fun scrollTo(index: Int) {
        compose.runOnIdle { runBlocking { listState.scrollToItem(index) } }
        compose.waitForIdle()
    }

    @Test
    fun theFormSlidesUpUntilSaveClearsTheKeyboard() {
        showForm()
        focusField()

        openKeyboard()

        compose.onNodeWithTag("save").assertIsDisplayed()
        compose.onNodeWithTag("field").assertIsDisplayed()
        compose.onNodeWithTag("save").assertTopPositionInRootIsEqualTo(452.dp)
    }

    @Test
    fun aFormTooTallToFitStopsWithTheFieldStillInView() {
        showForm(below = 900.dp)
        focusField()

        openKeyboard()

        compose.onNodeWithTag("field").assertIsDisplayed()
        compose.onNodeWithTag("field").assertTopPositionInRootIsEqualTo(12.dp)
        compose.onNodeWithTag("save").assertIsNotDisplayed()
    }

    @Test
    fun aFormThatFitsDoesNotMove() {
        showForm(above = 100.dp, below = 100.dp)
        focusField()

        openKeyboard()

        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun nothingMovesWithoutAFocusedField() {
        showForm()

        openKeyboard()

        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun scrollingByHandStaysPutWithTheKeyboardOpen() {
        showForm()
        focusField()
        openKeyboard()

        scrollHome()
        compose.waitForIdle()

        compose.onNodeWithTag("above").assertIsDisplayed()
        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun scrollingTheFieldOffAndBackIsNotTheFieldGrowing() {
        showForm(above = 600.dp, below = 900.dp)
        focusField()
        openKeyboard()
        scrollHome()
        compose.onNodeWithTag("field").assertIsNotDisplayed()

        // Back in view, but still well down the list.
        compose.runOnIdle { runBlocking { listState.scrollToItem(0, 300) } }
        compose.waitForIdle()

        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(300, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun closingTheKeyboardDoesNotMoveAScrolledForm() {
        showForm(below = 900.dp)
        focusField()
        openKeyboard()
        scrollHome()

        closeKeyboard()

        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun aFieldGrowingUnderTheKeyboardKeepsSaveAboveIt() {
        showForm()
        focusField()
        openKeyboard()

        fieldHeight = 96.dp
        compose.waitForIdle()

        compose.onNodeWithTag("save").assertIsDisplayed()
        compose.onNodeWithTag("save").assertTopPositionInRootIsEqualTo(452.dp)
    }
}
