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
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import tk.glucodata.ui.isAppInDarkTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
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
) {
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
        body = lerp(body, accent, 0.12f),
        shade = lerp(shade, accent, 0.2f),
        wall = lerp(shade, accent, 0.28f),
        wallDeep = lerp(wall, accent, 0.4f),
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
                ink = scheme.surfaceContainerLowest,
                detail = lerp(Color.White, far, 0.58f),
                accent = scheme.primary,
                cap = lerp(scheme.primary, scheme.primaryContainer, 0.5f),
                glint = Color.White,
                shadow = scheme.surfaceContainerLowest.copy(alpha = 0.2f),
            )
        } else {
            val far = lerp(scheme.outline, scheme.primary, 0.15f)
            SensorTones(
                highlight = Color.White,
                body = lerp(Color.White, scheme.surfaceContainerHighest, 0.3f),
                shade = lerp(Color.White, scheme.surfaceContainerHighest, 0.85f),
                wall = lerp(scheme.surfaceContainerHighest, far, 0.3f),
                wallDeep = lerp(scheme.surfaceContainerHighest, far, 0.62f),
                ink = scheme.onSurface,
                detail = lerp(scheme.surfaceContainerHighest, far, 0.75f),
                accent = scheme.primary,
                cap = scheme.primary,
                glint = Color.White,
                shadow = scheme.onSurface.copy(alpha = 0.055f),
            )
        }
    }
}

/**
 * One sensor family, drawn to its maker's product photos: outlines traced from top-down shots,
 * real thickness against real width, the side profile (straight wall, rounded or chamfered edge,
 * dome), and the ports, windows and notches where they really are, with no maker's name on them.
 * Drawn from code rather than shipped as bitmaps, so they follow the theme and stay sharp at any
 * size; the same drawing serves a list tile and a wizard hero.
 */
