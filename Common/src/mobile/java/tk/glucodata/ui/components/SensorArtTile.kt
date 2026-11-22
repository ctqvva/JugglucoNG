package tk.glucodata.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.pow
import kotlin.random.Random
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.acos
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.spring
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.ui.geometry.RoundRect
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
import tk.glucodata.ui.isAppInDarkTheme
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics
import tk.glucodata.ui.util.ExpressiveMotion
import tk.glucodata.ui.symbols.Symbols

/**
 * The launch tile: a sensor on the same soft square every row uses, so the sensors' own outlines
 * are what tells the rows apart. The sensor is drawn bigger than the square and set up out of it,
 * so it lies across the edge rather than sitting framed inside it, a few
 * degrees off straight. Pressing the card rounds the square towards a circle, pushes the sensor
 * in and turns it square to the row; letting go springs all three back. Everything moves in the
 * draw and layer phases, and nothing moves at rest or when a screen opens.
 *
 * The wizard heroes draw the sensor alone ([frame] false, no lift), in front of their shape. Raising [replay] drops the sensor on to its frame again. [lead] brings a pair's second sensor to the front: see [drawPairLed].
 */
@Composable
internal fun SensorArtTile(
    type: SensorType,
    containerColor: Color,
    tileSize: Dp,
    interactionSource: InteractionSource,
    modifier: Modifier = Modifier,
    replay: Int = 0,
    lead: () -> Float = { 0f },
    lift: Float = TILE_SHIFT,
    drift: Float = TILE_SHIFT_X,
    frame: Boolean = true,
    pairSpread: Float = 1f,
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
            // A soft hop and a springy settle, never a jump cut.
            landing.animateTo(1f, tween(durationMillis = 120, easing = FastOutSlowInEasing))
            landing.animateTo(0f, SoftSettle)
        }
    }

    Box(modifier = modifier.size(tileSize), contentAlignment = Alignment.Center) {
        if (frame) Canvas(modifier = Modifier.matchParentSize()) {
            val progress = press.value
            val half = size.minDimension / 2f
            val rest = half * TILE_CORNER
            val corner = (rest + (half - rest) * progress).coerceIn(0f, half)
            val shrink = 1f - 0.06f * progress
            scale(shrink, shrink) {
                drawRoundRect(containerColor, cornerRadius = CornerRadius(corner))
            }
        }
        Canvas(
            modifier = Modifier
                .requiredSize(tileSize * TILE_REACH)
                .graphicsLayer {
                    val progress = press.value
                    val drop = landing.value
                    val squeeze = (1f - 0.08f * progress) * (1f + 0.07f * drop)
                    scaleX = squeeze
                    scaleY = squeeze
                    rotationZ = -type.tilt * progress
                    // Up, and only a touch towards the text, so it stays clear of the title.
                    translationX = tileSize.toPx() * drift * (1f - progress)
                    translationY = -tileSize.toPx() * lift * (1f - progress)
                    translationY += -5.dp.toPx() * drop
                },
        ) {
            drawPairLed(type, tones, lead(), pairSpread)
        }
    }
}

/** The tile's corner at rest, against half its side: the 16 dp of the card's other squares at 56 dp. */
private const val TILE_CORNER = 0.57f

/** The sensor's drawing against the tile's side: a disc spans about its full width. */
private const val TILE_REACH = 1.26f

/** How far the sensor sits up out of the tile, against the tile's side. */
private const val TILE_SHIFT = 0.11f

/** How far it sits towards the text: barely, so it overlaps the tile's edge and not the title. */
private const val TILE_SHIFT_X = -0.01f

/**
 * What a sensor is drawn against, in the list and in the wizards alike, so the tile you tap and
 * the hero it opens are the same colour: `secondaryContainer`, opaque in light themes (a
 * see-through pale fill leaves white plastic without edges) and at 65% over `surface` in dark
 * ones, whatever the card under it. Always opaque, so the drawings' shading can take it in.
 */
@Composable
internal fun sensorBackdropColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return if (isAppInDarkTheme()) scheme.secondaryContainer.copy(alpha = 0.65f).compositeOver(scheme.surface) else scheme.secondaryContainer
}

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

