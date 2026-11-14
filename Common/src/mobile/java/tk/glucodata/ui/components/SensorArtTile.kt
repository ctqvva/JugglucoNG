package tk.glucodata.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.flow.drop
import kotlin.math.atan2
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.ui.rememberNavigationTick
import tk.glucodata.ui.util.ExpressiveMotion

/**
 * The launch tile: a sensor on the same soft square every row uses, so the sensors' own outlines
 * are what tells the rows apart. The sensor is drawn bigger than the square and set up out of it,
 * so it lies across the edge rather than sitting framed inside it, a few
 * degrees off straight. Pressing the card rounds the square towards a circle, pushes the sensor
 * in and turns it square to the row; letting go springs all three back. Everything moves in the
 * draw and layer phases, and nothing moves at rest or when a screen opens.
 *
 * [round] keeps the frame a circle throughout, for the wizard heroes. Raising [replay] drops the sensor on to its frame again. [lead] brings a pair's second sensor to the front: see [drawPairLed].
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
    lead: () -> Float = { 0f },
) {
    val context = LocalContext.current
    remember(context) { SensorImages.attach(context.applicationContext) }
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
                .requiredSize(tileSize * if (round) HERO_REACH else TILE_REACH)
                .graphicsLayer {
                    val progress = press.value
                    val drop = landing.value
                    val squeeze = (1f - 0.08f * progress) * (1f + 0.25f * drop)
                    scaleX = squeeze
                    scaleY = squeeze
                    rotationZ = -type.tilt * progress
                    if (!round) {
                        // Up, and only a touch towards the text, so it stays clear of the title.
                        translationX = tileSize.toPx() * TILE_SHIFT_X * (1f - progress)
                        translationY = -tileSize.toPx() * TILE_SHIFT * (1f - progress)
                    }
                    translationY += -6.dp.toPx() * drop
                    alpha = (1f - drop).coerceIn(0f, 1f) * 0.6f + 0.4f
                },
        ) {
            drawPairLed(type, tones, lead())
        }
    }
}

/** The tile's corner at rest, against half its side: the 16 dp of the card's other squares at 56 dp. */
private const val TILE_CORNER = 0.57f

/** The sensor's drawing against the tile's side: a disc spans about its full width. */
private const val TILE_REACH = 1.26f

/** The sensor against the hero's circle: a little past it, so it sits on it, not in it. */
private const val HERO_REACH = 1.1f

/** How far the sensor sits up out of the tile, against the tile's side. */
private const val TILE_SHIFT = 0.11f

/** How far it sits towards the text: barely, so it overlaps the tile's edge and not the title. */
private const val TILE_SHIFT_X = -0.01f

/** Heroes that have played their entrance; each plays once per launch, not on every visit. */
private val heroesPlayed = mutableSetOf<SensorType>()

/**
 * How a family connects, which the hero acts out with a phone: held over the sensor sending NFC
 * from its back, reading the code off what the sensor comes in, or standing apart to pair over
 * Bluetooth with the sensor's signal reaching it.
 */
internal enum class HeroCompanion { NFC, SCAN, PAIR }

private val SensorType.heroCompanion: HeroCompanion
    get() = when (this) {
        SensorType.LIBRE -> HeroCompanion.NFC
        SensorType.DEXCOM, SensorType.ICANHEALTH, SensorType.SIBIONICS,
        SensorType.ACCUCHEK, SensorType.CARESENS_AIR -> HeroCompanion.SCAN
        SensorType.AIDEX, SensorType.ANYTIME, SensorType.MQ, SensorType.OTTAI, SensorType.MEDTRUM -> HeroCompanion.PAIR
    }

/** What the family's code is printed on, for the scanning heroes. */
private val SensorType.codeCarrier: CodeCarrier
    get() = when (this) {
        SensorType.DEXCOM -> CodeCarrier.DEXCOM
        SensorType.ICANHEALTH -> CodeCarrier.ICAN
        SensorType.ACCUCHEK -> CodeCarrier.ACCUCHEK
        SensorType.CARESENS_AIR -> CodeCarrier.CARESENS
        else -> CodeCarrier.NONE
    }