@Composable
internal fun SensorArt(type: SensorType, modifier: Modifier = Modifier, onPad: Boolean = false) {
    val tones = rememberSensorTones()
    Canvas(modifier = modifier) { drawSensor(type, tones, onPad) }
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
 * [onPad] seats the sensor on the adhesive patch it is worn on. Off for a list tile, where the
 * tile is the frame and a second outline would only crowd it.
 */
internal fun DrawScope.drawSensor(type: SensorType, tones: SensorTones, onPad: Boolean = false) {
    inUnitSquare {
        when (type) {
            SensorType.LIBRE -> drawLibre(tones, onPad)
            SensorType.DEXCOM -> drawDexcom(tones, onPad)
            SensorType.SIBIONICS -> drawSibionics(tones)
            SensorType.AIDEX -> drawAiDex(tones, onPad)
            SensorType.OTTAI -> drawOttai(tones, onPad)
            SensorType.ICANHEALTH -> drawICan(tones, onPad)
            SensorType.ANYTIME -> drawAnytime(tones, onPad)
            SensorType.ACCUCHEK -> drawAccuChek(tones, onPad)
            SensorType.CARESENS_AIR -> drawCareSensAir(tones, onPad)
            SensorType.MQ -> drawMq(tones, onPad)
            SensorType.MEDTRUM -> drawMedtrum(tones, onPad)
        }
    }
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
// One camera for every sensor: seen from about 53 degrees up, so a top face is foreshortened to
// four fifths of its depth and a height shows at three fifths. Light comes from the upper left.
// Sizes are in drawing units with the longest side of each sensor near 72, and every height is
// that sensor's real thickness against its real width.

private const val VIEW_SQUASH = 0.8f
private const val WALL_SHOW = 0.6f

/** An outline in top view, shrunk evenly by its argument; insets give the edge profile. */
private typealias Outline = (inset: Float) -> Path

/**
 * A side profile, from the skin up: pairs of (inset from the outline, height). A vertical wall
 * keeps its inset; a rounded or chamfered edge steps in as it rises; the last pair is the top.
 */
private class Profile(val insets: FloatArray, val heights: FloatArray) {
    val height get() = heights.last()
    val top get() = insets.last()

    /** Height of the straight wall, before the edge starts to turn in. */
    val wall: Float
        get() {
            var h = 0f
            for (i in insets.indices) if (insets[i] == 0f) h = heights[i]
            return h
        }
}

/** A straight wall whose top edge rounds over with [radius]. */
private fun rounded(height: Float, radius: Float): Profile {
    val r = min(radius, height)
    val steps = 10
    val insets = FloatArray(steps + 2)
    val heights = FloatArray(steps + 2)
    for (i in 0..steps) {
        val a = i / steps.toFloat() * PI.toFloat() / 2f
        insets[i + 1] = r * (1f - cos(a))
        heights[i + 1] = height - r + r * sin(a)
    }
    return Profile(insets, heights)
}

/** A straight wall [wall] high, then a flat chamfer stepping in by [inset] to the top face. */
private fun chamfered(height: Float, wall: Float, inset: Float) =
    Profile(floatArrayOf(0f, 0f, inset * 0.12f, inset), floatArrayOf(0f, wall, wall + (height - wall) * 0.3f, height))

/** A foot [lip] high and [step] wider than the body, then a wall whose top rounds over. */
private fun footed(height: Float, lip: Float, step: Float, radius: Float): Profile {
    val upper = rounded(height - lip, radius)
    val insets = FloatArray(upper.insets.size + 2)
    val heights = FloatArray(upper.insets.size + 2)
    heights[1] = lip
    for (i in upper.insets.indices) {
        insets[i + 2] = step + upper.insets[i]
        heights[i + 2] = lip + upper.heights[i]
    }
    return Profile(insets, heights)
}

/** A short wall under a dome that rises [height] and draws in by [inset]. */
private fun domed(height: Float, wall: Float, inset: Float): Profile {
    val steps = 12
    val insets = FloatArray(steps + 2)
    val heights = FloatArray(steps + 2)
    for (i in 0..steps) {
        val a = i / steps.toFloat() * PI.toFloat() / 2f
        insets[i + 1] = inset * (1f - cos(a))
        heights[i + 1] = wall + (height - wall) * sin(a)
    }
    return Profile(insets, heights)
}

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

/** A superellipse: [n] = 2 is an ellipse, higher squares it off while keeping it soft. */
private fun superellipse(a: Float, b: Float, n: Float): Outline = { inset ->
    val ra = max(a - inset, 0.1f)
    val rb = max(b - inset, 0.1f)
    Path().apply {
        val steps = 144
        for (step in 0 until steps) {
            val t = step / steps.toFloat() * 2f * PI.toFloat()
            val c = cos(t)
            val s = sin(t)
            val x = ra * sign(c) * abs(c).pow(2f / n)
            val y = rb * sign(s) * abs(s).pow(2f / n)
            if (step == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

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

private fun leanOf(rotation: Float) = Matrix().apply {
    scale(1f, VIEW_SQUASH)
    rotateZ(rotation)
}

private fun Path.leaned(lean: Matrix) = Path().also {
    it.addPath(this)
    it.transform(lean)
}

/** Centres an object [height] tall in the square, so tall and flat sensors sit alike. */
private inline fun DrawScope.upright(height: Float, block: DrawScope.() -> Unit) {
    translate(0f, height * WALL_SHOW / 2f, block)
}

/** The adhesive patch, flat on the skin under the sensor. */
private fun DrawScope.patch(tones: SensorTones, outline: Outline, at: Offset = Offset.Zero) {
    val lean = leanOf(0f)
    translate(at.x, at.y * VIEW_SQUASH) {
        drawPath(outline(0f).leaned(lean), tones.highlight.copy(alpha = 0.2f))
        drawPath(outline(1.6f).leaned(lean), tones.highlight.copy(alpha = 0.08f))
        drawPath(outline(0f).leaned(lean), tones.highlight.copy(alpha = 0.3f), style = Stroke(width = 0.7f))
    }
}

/**
 * Builds [outline] up along [profile], standing [base] above the skin: a soft contact shadow,
 * then slice by slice from the skin up, each slice shaded by how far its surface faces up, so a
 * wall reads as a wall, a rounded edge rolls and catches the light at the upper left, and a
 * chamfer is a flat slope. The top face is flat, or lit as a [dome].
 *
 * Slices below [band] take a darker base material. [side] draws on the straight wall, with
 * y = 0 at the wall's top and the wall's projected height as its argument; [face] draws on the
 * top face in the outline's own coordinates, so it leans and turns with the object.
 */
private fun DrawScope.solid(
    tones: SensorTones,
    outline: Outline,
    profile: Profile,
    base: Float = 0f,
    rotation: Float = 0f,
    dome: Boolean = false,
    band: Float = 0f,
    side: DrawScope.(wall: Float) -> Unit = {},
    face: DrawScope.() -> Unit = {},
) {
    val lean = leanOf(rotation)
    val foot = outline(0f).leaned(lean)
    val bounds = foot.getBounds()
    val floor = -base * WALL_SHOW

    if (base == 0f) {
        // Contact shadow: stacked passes stand in for a blur, which a Canvas path cannot have.
        for (pass in 6 downTo 1) {
            translate(pass * 0.3f, floor + pass * 0.75f) { drawPath(foot, tones.shadow) }
        }
    }

    val lit = Offset(bounds.left, bounds.top)
    val dim = Offset(bounds.right, bounds.bottom)
    val faceBrush = if (dome) {
        Brush.radialGradient(
            0f to tones.highlight,
            0.6f to tones.body,
            1f to tones.shade,
            center = bounds.center + Offset(-bounds.width * 0.16f, -bounds.height * 0.24f),
            radius = max(bounds.width, bounds.height) * 0.7f,
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
    // Each slice is one opaque gradient, mixed from the wall's tones towards the face's by how far
    // the slice faces up, and towards the glint where a rolling edge catches the light. Opaque
    // slices stack without the banding that layered translucent ones leave.
    val wallStops = arrayOf(tones.shade, tones.wall, tones.wallDeep)
    val bandStops = arrayOf(
        lerp(tones.wall, tones.ink, 0.35f),
        lerp(tones.wall, tones.ink, 0.45f),
        lerp(tones.wallDeep, tones.ink, 0.55f),
    )
    val faceStops = arrayOf(tones.highlight, tones.body, lerp(tones.body, tones.shade, 0.55f))
    fun sliceBrush(up: Float, inBand: Boolean): Brush {
        val from = if (inBand) bandStops else wallStops
        val mix = up.pow(0.8f)
        val catch = 4f * up * (1f - up) * 0.55f
        return Brush.linearGradient(
            0f to lerp(lerp(from[0], faceStops[0], mix), tones.glint, catch),
            0.45f to lerp(from[1], faceStops[1], mix),
            1f to lerp(from[2], faceStops[2], mix),
            start = lit,
            end = dim,
        )
    }

    val insets = profile.insets
    val heights = profile.heights
    for (seg in 0 until insets.size - 1) {
        val i0 = insets[seg]
        val h0 = heights[seg]
        val di = insets[seg + 1] - i0
        val dh = heights[seg + 1] - h0
        val run = hypot(di, dh)
        if (run == 0f) continue
        val up = di / run
        val slices = max(ceil(max(dh * WALL_SHOW / 0.3f, di / 0.35f)).toInt(), 1)
        val plain = sliceBrush(up, inBand = false)
        val banded = sliceBrush(up, inBand = true)
        for (k in (if (seg == 0) 0 else 1)..slices) {
            val t = k / slices.toFloat()
            val h = h0 + dh * t
            val slice = outline(i0 + di * t).leaned(lean)
            translate(0f, floor - h * WALL_SHOW) {
                drawPath(slice, if (h < band) banded else plain)
            }
        }
    }
    translate(0f, floor) {
        drawPath(foot, tones.wallDeep.copy(alpha = 0.45f), style = Stroke(width = 0.6f))
    }
    translate(0f, floor - profile.wall * WALL_SHOW) { side(profile.wall * WALL_SHOW) }

    translate(0f, floor - profile.height * WALL_SHOW) {
        val top = outline(profile.top).leaned(lean)
        drawPath(top, faceBrush)
        if (dome) {
            val gloss = Rect(
                bounds.center + Offset(-bounds.width * 0.28f, -bounds.height * 0.34f),
                Size(bounds.width * 0.3f, bounds.height * 0.2f),
            )
            drawOval(
                Brush.radialGradient(
                    0f to tones.glint.copy(alpha = 0.7f),
                    1f to tones.glint.copy(alpha = 0f),
                    center = gloss.center,
                    radius = gloss.width / 2f,
                ),
                topLeft = gloss.topLeft,
                size = gloss.size,
            )
        }
        clipPath(top) { withTransform({ transform(lean) }, face) }
    }
}

/** A hole into the casing: dark, its far inner wall lit, a bright lip on the near edge. */
private fun DrawScope.hole(tones: SensorTones, center: Offset, radius: Float) {
    drawCircle(tones.ink, radius, center)
    drawArc(
        color = tones.detail,
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = center - Offset(radius * 0.72f, radius * 0.72f),
        size = Size(radius * 1.44f, radius * 1.44f),
        style = Stroke(width = radius * 0.4f, cap = StrokeCap.Round),
    )
    drawArc(
        color = tones.glint.copy(alpha = 0.8f),
        startAngle = 30f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = center - Offset(radius * 1.1f, radius * 1.1f),
        size = Size(radius * 2.2f, radius * 2.2f),
        style = Stroke(width = radius * 0.22f, cap = StrokeCap.Round),
    )
}

/** A parting line or groove: a dark cut with the light catching its lower edge. */
private fun DrawScope.groove(tones: SensorTones, path: Path, width: Float = 0.9f, depth: Float = 0.8f) {
    translate(0.25f, 0.5f) {
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

// ---- The sensors ----

/** Libre 3: a thin 21 mm coin, its one feature an oval port with the keyed filament slot. */
private fun DrawScope.drawLibre(tones: SensorTones, onPad: Boolean) = upright(10f) {
    if (onPad) patch(tones, circle(42f))
    solid(tones, circle(36f), rounded(10f, 4f)) {
        val port = Offset(-3.6f, -12f)
        val ring = stadium(13.5f, 18.5f, port)
        drawPath(ring(0f), tones.detail.copy(alpha = 0.16f))
        groove(tones, ring(0f), width = 1.1f)
        groove(tones, ring(2.2f), width = 0.6f, depth = 0.45f)
        drawPath(stadium(4.2f, 10f, port)(0f), tones.ink)
        drawPath(stadium(1.6f, 6f, port + Offset(0.3f, 0.4f))(0f), tones.detail)
        drawCircle(tones.detail, 0.9f, port + Offset(8.6f, -0.6f))
    }
}

/**
 * G7: 27.5 x 24 mm, 4.6 thick, its plan traced from a top-down shot: an oval fuller on the right,
 * with a square cut in the middle of each side. A slim foot, then a wall rounding over into a flat
 * top; the round port sits left of centre on the long axis, the pinhole below it.
 */
private fun DrawScope.drawDexcom(tones: SensorTones, onPad: Boolean) = upright(12.4f) {
    if (onPad) patch(tones, superellipse(45f, 41f, 2.2f), at = Offset(1f, 1f))
    val scale = 37f
    val outline = polar { angle ->
        scale * (
            0.9319f - 0.0194f * cos(angle) + 0.0667f * cos(2f * angle) - 0.0235f * cos(3f * angle) -
                0.0015f * cos(4f * angle) + 0.0065f * cos(5f * angle) + 0.001f * cos(6f * angle)
            ) - cut(angle, 0f, 2.3f, 0.085f) - cut(angle, PI.toFloat(), 2.3f, 0.085f)
    }
    solid(tones.grey(), outline, footed(12.4f, lip = 3.2f, step = 1.3f, radius = 3.4f)) {
        hole(tones, Offset(-15.4f, 0f), 2.9f)
        drawRoundRect(
            tones.ink,
            topLeft = Offset(-15f, 9.6f),
            size = Size(1.8f, 3.2f),
            cornerRadius = CornerRadius(0.9f),
        )
    }
}

/**
 * GS1: traced from a top-down shot, an upright capsule on a slightly wider flange, its top softly
 * cushioned, with the round metal-keyed port a third of the way down.
 */
private fun DrawScope.drawSibionics(tones: SensorTones) = upright(9.4f) {
    solid(tones, stadium(45.5f, 73.5f), rounded(1.4f, 0.6f))
    solid(tones, stadium(42f, 70f), rounded(8f, 5.5f), base = 1.4f) {
        val port = Offset(0f, -11f)
        val radius = 5.2f
        drawCircle(tones.glint.copy(alpha = 0.6f), radius + 0.8f, port + Offset(0.3f, 0.5f))
        drawCircle(
            Brush.radialGradient(
                0f to tones.highlight,
                0.6f to lerp(tones.body, tones.wall, 0.4f),
                1f to tones.wall,
                center = port - Offset(1.6f, 1.8f),
                radius = radius * 1.3f,
            ),
            radius,
            port,
        )
        for (key in 0 until 4) {
            drawArc(
                color = tones.ink,
                startAngle = 20f + key * 90f,
                sweepAngle = 42f,
                useCenter = false,
                topLeft = port - Offset(radius - 1.1f, radius - 1.1f),
                size = Size((radius - 1.1f) * 2f, (radius - 1.1f) * 2f),
                style = Stroke(width = 1.9f),
            )
        }
        drawRect(tones.highlight, topLeft = port - Offset(1.2f, 1.2f), size = Size(2.4f, 2.4f))
        drawRect(tones.ink, topLeft = port - Offset(0.5f, 0.5f), size = Size(1f, 1f))
        drawCircle(tones.ink.copy(alpha = 0.75f), radius, port, style = Stroke(width = 0.6f))
    }
}

/**
 * AiDEX: 36 x 22 x 8 mm, the deepest here. A rounded-rectangle transmitter whose wall breaks into
 * a broad flat chamfer round the top, clipped flush into a base of the same plan whose wall
 * carries ribbed grips and an opening.
 */
private fun DrawScope.drawAiDex(tones: SensorTones, onPad: Boolean) = upright(15f) {
    if (onPad) patch(tones, roundRect(88f, 66f, 26f))
    val outline = roundRect(70f, 45f, 18f)
    val baseHeight = 6f
    solid(
        tones.grey().copy(highlight = tones.highlight, body = tones.body),
        outline,
        rounded(baseHeight, 0.4f),
        side = { wall ->
            val front = 22.5f * VIEW_SQUASH
            for (rib in 0 until 6) {
                val x = -17f + rib * 2.2f
                drawLine(tones.wallDeep, Offset(x, front + 0.5f), Offset(x, front + wall - 0.5f), strokeWidth = 0.9f)
                drawLine(tones.highlight, Offset(x + 0.8f, front + 0.5f), Offset(x + 0.8f, front + wall - 0.5f), strokeWidth = 0.5f)
            }
            drawRect(tones.ink.copy(alpha = 0.7f), topLeft = Offset(4f, front + 0.9f), size = Size(7f, wall - 1.8f))
        },
    )
    solid(tones, outline, chamfered(9f, wall = 2.6f, inset = 7.5f), base = baseHeight)
}

/** M8: a 24 mm coin 3.5 mm thin, a small square button with a slit just right of centre. */
private fun DrawScope.drawOttai(tones: SensorTones, onPad: Boolean) = upright(10.8f) {
    if (onPad) patch(tones, circle(47f), at = Offset(3f, 4f))
    solid(tones, circle(36.5f), rounded(10.8f, 3.8f)) {
        val button = Offset(6.5f, -5f)
        val frame = roundRect(12.5f, 12.5f, 3.8f, button)
        drawPath(frame(0f), tones.detail.copy(alpha = 0.12f))
        groove(tones, frame(0f), width = 1f)
        groove(tones, frame(2f), width = 0.6f, depth = 0.4f)
        drawRoundRect(
            tones.ink,
            topLeft = button - Offset(0.6f, 1.9f),
            size = Size(1.2f, 3.8f),
            cornerRadius = CornerRadius(0.6f),
        )
    }
}

/**
 * i3: a thick white puck on a dark base ring. A teardrop-shaped recess holds a square window in
 * its broad end and three small contacts towards its tip; small slots break the rim.
 */
private fun DrawScope.drawICan(tones: SensorTones, onPad: Boolean) = upright(11f) {
    if (onPad) patch(tones, circle(45f))
    solid(
        tones,
        circle(37f),
        rounded(11f, 2.6f),
        band = 2.4f,
        side = { wall ->
            val front = 37f * VIEW_SQUASH
            drawRoundRect(
                tones.ink.copy(alpha = 0.55f),
                topLeft = Offset(-3f, front + wall * 0.25f),
                size = Size(6f, 1.3f),
                cornerRadius = CornerRadius(0.65f),
            )
        },
    ) {
        val recess = capsule(Offset(-13.5f, -20.5f), 11f, Offset(3f, -4.5f), 8f)
        drawPath(recess, tones.detail.copy(alpha = 0.14f))
        drawPath(recess, tones.ink.copy(alpha = 0.6f), style = Stroke(width = 1.5f))
        translate(0.3f, 0.6f) {
            drawPath(recess, tones.glint.copy(alpha = 0.45f), style = Stroke(width = 0.6f))
        }
        rotate(10f, pivot = Offset(-15.5f, -21f)) {
            window(tones, Offset(-15.5f, -21f), 6f, filled = false)
        }
        window(tones, Offset(-0.5f, -10.5f), 2.8f, filled = true)
        window(tones, Offset(-5.2f, -5.4f), 2.8f, filled = true)
        window(tones, Offset(0.6f, -4.2f), 2.6f, filled = false)
        drawLine(tones.detail, Offset(34f, 2f), Offset(34f, 6.6f), strokeWidth = 0.9f, cap = StrokeCap.Round)
        drawLine(tones.detail, Offset(-1.6f, -34f), Offset(1.6f, -34f), strokeWidth = 0.8f, cap = StrokeCap.Round)
    }
}

/** Anytime CT: a plain egg-shaped pebble, domed all over, the narrow end up. */
private fun DrawScope.drawAnytime(tones: SensorTones, onPad: Boolean) = upright(10f) {
    if (onPad) patch(tones, egg(34f, 45f, 0.06f))
    solid(tones, egg(26f, 36.5f, 0.08f), domed(11f, wall = 3f, inset = 9f), dome = true)
}

/**
 * SmartGuide sensor: a 33 mm disc 6 mm thick, a light grey cover with a rounded edge over a clear
 * base ring. The filament port sits just left of centre in a metal collar, a small light near the
 * rim, and a square cut in the rim to the upper right.
 */
private fun DrawScope.drawAccuChek(tones: SensorTones, onPad: Boolean) = upright(13f) {
    if (onPad) patch(tones, circle(45f))
    val baseHeight = 4.2f
    solid(tones.clear(), circle(36f), rounded(baseHeight, 1.2f))
    val cover = polar { angle -> 34.6f - cut(angle, -0.9f, 1.8f, 0.07f) }
    val coverTones = tones.grey().copy(highlight = tones.highlight, body = lerp(tones.body, tones.shade, 0.3f))
    solid(coverTones, cover, rounded(8.8f, 4.5f), base = baseHeight) {
        val port = Offset(-4f, 1f)
        drawCircle(
            Brush.radialGradient(
                0f to tones.highlight,
                1f to tones.wall,
                center = port - Offset(1.2f, 1.4f),
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

/** CareSens Air: a grey cushioned capsule on a thin clear base; a ribbed dial with a button. */
private fun DrawScope.drawCareSensAir(tones: SensorTones, onPad: Boolean) = upright(9f) {
    if (onPad) patch(tones, roundRect(86f, 58f, 22f))
    val slate = tones.grey().grey()
    solid(tones.clear(), roundRect(70f, 42f, 19f), rounded(1.4f, 0.5f))
    solid(slate, roundRect(66f, 38f, 17f), rounded(7.6f, 5.5f), base = 1.4f) {
        val dial = Offset(14f, 0f)
        val disc = Path().apply { addOval(Rect(dial.x - 11.5f, -11.5f, dial.x + 11.5f, 11.5f)) }
        drawPath(disc, lerp(slate.body, slate.wall, 0.35f))
        clipPath(disc) {
            var y = -11.5f
            while (y < 11.5f) {
                drawLine(slate.wall.copy(alpha = 0.75f), Offset(dial.x - 12f, y), Offset(dial.x + 12f, y), strokeWidth = 0.7f)
                y += 1.9f
            }
        }
        drawPath(disc, slate.wall, style = Stroke(width = 0.8f))
        drawCircle(
            Brush.radialGradient(
                0f to tones.highlight,
                1f to slate.shade,
                center = dial - Offset(1.4f, 1.6f),
                radius = 6.5f,
            ),
            5f,
            dial,
        )
        drawCircle(slate.wall, 5f, dial, style = Stroke(width = 0.6f))
        drawCircle(tones.ink, 0.9f, Offset(-10f, -6f))
    }
}

/** MQ: a thick cushion-square transmitter, its whole top rounded over, on a round patch. */
private fun DrawScope.drawMq(tones: SensorTones, onPad: Boolean) = upright(13f) {
    if (onPad) patch(tones, circle(47f))
    solid(tones, superellipse(36f, 30.5f, 3f), domed(10.5f, wall = 3.5f, inset = 9f), dome = true)
}

/**
 * TouchCare Nano: traced from a top-down shot, a slim rounded slab clipped on a clear base, the
 * base's round tab showing past one end.
 */
private fun DrawScope.drawMedtrum(tones: SensorTones, onPad: Boolean) = upright(6.6f) {
    if (onPad) patch(tones, roundRect(62f, 86f, 22f))
    val baseOutline: Outline = { inset ->
        Path().apply {
            op(roundRect(48f, 74f, 12f)(inset), circle(8.5f, Offset(0f, -36f))(inset), PathOperation.Union)
        }
    }
    solid(tones.clear(), baseOutline, rounded(2.2f, 0.8f))
    solid(tones, roundRect(45f, 71.5f, 11f), rounded(4.4f, 3.4f), base = 2.2f)
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