private fun stageFor(companion: HeroCompanion, carrier: CodeCarrier, pair: Boolean): HeroStage = when (companion) {
    HeroCompanion.SCAN -> if (carrier != CodeCarrier.NONE) {
        HeroStage(width = 2.75f, sensorX = -0.72f, carrier = Offset(0.16f, 0.12f), phone = Offset(0.96f, -0.06f), phoneTurn = 9f)
    } else {
        // A pair drawn apart takes more room, and the phone stands further off.
        if (pair) {
            HeroStage(width = 2.4f, sensorX = -0.4f, phone = Offset(0.7f, -0.04f), phoneTurn = 10f)
        } else {
            HeroStage(width = 2.1f, sensorX = -0.3f, phone = Offset(0.5f, -0.06f), phoneTurn = 10f)
        }
    }
    HeroCompanion.NFC -> HeroStage(width = 2.3f, sensorX = -0.36f, phone = Offset(0.7f, -0.1f), phoneTurn = 18f)
    HeroCompanion.PAIR -> HeroStage(width = 2.3f, sensorX = -0.42f, phone = Offset(0.78f, 0.04f), phoneTurn = 6f)
}

/**
 * The shape behind a hero's scene, the same rule for every family: each piece (the sensor, the
 * pack, the phone at its lean) is a box round what it shows, its top let out a little so tall
 * things rise past the shape; the outline is the convex hull of those boxes with one wide corner
 * radius throughout, so no corner can pinch, nothing is tailored to one sensor, and every
 * piece's base stands inside it. Where two pieces leave open air for a radio signal, the top
 * eases in.
 */
/**
 * The pieces are drawn ART times the size the shape is built for, and stand SPREAD times as far
 * apart, so they extend firmly out of the shape rather than being fitted into it.
 */
private const val ART = 1.1f
private const val SPREAD = 1.05f

/**
 * The shape behind a hero's scene, drawn fresh each time a wizard opens: one of these styles,
 * at its own small tilt, so a style that sits badly with one scene is not the one shown every
 * time, and a tap trades it for another. Every style is built round the same boxes, so each
 * fits the scene it is given.
 */
private enum class BackdropStyle {
    /** The pieces' boxes wrapped in one soft hull. */
    Hull,

    /** One capsule spanning the scene. */
    Capsule,

    /** A disc behind the sensor and one behind the phone, joined by straight tangents. */
    Bean,

    /** A superellipse spanning the scene, between a rectangle and an ellipse. */
    Squircle,
}

/** A style and a tilt in degrees for one showing of a hero, other than [not]. */
private fun rollBackdrop(random: Random, not: BackdropStyle? = null): Pair<BackdropStyle, Float> {
    val choices = BackdropStyle.entries.filter { it != not }
    return choices[random.nextInt(choices.size)] to (random.nextFloat() * 10f - 5f)
}

/** [style] round [pieces], a sensor disc at [sensorAt] and a phone disc at [phoneAt]. */
private fun backdropPath(style: BackdropStyle, pieces: List<ScenePiece>, sensorAt: Offset, phoneAt: Offset, tile: Float): Path {
    val pad = 0.08f * tile
    val left = pieces.minOf { it.centre.x - it.left } - pad
    val right = pieces.maxOf { it.centre.x + it.right } + pad
    val top = pieces.minOf { it.centre.y - it.up } - pad
    val bottom = pieces.maxOf { it.centre.y + it.down } + pad
    return when (style) {
        BackdropStyle.Hull -> sceneShape(pieces, tile, dip = 0f, dipFrom = 0f, dipTo = 0f)
        BackdropStyle.Capsule -> Path().apply {
            addRoundRect(RoundRect(left, top, right, bottom, CornerRadius((bottom - top) / 2f)))
        }
        BackdropStyle.Bean -> discHull(
            buildList {
                add(Disc(sensorAt + Offset(0f, 0.04f * tile), 0.6f * tile))
                pieces.drop(1).dropLast(1).forEach { add(Disc(it.centre, 0.46f * tile)) }
                add(Disc(phoneAt + Offset(0f, 0.06f * tile), 0.5f * tile))
            },
            step = 0.02f * tile,
        )
        BackdropStyle.Squircle -> Path().apply {
            val middle = Offset((left + right) / 2f, (top + bottom) / 2f)
            val a = (right - left) / 2f
            val b = (bottom - top) / 2f
            for (k in 0..120) {
                val angle = k / 120f * 2f * PI.toFloat()
                val c = cos(angle)
                val n = sin(angle)
                val x = a * sign(c) * abs(c).pow(0.5f)
                val y = b * sign(n) * abs(n).pow(0.5f)
                if (k == 0) moveTo(middle.x + x, middle.y + y) else lineTo(middle.x + x, middle.y + y)
            }
            close()
        }
    }
}

