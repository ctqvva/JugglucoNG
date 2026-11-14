package tk.glucodata.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import tk.glucodata.ui.isAppInDarkTheme
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The materials a sensor is drawn in. Sensors are white or grey plastic, so the tones are the
 * theme's neutrals, white at the lit end and the surface tones in shadow, with only a breath of
 * the theme's colour; that keeps them reading as real objects on any wallpaper palette while
 * needing no colour of their own. The few coloured parts (an applicator's cap, a clear base's
 * tint) take the theme's primary.
 */
@Immutable
internal data class SensorTones(
    val highlight: Color,
    val body: Color,
    val shade: Color,
    val wall: Color,
    val wallDeep: Color,
    val ink: Color,
    val detail: Color,
    val accent: Color,
    val cap: Color,
    val glint: Color,
    val shadow: Color,
    val tint: Color,
    /** How far the sensor is turned, in degrees clockwise; the light stays put. */
    val turn: Float = 0f,
) {
    /**
     * A move given in the picture's own directions (x right, y down, in drawing units on the
     * skin), as the turned sensor's drawing sees it: shadows and lit edges keep to the one light
     * however the sensor lies.
     */
    fun ground(x: Float, y: Float): Offset {
        val r = -turn * PI.toFloat() / 180f
        return Offset(x * cos(r) - y * sin(r), (x * sin(r) + y * cos(r)) / SQUASH)
    }

    /** Straight down the picture by [screen] units. */
    fun drop(screen: Float): Offset = ground(0f, screen)

    /** A light casing with a little of the theme's third colour in it, warm on most palettes. */
    fun warm(): SensorTones = copy(
        highlight = lerp(highlight, tint, 0.05f),
        body = lerp(lerp(body, wall, 0.18f), tint, 0.12f),
        shade = lerp(lerp(shade, wall, 0.2f), tint, 0.14f),
        wall = lerp(wall, tint, 0.16f),
        wallDeep = lerp(wallDeep, tint, 0.14f),
    )

    /**
     * The colour of what the sensor lies on, thrown back into its shaded side and its shadow, as
     * a white object on a coloured card picks it up: the tile's colour carries into the drawing
     * without the plastic itself being coloured.
     */
    fun bounce(surface: Color): SensorTones = copy(
        shade = lerp(shade, surface, 0.1f),
        wall = lerp(wall, surface, 0.2f),
        wallDeep = lerp(wallDeep, surface, 0.28f),
        shadow = lerp(ink, surface, 0.25f).copy(alpha = shadow.alpha),
    )

    /** A grey casing. */
    fun grey(): SensorTones = copy(
        highlight = lerp(highlight, wall, 0.3f),
        body = lerp(body, wall, 0.42f),
        shade = lerp(shade, wallDeep, 0.35f),
        wall = lerp(wall, wallDeep, 0.5f),
        wallDeep = lerp(wallDeep, ink, 0.22f),
        detail = lerp(detail, ink, 0.2f),
    )
}

@Composable
internal fun rememberSensorTones(): SensorTones {
    val scheme = MaterialTheme.colorScheme
    val dark = isAppInDarkTheme()
    return remember(scheme, dark) {
        if (dark) {
            val far = lerp(scheme.surfaceContainerHighest, scheme.primaryContainer, 0.25f)
            SensorTones(
                highlight = Color.White,
                body = lerp(Color.White, far, 0.06f),
                shade = lerp(Color.White, far, 0.24f),
                wall = lerp(Color.White, far, 0.46f),
                wallDeep = lerp(Color.White, far, 0.7f),
                ink = lerp(scheme.surfaceContainerLowest, scheme.primaryContainer, 0.35f),
                detail = lerp(Color.White, far, 0.58f),
                accent = scheme.primary,
                cap = lerp(scheme.primary, scheme.primaryContainer, 0.5f),
                glint = Color.White,
                shadow = scheme.surfaceContainerLowest.copy(alpha = 0.2f),
                tint = scheme.tertiary,
            )
        } else {
            val far = lerp(scheme.outline, scheme.primary, 0.15f)
            SensorTones(
                highlight = Color.White,
                body = lerp(Color.White, scheme.surfaceContainerHighest, 0.3f),
                shade = lerp(Color.White, scheme.surfaceContainerHighest, 0.85f),
                wall = lerp(scheme.surfaceContainerHighest, far, 0.3f),
                wallDeep = lerp(scheme.surfaceContainerHighest, far, 0.62f),
                ink = lerp(scheme.onSurface, scheme.onPrimaryContainer, 0.6f),
                detail = lerp(scheme.surfaceContainerHighest, far, 0.75f),
                accent = scheme.primary,
                cap = scheme.primary,
                glint = Color.White,
                shadow = scheme.onSurface.copy(alpha = 0.1f),
                tint = scheme.tertiary,
            )
        }
    }
}

/**
 * One sensor family, seen from above as its maker's top-down photos show it: the outline, the
 * real thickness against the real width, and the ports, windows, tabs and notches where they
 * really are, with no maker's name on them.
 * Drawn from code rather than shipped as bitmaps, so they follow the theme and stay sharp at any
 * size; the same drawing serves a list tile and a wizard hero.
 */
@Composable
internal fun SensorArt(type: SensorType, modifier: Modifier = Modifier) {
    val tones = rememberSensorTones()
    Canvas(modifier = modifier) { drawSensor(type, tones) }
}

/**
 * Every drawing lives around the origin of a 100-unit square, scaled to fit the canvas.
 * Keeps the object's lean and light whatever the canvas is.
 */
private inline fun DrawScope.inUnitSquare(block: DrawScope.() -> Unit) {
    val unit = min(size.width, size.height) / 100f
    translate(size.width / 2f, size.height / 2f) {
        scale(unit, unit, pivot = Offset.Zero, block = block)
    }
}

/**
 * The sensor alone, as it comes out of its applicator: never the adhesive patch it is worn on,
 * which is tape, not part of the device.
 */
internal fun DrawScope.drawSensor(type: SensorType, tones: SensorTones) {
    inUnitSquare {
        // The camera looks down at the skin a little from the front: the plan shortens by
        // SQUASH while the side walls show by WALL_SHOW, the same angle for both. The plan is
        // shortened along the sensor's own length before it is turned, so a turned rectangle
        // keeps square corners instead of shearing into a parallelogram at list size.
        rotate(type.tilt, pivot = Offset.Zero) {
            scale(1f, SQUASH, pivot = Offset.Zero) {
                val turned = tones.copy(turn = type.tilt)
                when (type) {
                    SensorType.LIBRE -> drawLibre(turned)
                    SensorType.DEXCOM -> drawDexcom(turned)
                    SensorType.SIBIONICS -> drawSibionics(turned)
                    SensorType.AIDEX -> drawAiDex(turned)
                    SensorType.OTTAI -> drawOttai(turned)
                    SensorType.ICANHEALTH -> drawICan(turned)
                    SensorType.ANYTIME -> drawAnytime(turned)
                    SensorType.ACCUCHEK -> drawAccuChek(turned)
                    SensorType.CARESENS_AIR -> drawCareSensAir(turned)
                    SensorType.MQ -> drawMq(turned)
                    SensorType.MEDTRUM -> drawMedtrum(turned)
                }
            }
        }
    }
}

