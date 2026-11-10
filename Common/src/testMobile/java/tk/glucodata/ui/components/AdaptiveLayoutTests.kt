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
import androidx.compose.material3.FilledTonalButton
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
import tk.glucodata.ui.setup.PinnedOrScrollingList
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
    fun buttonRowStacksRatherThanWrapALabel() {
        var width by mutableIntStateOf(320)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(typography = AppTypography) {
                    AdaptiveButtonRow(modifier = Modifier.width(width.dp)) {
                        OutlinedButton(onClick = {}, shapes = ButtonDefaults.shapes()) {
                            Text("Export key")
                        }
                        Button(onClick = {}, shapes = ButtonDefaults.shapes()) {
                            Text("Import key")
                        }
                    }
                }
            }
        }
        fun bounds(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

        // Both labels fit their share: equal widths on one line, as the weighted Row was.
        var first = bounds("Export key")
        var second = bounds("Import key")
        assertEquals(first.top, second.top, 0.5f)
        assertEquals(first.width, second.width, 1f)
        assertEquals(1, compose.onNodeWithText("Import key", useUnmergedTree = true).textLayout().lineCount)

        // A label would wrap in its share: full-width lines with one-line labels instead.
        compose.runOnIdle { width = 200 }
        first = bounds("Export key")
        second = bounds("Import key")
        assertTrue(second.top >= first.bottom)
        assertTrue(second.width > 190f)
        assertEquals(1, compose.onNodeWithText("Import key", useUnmergedTree = true).textLayout().lineCount)
    }

    @Test
    fun connectedGroupKeepsItsHeightAtNormalTextSize() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(typography = AppTypography) {
                    // Taller parent: the buttons must not stretch to fill it either.
                    Box(Modifier.width(288.dp).height(200.dp)) {
                        ConnectedButtonGroup(
                            modifier = Modifier.testTag("group"),
                            options = listOf("High", "Low"),
                            selectedOption = "High",
                            onOptionSelected = {},
                            label = { Text(it) },
                            labelText = { it },
                            itemHeight = 36.dp,
                        )
                    }
                }
            }
        }
        // Intrinsic sizing sees the 48dp touch target, which once drew every group 48dp tall.
        assertEquals(36f, compose.onNodeWithTag("group").fetchSemanticsNode().boundsInRoot.height, 0.5f)
        assertEquals(36f, compose.onNodeWithText("High").fetchSemanticsNode().boundsInRoot.height, 0.5f)
    }

    @Test
    fun connectedGroupGrowsForALabelTallerThanItsHeightButUnderTheTouchTarget() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2.2f)) {
                MaterialTheme(typography = AppTypography) {
                    // Wide enough for labelLarge: its 20sp line is past 36dp at this size, still under 48dp.
                    Box(Modifier.width(400.dp)) {
                        ConnectedButtonGroup(
                            modifier = Modifier.testTag("group"),
                            options = listOf("High", "Low"),
                            selectedOption = "High",
                            onOptionSelected = {},
                            label = { Text(it) },
                            labelText = { it },
                            itemHeight = 36.dp,
                        )
                    }
                }
            }
        }
        val label = compose.onNodeWithText("High", useUnmergedTree = true)
        val lineHeight = label.textLayout().multiParagraph.height
        assertTrue("label line $lineHeight", lineHeight > 36f && lineHeight < 48f)
        val group = compose.onNodeWithTag("group").fetchSemanticsNode().boundsInRoot
        assertTrue("group ${group.height} clips a $lineHeight label", group.height >= lineHeight)
        // Grows to the label, not to the invisible 48dp touch target.
        assertTrue("group ${group.height}", group.height < 48f)
    }

    @Test
    fun settingsRowWithoutATrailingControlAddsNoGapUnderALongTitle() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme(typography = AppTypography) {
                    // Narrower than the reading width at 2× text, so a trailing control would stack.
                    Box(Modifier.width(288.dp)) {
                        SettingsItem(
                            title = "Benachrichtigungseinstellungen",
                            modifier = Modifier.testTag("row"),
                        )
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Benachrichtigungseinstellungen", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        // 16dp padding under the title, and no stack gap for a control that isn't there.
        assertEquals(16f, row.bottom - title.bottom, 0.5f)
    }

    @Test
    fun scanListKeepsItsHeaderAndFooterPinnedWhenItHasRoom() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(typography = AppTypography) {
                    PinnedOrScrollingList(
                        modifier = Modifier.width(320.dp).height(400.dp),
                        header = { Box(Modifier.fillMaxWidth().height(120.dp).testTag("header")) },
                        footer = { Box(Modifier.fillMaxWidth().height(60.dp).testTag("footer")) },
                    ) {
                        items(20) { Text("Sensor $it", Modifier.height(72.dp)) }
                    }
                }
            }
        }
        assertEquals(400f, compose.onNodeWithTag("footer").fetchSemanticsNode().boundsInRoot.bottom, 0.5f)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Sensor 19"))
        // The list scrolled under a header and footer that stayed put.
        assertEquals(0f, compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.top, 0.5f)
        assertEquals(400f, compose.onNodeWithTag("footer").fetchSemanticsNode().boundsInRoot.bottom, 0.5f)
    }

    @Test
    fun scanListScrollsAsOneWhenHeaderAndFooterLeaveItNoRoom() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(typography = AppTypography) {
                    PinnedOrScrollingList(
                        modifier = Modifier.width(320.dp).height(200.dp),
                        header = { Box(Modifier.fillMaxWidth().height(150.dp).testTag("header")) },
                        footer = { Box(Modifier.fillMaxWidth().height(60.dp).testTag("footer")) },
                    ) {
                        items(3) { Text("Sensor $it", Modifier.height(72.dp)) }
                    }
                }
            }
        }
        // Pinned, the header and footer would leave the list no height at all.
        compose.onNodeWithText("Sensor 0").assertIsDisplayed()
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("Sensor 2"))
        compose.onNodeWithText("Sensor 2").assertIsDisplayed()
        list.performScrollToNode(hasTestTag("footer"))
        compose.onNodeWithTag("footer").assertIsDisplayed()
    }

    @Test
    fun scanListKeepsItsPlaceWhenItSwitchesBetweenPinnedAndScrolling() {
        var height by mutableStateOf(400)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(typography = AppTypography) {
                    PinnedOrScrollingList(
                        modifier = Modifier.width(320.dp).height(height.dp),
                        header = { Box(Modifier.fillMaxWidth().height(120.dp)) },
                        footer = { Box(Modifier.fillMaxWidth().height(60.dp)) },
                    ) {
                        items(30) { Text("Sensor $it", Modifier.height(72.dp)) }
                    }
                }
            }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Sensor 15"))
        compose.runOnIdle { height = 200 }
        compose.onNodeWithText("Sensor 15").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Sensor 22"))
        compose.runOnIdle { height = 400 }
        compose.onNodeWithText("Sensor 22").assertIsDisplayed()
    }

    @Test
    fun minimumHeightButtonKeepsItsHeightAtNormalTextSize() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(typography = AppTypography) {
                    Column(Modifier.width(320.dp)) {
                        // The calibration "Now" chip: heightIn(min) replaced height(), and must not
                        // pick up the 40dp default minimum in its place.
                        FilledTonalButton(
                            onClick = {},
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            modifier = Modifier.heightIn(min = 32.dp).testTag("now"),
                            shapes = ButtonDefaults.shapes(),
                        ) { Text("Now") }
                    }
                }
            }
        }
        assertEquals(32f, compose.onNodeWithTag("now").fetchSemanticsNode().boundsInRoot.height, 0.5f)
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