/**
 * Where the pieces of a hero stand, in tile sides from the middle of the stage: the sensor, what
 * the code is printed on, and the phone with its lean.
 */
private class HeroStage(
    val width: Float,
    val sensorX: Float,
    val phone: Offset,
    val phoneTurn: Float,
    val carrier: Offset = Offset.Zero,
)

private fun stageFor(companion: HeroCompanion, carrier: CodeCarrier): HeroStage = when (companion) {
    HeroCompanion.SCAN -> if (carrier != CodeCarrier.NONE) {
        HeroStage(width = 2.75f, sensorX = -0.8f, carrier = Offset(0.1f, 0.08f), phone = Offset(0.92f, -0.1f), phoneTurn = 9f)
    } else {
        HeroStage(width = 2.1f, sensorX = -0.3f, phone = Offset(0.5f, -0.1f), phoneTurn = 10f)
    }
    HeroCompanion.NFC -> HeroStage(width = 2.2f, sensorX = -0.32f, phone = Offset(0.66f, -0.14f), phoneTurn = 18f)
    HeroCompanion.PAIR -> HeroStage(width = 2.3f, sensorX = -0.42f, phone = Offset(0.78f, 0.02f), phoneTurn = 6f)
}

/**
 * A sensor at wizard size in a circle, with a phone acting out how it connects, and for the
 * families set up from a printed code, the thing the code is on. The phone's screen says what is
 * happening: the NFC mark, the Bluetooth mark, or the camera closing on the code.
 *
 * It answers a touch: the circle gives under the finger, and letting go drops the sensor on
 * again and replays the whole story, with a click on letting go and a
 * double click when the phone has the code, the NFC read or the pairing. It is a toy, not a
 * control, so it adds nothing for accessibility services to announce. Between touches nothing
 * runs; the entrance plays the first time a hero opens after launch, silently.
 *
 * [pairLead] brings a pair's second sensor to the front with a click as they pass; a new
 * [companion] replays the entrance with it.
 */