private class ScenePiece(
    val centre: Offset,
    val left: Float,
    val right: Float,
    val up: Float,
    val down: Float,
    val turn: Float = 0f,
)

private fun sceneShape(pieces: List<ScenePiece>, tile: Float, dip: Float, dipFrom: Float, dipTo: Float): Path {
    val round = 0.32f * tile
    val pad = 0.08f * tile
    val inset = round - pad
    fun pull(v: Float) = if (v > 0f) (v - inset).coerceAtLeast(0f) else (v + inset).coerceAtMost(0f)
    val corners = pieces.flatMap { piece ->
        val radians = piece.turn * PI.toFloat() / 180f
        listOf(
            Offset(-piece.left, -piece.up),
            Offset(piece.right, -piece.up),
            Offset(piece.right, piece.down),
            Offset(-piece.left, piece.down),
        ).map { corner ->
            val x = pull(corner.x)
            val y = pull(corner.y)
            piece.centre + Offset(x * cos(radians) - y * sin(radians), x * sin(radians) + y * cos(radians))
        }
    }
    return discHull(corners.map { Disc(it, round) }, step = 0.02f * tile, dip = dip, dipFrom = dipFrom, dipTo = dipTo)
}

private class Disc(val centre: Offset, val radius: Float)

/**
 * The convex hull of [discs]: arcs round the outer ones joined by straight outer tangents, any
 * disc inside the others' reach passed over rather than pulled in. Found from points round
 * every disc, then walked at an even step so the arcs stay smooth. [dip] then bows the top in
 * between [dipFrom] and [dipTo] (screen x), for an open gap the signal crosses.
 */
