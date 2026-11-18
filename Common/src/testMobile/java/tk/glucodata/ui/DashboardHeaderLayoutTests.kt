package tk.glucodata.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState

/** The dashboard header's split between the reading card and the sensor panel (#918). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardHeaderLayoutTests {
    @get:Rule val compose = createComposeRule()

    @Test fun baseSplitIsSeventyThirtyAndUsesEveryPixel() {
        assertEquals(456 to 196, heroSensorBaseSplit(652))
        for (available in 0..1200) {
            val (hero, sensor) = heroSensorBaseSplit(available)
            assertEquals(available, hero + sensor)
            assertTrue("hero $hero of $available", kotlin.math.abs(hero - available * 0.7f) <= 1f)
        }
    }

    @Test fun panelKeepsItsShareWhileItsLinesFit() {
        assertEquals(196, sensorPanelWidth(availablePx = 652, preferredPx = 150, heroNeedPx = 300, stepPx = 16))
        assertEquals(196, sensorPanelWidth(availablePx = 652, preferredPx = 196, heroNeedPx = 300, stepPx = 16))
    }

    @Test fun panelGrowsInStepsIntoWidthTheReadingDoesNotUse() {
        assertEquals(196 + 16, sensorPanelWidth(availablePx = 652, preferredPx = 200, heroNeedPx = 300, stepPx = 16))
        assertEquals(196 + 32, sensorPanelWidth(availablePx = 652, preferredPx = 228, heroNeedPx = 300, stepPx = 16))
    }

    @Test fun panelNeverTakesWidthTheReadingNeeds() {
        // The reading needs 440 of 652, so 212 is all there is, however much the panel asks for.
        assertEquals(212, sensorPanelWidth(availablePx = 652, preferredPx = 400, heroNeedPx = 440, stepPx = 16))
        // A reading with nothing to spare, or one that cannot say, keeps the split where it was.
        assertEquals(196, sensorPanelWidth(availablePx = 652, preferredPx = 400, heroNeedPx = 460, stepPx = 16))
        assertEquals(196, sensorPanelWidth(availablePx = 652, preferredPx = 400, heroNeedPx = null, stepPx = 16))
    }

    @Test fun panelNeverBecomesTheLargerCard() {
        assertEquals(326, sensorPanelWidth(availablePx = 652, preferredPx = 600, heroNeedPx = 100, stepPx = 16))
    }

    @Test fun crowdedOnlyWhenTheRoomyReadingLeavesTooLittle() {
        assertFalse(isSensorPanelCrowded(availablePx = 652, preferredPx = 190, heroRoomyNeedPx = 440))
        assertFalse(isSensorPanelCrowded(availablePx = 652, preferredPx = 212, heroRoomyNeedPx = 440))
        assertTrue(isSensorPanelCrowded(availablePx = 652, preferredPx = 213, heroRoomyNeedPx = 440))
        // No tighter arrangement to fall back on: nothing to ask of the reading card.
        assertFalse(isSensorPanelCrowded(availablePx = 652, preferredPx = 400, heroRoomyNeedPx = null))
    }

    @Test fun splitLaysOutBothCardsAtOneHeightAndFollowsThePanel() {
        var panelWidth by mutableStateOf(40)
        var crowded: Boolean? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HeroSensorSplit(
                    heroNeedPx = 100,
                    heroRoomyNeedPx = 150,
                    onPanelCrowded = { crowded = it },
                    modifier = Modifier.width(304.dp)
                ) {
                    Box(Modifier.height(80.dp).testTag("hero"))
                    Box(Modifier.width(panelWidth.dp).height(50.dp).testTag("panel"))
                }
            }
        }
        // 300 after the 4dp gap: 210 / 90.
        fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertEquals(210f, bounds("hero").width, 0f)
        assertEquals(214f, bounds("panel").left, 0f)
        assertEquals(90f, bounds("panel").width, 0f)
        assertEquals(80f, bounds("panel").height, 0f)
        assertEquals(false, crowded)

        // 120 wanted, 30 past its share: the panel takes whole 8dp steps, so 32.
        compose.runOnIdle { panelWidth = 120 }
        compose.waitForIdle()
        assertEquals(122f, bounds("panel").width, 0f)
        assertEquals(178f, bounds("hero").width, 0f)
        assertEquals(false, crowded)

        // 170 wanted: more than the roomy reading leaves (150), and capped at half the row.
        compose.runOnIdle { panelWidth = 170 }
        compose.waitForIdle()
        assertEquals(150f, bounds("panel").width, 0f)
        assertEquals(true, crowded)
    }

    private fun header(showReadingAge: Boolean) {
        val now = System.currentTimeMillis()
        val at = now - 20_000L
        val history = (0 until 16).map { i ->
            GlucosePoint(
                value = 8.2f - i * 0.03f + (if (i % 2 == 0) 0.35f else -0.3f) + (i % 5) * 0.07f,
                time = "",
                timestamp = at - (15 - i) * 60_000L,
                rawValue = 8f,
                sensorSerial = SENSOR
            )
        }.let { it.dropLast(1) + it.last().copy(value = 7.8f) }
        val snapshot = CurrentDisplaySource.Snapshot(
            timeMillis = at, rate = -0.4f, sensorId = SENSOR, sensorGen = 0, index = 0, viewMode = 0,
            source = "test", autoValue = 7.8f, rawValue = 7.8f, sharedDisplayValue = 7.8f, sharedMgdl = 140,
            isMmol = true,
            displayValues = DisplayValues(primaryValue = 7.8f, primaryStr = "7,8", fullFormatted = "7,8")
        )
        compose.setContent {
            JugglucoTheme(ThemeMode.LIGHT) {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    Box(Modifier.width(HEADER_WIDTH_DP.dp)) {
                        DashboardCombinedHeader(
                            currentGlucose = "7,8",
                            currentRate = -0.4f,
                            viewMode = 0,
                            latestPoint = history.last(),
                            sensorName = SENSOR,
                            daysRemaining = "5 / 15",
                            sensorStatus = "",
                            sensorProgress = 0.33f,
                            currentDay = 5,
                            history = history,
                            currentSnapshot = snapshot,
                            dataState = DisplayDataState.Status(DisplayDataState.Kind.FRESH, true, at, now - at),
                            isMmol = true,
                            showDelta = true,
                            showReadingAge = showReadingAge
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun left(text: String) =
        compose.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left

    private fun right(text: String) =
        compose.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right

    /** Where the sensor panel's text starts at the default 70 / 30: after the reading card, the gap and its padding. */
    private val defaultPanelTextLeft: Float
        get() = heroSensorBaseSplit(HEADER_WIDTH_DP * 2 - 4 * 2).first + (4 + 16) * 2f

    @Test fun headerKeepsItsSplitWhenThePanelFits() {
        header(showReadingAge = false)
        assertEquals(defaultPanelTextLeft, left("3MH01"), 0f)
    }

    @Test fun headerGivesThePanelRoomForTheReadingAge() {
        header(showReadingAge = true)
        val panelTextLeft = left("3MH01")
        assertTrue("panel grew: $panelTextLeft < $defaultPanelTextLeft", panelTextLeft < defaultPanelTextLeft)
        assertTrue("reading stays whole", right("7,8") < panelTextLeft - (4 + 16) * 2f)
    }

    @Test fun lifetimeIconComesBeforeTheLifetime() {
        header(showReadingAge = false)
        // The name starts at the panel's padding; the lifetime starts an icon and a gap further in.
        assertEquals(left("3MH01") + (14 + 8) * 2f, left("5 / 15"), 0f)
    }

    private companion object {
        const val SENSOR = "3MH01MABCDE"
        const val HEADER_WIDTH_DP = 328
    }
}
