package tk.glucodata.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w392dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationLabelTests {
    @get:Rule val compose = createComposeRule()

    private var fontScale by mutableFloatStateOf(1f)
    private var labels by mutableStateOf(emptyList<String>())

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(3f, fontScale)) {
            MaterialTheme(typography = AppTypography) {
                val size = rememberNavigationLabelSize(labels)
                Row(Modifier.fillMaxWidth()) {
                    labels.forEach { label ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            FontScaleCap { AdaptiveNavigationLabel(label, size) }
                        }
                    }
                }
            }
        }
    }

    /** How wide each label is drawn at [scale]; its size, since the text does not change. */
    private fun widthsAt(scale: Float): List<Float> {
        compose.runOnIdle { fontScale = scale }
        compose.waitForIdle()
        return labels.map { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("$label is cut at $scale", layout.isLineEllipsized(0))
            layout.getLineRight(0) - layout.getLineLeft(0)
        }
    }

    @Test
    fun largerTextNeverShrinksALabel() = assertLargerTextNeverShrinksALabel()

    // Narrow enough that the longest labels are below their usual size already.
    @Test
    @Config(qualifiers = "en-w380dp-h800dp-xxhdpi")
    fun largerTextNeverShrinksALabelOnANarrowScreen() = assertLargerTextNeverShrinksALabel()

    private fun assertLargerTextNeverShrinksALabel() {
        val sets = listOf(
            listOf("Главная", "Статистика", "Журнал", "Сенсоры", "Настройки"),
            listOf("Dashboard", "Statistics", "Journal", "Sensors", "Settings"),
            listOf("Главная", "Статистика", "Сенсоры", "Настройки")
        )
        labels = sets[0]
        show()
        for (set in sets) {
            compose.runOnIdle { labels = set }
            var previous = widthsAt(1f)
            for (scale in listOf(1.1f, 1.15f, 1.3f, 1.5f, 2f)) {
                val enlarged = widthsAt(scale)
                for (i in set.indices) {
                    assertTrue("${set[i]} at $scale: ${previous[i]} -> ${enlarged[i]}", enlarged[i] >= previous[i] - 1f)
                }
                previous = enlarged
            }
        }
    }

    @Test
    fun labelsThatAllFitShareOneSize() {
        labels = listOf("Dashboard", "Statistics", "Sensors", "Settings")
        show()
        val default = widthsAt(1f)
        val growth = widthsAt(1.3f).zip(default) { enlarged, usual -> enlarged / usual }
        assertTrue("$growth", growth.min() > 1.1f)
        assertEquals(growth.min(), growth.max(), 0.03f)
    }
}
