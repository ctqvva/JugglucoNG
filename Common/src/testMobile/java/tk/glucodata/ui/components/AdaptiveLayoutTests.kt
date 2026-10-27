package tk.glucodata.ui.components

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.R
import tk.glucodata.ui.setup.ScanFilterHeader
import tk.glucodata.ui.theme.AppTypography
import tk.glucodata.ui.util.ConnectedButtonGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "ru-w1200dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveLayoutTests {
    @get:Rule val compose = createComposeRule()

    private data class Environment(
        val width: Int = 320,
        val density: Float = 1f,
        val fontScale: Float = 2f,
        val direction: LayoutDirection = LayoutDirection.Ltr,
        val wideBoldFont: Boolean = false,
    )

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun SemanticsNodeInteraction.assertTextFits() {
        val layout = textLayout()
        assertFalse("text height clipped: ${layout.layoutInput.text}", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            // Paragraph widths are floats; Text rounds its measured size to integer px.
            assertTrue(
                "text width clipped: ${layout.layoutInput.text}",
                layout.getLineRight(line) <= layout.size.width + 1f &&
                    layout.getLineLeft(line) >= -1f,
            )
        }
    }

    @Test
    fun scanHeaderKeepsReadableTextAndWorkingFilterAcrossDensities() {
        var env by mutableStateOf(Environment())
        var showAll by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(env.density, env.fontScale),
                LocalLayoutDirection provides env.direction,
            ) {
                MaterialTheme(
                    typography =
                        if (env.wideBoldFont)
                            AppTypography.copy(
                                titleMedium =
                                    AppTypography.titleMedium.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                labelLarge =
                                    AppTypography.labelLarge.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontWeight = FontWeight.Bold,
                                    ),
                            )
                        else AppTypography
                ) {
                    Box(Modifier.width(env.width.dp).testTag("header")) {
                        ScanFilterHeader("Ищем датчики…", showAll) { showAll = !showAll }
                    }
                }
            }
        }
        // Physical density and available dp are independent. Include low dpi, narrow
        // windows, enlarged fonts, a normal wide window, and RTL placement.
        for (environment in
            listOf(
                Environment(240, 1f, 2f),
                Environment(288, 1f, 2f),
                Environment(320, 1.5f, 2f),
                Environment(360, 2f, 1.3f),
                Environment(600, 1f, 1f),
                Environment(288, 1f, 2f, wideBoldFont = true),
                Environment(288, 1f, 2f, LayoutDirection.Rtl),
            )) {
            compose.runOnIdle {
                env = environment
                showAll = true
            }
            val label = compose.onNodeWithText("Ищем датчики…")
            val layout = label.textLayout()
            label.assertTextFits()
            assertTrue("one-character wrapping at $environment", layout.lineCount <= 3)
            val titleBounds = label.fetchSemanticsNode().boundsInRoot
            val action = compose.onNodeWithText("Показывать только сенсоры")
            val actionBounds = action.fetchSemanticsNode().boundsInRoot
            action.assertTextFits()
            assertTrue(
                "title/action overlap at $environment",
                titleBounds.bottom <= actionBounds.top ||
                    titleBounds.right <= actionBounds.left ||
                    actionBounds.right <= titleBounds.left,
            )
            if (environment.width <= 288) assertTrue(actionBounds.top >= titleBounds.bottom)
            val output =
                File(
                    "build/outputs/layout-regressions/scan-${environment.width}-${environment.density}-${environment.fontScale}-${environment.direction}.png"
                )
            output.parentFile?.mkdirs()
            output.outputStream().use {
                compose
                    .onNodeWithTag("header")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            action.performClick()
            compose.runOnIdle { assertFalse(showAll) }
            compose.onNodeWithText("Показать все устройства").assertExists()
        }
    }

    @Test
    fun settingsTrailingActionCannotStarveItsTitle() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(288.dp)) {
                        SettingsItem(
                            title = "Настройки датчика",
                            subtitle = "Проверка подключения",
                            icon = Icons.Default.Settings,
                            trailingContent = { Text("Подключено") },
                        )
                    }
                }
            }
        }
        val title = compose.onNodeWithText("Настройки датчика", useUnmergedTree = true)
        assertTrue(title.textLayout().lineCount <= 3)
        title.assertTextFits()
        assertTrue(
            compose
                .onNodeWithText("Подключено", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .top >= title.fetchSemanticsNode().boundsInRoot.bottom
        )
    }

    @Test
    fun connectedCustomLabelsCanGrowBeyondNominalButtonHeight() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(typography = AppTypography) {
                    ConnectedButtonGroup(
                        modifier = Modifier.width(288.dp),
                        options = listOf("Первая строка\nВторая строка", "Другой вариант"),
                        selectedOption = "Другой вариант",
                        onOptionSelected = {},
                        label = { Text(it) },
                    )
                }
            }
        }
        val label = compose.onNodeWithText("Первая строка\nВторая строка")
        label.assertTextFits()
        assertTrue(label.fetchSemanticsNode().boundsInRoot.height > 40)
    }

    @Test
    fun translatedScanHeadersKeepFullTextAtLargeFontSize() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        var localeTag by mutableStateOf("ru")
        var showAll by mutableStateOf(false)
        compose.setContent {
            val configuration =
                Configuration(base.resources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(localeTag)))
                }
            val context = base.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(1f, 2f),
            ) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(240.dp)) {
                        ScanFilterHeader(
                            context.getString(R.string.aidex_searching_sensors),
                            showAll,
                        ) {
                            showAll = !showAll
                        }
                    }
                }
            }
        }
        // Every shipping locale, both states of the translated filter.
        for (tag in
            listOf(
                "en",
                "be",
                "de",
                "fr",
                "hu",
                "it",
                "mn",
                "nl",
                "pl",
                "pt",
                "ru",
                "so",
                "sv",
                "tr",
                "uk",
                "zh",
            )) {
            val resources =
                base
                    .createConfigurationContext(
                        Configuration(base.resources.configuration).apply {
                            setLocales(LocaleList(Locale.forLanguageTag(tag)))
                        }
                    )
                    .resources
            for (all in listOf(false, true)) {
                compose.runOnIdle {
                    localeTag = tag
                    showAll = all
                }
                val title =
                    compose.onNodeWithText(resources.getString(R.string.aidex_searching_sensors))
                title.assertTextFits()
                assertTrue("unreadable wrapping in $tag", title.textLayout().lineCount <= 5)
                compose
                    .onNodeWithText(
                        resources.getString(
                            if (all) R.string.show_sensors_only else R.string.see_all_devices
                        )
                    )
                    .assertTextFits()
            }
        }
    }

    @Test
    fun sharedSwitchHeadersRemainReadableAndInteractive() {
        var kind by mutableIntStateOf(0)
        var checked by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(240.dp)) {
                        when (kind) {
                            0 ->
                                SettingsSwitchItem(
                                    "Настройки датчика",
                                    "Проверка подключения",
                                    checked,
                                    { checked = it },
                                    Icons.Default.Settings,
                                )
                            1 ->
                                DisclosingSwitchCard(
                                    "Настройки датчика",
                                    "Проверка подключения",
                                    Icons.Default.Settings,
                                    checked,
                                    { checked = it },
                                    CardPosition.SINGLE,
                                ) {}
                            2 ->
                                MasterSwitchCard(
                                    "Настройки датчика",
                                    "Проверка подключения",
                                    checked,
                                    { checked = it },
                                    Icons.Default.Settings,
                                )
                            3 ->
                                ExpandableSettingsCard(
                                    "Настройки датчика",
                                    "Проверка подключения",
                                    Icons.Default.Settings,
                                    checked,
                                    { checked = it },
                                    CardPosition.SINGLE,
                                ) {}
                            4 ->
                                SettingsNavSwitchItem(
                                    title = "Настройки датчика",
                                    subtitle = "Проверка подключения",
                                    checked = checked,
                                    onCheckedChange = { checked = it },
                                    onClick = { checked = true },
                                    icon = Icons.Default.Settings,
                                )
                            5 ->
                                SettingsItem(
                                    title = "Настройки датчика",
                                    onClick = { checked = true },
                                    icon = Icons.Default.Settings,
                                    trailingContent = {
                                        IconButtonTooltip("Настройки") {
                                            IconButton(
                                                onClick = { checked = true },
                                                shapes = IconButtonDefaults.shapes(),
                                            ) {
                                                Icon(
                                                    Icons.Default.Settings,
                                                    contentDescription = "Настройки",
                                                )
                                            }
                                        }
                                    },
                                )
                        }
                    }
                }
            }
        }
        for (value in 0..5) {
            compose.runOnIdle {
                kind = value
                checked = false
            }
            val title = compose.onNodeWithText("Настройки датчика", useUnmergedTree = true)
            title.assertTextFits()
            assertTrue("unreadable shared header $value", title.textLayout().lineCount <= 4)
            compose.onNodeWithText("Настройки датчика").performClick()
            compose.runOnIdle { assertTrue(checked) }
        }
    }

    @Test
    fun buttonRowKeepsEqualWidthsUntilALabelWouldWrap() {
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme(typography = AppTypography) {
                    AdaptiveButtonRow(modifier = Modifier.width(320.dp)) {
                        OutlinedButton(onClick = {}, shapes = ButtonDefaults.shapes()) {
                            Text("Экспорт ключа")
                        }
                        Button(onClick = {}, shapes = ButtonDefaults.shapes()) {
                            Text("Импорт ключа")
                        }
                    }
                }
            }
        }
        fun bounds(text: String) =
            compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

        // Normal text: one line, equal shares, as the Row of weight(1f) buttons it replaced.
        var first = bounds("Экспорт ключа")
        var second = bounds("Импорт ключа")
        assertEquals(first.top, second.top, 0.5f)
        assertEquals(first.width, second.width, 1f)
        assertTrue(first.width > 150f)

        // Enlarged text: each button takes its own full-width line with an unbroken label.
        compose.runOnIdle { fontScale = 2f }
        first = bounds("Экспорт ключа")
        second = bounds("Импорт ключа")
        assertTrue(second.top >= first.bottom)
        assertEquals(first.width, second.width, 1f)
        assertTrue(second.width > 300f)
        compose.onNodeWithText("Импорт ключа", useUnmergedTree = true).assertTextFits()
    }

    @Test
    fun shortTrailingRowStaysCentredInATallerMinimumHeight() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(typography = AppTypography) {
                    Box(Modifier.width(360.dp).testTag("row")) {
                        SettingsItem(title = "Тема", trailingContent = { Text("Авто") })
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Тема", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // An icon-less, one-line row is shorter than the 72dp minimum; it must not sit at the top.
        assertEquals(row.center.y, title.center.y, 1.5f)
    }
}