/**
 * How each sensor lies, in degrees clockwise: a few degrees off square, the way a
 * sensor put on by hand sits, alternating so a list of them doesn't lean one way.
 */
internal val SensorType.tilt: Float
    get() = when (this) {
        SensorType.LIBRE -> -8f
        SensorType.DEXCOM -> 9f
        SensorType.SIBIONICS -> 11f
        SensorType.AIDEX -> -10f
        SensorType.OTTAI -> 8f
        SensorType.ICANHEALTH -> -7f
        SensorType.ANYTIME -> -11f
        SensorType.ACCUCHEK -> 9f
        SensorType.CARESENS_AIR -> -9f
        SensorType.MQ -> -8f
        SensorType.MEDTRUM -> 10f
    }

/** The applicator a hero shows beside its sensor, for the families that have one drawn. */
internal fun DrawScope.drawApplicator(type: SensorType, tones: SensorTones) = inUnitSquare {
    when (type) {
        SensorType.ACCUCHEK -> drawAccuChekApplicator(tones)
        else -> drawCareSensApplicatorBody(tones)
    }
}

/** A phone held to a sensor, for the NFC hero. */
internal fun DrawScope.drawNfcPhone(tones: SensorTones) = inUnitSquare {
    drawPhoneBody(tones)
}

// ---- The view ----
//
// Every sensor is seen from straight above, as the makers' own top-down shots show them, so its
// outline and the places of its ports and windows can be taken from a photo and checked against
// one. The camera leans 25 degrees towards the viewer, so the plan shortens to cos 25 and the
// side wall shows sin 25 of the sensor's thickness below its bottom edge: one angle for both, so
// a disc reads as a coin lying on the skin rather than a cut-out on a plinth. Light comes from
// the upper left, and each part casts its shadow down and to the right as far as it stands high.
// Sizes are in drawing units, the longest side of each sensor near 72, and each thickness is that
// sensor's real thickness against its real width.

/** How much of a sensor's thickness shows as wall below its bottom edge: sin 25°. */
private const val WALL_SHOW = 0.42f

/** How much the plan shortens from front to back: cos 25°. */
private const val SQUASH = 0.906f

/** How far a part's shadow falls on the skin for each unit it stands high. */
private const val SHADOW_REACH = 0.32f

/** An outline in top view, shrunk evenly by its argument; insets give the rounded edge. */
private typealias Outline = (inset: Float) -> Path

private fun circle(radius: Float, at: Offset = Offset.Zero): Outline = { inset ->
    val r = max(radius - inset, 0.1f)
    Path().apply { addOval(Rect(at.x - r, at.y - r, at.x + r, at.y + r)) }
}

private fun roundRect(width: Float, height: Float, radius: Float, at: Offset = Offset.Zero): Outline = { inset ->
    val w = max(width / 2f - inset, 0.1f)
    val h = max(height / 2f - inset, 0.1f)
    Path().apply {
        addRoundRect(
            RoundRect(
                Rect(at.x - w, at.y - h, at.x + w, at.y + h),
                CornerRadius(min(max(radius - inset, 0f), min(w, h))),
            ),
        )
    }
}

private fun stadium(width: Float, height: Float, at: Offset = Offset.Zero): Outline =
    roundRect(width, height, min(width, height) / 2f, at)

