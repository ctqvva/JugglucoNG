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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

    /** A clear, faintly tinted plastic, for the bases some sensors sit in. */
    fun clear(): SensorTones = copy(
        body = lerp(body, accent, 0.16f),
        shade = lerp(shade, accent, 0.26f),
        wall = lerp(shade, accent, 0.36f),
        wallDeep = lerp(wall, accent, 0.5f),
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

/** A small square window set into the face. */
private fun DrawScope.window(tones: SensorTones, center: Offset, side: Float, filled: Boolean) {
    val topLeft = center - Offset(side / 2f, side / 2f)
    if (filled) {
        drawRoundRect(tones.ink, topLeft, Size(side, side), CornerRadius(side * 0.18f))
    } else {
        drawRoundRect(lerp(tones.detail, tones.body, 0.55f), topLeft, Size(side, side), CornerRadius(side * 0.12f))
        drawRoundRect(
            tones.ink.copy(alpha = 0.7f),
            topLeft,
            Size(side, side),
            CornerRadius(side * 0.12f),
            style = Stroke(width = side * 0.14f),
        )
    }
}


/** Centres an object [thickness] thick in the square, so tall and flat sensors sit alike. */
private inline fun DrawScope.seated(tones: SensorTones, thickness: Float, block: DrawScope.() -> Unit) {
    val up = tones.drop(-thickness * WALL_SHOW / 2f)
    translate(up.x, up.y, block)
}

/**
 * One part of a sensor: its shadow on the skin, as long as the part stands high; the wall
 * showing under the near edge, [thickness] deep and standing [base] above the skin; then the
 * top, its edge rolling over across [bevel] into the face, flat or [dome]d. [band] darkens the
 * lowest share of the wall, for a casing on a darker foot. A part on the skin casts the shadow of
 * everything it carries, [stands] high in all. [face] draws on the top.
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
    face: DrawScope.() -> Unit = {},
) {
    val top = outline(0f)
    val bounds = top.getBounds()
    val reach = max(bounds.width, bounds.height) / 2f
    val wall = thickness * WALL_SHOW
    val lift = tones.drop(-base * WALL_SHOW)
    // The light's way across the part, upper left to lower right of the picture.
    val lit = bounds.center + tones.ground(-reach * 0.75f, -reach * 0.75f)
    val dim = bounds.center + tones.ground(reach * 0.75f, reach * 0.75f)

    if (base == 0f) {
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
        val layers = max(ceil(wall / 0.35f).toInt(), 1)
        for (layer in layers downTo 1) {
            val depth = wall * layer / layers
            val at = tones.drop(depth)
            translate(at.x, at.y) { drawPath(top, if (band > 0f && depth > wall * (1f - band)) foot else side) }
        }
        val floor = tones.drop(wall)
        translate(floor.x, floor.y) {
            drawPath(top, tones.wallDeep.copy(alpha = 0.6f), style = Stroke(width = 0.5f))
        }

        // The rolled edge: lit where it faces the light, darker where it turns away.
        drawPath(
            top,
            Brush.linearGradient(
                0f to tones.highlight,
                0.5f to lerp(tones.body, tones.shade, 0.5f),
                1f to tones.wall,
                start = lit,
                end = dim,
            ),
        )
        val faceBrush = if (dome) {
            Brush.radialGradient(
                0f to tones.highlight,
                0.35f to tones.highlight,
                0.75f to tones.body,
                1f to tones.shade,
                center = bounds.center + tones.ground(-reach * 0.3f, -reach * 0.36f),
                radius = reach * 1.25f,
            )
        } else {
            Brush.linearGradient(
                0f to tones.highlight,
                0.55f to tones.body,
                1f to lerp(tones.body, tones.shade, 0.55f),
                start = lit,
                end = dim,
            )
        }
        // Veils of the face, each a little further in, so the edge rolls rather than steps.
        val rolls = 6
        for (roll in 1 until rolls) {
            drawPath(outline(bevel * roll / rolls), faceBrush, alpha = 0.32f)
        }
        drawPath(outline(bevel), faceBrush)
        drawPath(top, tones.wall.copy(alpha = 0.5f), style = Stroke(width = 0.5f))
        clipPath(top, block = face)
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
 * The Sibionics pair, measured from top-down photos of both, the older behind the newer at their
 * true sizes against each other. GS1: a pill 1.6 as long as it is wide, its ends rounded short
 * of full circles; a round hole a seventh of its length off centre holds a white keyed plug.
 * Sibionics 2: a thin flat slab 1.4 as long as it is wide, a white cover on a clear frame that
 * shows a clip at each short end.
 */
private fun DrawScope.drawSibionics(tones: SensorTones) = seated(tones, 8f) {
    turned(tones, Offset(-11f, -12f), -24f, 0.8f) { tones ->
        part(tones, roundRect(72f, 44.5f, 19f), thickness = 9f, bevel = 4.5f) {
            keyedPort(tones, Offset(-10f, 0f), 5.6f)
        }
    }
    turned(tones, Offset(13f, 14f), 4f, 0.58f) { tones ->
        val cover = roundRect(70f, 50f, 13f)
        val frame: Outline = { inset ->
            Path().apply {
                op(cover(inset), roundRect(74f, 18f, 2.5f)(inset), PathOperation.Union)
            }
        }
        part(tones.clear(), frame, thickness = 4f, bevel = 0.5f, stands = 10f)
        part(tones, cover, thickness = 6f, bevel = 4.5f, base = 4f)
    }
}

/** A round hole holding a white plug, its key cut in it: the filament port of a Sibionics or i6. */
private fun DrawScope.keyedPort(tones: SensorTones, at: Offset, radius: Float) {
    drawCircle(tones.ink, radius, at)
    val plug = radius * 0.78f
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
 * AiDEX X and LinX, measured from a top-down photo: a plain round disc, its edge well rounded.
 * Two fifths of the way out, a raised white collar rings a dark gap round the plug, whose slot
 * points at the centre.
 */
private fun DrawScope.drawAiDex(tones: SensorTones) = seated(tones, 11f) {
    part(tones, circle(36f), thickness = 11f, bevel = 4f) {
        val toward = -52f
        val port = Offset(15f * cos(toward * PI.toFloat() / 180f), 15f * sin(toward * PI.toFloat() / 180f))
        drawCircle(tones.wall.copy(alpha = 0.5f), 10.4f, port + tones.ground(0.4f, 0.7f))
        drawCircle(
            Brush.radialGradient(
                0f to tones.highlight,
                1f to tones.body,
                center = port + tones.ground(-3f, -3f),
                radius = 13f,
            ),
            10f,
            port,
        )
        drawCircle(tones.ink, 8.4f, port)
        drawCircle(lerp(tones.body, tones.shade, 0.25f), 7f, port)
        rotate(toward, pivot = port) {
            val slot = roundRect(11.5f, 6f, 1.8f, port)(0f)
            drawPath(slot, tones.highlight)
            drawPath(slot, tones.ink, style = Stroke(width = 1.1f))
        }
    }
}

/**
 * M8, measured from a top-down photo: a coin 3.5 mm thin in light grey plastic. Two fifths of the
 * way out, a raised rounded frame lying along the radius holds a dark oblong hole.
 */
private fun DrawScope.drawOttai(tones: SensorTones) = seated(tones, 10.8f) {
    val grey = tones.grey()
    part(grey, circle(35f), thickness = 10.8f, bevel = 3.5f) {
        val toward = -20f
        val button = Offset(16f * cos(toward * PI.toFloat() / 180f), 16f * sin(toward * PI.toFloat() / 180f))
        rotate(toward, pivot = button) {
            val frame = roundRect(17f, 12.5f, 5f, button)
            drawPath(frame(0f), lerp(grey.highlight, grey.body, 0.4f))
            groove(grey, frame(0f), width = 0.9f)
            groove(grey, frame(2.4f), width = 0.6f, depth = 0.6f)
            drawPath(roundRect(8.5f, 5.6f, 2.4f, button)(0f), tones.ink)
        }
    }
}

/**
 * The iCan pair, the larger i3 behind the i6. i3, measured from a top-down photo: a thick white
 * puck, a teardrop groove whose round end lies near the rim holding a square window, its tip at
 * the centre with three contacts towards it; small slots break the rim. i6: a rounded octagon,
 * thin on a dark foot, a grey ring round its keyed port above the centre.
 */
private fun DrawScope.drawICan(tones: SensorTones) = seated(tones, 9f) {
    turned(tones, Offset(-10f, -9f), 160f, 0.8f) { tones ->
        part(tones, circle(36.5f), thickness = 11f, bevel = 2.6f) {
            val recess = capsule(Offset(-3f, 19f), 11f, Offset(-3f, 2f), 6.5f)
            drawPath(recess, lerp(tones.body, tones.shade, 0.3f))
            drawPath(recess, tones.ink.copy(alpha = 0.65f), style = Stroke(width = 1f))
            window(tones, Offset(-3f, 23f), 5.6f, filled = false)
            drawRoundRect(tones.ink, Offset(-8.6f, 6.8f), Size(3.2f, 2.4f), CornerRadius(0.5f))
            drawRoundRect(tones.ink, Offset(-1f, 6.8f), Size(3.2f, 2.4f), CornerRadius(0.5f))
            window(tones, Offset(-3f, 1.6f), 2.2f, filled = false)
            for (slot in listOf(235f, 305f, 150f, 20f)) {
                rotate(slot, pivot = Offset.Zero) {
                    drawLine(tones.wallDeep, Offset(35.2f, -2f), Offset(35.2f, 2f), strokeWidth = 0.9f, cap = StrokeCap.Round)
                }
            }
        }
    }
    turned(tones, Offset(15f, 15f), -10f, 0.6f) { tones ->
        part(tones, octagon(34f, 7f), thickness = 8f, bevel = 3f, band = 0.35f) {
            val port = Offset(-3f, -10f)
            drawCircle(lerp(tones.wall, tones.wallDeep, 0.4f), 7.2f, port)
            keyedPort(tones, port, 5f)
        }
    }
}

/**
 * The Anytime pair, the CT4 behind the 5 Pro. CT4, measured from a top-down photo: an egg 1.2 as
 * long as it is wide, gently domed. 5 Pro: a white disc in a clear rim, a clear band curving
 * across its upper part, above it the port between two short slots and a dot by the rim.
 */
private fun DrawScope.drawAnytime(tones: SensorTones) = seated(tones, 10f) {
    turned(tones, Offset(-10f, -9f), -62f, 0.82f) { tones ->
        part(tones, egg(30.5f, 36f, 0.22f), thickness = 13f, bevel = 6f, dome = true)
    }
    turned(tones, Offset(15f, 15f), 8f, 0.6f) { tones ->
        part(tones.clear(), circle(36f), thickness = 3f, bevel = 1f, stands = 10f)
        part(tones, circle(33.5f), thickness = 7f, bevel = 4f, base = 3f) {
            val sweep = Path().apply { addOval(Rect(-32.5f, -74f, 32.5f, -9f)) }
            drawPath(sweep, lerp(tones.clear().shade, tones.body, 0.25f), style = Stroke(width = 2.6f))
            groove(tones, Path().apply { addOval(Rect(-31.2f, -72.7f, 31.2f, -10.3f)) }, width = 0.5f, depth = 0.5f)
            groove(tones, Path().apply { addOval(Rect(-33.8f, -75.3f, 33.8f, -7.7f)) }, width = 0.5f, depth = 0.5f)
            drawCircle(lerp(tones.body, tones.shade, 0.4f), 2.8f, Offset(0f, -20f))
            drawCircle(tones.ink, 1f, Offset(0f, -20f))
            drawPath(stadium(2.2f, 4.6f, Offset(-9f, -20f))(0f), lerp(tones.body, tones.wall, 0.5f))
            drawPath(stadium(2.2f, 4.6f, Offset(9f, -20f))(0f), lerp(tones.body, tones.wall, 0.5f))
            drawCircle(lerp(tones.body, tones.wall, 0.5f), 1.2f, Offset(0f, -29f))
        }
    }
}

/**
 * SmartGuide sensor: a 33 mm disc 6 mm thick, a light grey cover with a rolled edge on a clear
 * lower shell of the same outline. The filament port sits just left of centre in a metal collar, a small light near the
 * rim, and a square cut in the rim to the upper right.
 */
private fun DrawScope.drawAccuChek(tones: SensorTones) = seated(tones, 13f) {
    val cover = polar { angle -> 35.5f - cut(angle, -0.9f, 1.8f, 0.07f) }
    part(tones.clear(), cover, thickness = 4.2f, bevel = 0.6f, stands = 13f)
    val coverTones = tones.grey().copy(highlight = tones.highlight, body = lerp(tones.body, tones.shade, 0.3f))
    part(coverTones, cover, thickness = 8.8f, bevel = 4f, base = 4.2f) {
        val port = Offset(-4f, 1f)
        drawCircle(
            Brush.radialGradient(
                0f to tones.highlight,
                1f to tones.wall,
                center = port + tones.ground(-1.2f, -1.4f),
                radius = 5f,
            ),
            3.9f,
            port,
        )
        drawCircle(tones.wallDeep, 3.9f, port, style = Stroke(width = 0.5f))
        hole(tones, port, 1.9f)
        drawCircle(lerp(tones.highlight, tones.accent, 0.15f), 1.3f, Offset(-26f, -1f))
        drawCircle(tones.wall, 1.3f, Offset(-26f, -1f), style = Stroke(width = 0.4f))
    }
}

/** CareSens Air: a light grey cushioned capsule on a clear lower shell; a ribbed dial with a button. */
private fun DrawScope.drawCareSensAir(tones: SensorTones) = seated(tones, 10f) {
    val grey = tones.grey()
    val outline = roundRect(80f, 46f, 21f)
    part(tones.clear(), outline, thickness = 1.6f, bevel = 0.4f, stands = 10f)
    part(grey, outline, thickness = 8.4f, bevel = 5.5f, base = 1.6f) {
        val dial = Offset(16.5f, 0f)
        val reach = 13.2f
        val disc = Path().apply { addOval(Rect(dial.x - reach, -reach, dial.x + reach, reach)) }
        drawPath(disc, lerp(grey.body, grey.wall, 0.4f))
        clipPath(disc) {
            var y = -reach
            while (y < reach) {
                drawLine(grey.wall.copy(alpha = 0.8f), Offset(dial.x - reach, y), Offset(dial.x + reach, y), strokeWidth = 0.8f)
                y += 2.2f
            }
        }
        drawPath(disc, grey.wall, style = Stroke(width = 0.9f))
        drawCircle(
            Brush.radialGradient(
                0f to tones.highlight,
                1f to grey.shade,
                center = dial + tones.ground(-1.6f, -1.8f),
                radius = 7.5f,
            ),
            5.8f,
            dial,
        )
        drawCircle(grey.wall, 5.8f, dial, style = Stroke(width = 0.6f))
        drawCircle(tones.ink, 1f, Offset(-11.5f, -7f))
    }
}

/**
 * Glutec v3, measured from a top-down photo: an oblong 1.33 as long as it is wide, one end square
 * cornered and carrying a latch, the other rounded off; a parting line runs across just inside
 * the rounded end.
 */
private fun DrawScope.drawMq(tones: SensorTones) = seated(tones, 12f) {
    part(tones, roundEnds(72f, 54f, 11f, 22f), thickness = 12f, bevel = 5f) {
        val latch = Path().apply {
            addRoundRect(RoundRect(Rect(-35.5f, -8.5f, -26f, 8.5f), CornerRadius(2f)))
        }
        drawPath(latch, lerp(tones.body, tones.wall, 0.55f))
        groove(tones, latch, width = 0.8f)
        drawRoundRect(
            lerp(tones.highlight, tones.body, 0.3f),
            topLeft = Offset(-34.5f, -5.5f),
            size = Size(5f, 11f),
            cornerRadius = CornerRadius(1.2f),
        )
        groove(tones, Path().apply {
            moveTo(22f, -28f)
            lineTo(22f, 28f)
        }, width = 0.9f)
    }
}

/**
 * TouchCare Nano, traced from a top-down shot: a pillowy rounded slab 0.63 as wide as it is long,
 * the round tab of the clear base it clips into just showing past its top end.
 */
private fun DrawScope.drawMedtrum(tones: SensorTones) = seated(tones, 7f) {
    part(tones.clear(), circle(7.5f, Offset(0f, -34f)), thickness = 1.6f, bevel = 0.5f)
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
