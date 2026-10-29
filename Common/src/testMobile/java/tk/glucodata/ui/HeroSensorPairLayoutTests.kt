package tk.glucodata.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.logic.TrendEngine

/** Real Compose measurement: a pure layout-policy test cannot detect SubcomposeLayout crashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "en-w640dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroSensorPairLayoutTests {
    @get:Rule val compose = createComposeRule()

    private fun render(width: Int, fontScale: Float, primary: String, peer: String) {
        var selected: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme {
                    // Same intrinsic-height request and weighted cards as the portrait header.
                    Row(Modifier.width(width.dp).height(IntrinsicSize.Min)) {
                        Box(Modifier.weight(0.7f).fillMaxHeight().padding(8.dp)) {
                            DashboardHeroSensorPair(
                                primaryText = primary,
                                primaryStyle = MaterialTheme.typography.displayMedium,
                                primaryColor = MaterialTheme.colorScheme.onSurface,
                                primaryRangeText = null,
                                primaryTrend = TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, 1f, 0f, 1f),
                                primaryArrowColor = MaterialTheme.colorScheme.onSurface,
                                primaryDeltaText = "+0.2",
                                peer = PeerCurrentReading("peer", peer, null, -1f, 1L),
                                peerStyle = MaterialTheme.typography.headlineSmall,
                                selectedSensorIds = listOf("main", "peer"),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onPeerClick = { selected = it }
                            )
                        }
                        Text("Sensor", Modifier.weight(0.3f))
                    }
                }
            }
        }
        compose.onNodeWithText(primary).assertIsDisplayed()
        compose.onNodeWithText(peer).assertIsDisplayed()
        compose.onNodeWithContentDescription("peer").performClick()
        compose.runOnIdle { assertEquals("peer", selected) }
    }

    @Test fun portraitHeaderMeasuresAndPeerRemainsSelectable() = render(360, 1f, "112", "85")
    @Test fun largeTextAndMmolValuesSurviveIntrinsicMeasurement() = render(360, 2f, "6.2", "4.7")
    @Test fun widerHeaderMeasuresWithoutSubcomposition() = render(600, 1f, "112", "85")
}
