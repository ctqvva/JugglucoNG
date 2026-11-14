package tk.glucodata.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.ui.rememberNavigationTick
import tk.glucodata.ui.util.ExpressiveMotion

/**
 * The launch tile: a sensor on the same soft square every row uses, so the sensors' own outlines
 * are what tells the rows apart. The sensor is drawn bigger than the square and set towards its
 * upper right corner, so it lies across the edge rather than sitting framed inside it, a few
 * degrees off straight. Pressing the card rounds the square towards a circle, pushes the sensor
 * in and turns it square to the row; letting go springs all three back. Everything moves in the
 * draw and layer phases, and nothing moves at rest or when a screen opens.
 *
 * [round] keeps the frame a circle throughout and the drawing inside it, for the wizard heroes. Raising [replay] drops the sensor on to its frame again.
 */
@Composable
internal fun SensorArtTile(
    type: SensorType,
    containerColor: Color,
    tileSize: Dp,
    interactionSource: InteractionSource,
    modifier: Modifier = Modifier,
    replay: Int = 0,
    round: Boolean = false,
) {
    val plain = rememberSensorTones()
    val tones = remember(plain, containerColor) { plain.bounce(containerColor) }

    val press = remember { Animatable(0f) }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        press.animateTo(if (pressed) 1f else 0f, ExpressiveMotion.fastSpatial())
    }

    val landing = remember { Animatable(0f) }
    LaunchedEffect(replay) {
        if (replay > 0) {
            landing.snapTo(1f)
            landing.animateTo(0f, ExpressiveMotion.fastSpatial())
        }
    }

    Box(modifier = modifier.size(tileSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val progress = press.value
            val half = size.minDimension / 2f
            val rest = if (round) half else half * TILE_CORNER
            val corner = (rest + (half - rest) * progress).coerceIn(0f, half)
            val shrink = 1f - 0.06f * progress
            scale(shrink, shrink) {
                drawRoundRect(containerColor, cornerRadius = CornerRadius(corner))
            }
        }
        Canvas(
            modifier = Modifier
                .requiredSize(if (round) tileSize else tileSize * TILE_REACH)
                .graphicsLayer {
                    val progress = press.value
                    val drop = landing.value
                    val squeeze = (1f - 0.08f * progress) * (1f + 0.25f * drop)
                    scaleX = squeeze
                    scaleY = squeeze
                    rotationZ = -type.tilt * progress
                    if (!round) {
                        translationX = tileSize.toPx() * TILE_SHIFT * (1f - progress)
                        translationY = -tileSize.toPx() * TILE_SHIFT * (1f - progress)
                    }
                    translationY += -6.dp.toPx() * drop
                    alpha = (1f - drop).coerceIn(0f, 1f) * 0.6f + 0.4f
                },
        ) {
            drawSensorCached(type, tones)
        }
    }
}

/** The tile's corner at rest, against half its side: the 16 dp of the card's other squares at 56 dp. */
private const val TILE_CORNER = 0.57f

/** The sensor's drawing against the tile's side: a disc spans about its full width. */
private const val TILE_REACH = 1.26f

/** How far the sensor sits towards the tile's upper right corner, against the tile's side. */
private const val TILE_SHIFT = 0.11f

/** Heroes that have played their entrance; each plays once per launch, not on every visit. */
private val heroesPlayed = mutableSetOf<SensorType>()

/** What a hero shows beside the sensor, moved aside as it arrives. */
private enum class HeroCompanion { NONE, APPLICATOR, PHONE }

private val SensorType.heroCompanion: HeroCompanion
    get() = when (this) {
        SensorType.CARESENS_AIR, SensorType.ACCUCHEK -> HeroCompanion.APPLICATOR
        SensorType.LIBRE -> HeroCompanion.PHONE
        else -> HeroCompanion.NONE
    }

/**
 * A sensor at wizard size in a circle. It answers a touch: the circle gives under the finger
 * and letting go drops the sensor on again, with one tick. It is a toy, not a
 * control, so it adds nothing for accessibility services to announce. Between touches nothing
 * runs.
 *
 * Beside it, the CareSens Air or SmartGuide applicator lifts away from the sensor it has just
 * placed, and a phone comes in over the Libre, its signal rippling out once from the sensor. That
 * plays the first time a hero opens after launch; later visits find it already in place.
 */