/** An egg standing on its broad end: [taper] narrows the top and widens the bottom. */
private fun egg(a: Float, b: Float, taper: Float): Outline = { inset ->
    val ra = max(a - inset, 0.1f)
    val rb = max(b - inset, 0.1f)
    Path().apply {
        val steps = 144
        for (step in 0 until steps) {
            val t = step / steps.toFloat() * 2f * PI.toFloat()
            val x = ra * sin(t) * (1f - taper * cos(t))
            val y = -rb * cos(t)
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** A closed outline from a radius per angle (radians, 0 = +x, clockwise on screen). */
private fun polar(steps: Int = 240, radius: (Float) -> Float): Outline = { inset ->
    Path().apply {
        for (step in 0 until steps) {
            val angle = step / steps.toFloat() * 2f * PI.toFloat()
            val r = max(radius(angle) - inset, 0.1f)
            val x = cos(angle) * r
            val y = sin(angle) * r
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/** A square-sided cut of [depth] centred on [at] radians, about [halfWidth] radians each side. */
private fun cut(angle: Float, at: Float, depth: Float, halfWidth: Float): Float {
    var delta = angle - at
    while (delta > PI) delta -= 2f * PI.toFloat()
    while (delta < -PI) delta += 2f * PI.toFloat()
    val x = delta / halfWidth
    return depth * exp(-(x * x * x * x * x * x))
}

/** A rounded rectangle whose left corners round by [left] and right corners by [right]. */
private fun roundEnds(width: Float, height: Float, left: Float, right: Float): Outline = { inset ->
    val w = max(width / 2f - inset, 0.1f)
    val h = max(height / 2f - inset, 0.1f)
    val l = CornerRadius(min(max(left - inset, 0f), min(w, h)))
    val r = CornerRadius(min(max(right - inset, 0f), min(w, h)))
    Path().apply { addRoundRect(RoundRect(Rect(-w, -h, w, h), l, r, r, l)) }
}

/** A regular octagon, flat side up, [apothem] from centre to side, its corners rounded by [round]. */
private fun octagon(apothem: Float, round: Float): Outline = { inset ->
    val a = max(apothem - inset, 0.1f)
    val corner = a / cos(PI.toFloat() / 8f)
    val cut = min(round, corner * 0.38f)
    Path().apply {
        for (side in 0 until 8) {
            val angle = (side * 45f - 112.5f) * PI.toFloat() / 180f
            val vertex = Offset(cos(angle) * corner, sin(angle) * corner)
            val prev = (side * 45f - 157.5f) * PI.toFloat() / 180f
            val next = (side * 45f - 67.5f) * PI.toFloat() / 180f
            val before = vertex + (Offset(cos(prev) * corner, sin(prev) * corner) - vertex) * (cut / (2f * corner * sin(PI.toFloat() / 8f)))
            val after = vertex + (Offset(cos(next) * corner, sin(next) * corner) - vertex) * (cut / (2f * corner * sin(PI.toFloat() / 8f)))
            if (side == 0) moveTo(before.x, before.y) else lineTo(before.x, before.y)
            quadraticTo(vertex.x, vertex.y, after.x, after.y)
        }
        close()
    }
}

/** Two circles joined by their common tangents: a teardrop when the radii differ. */
private fun capsule(from: Offset, fromRadius: Float, to: Offset, toRadius: Float) = Path().apply {
    val d = hypot(to.x - from.x, to.y - from.y)
    val phi = atan2(to.y - from.y, to.x - from.x)
    val alpha = acos(((fromRadius - toRadius) / d).coerceIn(-1f, 1f))
    val deg = 180f / PI.toFloat()
    arcTo(
        Rect(from.x - fromRadius, from.y - fromRadius, from.x + fromRadius, from.y + fromRadius),
        (phi + alpha) * deg,
        (2f * PI.toFloat() - 2f * alpha) * deg,
        forceMoveTo = true,
    )
    arcTo(
        Rect(to.x - toRadius, to.y - toRadius, to.x + toRadius, to.y + toRadius),
        (phi - alpha) * deg,
        2f * alpha * deg,
        forceMoveTo = false,
    )
    close()
}


/** A hole into the casing: dark, its far inner wall lit, a bright lip on the near edge. */
private fun DrawScope.hole(tones: SensorTones, center: Offset, radius: Float) {
    drawCircle(tones.ink, radius, center)
    drawArc(
        color = tones.detail,
        startAngle = 200f - tones.turn,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = center - Offset(radius * 0.72f, radius * 0.72f),
        size = Size(radius * 1.44f, radius * 1.44f),
        style = Stroke(width = radius * 0.4f, cap = StrokeCap.Round),
    )
    drawArc(
        color = tones.glint.copy(alpha = 0.8f),
        startAngle = 30f - tones.turn,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = center - Offset(radius * 1.1f, radius * 1.1f),
        size = Size(radius * 2.2f, radius * 2.2f),
        style = Stroke(width = radius * 0.22f, cap = StrokeCap.Round),
    )
}

/** A parting line or groove: a dark cut with the light catching its lower edge. */
private fun DrawScope.groove(tones: SensorTones, path: Path, width: Float = 0.9f, depth: Float = 0.8f) {
    val lip = tones.ground(0.25f, 0.5f)
    translate(lip.x, lip.y) {
        drawPath(path, tones.glint.copy(alpha = 0.55f), style = Stroke(width = width, cap = StrokeCap.Round))
    }
    drawPath(path, tones.detail.copy(alpha = depth), style = Stroke(width = width, cap = StrokeCap.Round))
}

/** Centres an object [thickness] thick in the square, so tall and flat sensors sit alike. */
private inline fun DrawScope.seated(tones: SensorTones, thickness: Float, block: DrawScope.() -> Unit) {
    val up = tones.drop(-thickness * WALL_SHOW / 2f)
    translate(up.x, up.y, block)
}

/** Far enough out to hold every drawing, for cutting a band along the inside of a shape. */
private val EVERYWHERE = Rect(-200f, -200f, 200f, 200f)

/** [path] moved by [by]. */
private fun Path.moved(by: Offset): Path = Path().apply { addPath(this@moved, by) }

/** Everything except [path] moved by [by]: clipped to [path], a band along its far side. */
private fun outside(path: Path, by: Offset): Path = Path().apply {
    op(Path().apply { addRect(EVERYWHERE) }, path.moved(by), PathOperation.Difference)
}

/**
 * A shallow recess in the face, [depth] deep: its floor a shade down, the rim's shadow lying
 * along the side nearer the light and the far inner wall catching it.
 */
private fun DrawScope.recess(tones: SensorTones, path: Path, depth: Float = 0.8f, floor: Color? = null) {
    drawPath(path, floor ?: lerp(tones.body, tones.shade, 0.35f))
    clipPath(path) {
        drawPath(outside(path, tones.ground(depth, depth)), tones.ink.copy(alpha = 0.2f))
        drawPath(outside(path, tones.ground(-depth * 0.6f, -depth * 0.6f)), tones.highlight.copy(alpha = 0.85f))
    }
}

/** A dark opening through the face: black inside, its far inner wall just lit. */
private fun DrawScope.pit(tones: SensorTones, path: Path, depth: Float = 0.7f) {
    drawPath(path, tones.ink)
    clipPath(path) {
        drawPath(outside(path, tones.ground(-depth, -depth)), lerp(tones.ink, tones.detail, 0.55f))
    }
}

/**
 * A part standing [height] proud of the face: its shadow on the face, its side showing under
 * the near edge, its top lit along the edge towards the light and shaded along the other.
 */
private fun DrawScope.raised(tones: SensorTones, path: Path, height: Float, top: Color? = null) {
    val cast = tones.ground(0.55f, 1f) * (height * SHADOW_REACH * 2f) + tones.drop(height * WALL_SHOW)
    drawPath(path.moved(cast), tones.shadow.copy(alpha = tones.shadow.alpha * 2.5f))
    drawPath(path.moved(tones.drop(height * WALL_SHOW)), tones.wall)
    drawPath(path, top ?: tones.body)
    val edge = height * 0.5f + 0.3f
    clipPath(path) {
        drawPath(outside(path, tones.ground(edge, edge)), tones.highlight.copy(alpha = 0.9f))
        drawPath(outside(path, tones.ground(-edge, -edge)), tones.shade.copy(alpha = 0.9f))
    }
}

/**
 * One part of a sensor, built up from the skin as it really stands: its shadow, as long as the
 * part stands high; a straight side; then the top edge rounding over across [bevel] into the
 * face, flat or [dome]d. Each slice of the edge is shaded by how far it has turned towards the
 * light, so the edge rolls the way a moulded edge does instead of a lid sitting on a can. [base]
 * lifts the part onto the one below; [band] darkens the lowest share of the side, for a casing
 * on a darker foot, and [seam] draws the parting line of a two-piece casing at that share of
 * its height. A part on the skin casts the shadow of everything it carries, [stands] high in
 * all. [face] draws on the top.
 */
private fun DrawScope.part(
    tones: SensorTones,
    outline: Outline,
    thickness: Float,
    bevel: Float,
    base: Float = 0f,
    dome: Boolean = false,
    band: Float = 0f,
    stands: Float = thickness,
    shadow: Boolean = base == 0f,
    seam: Float = 0f,
    face: DrawScope.() -> Unit = {},
) {
    val top = outline(0f)
    val bounds = top.getBounds()
    val reach = max(bounds.width, bounds.height) / 2f
    val lift = tones.drop(-base * WALL_SHOW)
    // The light's way across the part, upper left to lower right of the picture.
    val lit = bounds.center + tones.ground(-reach * 0.75f, -reach * 0.75f)
    val dim = bounds.center + tones.ground(reach * 0.75f, reach * 0.75f)

    if (shadow) {
        // The shadow, sharp near the part and fading as it reaches out: stacked passes stand in
        // for a blur, which a Canvas path cannot have.
        val far = tones.ground(0.55f, 1f) * (stands * SHADOW_REACH) + tones.drop(stands * WALL_SHOW)
        for (pass in 1..5) {
            val at = far * (pass / 5f)
            translate(at.x, at.y) { drawPath(top, tones.shadow) }
        }
    }
    translate(lift.x, lift.y) {
        val side = Brush.linearGradient(
            0f to tones.shade,
            0.45f to tones.wall,
            1f to tones.wallDeep,
            start = bounds.center + tones.ground(-reach, 0f),
            end = bounds.center + tones.ground(reach, 0f),
        )
        val foot = Brush.linearGradient(
            0f to lerp(tones.wall, tones.ink, 0.35f),
            1f to lerp(tones.wallDeep, tones.ink, 0.55f),
            start = bounds.center + tones.ground(-reach, 0f),
            end = bounds.center + tones.ground(reach, 0f),
        )
        // The edge's colours along the light's way, from the upright side through the turn of
        // the edge to the face: one opaque fill per slice, so no seams show between them.
        val upright = listOf(tones.shade, tones.wall, tones.wallDeep)
        val turning = listOf(tones.highlight, lerp(tones.body, tones.shade, 0.5f), tones.wall)
        val facing = listOf(tones.highlight, tones.body, lerp(tones.body, tones.shade, 0.5f))
        fun edge(up: Float): Brush {
            val from = if (up < 0.5f) upright else turning
            val to = if (up < 0.5f) turning else facing
            val t = if (up < 0.5f) up * 2f else up * 2f - 1f
            return Brush.linearGradient(
                0f to lerp(from[0], to[0], t),
                0.5f to lerp(from[1], to[1], t),
                1f to lerp(from[2], to[2], t),
                start = lit,
                end = dim,
            )
        }
        val faceBrush = if (dome) {
            Brush.radialGradient(
                0f to tones.highlight,
                0.4f to tones.highlight,
                0.8f to tones.body,
                1f to lerp(tones.body, tones.shade, 0.6f),
                center = bounds.center + tones.ground(-reach * 0.28f, -reach * 0.32f),
                radius = reach * 1.2f,
            )
        } else {
            Brush.linearGradient(
                0f to tones.highlight,
                0.55f to tones.body,
                1f to lerp(tones.body, tones.shade, 0.5f),
                start = lit,
                end = dim,
            )
        }

        // The edge's vertical reach: a round as tall as it is wide, unless the part is thinner.
        val round = min(bevel, thickness * 0.75f)
        val straight = thickness - round
        // A small round at the foot, so the side meets the skin softly.
        val foot0 = min(thickness * 0.15f, 1.2f)
        val wallSteps = max(ceil(straight * WALL_SHOW / 0.3f).toInt(), 1)
        for (step in 0 until wallSteps) {
            val z = straight * step / wallSteps
            val tuck = if (z < foot0) foot0 * (1f - sqrt(1f - ((foot0 - z) / foot0).let { it * it })) else 0f
            val at = tones.drop((thickness - z) * WALL_SHOW)
            val dark = band > 0f && z < thickness * band
            translate(at.x, at.y) {
                drawPath(outline(tuck), if (dark) foot else side)
                if (seam > 0f && step == (wallSteps * seam / (1f - round / thickness)).toInt().coerceIn(0, wallSteps - 1)) {
                    drawPath(outline(tuck), tones.wallDeep.copy(alpha = 0.7f), style = Stroke(width = 0.6f))
                }
            }
        }
        val edgeSteps = max(ceil(bevel / 0.35f).toInt(), 6)
        // What shows of the top from above, the face and the turn of its edge: details stay on it.
        val seen = outline(bevel)
        for (step in 0 until edgeSteps) {
            val turn = (PI.toFloat() / 2f) * step / edgeSteps
            val z = straight + round * sin(turn)
            val inset = bevel * (1f - cos(turn))
            val at = tones.drop((thickness - z) * WALL_SHOW)
            val slice = outline(inset)
            translate(at.x, at.y) { drawPath(slice, edge(sin(turn))) }
            if (step % 3 == 0) seen.op(seen, slice.moved(at), PathOperation.Union)
        }
        drawPath(outline(bevel), faceBrush)
        seen.op(seen, top, PathOperation.Intersect)
        clipPath(seen, block = face)
    }
}

/**
 * Clear plastic: the skin and tile show through, its edges catch the light, the edge towards the
 * light bright and the far one dark. [thickness] deep, standing [base] above the skin.
 */
private fun DrawScope.glass(
    tones: SensorTones,
    outline: Outline,
    thickness: Float,
    base: Float = 0f,
    stands: Float = thickness,
) {
    val top = outline(0f)
    val bounds = top.getBounds()
    val reach = max(bounds.width, bounds.height) / 2f
    val lit = bounds.center + tones.ground(-reach * 0.75f, -reach * 0.75f)
    val dim = bounds.center + tones.ground(reach * 0.75f, reach * 0.75f)
    if (base == 0f) {
        val far = tones.ground(0.55f, 1f) * (stands * SHADOW_REACH) + tones.drop(stands * WALL_SHOW)
        for (pass in 1..5) {
            val at = far * (pass / 5f)
            translate(at.x, at.y) { drawPath(top, tones.shadow.copy(alpha = tones.shadow.alpha * 0.7f)) }
        }
    }
    val lift = tones.drop(-base * WALL_SHOW)
    translate(lift.x, lift.y) {
        val floor = tones.drop(thickness * WALL_SHOW)
        val body = Path().apply {
            op(top.moved(floor), top, PathOperation.Union)
        }
        val steps = max(ceil(thickness * WALL_SHOW / 0.5f).toInt(), 1)
        for (step in 1..steps) {
            val at = tones.drop(thickness * WALL_SHOW * step / steps)
            body.op(body, top.moved(at), PathOperation.Union)
        }
        val tint = lerp(tones.highlight, tones.accent, 0.35f)
        drawPath(body, tint.copy(alpha = 0.22f))
        drawPath(top.moved(floor), tones.wallDeep.copy(alpha = 0.5f), style = Stroke(width = 0.5f))
        drawPath(top, tint.copy(alpha = 0.18f))
        drawPath(
            top,
            Brush.linearGradient(
                0f to tones.glint,
                0.5f to tones.glint.copy(alpha = 0.35f),
                1f to tones.wallDeep.copy(alpha = 0.6f),
                start = lit,
                end = dim,
            ),
            style = Stroke(width = 0.8f),
        )
        drawPath(outline(1.4f), tones.glint.copy(alpha = 0.3f), style = Stroke(width = 0.5f))
    }
}

// ---- The sensors ----

/**
 * Libre 3: a 21 mm disc 2.9 mm thick. Its one feature, a third of the way up and just left of
 * centre, is a clear round window holding the keyed filament slot, with a small nub at its right.
 */
private fun DrawScope.drawLibre(tones: SensorTones) = seated(tones, 10f) {
    part(tones, circle(35f), thickness = 10f, bevel = 2.4f) {
        val window = Offset(-2.8f, -13f)
        drawCircle(lerp(tones.body, tones.shade, 0.35f), 9.5f, window)
        drawCircle(tones.wall.copy(alpha = 0.8f), 9.5f, window, style = Stroke(width = 0.9f))
        drawCircle(tones.wall.copy(alpha = 0.45f), 8.4f, window, style = Stroke(width = 0.5f))
        drawArc(
            color = tones.glint,
            startAngle = 190f - tones.turn,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = window - Offset(8.9f, 8.9f),
            size = Size(17.8f, 17.8f),
            style = Stroke(width = 0.7f, cap = StrokeCap.Round),
        )
        val slot = stadium(4.2f, 11.1f, window + Offset(-0.3f, 0f))(0f)
        drawPath(slot, tones.ink)
        drawPath(slot, tones.wall, style = Stroke(width = 0.6f))
        drawRect(tones.ink, topLeft = window + Offset(1.6f, -0.9f), size = Size(0.8f, 1.6f))
        drawCircle(lerp(tones.shade, tones.wall, 0.4f), 1.2f, window + Offset(7.6f, 0.3f))
    }
}

/**
 * G7 radius every 5 degrees from +x, clockwise on screen, fitted to a top-down photo of the
 * sensor: an oval that is fuller on the right, wider than tall, the side cuts left out.
 */
private val G7_RADII = floatArrayOf(
    36.72f, 36.70f, 36.68f, 36.63f, 36.53f, 36.37f, 36.20f, 36.02f,
    35.85f, 35.65f, 35.39f, 35.03f, 34.60f, 34.16f, 33.73f, 33.34f,
    32.95f, 32.54f, 32.10f, 31.69f, 31.44f, 31.42f, 31.68f, 32.16f,
    32.73f, 33.29f, 33.77f, 34.21f, 34.66f, 35.17f, 35.72f, 36.22f,
    36.59f, 36.77f, 36.83f, 36.85f, 36.93f, 37.07f, 37.19f, 37.16f,
    36.90f, 36.41f, 35.78f, 35.15f, 34.59f, 34.09f, 33.60f, 33.04f,
    32.42f, 31.81f, 31.36f, 31.19f, 31.31f, 31.66f, 32.11f, 32.55f,
    32.94f, 33.29f, 33.65f, 34.06f, 34.50f, 34.91f, 35.24f, 35.50f,
    35.72f, 35.94f, 36.18f, 36.42f, 36.62f, 36.74f, 36.78f, 36.76f,
)

private fun g7Radius(angle: Float): Float {
    val turn = (angle / (2f * PI.toFloat())).mod(1f) * G7_RADII.size
    val index = turn.toInt()
    val next = (index + 1) % G7_RADII.size
    val t = turn - index
    return G7_RADII[index] + (G7_RADII[next] - G7_RADII[index]) * t
}

/**
 * G7: 27.5 x 24 mm, 4.6 thick, its plan fitted to a top-down photo, a square cut in the middle of
 * each side. The top is a raised plateau set up and to the left, a broad slope around it widest
 * at the right and the foot. The round port sits left of centre on the long axis, the pinhole
 * below it.
 */
private fun DrawScope.drawDexcom(tones: SensorTones) = seated(tones, 12.4f) {
    val outline = polar { angle ->
        g7Radius(angle) - cut(angle, 0f, 2.3f, 0.085f) - cut(angle, PI.toFloat(), 2.3f, 0.085f)
    }
    val grey = tones.warm()
    part(grey, outline, thickness = 12.4f, bevel = 1.2f) {
        val plateau = Path().apply {
            val steps = 144
            for (step in 0 until steps) {
                val angle = step / steps.toFloat() * 2f * PI.toFloat()
                val r = g7Radius(angle)
                val x = cos(angle) * r * 0.92f - 1.9f
                val y = sin(angle) * r * 0.85f
                if (step == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        val bounds = plateau.getBounds()
        // The slope round the plateau: lit at the upper left, falling into shade at the lower right.
        drawPath(
            outline(0f),
            Brush.linearGradient(
                0f to grey.highlight,
                0.5f to lerp(grey.body, grey.wall, 0.45f),
                1f to grey.wall,
                start = tones.ground(-30f, -30f),
                end = tones.ground(30f, 30f),
            ),
        )
        drawPath(
            plateau,
            Brush.linearGradient(
                0f to grey.highlight,
                0.6f to grey.body,
                1f to lerp(grey.body, grey.shade, 0.4f),
                start = bounds.center + tones.ground(-26f, -26f),
                end = bounds.center + tones.ground(26f, 26f),
            ),
        )
        drawPath(plateau, grey.wall.copy(alpha = 0.35f), style = Stroke(width = 0.5f))
        hole(tones, Offset(-16f, -0.4f), 2.9f)
        drawRoundRect(
            tones.ink,
            topLeft = Offset(-15.7f, 9.3f),
            size = Size(1.6f, 3f),
            cornerRadius = CornerRadius(0.8f),
        )
    }
}

/**
 * The Sibionics pair, the GS1 behind the Sibionics 2 at their published sizes against each
 * other: GS1 33.5 x 20.0 x 5.3 mm, Sibionics 2 (GS1ECO, the reusable GS1-P2 transmitter) 35 x
 * 24.4 x 5.9 mm. GS1, traced from a top-down photo: a pill whose ends round short of full
 * circles; a round hole a seventh of its length off centre holds a white keyed plug. Sibionics 2:
 * a flat oblong with a white cover over a clear base that shows a clip at each short end.
 */
private fun DrawScope.drawSibionics(tones: SensorTones) = seated(tones, 8f) {
    turned(tones, Offset(-13f, -12f), -22f, 33.5f * PAIR_SCALE / 72f) { tones ->
        part(tones, roundRect(72f, 43f, 18.5f), thickness = 11.4f, bevel = 5.5f) {
            keyedPort(tones, Offset(-10f, 0f), 5.6f)
        }
    }
    turned(tones, Offset(11f, 12f), 5f, 35f * PAIR_SCALE / 72f) { tones ->
        val cover = roundRect(72f, 50.2f, 13f)
        val frame: Outline = { inset ->
            Path().apply {
                op(roundRect(72.6f, 50.8f, 13.2f)(inset), roundRect(75f, 16f, 2.5f)(inset), PathOperation.Union)
            }
        }
        glass(tones, frame, thickness = 4.6f, stands = 12.1f)
        part(tones, cover, thickness = 7.5f, bevel = 4.5f, base = 4.6f)
    }
}

/** Millimetres to drawing units in a pair: both fit the tile at their true sizes against each other. */
private const val PAIR_SCALE = 1.58f

/** A round hole holding a white plug, its key cut in it: the filament port of a Sibionics or i6. */
private fun DrawScope.keyedPort(tones: SensorTones, at: Offset, radius: Float) {
    pit(tones, Path().apply { addOval(Rect(at.x - radius, at.y - radius, at.x + radius, at.y + radius)) })
    val plug = radius * 0.76f
    drawCircle(
        Brush.radialGradient(
            0f to tones.highlight,
            1f to tones.shade,
            center = at + tones.ground(-plug * 0.3f, -plug * 0.3f),
            radius = plug * 1.3f,
        ),
        plug,
        at,
    )
    val k = plug / 4.2f
    val key = Path().apply {
        moveTo(at.x + 1.6f * k, at.y - 2.2f * k)
        lineTo(at.x - 1.8f * k, at.y - 2.2f * k)
        lineTo(at.x - 1.8f * k, at.y + 2.2f * k)
        lineTo(at.x + 1.6f * k, at.y + 2.2f * k)
        moveTo(at.x + 0.2f * k, at.y - 0.5f * k)
        lineTo(at.x + 1.6f * k, at.y - 0.5f * k)
        lineTo(at.x + 1.6f * k, at.y + 0.7f * k)
    }
    drawPath(key, tones.ink, style = Stroke(width = 1.1f * k))
    for (notch in listOf(35f, 125f, 215f, 305f)) {
        drawArc(
            color = tones.ink,
            startAngle = notch,
            sweepAngle = 24f,
            useCenter = false,
            topLeft = at - Offset(plug - 0.5f * k, plug - 0.5f * k),
            size = Size((plug - 0.5f * k) * 2f, (plug - 0.5f * k) * 2f),
            style = Stroke(width = 1.3f * k),
        )
    }
}

/**
 * Draws one of a pair at [at], turned [by] degrees further and drawn at [size] of its own
 * measure, the light held where it was.
 */
private inline fun DrawScope.turned(
    tones: SensorTones,
    at: Offset,
    by: Float,
    size: Float,
    block: DrawScope.(SensorTones) -> Unit,
) {
    // Undo the camera's shortening, turn, and shorten again, so this one doesn't shear either.
    translate(at.x, at.y) {
        scale(size, size / SQUASH, pivot = Offset.Zero) {
            rotate(by, pivot = Offset.Zero) {
                scale(1f, SQUASH, pivot = Offset.Zero) { block(tones.copy(turn = tones.turn + by)) }
            }
        }
    }
}

/**
 * AiDEX X and LinX: 22 mm across and 4.22 mm thick, a plain round disc, its edge well rounded,
 * a ringed port at the upper right holding the upright slot of the filament.
 */
private fun DrawScope.drawAiDex(tones: SensorTones) = seated(tones, 13.8f) {
    part(tones, circle(36f), thickness = 13.8f, bevel = 5.5f) {
        val port = Offset(10.8f, -14.1f)
        drawCircle(lerp(tones.body, tones.shade, 0.3f), 8.3f, port)
        drawCircle(tones.wallDeep, 8.3f, port, style = Stroke(width = 0.9f))
        drawArc(
            color = tones.wall,
            startAngle = -60f - tones.turn,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = port - Offset(7f, 7f),
            size = Size(14f, 14f),
            style = Stroke(width = 0.6f),
        )
        val slot = stadium(5f, 10.5f, port + Offset(-0.6f, 0f))(0f)
        drawPath(slot, lerp(tones.ink, tones.wall, 0.35f))
        drawPath(slot, tones.ink, style = Stroke(width = 0.5f))
    }
}

/**
 * M8: 24 mm across and 3.5 mm thick, in light grey plastic, its edge rounded over. Two fifths of
 * the way out, a raised white frame lying along the radius holds a dark oblong opening.
 */
private fun DrawScope.drawOttai(tones: SensorTones) = seated(tones, 10.5f) {
    val grey = tones.grey()
    part(grey, circle(36f), thickness = 10.5f, bevel = 5f) {
        val toward = -20f
        val button = Offset(16f * cos(toward * PI.toFloat() / 180f), 16f * sin(toward * PI.toFloat() / 180f))
        rotate(toward, pivot = button) {
            raised(grey, roundRect(17f, 12.5f, 5f, button)(0f), 1.2f, top = lerp(tones.highlight, tones.body, 0.3f))
            pit(grey, roundRect(9f, 5.8f, 2.6f, button)(0f))
        }
    }
}

/**
 * The iCan pair, the i3 behind the i6 at their published sizes against each other: i3 32 mm
 * across and 5.7 mm thick, i6 23.3 mm across and 3.95 mm thick. i3, measured from a top-down
 * photo: a teardrop groove whose round end lies near the rim holding a recessed square window,
 * its tip near the centre with three small contacts towards it; small notches break the rim.
 * i6: a rounded octagon on a darker foot, a raised grey ring round its keyed port.
 */
private fun DrawScope.drawICan(tones: SensorTones) = seated(tones, 9f) {
    turned(tones, Offset(-10f, -9f), 160f, 32f * PAIR_SCALE / 73f) { tones ->
        part(tones, circle(36.5f), thickness = 13f, bevel = 4f) {
            groove(tones, capsule(Offset(-3f, 19f), 11f, Offset(-3f, 2f), 6.5f), width = 1.1f)
            recess(tones, roundRect(5.6f, 5.6f, 0.8f, Offset(-3f, 23f))(0f), depth = 0.9f)
            pit(tones, roundRect(3f, 2.2f, 0.5f, Offset(-7f, 8f))(0f))
            pit(tones, roundRect(3f, 2.2f, 0.5f, Offset(1f, 8f))(0f))
            recess(tones, roundRect(2.2f, 2.2f, 0.4f, Offset(-3f, 1.6f))(0f), depth = 0.5f)
            for (notch in listOf(235f, 305f, 150f, 20f)) {
                rotate(notch, pivot = Offset.Zero) {
                    recess(tones, roundRect(1.6f, 4.4f, 0.6f, Offset(35.6f, 0f))(0f), depth = 0.5f)
                }
            }
        }
    }
    turned(tones, Offset(16f, 15f), -10f, 23.3f * PAIR_SCALE / 73.6f) { tones ->
        part(tones, octagon(34f, 7f), thickness = 12.5f, bevel = 4f, band = 0.3f) {
            val port = Offset(-3f, -10f)
            val ring = Path().apply { addOval(Rect(port.x - 7.4f, port.y - 7.4f, port.x + 7.4f, port.y + 7.4f)) }
            raised(tones, ring, 0.8f, top = lerp(tones.wall, tones.body, 0.35f))
            keyedPort(tones, port, 5f)
        }
    }
}

/**
 * The Anytime pair, the CT4 behind the 5 Pro. The 5 Pro is 23 mm across and 4.4 mm thick; the
 * CT4, which has no published size, is drawn from photos: an egg 1.2 as long as it is wide and
 * about 7 mm thick, gently domed, its edge rounded over. 5 Pro: a white disc set in a clear ring
 * a little lower than it, a clear band curving across its upper part, above it the port between
 * two short slots and a dot by the rim.
 */
private fun DrawScope.drawAnytime(tones: SensorTones) = seated(tones, 10f) {
    turned(tones, Offset(-10f, -9f), -62f, 36f * PAIR_SCALE / 72f) { tones ->
        part(tones, egg(30.5f, 36f, 0.22f), thickness = 14f, bevel = 4.5f, dome = true, seam = 0.4f)
    }
    turned(tones, Offset(16f, 15f), 8f, 23f * PAIR_SCALE / 72f) { tones ->
        glass(tones, circle(36f), thickness = 11.5f, stands = 13.8f)
        part(tones, circle(32.4f), thickness = 13.8f, bevel = 4.5f, shadow = false) {
            val band = Path().apply {
                op(
                    Path().apply { addOval(Rect(-34.3f, -76.5f, 34.3f, -7.5f)) },
                    Path().apply { addOval(Rect(-31f, -73.5f, 31f, -10.8f)) },
                    PathOperation.Difference,
                )
            }
            recess(tones, band, depth = 0.6f, floor = lerp(tones.highlight, tones.accent, 0.2f).copy(alpha = 0.55f))
            pit(tones, Path().apply { addOval(Rect(-1.2f, -21.2f, 1.2f, -18.8f)) })
            recess(tones, Path().apply { addOval(Rect(-3f, -23f, 3f, -17f)) }, depth = 0.5f)
            pit(tones, Path().apply { addOval(Rect(-1f, -21f, 1f, -19f)) })
            recess(tones, stadium(2.2f, 4.6f, Offset(-9f, -20f))(0f), depth = 0.5f)
            recess(tones, stadium(2.2f, 4.6f, Offset(9f, -20f))(0f), depth = 0.5f)
            recess(tones, Path().apply { addOval(Rect(-1.2f, -30.2f, 1.2f, -27.8f)) }, depth = 0.4f)
        }
    }
}

/**
 * SmartGuide sensor: 33.3 mm across and about 5.4 mm thick, measured from the maker's photos. A
 * cool light grey disc with one short flat side, its edge rounded over, on a clear base that shows
 * round its foot. The filament port sits at the centre, a dark well under a clear lens in a grey
 * collar; a small dimple sits two thirds of the way towards the rim opposite the flat.
 */
private fun DrawScope.drawAccuChek(tones: SensorTones) = seated(tones, 11.7f) {
    // A circle with one short flat side, the corners where they meet rounded off.
    val disc: (Float) -> Outline = { grow ->
        polar { angle ->
            val round = 36f + grow
            val flat = (28.8f + grow * 0.4f) / max(sin(angle), 0.02f)
            val soft = 1.6f
            -soft * ln(exp(-round / soft) + exp(-flat / soft))
        }
    }
    val cool = tones.grey().let {
        it.copy(
            highlight = lerp(tones.highlight, tones.accent, 0.04f),
            body = lerp(it.body, tones.accent, 0.08f),
            shade = lerp(it.shade, tones.accent, 0.1f),
        )
    }
    glass(tones, disc(0.8f), thickness = 3.2f, stands = 11.7f)
    part(cool, disc(0f), thickness = 8.5f, bevel = 5f, base = 3.2f) {
        val port = Offset(0f, 0f)
        raised(
            cool,
            Path().apply { addOval(Rect(-4.8f, -4.8f, 4.8f, 4.8f)) },
            0.6f,
            top = lerp(cool.wall, cool.body, 0.3f),
        )
        // Under the clear lens, the dark well with the light metal key of the filament in it.
        drawCircle(lerp(tones.ink, cool.wall, 0.45f), 3.4f, port)
        drawRoundRect(lerp(tones.highlight, cool.wall, 0.25f), port - Offset(1.6f, 1.1f), Size(3.2f, 2.2f), CornerRadius(0.5f))
        pit(tones, roundRect(1.8f, 0.8f, 0.3f, port)(0f), depth = 0.3f)
        drawArc(
            color = tones.glint.copy(alpha = 0.7f),
            startAngle = 200f - tones.turn,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = port - Offset(2.6f, 2.6f),
            size = Size(5.2f, 5.2f),
            style = Stroke(width = 0.6f, cap = StrokeCap.Round),
        )
        recess(cool, Path().apply { addOval(Rect(-1.9f, -25.9f, 1.9f, -22.1f)) }, depth = 0.6f)
    }
}

/** CareSens Air: 35.2 x 19.2 x 5.0 mm, a light grey cushioned capsule on a clear base; a ribbed dial with a button. */
private fun DrawScope.drawCareSensAir(tones: SensorTones) = seated(tones, 11.4f) {
    val grey = tones.grey()
    val outline = roundRect(80f, 43.6f, 20f)
    glass(tones, roundRect(80.8f, 44.4f, 20.4f), thickness = 2f, stands = 11.4f)
    part(grey, outline, thickness = 9.4f, bevel = 5.5f, base = 2f) {
        val dial = Offset(16.5f, 0f)
        val reach = 12.6f
        val disc = Path().apply { addOval(Rect(dial.x - reach, -reach, dial.x + reach, reach)) }
        recess(grey, disc, depth = 0.8f, floor = lerp(grey.body, grey.wall, 0.35f))
        clipPath(disc) {
            var y = -reach
            while (y < reach) {
                drawLine(grey.wall.copy(alpha = 0.7f), Offset(dial.x - reach, y), Offset(dial.x + reach, y), strokeWidth = 0.8f)
                y += 2.2f
            }
        }
        raised(grey, Path().apply { addOval(Rect(dial.x - 5.6f, -5.6f, dial.x + 5.6f, 5.6f)) }, 1f, top = lerp(tones.highlight, grey.body, 0.4f))
        pit(tones, Path().apply { addOval(Rect(-12.5f, -8f, -10.5f, -6f)) })
    }
}

/**
 * Glutec v3, measured from top-down photos: an oblong 1.38 as long as it is wide, one end
 * rounded off and the other squarer. A parting line runs across just inside the rounded end; at
 * the square end a grey pull tab sits in a U-shaped dip in the top, open to the edge.
 */
private fun DrawScope.drawMq(tones: SensorTones) = seated(tones, 13f) {
    part(tones, roundEnds(72f, 52f, 10f, 19f), thickness = 13f, bevel = 5.5f) {
        val dip = Path().apply {
            op(
                Path().apply { addRect(Rect(-40f, -8.5f, -28f, 8.5f)) },
                Path().apply { addOval(Rect(-36.5f, -8.5f, -19.5f, 8.5f)) },
                PathOperation.Union,
            )
        }
        recess(tones, dip, depth = 1.2f)
        raised(
            tones.grey(),
            roundRect(6f, 11f, 1.6f, Offset(-33.5f, 0f))(0f),
            1.4f,
            top = lerp(tones.wall, tones.body, 0.25f),
        )
        groove(tones, Path().apply {
            moveTo(21.6f, -24.5f)
            lineTo(21.6f, 24.5f)
        }, width = 0.9f)
    }
}

/**
 * TouchCare Nano, traced from a top-down shot: a pillowy rounded slab 0.63 as wide as it is long,
 * the round tab of the clear base it clips into just showing past its top end.
 */
private fun DrawScope.drawMedtrum(tones: SensorTones) = seated(tones, 7f) {
    glass(tones, circle(7.5f, Offset(0f, -34f)), thickness = 1.6f)
    part(tones, roundRect(46f, 72f, 11f), thickness = 7f, bevel = 5.5f)
}

/**
 * The SmartGuide applicator, for the wizard hero: a white barrel with a flat top on a twist cap
 * ribbed at its foot, a pull tab running down a slot across the joint into a U cut in the cap.
 */
private fun DrawScope.drawAccuChekApplicator(tones: SensorTones) {
    val half = 25f
    val rise = 5.5f
    val top = -38f
    val shoulder = 4f
    val joint = 4f
    val bottom = 38f
    for (pass in 6 downTo 1) {
        drawOval(
            tones.shadow,
            topLeft = Offset(-half - pass * 0.6f + 1.5f, bottom - rise + pass * 0.7f),
            size = Size(half * 2f + pass * 1.2f, rise * 2f + 1f),
        )
    }
    fun band(upper: Float, lower: Float) = Path().apply {
        moveTo(-half, upper)
        lineTo(-half, lower)
        arcTo(Rect(-half, lower - rise, half, lower + rise), 180f, -180f, forceMoveTo = false)
        lineTo(half, upper)
        arcTo(Rect(-half, upper - rise, half, upper + rise), 0f, 180f, forceMoveTo = false)
        close()
    }
    val capDeep = lerp(tones.cap, tones.ink, 0.45f)
    val capLit = lerp(tones.cap, tones.highlight, 0.2f)
    val capBand = band(joint, bottom)
    drawPath(
        capBand,
        Brush.horizontalGradient(
            0f to capDeep,
            0.28f to capLit,
            0.6f to tones.cap,
            1f to capDeep,
            startX = -half,
            endX = half,
        ),
    )
    // The ribs follow the barrel: bunched at the edges, wide at the front.
    clipPath(capBand) {
        for (rib in 1 until 26) {
            val angle = rib / 26f * PI.toFloat()
            val x = -half * cos(angle)
            val curve = rise * sin(angle)
            drawLine(capDeep.copy(alpha = 0.5f), Offset(x, bottom - 17f + curve), Offset(x, bottom + curve), strokeWidth = 0.6f)
        }
        // The U cut the tab drops into.
        drawRoundRect(
            capDeep.copy(alpha = 0.55f),
            topLeft = Offset(-9f, joint - 2f),
            size = Size(18f, 26f),
            cornerRadius = CornerRadius(9f),
        )
    }
    val barrel = Path().apply {
        moveTo(-half, joint)
        lineTo(-half, top + shoulder)
        arcTo(Rect(-half, top - rise, half, top + rise + shoulder * 2f), 180f, 180f, forceMoveTo = false)
        lineTo(half, joint)
        arcTo(Rect(-half, joint - rise, half, joint + rise), 0f, 180f, forceMoveTo = false)
        close()
    }
    drawPath(
        barrel,
        Brush.horizontalGradient(
            0f to tones.shade,
            0.3f to tones.highlight,
            0.62f to tones.body,
            1f to tones.wall,
            startX = -half,
            endX = half,
        ),
    )
    val lid = Rect(-half + 1f, top - rise + 0.5f, half - 1f, top + rise + 0.5f)
    drawOval(
        Brush.linearGradient(
            0f to tones.highlight,
            1f to tones.body,
            start = lid.topLeft,
            end = lid.bottomRight,
        ),
        topLeft = lid.topLeft,
        size = lid.size,
    )
    // Slot and pull tab, front and centre.
    val slot = Path().apply { addRoundRect(RoundRect(Rect(-7f, -20f, 7f, 22f), CornerRadius(7f))) }
    drawPath(slot, Brush.verticalGradient(0f to tones.wall, 1f to tones.shade, startY = -20f, endY = 0f))
    val tab = Path().apply { addRoundRect(RoundRect(Rect(-5f, -8f, 5f, 24f), CornerRadius(5f))) }
    drawPath(tab, Brush.horizontalGradient(0f to capLit, 1f to capDeep, startX = -5f, endX = 5f))
    drawPath(tab, tones.glint.copy(alpha = 0.35f), style = Stroke(width = 0.5f))
}

private fun DrawScope.drawCareSensApplicatorBody(tones: SensorTones) {
    val half = 28f
    val rise = 8f
    val bottom = 34f
    rotate(-10f, pivot = Offset.Zero) {
        val shell = Path().apply {
            moveTo(-half, bottom)
            lineTo(-half, -12f)
            cubicTo(-half, -40f, half, -40f, half, -12f)
            lineTo(half, bottom)
            arcTo(Rect(-half, bottom - rise, half, bottom + rise), 0f, 180f, forceMoveTo = false)
            close()
        }
        drawPath(
            shell,
            Brush.horizontalGradient(
                0f to tones.shade,
                0.28f to tones.highlight,
                0.6f to tones.body,
                1f to tones.wall,
                startX = -half,
                endX = half,
            ),
        )
        // The rubber lip it stands on.
        val lip = Path().apply {
            arcTo(Rect(-half, bottom - rise - 4f, half, bottom + rise - 4f), 180f, -180f, forceMoveTo = true)
        }
        drawPath(lip, tones.detail, style = Stroke(width = 2.4f))
        val slot = Path().apply {
            addRoundRect(RoundRect(Rect(-14f, -22f, 2f, 16f), CornerRadius(8f)))
        }
        val slotDeep = lerp(tones.cap, tones.ink, 0.55f)
        drawPath(slot, Brush.verticalGradient(0f to tones.cap, 1f to slotDeep, startY = -22f, endY = 16f))
        clipPath(slot) {
            var y = -21f
            while (y < 16f) {
                drawLine(slotDeep.copy(alpha = 0.6f), Offset(-15f, y), Offset(3f, y + 2f), strokeWidth = 0.8f)
                y += 2.6f
            }
        }
        drawCircle(
            Brush.radialGradient(
                0f to lerp(tones.cap, tones.highlight, 0.45f),
                1f to tones.cap,
                center = Offset(-7.4f, 5f),
                radius = 8f,
            ),
            6.6f,
            Offset(-6f, 7f),
        )
    }
}

/** A phone seen leaning in: a thin slab with a dark glass face. */
private fun DrawScope.drawPhoneBody(tones: SensorTones) {
    val glass = RoundRect(Rect(-23f, -44f, 23f, 44f), CornerRadius(8f))
    val slab = Path().apply { addRoundRect(glass) }
    translate(1.8f, 2.2f) { drawPath(slab, tones.wallDeep) }
    drawPath(
        slab,
        Brush.linearGradient(
            0f to lerp(tones.ink, tones.detail, 0.35f),
            1f to tones.ink,
            start = Offset(-23f, -44f),
            end = Offset(23f, 44f),
        ),
    )
    clipPath(slab) {
        val sheen = Path().apply {
            moveTo(-23f, -8f)
            lineTo(23f, -44f)
            lineTo(-23f, -44f)
            close()
        }
        drawPath(sheen, tones.glint.copy(alpha = 0.1f))
    }
    drawPath(slab, tones.glint.copy(alpha = 0.32f), style = Stroke(width = 1f))
    drawCircle(tones.detail, 1.6f, Offset(0f, -38f))
}
