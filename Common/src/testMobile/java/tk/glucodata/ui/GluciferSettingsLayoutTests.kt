package tk.glucodata.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.OutboundApiSettings
import tk.glucodata.R
import tk.glucodata.ui.theme.AppTypography

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w1200dp-h2400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GluciferSettingsLayoutTests {
    @get:Rule val compose = createComposeRule()
    private var destination by mutableStateOf(OutboundApiSettings.createDestination(OutboundApiSettings.PRESET_GLUCIFER))
    private var language by mutableStateOf("en")
    private var fontScale by mutableFloatStateOf(1f)
    private var width by mutableIntStateOf(360)
    private lateinit var context: Context
    private var openedUri: String? = null
    // Neutral dynamic palette from the user's Telegram reference, with visibly distinct surfaces.
    private val colors = darkColorScheme(
        surface = Color(0xFF0E0E0E),
        surfaceContainer = Color(0xFF19191B),
        surfaceContainerHigh = Color(0xFF1F1F21),
        primary = Color(0xFFC2C6D6),
        onPrimary = Color(0xFF404550),
        onSurface = Color(0xFFE5E2E1),
        onSurfaceVariant = Color(0xFFC5C6D0)
    )
    private val scanRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                input: I, options: ActivityOptionsCompat?) {
                error("The layout test must not launch a scanner")
            }
        }
    }

    @After fun resetFontScale() { RuntimeEnvironment.setFontScale(1f) }

    private fun render(fullEditor: Boolean = false, telegramReference: Boolean = false) {
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale(language))
                fontScale = this@GluciferSettingsLayoutTests.fontScale
            }
            context = base.createConfigurationContext(config)
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides config,
                LocalDensity provides Density(1f, fontScale),
                LocalActivityResultRegistryOwner provides scanRegistryOwner,
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { openedUri = uri }
                }
            ) {
                MaterialTheme(colorScheme = colors, typography = AppTypography) {
                    Column(Modifier.width(width.dp).height(800.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).testTag("editor")
                        .padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (fullEditor) {
                            DestinationEditor(destination, false, {}, { destination = it }, {})
                        } else {
                            if (telegramReference) {
                                BubbleRefreshSection(OutboundApiSettings.createDestination(OutboundApiSettings.PRESET_TELEGRAM_BOT)
                                    .copy(refreshInPlaceEnabled = true)) {}
                            } else GluciferConnectionHeading()
                            GluciferOptions(destination) { destination = it }
                        }
                    }
                }
            }
        }
    }

    private fun label(id: Int) = context.getString(id)
    private fun tap(id: Int) = compose.onNodeWithText(label(id)).performScrollTo().performClick()

    private fun capture(name: String, dialog: Boolean = false) {
        val output = File("build/outputs/layout-regressions/glucifer-$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {
            (if (dialog) compose.onNode(isDialog()) else compose.onNodeWithTag("editor"))
                .captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertTextFits(id: Int) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label(id), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse("Clipped height: ${layout.layoutInput.text}", layout.didOverflowHeight)
        // Text rounds its width to integer pixels, while paragraph advances are floats.
        for (line in 0 until layout.lineCount) {
            assertTrue("Clipped width: ${layout.layoutInput.text}",
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
        }
        assertFalse("Ellipsized: ${layout.layoutInput.text}", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
    }

    @Test fun overviewHidesExplanationsAndOptionalControls() {
        render()
        compose.onNodeWithText(label(R.string.stats_trend)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.glucifer_fallback_short)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.glucifer_journal_help)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.glucifer_selection_summary, 2, 15)).assertExists()
        compose.onNodeWithText(context.getString(R.string.glucifer_selection_summary, 12, 12)).assertExists()
        val bottom = compose.onNodeWithText(label(R.string.glucifer_delivery_title)).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Default options should fit without scrolling", bottom < 800f)
        capture("overview")
    }

    @Test fun connectionKeepsCommonFieldsAndOffersHelpBeforePairing() {
        destination = destination.copy(enabled = true, name = "Home Assistant")
        render(fullEditor = true)
        compose.onNodeWithText(label(R.string.outbound_api_destination_name)).assertExists()
        compose.onNodeWithText(label(R.string.outbound_api_provider)).assertExists()
        compose.onNodeWithText(label(R.string.outbound_api_url_label)).assertExists()
        compose.onNodeWithText(label(R.string.help)).assertIsDisplayed()
        val scan = compose.onNodeWithText(label(R.string.scan_qr_button))
        scan.assertIsDisplayed()
        assertTrue("QR action should have the standard prominent target", scan.fetchSemanticsNode().boundsInRoot.height >= 56f)
        compose.onNodeWithText(label(R.string.glucifer_done)).assertDoesNotExist()
        capture("connection")
    }

    @Test fun fieldAndAlertRowsChangeOnlyTheirOwnSelections() {
        val original = destination
        render()
        tap(R.string.glucifer_data_title)
        tap(R.string.stats_trend)
        compose.runOnIdle {
            assertEquals(original.copy(gluciferFields = original.gluciferFields - "trend"), destination)
        }
        tap(R.string.wear_prediction_title)
        compose.runOnIdle { assertTrue("predictions" in destination.gluciferFields) }
        tap(R.string.glucifer_data_title)
        tap(R.string.glucose_alerts_title)
        val low = compose.onNodeWithText(label(R.string.alert_low)).performScrollTo()
        assertTrue("Switch row must retain its touch target", low.fetchSemanticsNode().boundsInRoot.height >= 48f)
        low.performClick()
        compose.runOnIdle {
            assertFalse("alert:low" in destination.gluciferFields)
            assertTrue("alert:high" in destination.gluciferFields)
            assertTrue("predictions" in destination.gluciferFields)
        }
    }

    private data class RowAppearance(val background: Int, val labelLeft: Float, val switchRight: Float)

    private fun appearance(title: Int): RowAppearance {
        val row = compose.onNode(hasText(label(title)) and isToggleable()).performScrollTo()
        val bounds = row.fetchSemanticsNode().boundsInRoot
        val pixels = row.captureToImage().toPixelMap()
        val right = (0 until pixels.width).lastOrNull { x ->
            (0 until pixels.height).any { y -> pixels[x, y].toArgb() == colors.primary.toArgb() }
        } ?: error("No checked switch track in ${label(title)}")
        return RowAppearance(
            pixels[pixels.width / 2, 1].toArgb(),
            compose.onNode(hasText(label(title)) and hasAnyAncestor(isToggleable()), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.left,
            bounds.left + right
        )
    }

    @Test fun expandedControlsMatchTelegramPanelColorAndTheSurroundingSwitchGrid() {
        destination = destination.copy(gluciferHistory = true, gluciferJournal = true, gluciferJournalNotes = true)
        render(telegramReference = true)
        val telegram = appearance(R.string.outbound_api_bubble_refresh_title)
        assertEquals(colors.surfaceContainer.toArgb(), telegram.background)
        tap(R.string.glucifer_data_title)
        assertEquals(telegram, appearance(R.string.stats_trend))
        tap(R.string.glucifer_data_title)
        tap(R.string.glucose_alerts_title)
        assertEquals(telegram, appearance(R.string.alert_low))
        assertEquals(telegram, appearance(R.string.glucifer_history_title))
        assertEquals(telegram, appearance(R.string.glucifer_journal))
        capture("alerts-grid")
        assertEquals(telegram, appearance(R.string.glucifer_journal_notes))
        capture("journal-grid")
        tap(R.string.glucose_alerts_title)
        tap(R.string.glucifer_delivery_title)
        assertEquals(telegram, appearance(R.string.glucifer_live_short))
        capture("delivery-grid")
    }

    @Test fun selectorsKeepTheirFullRangesAndIndependentValues() {
        render()
        tap(R.string.glucifer_delivery_title)
        tap(R.string.glucifer_fallback_short)
        compose.onNodeWithText(context.getString(R.string.glucifer_hours, 24)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(86400, destination.gluciferFallbackSeconds)
            assertEquals(1, destination.gluciferMinIntervalSeconds)
        }
        tap(R.string.glucifer_spacing_short)
        compose.onNodeWithText(context.getString(R.string.glucifer_seconds, 5)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(5, destination.gluciferMinIntervalSeconds) }
        compose.onNode(hasText(label(R.string.glucifer_live_short)) and isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.glucifer_all_spacing_title)).assertExists()
        tap(R.string.glucifer_journal)
        tap(R.string.glucifer_journal_days)
        compose.onNodeWithText(context.getString(R.string.glucifer_days, 90)).performScrollTo().performClick()
        tap(R.string.glucifer_journal_notes)
        compose.runOnIdle {
            assertEquals(90, destination.gluciferJournalDays)
            assertTrue(destination.gluciferJournalNotes)
            assertFalse(destination.gluciferLiveBypass)
            assertEquals(86400, destination.gluciferFallbackSeconds)
        }
    }

    @Test
    @Config(qualifiers = "ru-w320dp-h800dp-mdpi")
    fun narrowLargeFontLayoutKeepsTitlesAndSelectorsReadable() {
        RuntimeEnvironment.setFontScale(2f)
        language = "ru"
        fontScale = 2f
        width = 320
        render()
        capture("overview-ru-large-font")
        listOf(R.string.glucifer_data_title, R.string.glucose_alerts_title, R.string.glucifer_history_title,
            R.string.glucifer_journal, R.string.glucifer_delivery_title).forEach(::assertTextFits)
        tap(R.string.glucifer_delivery_title)
        tap(R.string.glucifer_fallback_short)
        compose.onNodeWithText(context.getString(R.string.glucifer_hours, 24)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.glucifer_spacing_short)).performScrollTo()
        listOf(R.string.glucifer_fallback_short, R.string.glucifer_spacing_short).forEach(::assertTextFits)
        capture("delivery-ru-large-font")
        tap(R.string.glucifer_data_title)
        compose.onNodeWithText(label(R.string.glucifer_auto)).performScrollTo()
        assertTextFits(R.string.glucifer_auto)
        val textBounds = compose.onNodeWithText(label(R.string.glucifer_auto), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val rowBounds = compose.onNode(hasText(label(R.string.glucifer_auto)) and isToggleable())
            .fetchSemanticsNode().boundsInRoot
        assertTrue("A long label needs the full row, with its switch underneath", rowBounds.bottom - textBounds.bottom >= 48f)
        capture("fields-ru-large-font")
    }

    @Test
    @Config(qualifiers = "en-w360dp-h800dp-mdpi")
    fun helpExplainsPairingAndOpensTheIntegrationGuide() {
        val original = destination
        render()
        tap(R.string.help)
        compose.onNodeWithText(label(R.string.glucifer_help_install)).assertExists()
        compose.onNodeWithText(label(R.string.glucifer_help_pair)).assertExists()
        capture("help", dialog = true)
        compose.onNodeWithText(label(R.string.glucifer_setup_guide)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(GLUCIFER_SETUP_GUIDE, openedUri)
            assertEquals(original, destination)
        }
        compose.onNodeWithText(label(R.string.close)).performClick()
        tap(R.string.glucifer_delivery_title)
        compose.onAllNodesWithText(label(R.string.help)).onLast().performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.glucifer_live_bypass_help)).assertExists()
    }

    @Test
    @Config(qualifiers = "en-w360dp-h800dp-mdpi")
    fun helpKeepsItsSetupLinkAndCloseActionAtLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        fontScale = 2f
        render()
        tap(R.string.help)
        capture("help-large-font", dialog = true)
        compose.onNodeWithText(label(R.string.glucifer_setup_guide)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GLUCIFER_SETUP_GUIDE, openedUri) }
        compose.onNodeWithText(label(R.string.close)).performClick()
        compose.onNodeWithText(label(R.string.glucifer_help_install)).assertDoesNotExist()
    }
}
