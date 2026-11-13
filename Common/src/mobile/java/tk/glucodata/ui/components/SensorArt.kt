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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The tones one sensor is drawn in, all taken from the theme, so every drawing follows the
 * wallpaper palette and needs no colour of its own. The fixed colour roles would be the natural
 * fit, but the pre-API-31 fallback schemes do not set them; these roles exist in every scheme.
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
    /** A darker casing, for the sensors that are grey rather than white. */
    fun slate(): SensorTones = copy(
        highlight = lerp(body, wall, 0.45f),
        body = lerp(shade, wall, 0.55f),
        shade = lerp(wall, wallDeep, 0.35f),
        wall = lerp(wall, wallDeep, 0.6f),
        detail = lerp(wall, wallDeep, 0.7f),
        glint = glint.copy(alpha = 0.55f),
    )

    /** A clear, faintly tinted plastic, for the bases some transmitters clip on to. */
    fun clear(): SensorTones = copy(
        highlight = lerp(highlight, accent, 0.25f),
        body = lerp(body, accent, 0.35f),
        shade = lerp(shade, accent, 0.45f),
        wall = lerp(wall, accent, 0.4f),
        wallDeep = lerp(wallDeep, accent, 0.3f),
    )
}

@Composable
internal fun rememberSensorTones(): SensorTones {
    val scheme = MaterialTheme.colorScheme
    val dark = isAppInDarkTheme()
    return remember(scheme, dark) {
        if (dark) {
            SensorTones(
                highlight = lerp(scheme.onPrimaryContainer, Color.White, 0.7f),
                body = lerp(scheme.onPrimaryContainer, Color.White, 0.25f),
                shade = lerp(scheme.onPrimaryContainer, scheme.primary, 0.7f),
                wall = lerp(scheme.primary, scheme.primaryContainer, 0.45f),
                wallDeep = lerp(scheme.primaryContainer, scheme.surfaceContainerLowest, 0.35f),
                ink = scheme.onPrimary,
                detail = lerp(scheme.primary, scheme.primaryContainer, 0.3f),
                accent = scheme.tertiaryContainer,
                cap = lerp(scheme.tertiary, scheme.tertiaryContainer, 0.45f),
                glint = Color.White,
                shadow = scheme.surfaceContainerLowest.copy(alpha = 0.13f),
            )
        } else {
            SensorTones(
                highlight = Color.White,
                body = lerp(Color.White, scheme.primaryContainer, 0.2f),
                shade = lerp(Color.White, scheme.primaryContainer, 0.85f),
                wall = lerp(scheme.primaryContainer, scheme.primary, 0.3f),
                wallDeep = lerp(scheme.primaryContainer, scheme.primary, 0.6f),
                ink = scheme.onPrimaryContainer,
                detail = lerp(scheme.primaryContainer, scheme.primary, 0.45f),
                accent = scheme.tertiary,
                cap = scheme.tertiary,
                glint = Color.White,
                shadow = scheme.onSurface.copy(alpha = 0.045f),
            )
        }
    }
}

/**
 * One sensor family, drawn from its maker's product photos: the true outline and proportions,
 * the thickness against the width, and the ports, windows and notches where they really are,
 * with no maker's name on them. Drawn from code rather than shipped as bitmaps, so they follow
 * the theme and stay sharp at any size; the same drawing serves a list tile and a wizard hero.
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
            SensorType.ACCUCHEK -> drawAccuChekApplicator(tones)
            SensorType.CARESENS_AIR -> drawCareSensAir(tones, onPad)
            SensorType.MQ -> drawMq(tones, onPad)
            SensorType.MEDTRUM -> drawMedtrum(tones, onPad)
        }
    }
}

/** The CareSens Air applicator, for the wizard hero. */
internal fun DrawScope.drawCareSensAirApplicator(tones: SensorTones) = inUnitSquare {
    drawCareSensApplicatorBody(tones)
}

