package tk.glucodata.ui

import android.app.Application
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.ui.components.SensorArtTile
import tk.glucodata.ui.components.SensorHeroArt
import tk.glucodata.ui.components.SensorType
import tk.glucodata.ui.components.sensorTypeOptions

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SensorArtTileTests {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everySensorFamilyIsOffered() {
        assertEquals(SensorType.entries.toSet(), sensorTypeOptions.map { it.type }.toSet())
    }

    /** The press spring overshoots, so the morph is asked for progress outside 0..1. */
    @Test
    fun everySensorSurvivesAPressAndItsOvershoot() {
        val interactions = MutableInteractionSource()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            JugglucoTheme(ThemeMode.DARK) {
                Column {
                    SensorType.entries.forEachIndexed { order, type ->
                        SensorArtTile(
                            type = type,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            tileSize = 56.dp,
                            interactionSource = interactions,
                            arrivalOrder = order,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val press = PressInteraction.Press(Offset.Zero)
        runBlocking { interactions.emit(press) }
        repeat(20) { compose.mainClock.advanceTimeBy(16) }
        runBlocking { interactions.emit(PressInteraction.Release(press)) }
        repeat(40) { compose.mainClock.advanceTimeBy(16) }
    }

    @Test
    fun everyHeroPlaysItsArrival() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            JugglucoTheme(ThemeMode.LIGHT) {
                Column {
                    SensorType.entries.forEach { type -> SensorHeroArt(type) }
                }
            }
        }
        repeat(100) { compose.mainClock.advanceTimeBy(16) }
    }
}