@Composable
internal fun SensorHeroArt(
    type: SensorType,
    modifier: Modifier = Modifier,
    tileSize: Dp = 120.dp,
) {
    val tones = rememberSensorTones()
    val ripple = MaterialTheme.colorScheme.primary
    val tick = rememberNavigationTick()
    val interactionSource = remember { MutableInteractionSource() }
    var plays by remember { mutableIntStateOf(0) }
    val companionKind = type.heroCompanion

    // 0 = on top of the sensor, 1 = moved aside.
    val firstVisit = remember(type) { heroesPlayed.add(type) }
    val companion = remember { Animatable(if (firstVisit) 0f else 1f) }
    val signal = remember { Animatable(1f) }
    LaunchedEffect(plays) {
        if (plays == 0 && !firstVisit) return@LaunchedEffect
        if (companionKind != HeroCompanion.NONE) {
            companion.snapTo(0f)
            companion.animateTo(1f, ExpressiveMotion.slowSpatial())
        }
        if (companionKind == HeroCompanion.PHONE) {
            signal.snapTo(0f)
            signal.animateTo(1f, tween(durationMillis = 1100, easing = LinearOutSlowInEasing))
        }
    }

    Box(
        modifier = modifier
            .size(width = tileSize * 2, height = tileSize * 1.25f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { position ->
                        val press = PressInteraction.Press(position)
                        interactionSource.emit(press)
                        val released = tryAwaitRelease()
                        interactionSource.emit(
                            if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                        )
                        if (released) {
                            plays += 1
                            tick.play()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (companionKind == HeroCompanion.PHONE) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val progress = signal.value
                if (progress < 1f) {
                    val reach = tileSize.toPx() * 0.95f
                    for (ring in 0..2) {
                        val local = (progress * 1.5f - ring * 0.25f).coerceIn(0f, 1f)
                        if (local > 0f && local < 1f) {
                            drawCircle(
                                ripple.copy(alpha = (1f - local) * 0.7f),
                                radius = reach * (0.45f + 0.55f * local),
                                center = center + Offset(-tileSize.toPx() * 0.22f, 0f),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
        val tileShift = when (companionKind) {
            HeroCompanion.NONE -> 0.dp
            HeroCompanion.APPLICATOR -> tileSize * 0.3f
            HeroCompanion.PHONE -> -tileSize * 0.22f
        }
        SensorArtTile(
            type = type,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            tileSize = tileSize,
            interactionSource = interactionSource,
            modifier = Modifier.offset(x = tileShift),
            replay = plays,
            round = true,
        )
        if (companionKind != HeroCompanion.NONE) {
            Canvas(
                modifier = Modifier
                    .requiredSize(tileSize * 1.05f)
                    .graphicsLayer {
                        val moved = companion.value
                        val tile = tileSize.toPx()
                        val shift = tileShift.toPx()
                        if (companionKind == HeroCompanion.APPLICATOR) {
                            // Starts seated on the sensor, ends standing beside it.
                            translationX = shift + (-tile * 0.62f) * moved
                            translationY = (-tile * 0.42f) * (1f - moved) + tile * 0.02f * moved
                            rotationZ = 14f * (1f - moved)
                        } else {
                            // Comes in from the top corner and hangs just over the sensor.
                            translationX = tile * 0.46f + tile * 0.5f * (1f - moved)
                            translationY = -tile * 0.1f - tile * 0.5f * (1f - moved)
                            rotationZ = 16f + 18f * (1f - moved)
                        }
                        alpha = (moved * 2.5f).coerceIn(0f, 1f)
                    },
            ) {
                if (companionKind == HeroCompanion.APPLICATOR) {
                    drawApplicator(type, tones)
                } else {
                    drawNfcPhone(tones)
                }
            }
        }
    }
}

/**
 * The drawing and the instruction together, above a scanner or a field: what the user is holding
 * and what to do with it, like Ottai's applicator card.
 */
@Composable
internal fun SensorHeroCard(
    type: SensorType,
    title: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SensorHeroArt(type)
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                textAlign = TextAlign.Center,
            )
        }
    }
}
