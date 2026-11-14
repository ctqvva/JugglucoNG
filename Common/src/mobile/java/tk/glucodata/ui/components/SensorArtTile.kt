package tk.glucodata.ui.components

import androidx.compose.animation.AnimatedContent
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
 * [round] keeps the frame a circle throughout and the drawing inside it, for the wizard heroes. Raising [replay] drops the sensor on to its frame again. [lead] brings a pair's second sensor to the front: see [drawPairLed].
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
                .requiredSize(if (round) tileSize else tileSize * TILE_REACH)
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

/** How far the sensor sits up out of the tile, against the tile's side. */
private const val TILE_SHIFT = 0.11f

/** How far it sits towards the text: barely, so it overlaps the tile's edge and not the title. */
private const val TILE_SHIFT_X = -0.01f

/** Heroes that have played their entrance; each plays once per launch, not on every visit. */
private val heroesPlayed = mutableSetOf<SensorType>()

/**
 * What a hero shows beside the sensor: how this family connects. The applicator lifts away from
 * the sensor it has just placed; a phone held over a Libre sends one NFC pulse; a phone reading a
 * code sweeps its viewfinder once; a phone pairing over Bluetooth gets a signal from the sensor.
 */
internal enum class HeroCompanion { APPLICATOR, NFC, SCAN, PAIR }

private val SensorType.heroCompanion: HeroCompanion
    get() = when (this) {
        SensorType.CARESENS_AIR, SensorType.ACCUCHEK -> HeroCompanion.APPLICATOR
        SensorType.LIBRE -> HeroCompanion.NFC
        SensorType.DEXCOM, SensorType.ICANHEALTH, SensorType.SIBIONICS -> HeroCompanion.SCAN
        SensorType.AIDEX, SensorType.ANYTIME, SensorType.MQ, SensorType.OTTAI, SensorType.MEDTRUM -> HeroCompanion.PAIR
    }

/**
 * A sensor at wizard size in a circle, with its companion beside it. It answers a touch: the
 * circle gives under the finger and letting go drops the sensor on again and replays the
 * companion, with one tick. It is a toy, not a control, so it adds nothing for accessibility
 * services to announce. Between touches nothing runs.
 *
 * The companion's motion plays the first time a hero opens after launch; later visits, and the
 * switch between full and compact sizes, find it already in place.
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
    val accent = MaterialTheme.colorScheme.primary
    val tick = rememberNavigationTick()
    val interactionSource = remember { MutableInteractionSource() }
    var plays by remember { mutableIntStateOf(0) }
    val companionKind = companion ?: type.heroCompanion
    // A new companion (Ottai's wake by NFC or not) comes in as on a first visit.
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

    // 0 = not yet arrived, 1 = in place.
    val firstVisit = remember(type) { heroesPlayed.add(type) }
    val companion = remember { Animatable(if (firstVisit) 0f else 1f) }
    // The companion's one beat: the NFC pulse, the viewfinder's sweep, the Bluetooth signal.
    val signal = remember { Animatable(1f) }
    LaunchedEffect(plays) {
        if (plays == 0 && !firstVisit) return@LaunchedEffect
        companion.snapTo(0f)
        signal.snapTo(0f)
        companion.animateTo(1f, ExpressiveMotion.slowSpatial())
        if (companionKind != HeroCompanion.APPLICATOR) {
            signal.animateTo(1f, tween(durationMillis = 1100, easing = LinearOutSlowInEasing))
        }
    }

    val tileShift by animateDpAsState(
        targetValue = when (companionKind) {
            HeroCompanion.APPLICATOR -> tileSize * 0.3f
            HeroCompanion.NFC, HeroCompanion.SCAN -> -tileSize * 0.22f
            HeroCompanion.PAIR -> -tileSize * 0.3f
        },
        animationSpec = ExpressiveMotion.defaultSpatial(),
        label = "heroShift",
    )
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
        if (companionKind == HeroCompanion.NFC || companionKind == HeroCompanion.PAIR) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val progress = signal.value
                val tile = tileSize.toPx()
                val sensor = center + Offset(tileShift.toPx(), 0f)
                if (companionKind == HeroCompanion.NFC) {
                    // One pulse of the field, ringing out from the sensor and gone.
                    if (progress < 1f) {
                        for (ring in 0..2) {
                            val local = (progress * 1.5f - ring * 0.25f).coerceIn(0f, 1f)
                            if (local > 0f && local < 1f) {
                                drawCircle(
                                    accent.copy(alpha = (1f - local) * 0.7f),
                                    radius = tile * 0.95f * (0.45f + 0.55f * local),
                                    center = sensor,
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }
                    }
                } else {
                    // Three arcs from the sensor towards the phone: they light in turn, then stay
                    // faint, so the picture still says "pairing" at rest.
                    val from = sensor + Offset(tile * 0.46f, 0f)
                    for (arc in 0..2) {
                        val lit = (progress * 3f - arc).coerceIn(0f, 1f)
                        val radius = tile * (0.1f + 0.09f * arc)
                        drawArc(
                            color = accent.copy(alpha = 0.45f + 0.55f * lit * (1f - progress)),
                            startAngle = -40f,
                            sweepAngle = 80f,
                            useCenter = false,
                            topLeft = from - Offset(radius, radius),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
        SensorArtTile(
            type = type,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            tileSize = tileSize,
            interactionSource = interactionSource,
            modifier = Modifier.offset(x = tileShift),
            replay = plays,
            round = true,
            lead = { lead },
        )
        Canvas(
            modifier = Modifier
                .requiredSize(tileSize * 1.05f)
                .graphicsLayer {
                    val moved = companion.value
                    val tile = tileSize.toPx()
                    val shift = tileShift.toPx()
                    when (companionKind) {
                        HeroCompanion.APPLICATOR -> {
                            // Starts seated on the sensor, ends standing beside it.
                            translationX = shift + (-tile * 0.62f) * moved
                            translationY = (-tile * 0.42f) * (1f - moved) + tile * 0.02f * moved
                            rotationZ = 14f * (1f - moved)
                        }
                        HeroCompanion.NFC, HeroCompanion.SCAN -> {
                            // Comes in from the top corner and hangs just over the sensor.
                            translationX = tile * 0.46f + tile * 0.5f * (1f - moved)
                            translationY = -tile * 0.1f - tile * 0.5f * (1f - moved)
                            rotationZ = (if (companionKind == HeroCompanion.SCAN) 10f else 16f) + 18f * (1f - moved)
                        }
                        HeroCompanion.PAIR -> {
                            // Rises into place a little way off, upright: it talks over the air.
                            translationX = tile * 0.84f
                            translationY = tile * 0.04f + tile * 0.35f * (1f - moved)
                            rotationZ = 6f
                        }
                    }
                    alpha = (moved * 2.5f).coerceIn(0f, 1f)
                },
        ) {
            when (companionKind) {
                HeroCompanion.APPLICATOR -> drawApplicator(type, tones)
                HeroCompanion.NFC, HeroCompanion.PAIR -> drawNfcPhone(tones)
                HeroCompanion.SCAN -> drawScanPhone(tones, accent, signal.value)
            }
        }
    }
}

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
                SensorHeroArt(type, tileSize = if (wide) 120.dp else 104.dp, pairLead = pairLead, companion = companion)
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