private fun discHull(discs: List<Disc>, step: Float, dip: Float = 0f, dipFrom: Float = 0f, dipTo: Float = 0f): Path {
    val samples = ArrayList<Offset>(discs.size * 72)
    for (disc in discs) for (k in 0 until 72) {
        val angle = k / 72f * 2f * PI.toFloat()
        samples += disc.centre + Offset(cos(angle), sin(angle)) * disc.radius
    }
    samples.sortWith(compareBy<Offset>({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val hull = ArrayList<Offset>()
    for (pass in 0..1) {
        val start = hull.size
        val ordered = if (pass == 0) samples else samples.asReversed()
        for (point in ordered) {
            while (hull.size >= start + 2 && cross(hull[hull.size - 2], hull[hull.size - 1], point) <= 0f) hull.removeAt(hull.size - 1)
            hull += point
        }
        hull.removeAt(hull.size - 1)
    }
    // Even steps along the outline, so a long tangent can bend for the dip.
    val even = ArrayList<Offset>()
    for (i in hull.indices) {
        val from = hull[i]
        val to = hull[(i + 1) % hull.size]
        val pieces = ((to - from).getDistance() / step).toInt().coerceAtLeast(1)
        for (k in 0 until pieces) even += from + (to - from) * (k.toFloat() / pieces)
    }
    val middle = even.fold(Offset.Zero) { sum, point -> sum + point } / even.size.toFloat()
    return Path().apply {
        even.forEachIndexed { index, point ->
            var at = point
            if (dip > 0f && at.y < middle.y && at.x > dipFrom && at.x < dipTo) {
                // Eased in at both ends, so the waist starts without a kink.
                val bend = sin(PI.toFloat() * (at.x - dipFrom) / (dipTo - dipFrom))
                at += Offset(0f, dip * bend * bend)
            }
            if (index == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
        }
        close()
    }
}

/**
 * A sensor at wizard size with a phone acting out how it connects, in front of a hull of discs
 * built round them, and for the
 * families set up from a printed code, the thing the code is on. The phone's screen says what is
 * happening: the NFC mark, the Bluetooth mark, or the camera closing on the code.
 *
 * It answers a touch: the sensor gives under the finger, and letting go stretches the shape, drops the sensor on
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
    tileSize: Dp = 112.dp,
    pairLead: Boolean = false,
    companion: HeroCompanion? = null,
) {
    val tones = rememberSensorTones()
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val screenColor = scheme.surfaceContainerHighest
    val screenAccent = scheme.primary
    val phoneRim = scheme.outline
    val blobColor = sensorBackdropColor()
    val nfcMark = rememberVectorPainter(Symbols.NfcFilled)
    val bluetoothMark = rememberVectorPainter(Symbols.BluetoothFilled)
    val tick = rememberNavigationTick()
    val interactionSource = remember { MutableInteractionSource() }
    var plays by remember { mutableIntStateOf(0) }
    var entrances by remember { mutableIntStateOf(0) }
    val companionKind = companion ?: type.heroCompanion
    val carrier = if (companionKind == HeroCompanion.SCAN) type.codeCarrier else CodeCarrier.NONE
    val pair = type.tradesPlaces
    val stage = stageFor(companionKind, carrier, pair)
    // A pair is drawn a little larger and its two sensors further apart than in the list.
    val sensorSize = if (pair) PAIR_HERO_SIZE else 1f
    // Where everything stands, moving on the spatial spring when the companion changes.
    val spring = ExpressiveMotion.defaultSpatial<Float>()
    val stageWidth by animateFloatAsState(stage.width, spring, label = "heroWidth")
    val phoneX by animateFloatAsState(stage.phone.x, spring, label = "heroPhoneX")
    val phoneY by animateFloatAsState(stage.phone.y, spring, label = "heroPhoneY")
    val phoneTurn by animateFloatAsState(stage.phoneTurn, spring, label = "heroPhoneTurn")
    // A new companion (Ottai's wake by NFC or not) comes in as on a first visit, and was asked for.
    var shownCompanion by remember { mutableStateOf(companionKind) }
    LaunchedEffect(companionKind) {
        if (companionKind != shownCompanion) {
            shownCompanion = companionKind
            entrances += 1
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
    // The sensor springs up on a bounce, and bounces again on every replay.
    val pop = remember { Animatable(if (firstVisit) 0f else 1f) }
    // The shape is rolled fresh each time the hero is shown; a tap trades it for another.
    val random = remember { Random(System.nanoTime()) }
    // The styles on show, newest last, each with how far it has faded in. A tap adds one and
    // fades every older one out from wherever it is, so taps in quick succession blend on
    // instead of snapping a half-faded shape to full.
    val backdrops = remember { mutableStateListOf(BackdropLayer(rollBackdrop(random), Animatable(1f))) }
    // The phone leaning in towards the sensor on a tap, and back.
    val nudge = remember { Animatable(0f) }
    // The shape's stretch: a tap pulls it wide and it springs back.
    val swing = remember { Animatable(0f) }
    // Under the finger the shape gathers in a little.
    val press = remember { Animatable(0f) }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        press.animateTo(if (pressed) 1f else 0f, ExpressiveMotion.fastSpatial())
    }

    // All of the hero's motion has one owner: a new tap or entrance restarts this effect, which
    // cancels whatever was running and carries on from where every value then stands, so no
    // two runs fight over the same animation and nothing is left halfway.
    val handledPlays = remember { intArrayOf(0) }
    LaunchedEffect(entrances, plays) {
        val tapped = plays != handledPlays[0]
        handledPlays[0] = plays
        if (!tapped) {
            // The entrance: the first time after launch, and when a new companion comes in. Silent.
            if (entrances == 0 && !firstVisit) return@LaunchedEffect
            coroutineScope {
                launch {
                    pop.snapTo(0.2f)
                    pop.animateTo(1f, HeroBounce)
                }
                launch { settleBackdrops(backdrops) }
                launch { nudge.animateTo(0f, SoftSettle) }
                launch { swing.animateTo(0f, SoftSettle) }
                arrival.snapTo(0f)
                signal.snapTo(0f)
                arrival.animateTo(1f, ExpressiveMotion.slowSpatial())
                signal.animateTo(1f, tween(durationMillis = 1_200, easing = LinearOutSlowInEasing))
            }
            return@LaunchedEffect
        }
        // A tap: nothing vanishes or jumps. The shape breathes out and melts into its next
        // style, the sensor hops and settles, the phone leans in and back, and the signal plays
        // again, ending on one light click. An entrance still under way finishes in place.
        coroutineScope {
            launch { if (arrival.value < 1f) arrival.animateTo(1f, ExpressiveMotion.slowSpatial()) }
            launch {
                pop.animateTo(0.95f, tween(durationMillis = 120, easing = FastOutSlowInEasing))
                pop.animateTo(1f, SoftSettle)
            }
            launch {
                swing.animateTo(5f, tween(durationMillis = 140, easing = FastOutSlowInEasing))
                swing.animateTo(0f, SoftSettle)
            }
            launch { settleBackdrops(backdrops) }
            launch {
                nudge.animateTo(1f, tween(durationMillis = 160, easing = FastOutSlowInEasing))
                nudge.animateTo(0f, SoftSettle)
            }
            signal.snapTo(0f)
            signal.animateTo(1f, tween(durationMillis = 1_000, easing = LinearOutSlowInEasing))
            tick.playLight()
        }
    }

    val sensorShift by animateDpAsState(
        targetValue = tileSize * (stage.sensorX * SPREAD),
        animationSpec = ExpressiveMotion.defaultSpatial(),
        label = "heroShift",
    )
    Box(
        modifier = modifier
            .size(width = tileSize * (stageWidth * SPREAD), height = tileSize * 1.6f)
            // The scene sits a little high in its box: the shape grounds every piece, so it
            // reaches further down than up, and the heading under it keeps its distance.
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
                            backdrops += BackdropLayer(rollBackdrop(random, not = backdrops.last().style.first), Animatable(0f))
                            plays += 1
                            tick.play()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val tile = tileSize.toPx()
            val sensorAt = center + Offset(sensorShift.toPx(), 0f)
            val phoneAt = center + Offset(phoneX * tile, phoneY * tile) * SPREAD
            // A box round each piece at the size the shape is built for; the pieces themselves
            // are drawn larger and stand out past it.
            val pieces = buildList {
                val reach = tile * sensorSize
                add(ScenePiece(sensorAt, left = 0.46f * reach, right = 0.46f * reach, up = 0.4f * reach, down = 0.44f * reach))
                if (carrier != CodeCarrier.NONE) {
                    val carrierAt = center + Offset(stage.carrier.x * tile, stage.carrier.y * tile) * SPREAD
                    add(ScenePiece(carrierAt, left = 0.4f * tile, right = 0.36f * tile, up = 0.3f * tile, down = 0.48f * tile))
                }
                add(ScenePiece(phoneAt, left = 0.26f * tile, right = 0.26f * tile, up = 0.3f * tile, down = 0.5f * tile, turn = phoneTurn))
            }
            // Opens out from the sensor on entrance; a tap stretches it, a press gathers it in.
            val grow = backdropGrow(pop.value) * (1f - 0.04f * press.value)
            val kick = swing.value / 100f
            scale(grow * (1f + kick), grow * (1f - kick), pivot = sensorAt) {
                // Each style at its own fade: a fading one shrinks a touch, a new one swells in.
                for (layer in backdrops) {
                    val shown = layer.shown.value
                    if (shown <= 0f) continue
                    val (style, tilt) = layer.style
                    val outline = backdropPath(style, pieces, sensorAt, phoneAt, tile)
                    val size = 0.94f + 0.06f * shown
                    scale(size, size, pivot = center) {
                        rotate(tilt, pivot = center) { drawPath(outline, blobColor, alpha = shown) }
                    }
                }
            }
        }
        SensorArtTile(
            type = type,
            containerColor = blobColor,
            tileSize = tileSize * (ART * sensorSize),
            interactionSource = interactionSource,
            modifier = Modifier
                .offset(x = sensorShift)
                .graphicsLayer {
                    // Springs up on a bounce on entrance and on every replay.
                    val grow = 0.4f + 0.6f * pop.value
                    scaleX = grow
                    scaleY = grow
                    rotationZ = -14f * (1f - pop.value)
                },
            replay = plays,
            lead = { lead },
            lift = 0f,
            drift = 0f,
            frame = false,
            pairSpread = if (pair) PAIR_HERO_SPREAD else 1f,
        )
        // The signal, over the sensor and under the phone: from the phone's edge to the sensor for
        // NFC, from the sensor to the phone for Bluetooth. It lights in turn and stays faint.
        if (companionKind != HeroCompanion.SCAN) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val progress = signal.value
                val tile = tileSize.toPx()
                // Where the sensor lies: up out of its tile by the list tile's lift.
                val sensor = center + Offset(sensorShift.toPx(), 0f)
                val phone = center + Offset(phoneX * tile, phoneY * tile) * SPREAD
                val (from, toward) = if (companionKind == HeroCompanion.NFC) {
                    // The phone's near edge, where it faces the sensor.
                    (phone + Offset(-0.3f * tile, 0.16f * tile) * ART) to sensor
                } else {
                    // From the sensor's edge as it is drawn, so the arcs keep to it while it pops in.
                    (sensor + Offset(0.5f * tile * ART * (0.4f + 0.6f * pop.value), 0f)) to phone
                }
                val aim = Math.toDegrees(atan2((toward - from).y, (toward - from).x).toDouble()).toFloat()
                if (companionKind == HeroCompanion.NFC && progress < 1f) {
                    // The sensor answers: one ring out from it, gone by the end.
                    val local = (progress * 1.6f - 0.4f).coerceIn(0f, 1f)
                    if (local > 0f && local < 1f) {
                        drawCircle(
                            accent.copy(alpha = (1f - local) * 0.6f),
                            radius = tile * ART * (0.5f + 0.16f * local),
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
                    .requiredSize(tileSize * (1.02f * ART))
                    .graphicsLayer {
                        val moved = arrival.value
                        val tile = tileSize.toPx()
                        // Comes out from behind the sensor and stands beside it.
                        val start = sensorShift.toPx()
                        translationX = start + (stage.carrier.x * tile * SPREAD - start) * moved
                        translationY = stage.carrier.y * tile * SPREAD + tile * 0.12f * (1f - moved) - nudge.value * 0.04f * tile
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
                .requiredSize(tileSize * (1.08f * ART))
                .graphicsLayer {
                    val moved = arrival.value
                    val tile = tileSize.toPx()
                    translationX = phoneX * tile * SPREAD - nudge.value * 0.08f * tile
                    translationY = phoneY * tile * SPREAD
                    rotationZ = phoneTurn - 5f * nudge.value
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
            drawHeroPhone(tones, screenColor, screenAccent, phoneRim, screen)
        }
    }
}

/** The shape opens out from the sensor, a little behind the sensor's own bounce. */
private fun backdropGrow(pop: Float): Float = 0.35f + 0.65f * pop

/** How much larger a pair is drawn in a hero than the tile size, and how far apart its two are. */
private const val PAIR_HERO_SIZE = 1.1f
private const val PAIR_HERO_SPREAD = 1.3f

/** One style on show behind a hero, and how far it has faded in. */
private class BackdropLayer(val style: Pair<BackdropStyle, Float>, val shown: Animatable<Float, AnimationVector1D>)

/**
 * Fades the newest style in and every older one out, each from where it stands, then drops the
 * ones that have gone. Safe to cancel and run again at any point.
 */
private suspend fun settleBackdrops(layers: SnapshotStateList<BackdropLayer>) {
    val newest = layers.last()
    coroutineScope {
        for (layer in layers.toList()) {
            launch {
                layer.shown.animateTo(
                    if (layer === newest) 1f else 0f,
                    tween(durationMillis = 420, easing = FastOutSlowInEasing),
                )
            }
        }
    }
    layers.removeAll { it !== newest && it.shown.value <= 0f }
}

/** The settle after a tap's hop: soft, with one small overshoot. */
private val SoftSettle = spring<Float>(dampingRatio = 0.55f, stiffness = 260f)

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
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
