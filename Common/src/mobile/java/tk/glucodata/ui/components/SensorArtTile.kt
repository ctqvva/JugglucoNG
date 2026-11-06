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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.ui.rememberNavigationTick
import tk.glucodata.ui.util.ExpressiveMotion

/** The drawing's canvas against its shape: a sensor reaches about four fifths of its canvas. */
private const val ART_OVERHANG = 1.06f

/** Between one tile's arrival and the next, so a list settles from the top down. */
private const val ARRIVAL_STAGGER_MS = 28L

/**
 * Each sensor's own shape, picked to echo its outline: scalloped for the adhesive discs, long
 * for the capsules, an arch for the one that stands upright. Soft shapes only: the sensor is
 * the subject and a burst behind it would shout over it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun SensorType.tileShape(): RoundedPolygon = when (this) {
    SensorType.LIBRE -> MaterialShapes.Cookie12Sided
    SensorType.DEXCOM -> MaterialShapes.Cookie6Sided
    SensorType.SIBIONICS -> MaterialShapes.Pill
    SensorType.AIDEX -> MaterialShapes.Cookie9Sided
    SensorType.OTTAI -> MaterialShapes.Sunny
    SensorType.ICANHEALTH -> MaterialShapes.Clover8Leaf
    SensorType.ANYTIME -> MaterialShapes.Puffy
    SensorType.ACCUCHEK -> MaterialShapes.Arch
    SensorType.CARESENS_AIR -> MaterialShapes.Oval
    SensorType.MQ -> MaterialShapes.Cookie4Sided
    SensorType.MEDTRUM -> MaterialShapes.Clover4Leaf
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun morphOf(type: SensorType) = Morph(type.tileShape(), MaterialShapes.Circle)

/**
 * The launch tile: a sensor resting on a shape of its own. Pressing the card behind it rounds the
 * shape off and pushes the sensor down into it; letting go springs both back. Everything moves
 * in the draw and layer phases, and nothing moves at rest.
 *
 * [arrivalOrder] plays a one-off entrance, the shape growing and the sensor dropping on to it in
 * turn. Null keeps a tile still on arrival, for lists that recompose it as it scrolls into view.
 * Raising [replay] drops the sensor on to the shape again.
 */
@Composable
internal fun SensorArtTile(
    type: SensorType,
    containerColor: Color,
    tileSize: Dp,
    interactionSource: InteractionSource,
    modifier: Modifier = Modifier,
    arrivalOrder: Int? = null,
    replay: Int = 0,
    onPad: Boolean = false,
) {
    val tones = rememberSensorTones()
    val morph = remember(type) { morphOf(type) }
    val outline = remember { android.graphics.Path() }
    val composeOutline = remember(outline) { outline.asComposePath() }

    val press = remember { Animatable(0f) }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        press.animateTo(if (pressed) 1f else 0f, ExpressiveMotion.fastSpatial())
    }

    val shapeArrival = remember { Animatable(if (arrivalOrder == null) 1f else 0f) }
    val sensorArrival = remember { Animatable(if (arrivalOrder == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (arrivalOrder != null) {
            delay(arrivalOrder * ARRIVAL_STAGGER_MS)
            launch { shapeArrival.animateTo(1f, ExpressiveMotion.defaultSpatial()) }
            delay(ARRIVAL_STAGGER_MS * 3)
            sensorArrival.animateTo(1f, ExpressiveMotion.fastSpatial())
        }
    }
    LaunchedEffect(replay) {
        if (replay > 0) {
            sensorArrival.snapTo(0f)
            sensorArrival.animateTo(1f, ExpressiveMotion.fastSpatial())
        }
    }

    Box(modifier = modifier.size(tileSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val progress = press.value
            morph.toPath(progress, outline)
            val grown = shapeArrival.value * (1f - 0.06f * progress)
            scale(grown, grown) {
                rotate(degrees = 40f * progress) {
                    scale(size.width, size.height, pivot = Offset.Zero) {
                        drawPath(composeOutline, containerColor)
                    }
                }
            }
        }
        Canvas(
            modifier = Modifier
                .requiredSize(tileSize * ART_OVERHANG)
                .graphicsLayer {
                    val progress = press.value
                    val landing = 1f - sensorArrival.value
                    val squeeze = 1f - 0.1f * progress
                    scaleX = squeeze * (1f + 0.35f * landing)
                    scaleY = squeeze * (1f + 0.35f * landing)
                    rotationZ = -8f * progress - 24f * landing
                    rotationX = 16f * progress
                    translationY = -8.dp.toPx() * landing
                    alpha = sensorArrival.value.coerceIn(0f, 1f)
                },
        ) {
            drawSensor(type, tones, onPad)
        }
    }
}

/** What a hero shows beside the sensor, moved aside as it arrives. */
private enum class HeroCompanion { NONE, APPLICATOR, PHONE }

private val SensorType.heroCompanion: HeroCompanion
    get() = when (this) {
        SensorType.CARESENS_AIR -> HeroCompanion.APPLICATOR
        SensorType.LIBRE -> HeroCompanion.PHONE
        else -> HeroCompanion.NONE
    }

/**
 * A sensor at wizard size. It plays its arrival when the page opens, and answers a touch: the
 * shape rounds under the finger and letting go drops the sensor on again, with one tick. It is
 * a toy, not a control, so it adds nothing for accessibility services to announce. Between
 * touches nothing runs.
 *
 * Beside it, CareSens Air's applicator lifts away from the sensor it has just placed, and a
 * phone comes in over the Libre, its signal rippling out once from the sensor.
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
    val companion = remember { Animatable(0f) }
    val signal = remember { Animatable(1f) }
    LaunchedEffect(plays) {
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
            arrivalOrder = 0,
            replay = plays,
            onPad = true,
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
                    drawCareSensAirApplicator(tones)
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