/** A phone held to a sensor, for the NFC hero. */
internal fun DrawScope.drawNfcPhone(tones: SensorTones) = inUnitSquare {
    drawPhoneBody(tones)
}

// ---- The view ----
//
// One camera for every sensor: seen from about 53 degrees up, so a top face is foreshortened to
// four fifths of its depth and a side wall shows at three fifths of its height. Light comes from
// the upper left. Sizes are in drawing units with the longest side of each sensor near 72, and
// every thickness is that sensor's real thickness against its real width.

private const val VIEW_SQUASH = 0.8f
private const val WALL_SHOW = 0.6f

/** An outline in top view, shrunk evenly by its argument; insets give fillets and grooves. */
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

/** A superellipse: [n] = 2 is an ellipse, higher squares it off while keeping it soft. */
private fun superellipse(a: Float, b: Float, n: Float, at: Offset = Offset.Zero): Outline = { inset ->
    val ra = max(a - inset, 0.1f)
    val rb = max(b - inset, 0.1f)
    Path().apply {
        val steps = 144
        for (step in 0 until steps) {
            val t = step / steps.toFloat() * 2f * PI.toFloat()
            val c = cos(t)
            val s = sin(t)
            val x = at.x + ra * sign(c) * abs(c).pow(2f / n)
            val y = at.y + rb * sign(s) * abs(s).pow(2f / n)
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
private fun polarPath(steps: Int, radius: (Float) -> Float) = Path().apply {
    for (step in 0 until steps) {
        val angle = step / steps.toFloat() * 2f * PI.toFloat()
        val r = radius(angle)
        val x = cos(angle) * r
        val y = sin(angle) * r
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
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

/** Centres an object [thickness] tall in the square, so tall and flat sensors sit alike. */
private inline fun DrawScope.upright(thickness: Float, block: DrawScope.() -> Unit) {
    translate(0f, thickness * WALL_SHOW / 2f, block)
}

/** The adhesive patch, flat on the skin under the sensor. */
private fun DrawScope.patch(tones: SensorTones, outline: Outline, at: Offset = Offset.Zero) {
    val lean = leanOf(0f)
    val flat = outline(0f).leaned(lean)
    translate(at.x, at.y * VIEW_SQUASH) {
        drawPath(flat, tones.highlight.copy(alpha = 0.2f))
        drawPath(outline(1.6f).leaned(lean), tones.highlight.copy(alpha = 0.08f))
        drawPath(flat, tones.highlight.copy(alpha = 0.3f), style = Stroke(width = 0.7f))
    }
}

/**
 * Raises [outline] into a solid of [thickness], standing [base] above the skin: a soft contact
 * shadow, the side wall lit from the left, a rounded edge [fillet] wide that catches the light at
 * the upper left and falls away at the lower right, then the top face, flat or [dome]d.
 *
 * [band] darkens that share of the wall from the bottom, for a base of another material.
 * [side] draws on the wall, with y = 0 on the top face's plane; [face] draws on the top face in
 * the outline's own coordinates, so it leans and turns with the object.
 */
private fun DrawScope.solid(
    tones: SensorTones,
    outline: Outline,
    thickness: Float,
    fillet: Float,
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
    val wall = thickness * WALL_SHOW
    val floor = -base * WALL_SHOW
    val roof = floor - wall

    if (base == 0f) {
        // Contact shadow: stacked passes stand in for a blur, which a Canvas path cannot have.
        for (pass in 6 downTo 1) {
            translate(pass * 0.3f, floor + pass * 0.75f) { drawPath(foot, tones.shadow) }
        }
    }

    val wallBrush = Brush.horizontalGradient(
        0f to tones.shade,
        0.35f to tones.wall,
        1f to tones.wallDeep,
        startX = bounds.left,
        endX = bounds.right,
    )
    val bandBrush = Brush.horizontalGradient(
        0f to lerp(tones.wall, tones.ink, 0.35f),
        1f to lerp(tones.wallDeep, tones.ink, 0.5f),
        startX = bounds.left,
        endX = bounds.right,
    )
    val layers = max((wall / 0.4f).toInt(), 1)
    for (layer in layers downTo 0) {
        val drop = wall * layer / layers
        translate(0f, roof + drop) {
            drawPath(foot, if (band > 0f && drop > wall * (1f - band)) bandBrush else wallBrush)
        }
    }
    translate(0f, floor) {
        drawPath(foot, tones.wallDeep.copy(alpha = 0.5f), style = Stroke(width = 0.6f))
    }

    translate(0f, roof) {
        side(wall)
        val lit = Offset(bounds.left, bounds.top)
        val dim = Offset(bounds.right, bounds.bottom)
        drawPath(
            foot,
            Brush.linearGradient(
                0f to tones.glint,
                0.4f to tones.body,
                1f to tones.wall,
                start = lit,
                end = dim,
            ),
        )
        val faceBrush = if (dome) {
            Brush.radialGradient(
                0f to tones.highlight,
                0.55f to tones.body,
                1f to lerp(tones.shade, tones.wall, 0.35f),
                center = bounds.center + Offset(-bounds.width * 0.18f, -bounds.height * 0.22f),
                radius = max(bounds.width, bounds.height) * 0.75f,
            )
        } else {
            Brush.linearGradient(
                0f to tones.highlight,
                0.55f to tones.body,
                1f to lerp(tones.body, tones.shade, 0.6f),
                start = lit,
                end = dim,
            )
        }
        // The edge rolls into the face: thin veils of the face, each a little further in, so the
        // fillet reads as a curve rather than a rim stepped up from a tray.
        val rolls = 7
        for (roll in 1 until rolls) {
            drawPath(outline(fillet * roll / rolls).leaned(lean), faceBrush, alpha = 0.3f)
        }
        val top = outline(fillet).leaned(lean)
        drawPath(top, faceBrush)
        if (dome) {
            val gloss = Rect(
                bounds.center + Offset(-bounds.width * 0.3f, -bounds.height * 0.32f),
                Size(bounds.width * 0.3f, bounds.height * 0.2f),
            )
            drawOval(
                Brush.radialGradient(
                    0f to tones.glint.copy(alpha = 0.75f),
                    1f to tones.glint.copy(alpha = 0f),
                    center = gloss.center,
                    radius = gloss.width / 2f,
                ),
                topLeft = gloss.topLeft,
                size = gloss.size,
            )
        }
        clipPath(foot) { withTransform({ transform(lean) }, face) }
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
    solid(tones, circle(36f), thickness = 10f, fillet = 3.4f) {
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
 * G7: 27.5 x 24 mm and 4.6 thick, a soft rounded square in plan with three square cuts in the rim;
 * a port and a pinhole near one corner.
 */
private fun DrawScope.drawDexcom(tones: SensorTones, onPad: Boolean) = upright(12.4f) {
    if (onPad) patch(tones, superellipse(44f, 39f, 2.3f))
    val a = 37f
    val b = 32.3f
    val n = 2.15f
    val outline: Outline = { inset ->
        polarPath(steps = 360) { angle ->
            val c = abs(cos(angle)) / a
            val s = abs(sin(angle)) / b
            (c.pow(n) + s.pow(n)).pow(-1f / n) - inset -
                cut(angle, 3.49f, 1.5f, 0.05f) -
                cut(angle, 0.52f, 1.5f, 0.05f) -
                cut(angle, 1.38f, 1.5f, 0.06f)
        }
    }
    solid(tones, outline, thickness = 12.4f, fillet = 2.8f) {
        hole(tones, Offset(-14.8f, -11.3f), 2.9f)
        rotate(-25f, pivot = Offset(-18f, -2.6f)) {
            drawOval(tones.ink, topLeft = Offset(-18.7f, -4.2f), size = Size(1.4f, 3.2f))
        }
    }
}

/**
 * GS1: an upright capsule on a slightly wider flange, its top softly cushioned, with the round
 * metal-keyed port a third of the way down.
 */
private fun DrawScope.drawSibionics(tones: SensorTones) = upright(9.4f) {
    solid(tones, stadium(45.5f, 73.5f), thickness = 1.4f, fillet = 0.7f)
    solid(tones, stadium(42f, 70f), thickness = 8f, fillet = 5.5f, base = 1.4f) {
        val port = Offset(0f, -11f)
        val radius = 5.4f
        drawCircle(tones.glint.copy(alpha = 0.6f), radius + 0.8f, port + Offset(0.3f, 0.5f))
        drawCircle(
            Brush.radialGradient(
                0f to tones.highlight,
                0.6f to lerp(tones.body, tones.wall, 0.35f),
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
 * AiDEX: 36 x 22 x 8 mm, the deepest here. A rounded-rectangle transmitter with a broad chamfer
 * round its face, clipped into a base whose sides are ribbed grips.
 */
private fun DrawScope.drawAiDex(tones: SensorTones, onPad: Boolean) = upright(15f) {
    if (onPad) patch(tones, roundRect(90f, 66f, 26f))
    val baseHeight = 6.5f
    solid(
        tones,
        roundRect(72f, 50f, 19f),
        thickness = baseHeight,
        fillet = 1f,
        side = { wall ->
            val front = 25f * VIEW_SQUASH
            for (rib in 0 until 4) {
                val x = -15f + rib * 2.6f
                drawLine(tones.wallDeep, Offset(x, front + 0.4f), Offset(x, front + wall - 0.4f), strokeWidth = 1f)
                drawLine(tones.shade, Offset(x + 0.9f, front + 0.4f), Offset(x + 0.9f, front + wall - 0.4f), strokeWidth = 0.6f)
            }
            drawRect(tones.ink.copy(alpha = 0.75f), topLeft = Offset(5f, front + 0.6f), size = Size(6f, wall - 1.4f))
        },
    )
    solid(tones, roundRect(68f, 45f, 17f), thickness = 8.5f, fillet = 7.5f, base = baseHeight)
}

/** M8: a 24 mm coin 3.5 mm thin, a small square button with a slit just right of centre. */
private fun DrawScope.drawOttai(tones: SensorTones, onPad: Boolean) = upright(10.8f) {
    if (onPad) patch(tones, circle(47f), at = Offset(3f, 4f))
    solid(tones, circle(36.5f), thickness = 10.8f, fillet = 3f) {
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
        thickness = 11f,
        fillet = 2.4f,
        band = 0.24f,
        side = { wall ->
            val front = 37f * VIEW_SQUASH
            drawRoundRect(
                tones.ink.copy(alpha = 0.55f),
                topLeft = Offset(-3f, front + wall * 0.22f),
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
        drawLine(tones.detail, Offset(35f, 2f), Offset(35f, 6.6f), strokeWidth = 0.9f, cap = StrokeCap.Round)
        drawLine(tones.detail, Offset(-1.6f, -35.4f), Offset(1.6f, -35.4f), strokeWidth = 0.8f, cap = StrokeCap.Round)
    }
}

/** Anytime CT: a plain egg-shaped pebble, domed all over, the narrow end up. */
private fun DrawScope.drawAnytime(tones: SensorTones, onPad: Boolean) = upright(9.5f) {
    if (onPad) patch(tones, egg(36f, 45f, 0.06f))
    solid(tones, egg(28.5f, 36.5f, 0.08f), thickness = 9.5f, fillet = 10f, dome = true)
}

/**
 * SmartGuide is known by its applicator rather than the small sensor inside it: a white barrel
 * with rounded shoulders on a petrol twist cap ribbed at its foot, and a pull tab in a long slot
 * across the joint. Seen from a little above, so its ends are shallow ellipses.
 */
private fun DrawScope.drawAccuChekApplicator(tones: SensorTones) {
    val half = 25f
    val rise = 5.5f
    val top = -38f
    val shoulder = 10f
    val joint = 2f
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
    val capDeep = lerp(tones.cap, tones.ink, 0.5f)
    val capLit = lerp(tones.cap, tones.highlight, 0.25f)
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
        for (rib in 1 until 22) {
            val angle = rib / 22f * PI.toFloat()
            val x = -half * cos(angle)
            val curve = rise * sin(angle)
            drawLine(capDeep.copy(alpha = 0.55f), Offset(x, bottom - 12f + curve), Offset(x, bottom + curve), strokeWidth = 0.7f)
        }
    }
    // The barrel: straight sides into rounded shoulders and a flat top.
    val barrel = Path().apply {
        moveTo(-half, joint)
        lineTo(-half, top + shoulder)
        arcTo(Rect(-half, top - rise, half, top + shoulder * 2f + rise), 180f, 180f, forceMoveTo = false)
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
    clipPath(barrel) {
        val lid = Rect(-half + shoulder * 0.8f, top - rise * 0.55f, half - shoulder * 0.8f, top + rise * 0.95f)
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
        drawRect(
            Brush.verticalGradient(
                0f to tones.glint.copy(alpha = 0.55f),
                1f to tones.glint.copy(alpha = 0f),
                startY = top - rise,
                endY = top + shoulder,
            ),
            topLeft = Offset(-half, top - rise),
            size = Size(half * 2f, shoulder + rise),
        )
    }
    // Slot, pull tab and the mark on it.
    val slot = RoundRect(Rect(7.5f, -17f, 20f, 30f), CornerRadius(6.2f))
    val slotPath = Path().apply { addRoundRect(slot) }
    drawPath(slotPath, tones.detail.copy(alpha = 0.55f))
    val tab = Path().apply { addRoundRect(RoundRect(Rect(9.5f, -14.5f, 18f, 27.5f), CornerRadius(4.2f))) }
    clipPath(tab) {
        drawRect(tones.body, topLeft = Offset(9f, -15f), size = Size(10f, 18f))
        drawRect(capDeep, topLeft = Offset(9f, 2.5f), size = Size(10f, 26f))
    }
    drawPath(tab, tones.glint.copy(alpha = 0.4f), style = Stroke(width = 0.6f))
    drawCircle(tones.cap, 2.2f, Offset(13.75f, -8.5f))
}

/** CareSens Air: a grey cushioned capsule; a ribbed round dial with a centre button at one end. */
private fun DrawScope.drawCareSensAir(tones: SensorTones, onPad: Boolean) = upright(8f) {
    if (onPad) patch(tones, roundRect(86f, 58f, 22f))
    val slate = tones.slate()
    solid(slate, stadium(68f, 38f), thickness = 8f, fillet = 6f) {
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

/** MQ: a cushion-square transmitter, domed and thick, worn on a round patch. */
private fun DrawScope.drawMq(tones: SensorTones, onPad: Boolean) = upright(12f) {
    if (onPad) patch(tones, circle(47f))
    solid(tones, superellipse(35f, 32f, 3.4f), thickness = 12f, fillet = 10f, dome = true)
}

/**
 * TouchCare Nano: a slim rounded slab clipped on a clear base, the base's round tab showing past
 * one end.
 */
private fun DrawScope.drawMedtrum(tones: SensorTones, onPad: Boolean) = upright(6.6f) {
    if (onPad) patch(tones, roundRect(62f, 86f, 22f))
    val baseOutline: Outline = { inset ->
        Path().apply {
            op(roundRect(50f, 74f, 15f)(inset), circle(8.5f, Offset(0f, -36f))(inset), PathOperation.Union)
        }
    }
    solid(tones.clear(), baseOutline, thickness = 2.2f, fillet = 0.8f)
    solid(tones, roundRect(46f, 70f, 13f), thickness = 4.4f, fillet = 5f, base = 2.2f)
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