@Composable
internal fun SensorHeroArt(
    type: SensorType,
    modifier: Modifier = Modifier,
    tileSize: Dp = 120.dp,
    pairLead: Boolean = false,
    companion: HeroCompanion? = null,
) {
    val tones = rememberSensorTones()
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val screenColor = scheme.surfaceContainerHighest
    val nfcMark = rememberVectorPainter(Icons.Rounded.Nfc)
    val bluetoothMark = rememberVectorPainter(Icons.Rounded.Bluetooth)
    val tick = rememberNavigationTick()
    val interactionSource = remember { MutableInteractionSource() }
    var plays by remember { mutableIntStateOf(0) }
    val companionKind = companion ?: type.heroCompanion
    val carrier = if (companionKind == HeroCompanion.SCAN) type.codeCarrier else CodeCarrier.NONE
    val stage = stageFor(companionKind, carrier)
    // A new companion (Ottai's wake by NFC or not) comes in as on a first visit, and was asked for.
    var shownCompanion by remember { mutableStateOf(companionKind) }
    LaunchedEffect(companionKind) {
        if (companionKind != shownCompanion) {
            shownCompanion = companionKind
            plays += 1
        }
    }
    // Opens with the chosen sensor already in front; a new choice brings the other forward.
    val lead by animateFloatAsState(
        targetValue = if (pairLead) 1f else 0f,
        animationSpec = ExpressiveMotion.slowSpatial(),
        label = "pairLead",
    )
    LaunchedEffect(Unit) {
        snapshotFlow { lead >= 0.5f }.drop(1).collect { tick.play() }
    }

    // 0 = not yet arrived, 1 = in place.
    val firstVisit = remember(type) { heroesPlayed.add(type) }
    val arrival = remember { Animatable(if (firstVisit) 0f else 1f) }
    // The phone's one beat: the NFC read, the camera's sweep and read, the Bluetooth signal.
    val signal = remember { Animatable(1f) }
    // The sensor's circle springs in with a bounce, and bounces again on every replay.
    val pop = remember { Animatable(if (firstVisit) 0f else 1f) }
    LaunchedEffect(plays) {
        if (plays == 0 && !firstVisit) return@LaunchedEffect
        val asked = plays > 0
        coroutineScope {
            launch {
                pop.snapTo(if (asked) 0.86f else 0f)
                pop.animateTo(1f, HeroBounce)
            }
            arrival.snapTo(0f)
            signal.snapTo(0f)
            arrival.animateTo(1f, ExpressiveMotion.slowSpatial())
            signal.animateTo(1f, tween(durationMillis = 1_200, easing = LinearOutSlowInEasing))
            if (asked) tick.playConfirm()
        }
    }

    val sensorShift by animateDpAsState(
        targetValue = tileSize * stage.sensorX,
        animationSpec = ExpressiveMotion.defaultSpatial(),
        label = "heroShift",
    )
    Box(
        modifier = modifier
            .size(width = tileSize * stage.width, height = tileSize * 1.3f)
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
        SensorArtTile(
            type = type,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            tileSize = tileSize,
            interactionSource = interactionSource,
            modifier = Modifier
                .offset(x = sensorShift)
                .graphicsLayer {
                    val grow = 0.5f + 0.5f * pop.value
                    scaleX = grow
                    scaleY = grow
                },
            replay = plays,
            round = true,
            lead = { lead },
        )
        // The signal, over the sensor and under the phone: from the phone's edge to the sensor for
        // NFC, from the sensor to the phone for Bluetooth. It lights in turn and stays faint.
        if (companionKind != HeroCompanion.SCAN) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val progress = signal.value
                val tile = tileSize.toPx()
                val sensor = center + Offset(sensorShift.toPx(), 0f)
                val phone = center + Offset(stage.phone.x * tile, stage.phone.y * tile)
                val (from, toward) = if (companionKind == HeroCompanion.NFC) {
                    // The phone's near edge, where it faces the sensor.
                    (phone + Offset(-0.3f * tile, 0.16f * tile)) to sensor
                } else {
                    (sensor + Offset(0.5f * tile, 0f)) to phone
                }
                val aim = Math.toDegrees(atan2((toward - from).y, (toward - from).x).toDouble()).toFloat()
                if (companionKind == HeroCompanion.NFC && progress < 1f) {
                    // The sensor answers: one ring out from it, gone by the end.
                    val local = (progress * 1.6f - 0.4f).coerceIn(0f, 1f)
                    if (local > 0f && local < 1f) {
                        drawCircle(
                            accent.copy(alpha = (1f - local) * 0.6f),
                            radius = tile * (0.5f + 0.32f * local),
                            center = sensor,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                for (arc in 0..2) {
                    val lit = (progress * 3f - arc).coerceIn(0f, 1f)
                    val radius = tile * (0.1f + 0.08f * arc)
                    drawArc(
                        color = accent.copy(alpha = 0.5f + 0.5f * lit * (1f - progress)),
                        startAngle = aim - 38f,
                        sweepAngle = 76f,
                        useCenter = false,
                        topLeft = from - Offset(radius, radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        if (carrier != CodeCarrier.NONE) {
            Canvas(
                modifier = Modifier
                    .requiredSize(tileSize * 1.02f)
                    .graphicsLayer {
                        val moved = arrival.value
                        val tile = tileSize.toPx()
                        // Comes out from behind the sensor and stands beside it.
                        val start = sensorShift.toPx()
                        translationX = start + (stage.carrier.x * tile - start) * moved
                        translationY = stage.carrier.y * tile + tile * 0.12f * (1f - moved)
                        val grow = 0.7f + 0.3f * moved
                        scaleX = grow
                        scaleY = grow
                        rotationZ = -12f * (1f - moved)
                        alpha = (moved * 2.5f).coerceIn(0f, 1f)
                    },
            ) {
                drawCodeCarrier(carrier, tones)
            }
        }
        Canvas(
            modifier = Modifier
                .requiredSize(tileSize * 1.08f)
                .graphicsLayer {
                    val moved = arrival.value
                    val tile = tileSize.toPx()
                    translationX = stage.phone.x * tile
                    translationY = stage.phone.y * tile
                    rotationZ = stage.phoneTurn
                    when (companionKind) {
                        HeroCompanion.NFC, HeroCompanion.SCAN -> {
                            // Comes in from the top corner.
                            translationX += tile * 0.5f * (1f - moved)
                            translationY += -tile * 0.5f * (1f - moved)
                            rotationZ += 18f * (1f - moved)
                        }
                        HeroCompanion.PAIR -> {
                            // Rises into place a little way off, upright: it talks over the air.
                            translationY += tile * 0.35f * (1f - moved)
                        }
                    }
                    alpha = (moved * 2.5f).coerceIn(0f, 1f)
                },
        ) {
            val beat = signal.value
            val screen = when (companionKind) {
                HeroCompanion.NFC -> PhoneScreen.Glyph(nfcMark, pulse = beat)
                HeroCompanion.PAIR -> PhoneScreen.Glyph(bluetoothMark, pulse = beat)
                HeroCompanion.SCAN -> PhoneScreen.Viewfinder(
                    code = carrier.code,
                    sweep = (beat / 0.6f).coerceIn(0f, 1f),
                    lock = ((beat - 0.55f) / 0.45f).coerceIn(0f, 1f),
                )
            }
            drawHeroPhone(tones, screenColor, accent, screen)
        }
    }
}

/** The bounce the hero's circle comes in on: under-damped, so it overshoots and settles. */
private val HeroBounce = spring<Float>(dampingRatio = 0.45f, stiffness = 380f)

/**
 * How much room a wizard's hero takes: the full picture with its text centred under it, or a
 * compact row with the picture beside its text. Compact gives the space back when the page has
 * a camera or a long list below it on a short screen, and whenever the keyboard is up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberSensorHeroCompact(sharesScreen: Boolean): Boolean {
    val height = LocalConfiguration.current.screenHeightDp
    val keyboard = WindowInsets.isImeVisible
    return keyboard || height < 600 || (sharesScreen && height < 860)
}

/**
 * The top of a sensor wizard's main step, the same in every wizard: the sensor and its companion
 * with what to do. [title] and [subtitle] are the step's own heading; when the step's own list
 * header already says it, both can be left out and the picture stands alone. [compact] trades the
 * full picture for a row, moving between the two on the expressive spring. [pairLead] puts a
 * pair's second sensor in front, for a step where it is the one chosen; [companion] stands in for
 * the family's own when the chosen model connects another way.
 */
@Composable
internal fun SensorWizardHero(
    type: SensorType,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    compact: Boolean = false,
    pairLead: Boolean = false,
    companion: HeroCompanion? = null,
) {
    val wide = !rememberAdaptiveWindowMetrics().isCompact
    AnimatedContent(
        targetState = compact,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            (fadeIn(ExpressiveMotion.defaultEffects()) togetherWith fadeOut(ExpressiveMotion.fastEffects()))
                .using(SizeTransform(clip = false) { _, _ -> ExpressiveMotion.defaultSpatial() })
        },
        contentAlignment = Alignment.TopCenter,
        label = "sensorHero",
    ) { small ->
        if (small) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (title == null) Arrangement.Center else Arrangement.Start,
            ) {
                SensorHeroArt(type, tileSize = 56.dp, pairLead = pairLead, companion = companion)
                if (title != null) {
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SensorHeroArt(type, tileSize = if (wide) 128.dp else 112.dp, pairLead = pairLead, companion = companion)
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        textAlign = TextAlign.Center,
                    )
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
