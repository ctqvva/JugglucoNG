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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
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
 * One sensor family as a small sculpted object. The drawings are stylised, not photographs:
 * the outline and the features a user recognises the sensor by, with no maker's name on them.
 * Drawn from code rather than shipped as bitmaps, so they follow the theme and stay sharp at
 * any size; the same drawing serves a list tile and a full-width wizard hero.
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
 * [onPad] seats the sensors that are seen on an adhesive pad on it. Off for a tile: the shape
 * behind the sensor is already doing that job, and two outlines there read as clutter.
 */
internal fun DrawScope.drawSensor(type: SensorType, tones: SensorTones, onPad: Boolean = false) {
    inUnitSquare {
        when (type) {
            SensorType.LIBRE -> drawLibre(tones, onPad)
            SensorType.DEXCOM -> drawDexcom(tones)
            SensorType.SIBIONICS -> drawSibionics(tones)
            SensorType.AIDEX -> drawAiDex(tones)
            SensorType.OTTAI -> drawOttai(tones)
            SensorType.ICANHEALTH -> drawICan(tones)
            SensorType.ANYTIME -> drawAnytime(tones)
            SensorType.ACCUCHEK -> drawAccuChekApplicator(tones)
            SensorType.CARESENS_AIR -> drawCareSensAir(tones, onPad)
            SensorType.MQ -> drawMq(tones)
            SensorType.MEDTRUM -> drawMedtrum(tones)
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

// ---- The sculpting ----

/** How much the object leans away: a circle seen from a little above is this much as tall. */
private const val VIEW_SQUASH = 0.86f

private fun circlePath(radius: Float) = Path().apply {
    addOval(Rect(-radius, -radius, radius, radius))
}

private fun stadiumPath(width: Float, height: Float, radius: Float = min(width, height) / 2f) = Path().apply {
    addRoundRect(
        RoundRect(
            Rect(-width / 2f, -height / 2f, width / 2f, height / 2f),
            CornerRadius(radius),
        ),
    )
}

/** A closed outline from a radius per angle (radians, 0 = +x, clockwise on screen). */
private fun polarPath(steps: Int = 96, radius: (Float) -> Float) = Path().apply {
    for (step in 0 until steps) {
        val angle = step / steps.toFloat() * 2f * PI.toFloat()
        val r = radius(angle)
        val x = cos(angle) * r
        val y = sin(angle) * r
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A smooth dent of [depth] centred on [at] radians, [width] radians wide. */
private fun notch(angle: Float, at: Float, depth: Float, width: Float): Float {
    var delta = angle - at
    while (delta > PI) delta -= 2f * PI.toFloat()
    while (delta < -PI) delta += 2f * PI.toFloat()
    return depth * exp(-(delta * delta) / (2f * width * width))
}

/**
 * Raises [outline] into a solid: a soft contact shadow, the side wall, then a lit top face with
 * a bright upper-left rim and a dark lower-right one. [face] draws on the top surface in the
 * outline's own coordinates, so it leans and turns with the object.
 */
private fun DrawScope.solid(
    tones: SensorTones,
    outline: Path,
    rotation: Float,
    depth: Float,
    squash: Float = VIEW_SQUASH,
    face: DrawScope.() -> Unit = {},
) {
    val lean = Matrix().apply {
        scale(1f, squash)
        rotateZ(rotation)
    }
    val top = Path().apply {
        addPath(outline)
        transform(lean)
    }
    val lift = -depth / 2f
    translate(0f, lift) {
        // Contact shadow: stacked passes stand in for a blur, which a Canvas path cannot have.
        for (pass in 6 downTo 1) {
            translate(pass * 0.45f, depth + pass * 0.85f) {
                drawPath(top, tones.shadow)
            }
        }
        val wallBrush = Brush.horizontalGradient(
            0f to tones.shade,
            0.4f to tones.wall,
            1f to tones.wallDeep,
            startX = -44f,
            endX = 44f,
        )
        var step = depth
        while (step > 0f) {
            translate(0f, step) { drawPath(top, wallBrush) }
            step -= 0.5f
        }
        drawPath(
            top,
            Brush.linearGradient(
                0f to tones.highlight,
                0.5f to tones.body,
                1f to tones.shade,
                start = Offset(-30f, -34f),
                end = Offset(30f, 34f),
            ),
        )
        clipPath(top) {
            drawPath(
                top,
                Brush.linearGradient(
                    0f to tones.glint.copy(alpha = 0.95f),
                    0.42f to tones.glint.copy(alpha = 0f),
                    start = Offset(-30f, -34f),
                    end = Offset(30f, 34f),
                ),
                style = Stroke(width = 2.6f),
            )
            drawPath(
                top,
                Brush.linearGradient(
                    0.55f to tones.wall.copy(alpha = 0f),
                    1f to tones.wall.copy(alpha = 0.7f),
                    start = Offset(-30f, -34f),
                    end = Offset(30f, 34f),
                ),
                style = Stroke(width = 3.2f),
            )
            withTransform({ transform(lean) }, face)
        }
    }
}

/** A recess in the face: a soft collar, a dark well, and a lit lower lip. */
private fun DrawScope.well(tones: SensorTones, center: Offset, radius: Float, collar: Float = 1.7f) {
    if (collar > 1f) {
        drawCircle(tones.detail.copy(alpha = 0.32f), radius * collar, center)
    }
    drawCircle(
        Brush.radialGradient(
            0f to lerp(tones.ink, tones.detail, 0.45f),
            1f to tones.ink,
            center = center + Offset(radius * 0.25f, radius * 0.35f),
            radius = radius,
        ),
        radius,
        center,
    )
    drawArc(
        color = tones.glint.copy(alpha = 0.7f),
        startAngle = 25f,
        sweepAngle = 110f,
        useCenter = false,
        topLeft = center - Offset(radius, radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = radius * 0.2f, cap = StrokeCap.Round),
    )
}

/** A groove cut into the face: a dark line with a lit edge just below it. */
private fun DrawScope.groove(tones: SensorTones, path: Path, width: Float = 1.1f) {
    translate(0.3f, 0.6f) {
        drawPath(path, tones.glint.copy(alpha = 0.6f), style = Stroke(width = width, cap = StrokeCap.Round))
    }
    drawPath(path, tones.detail.copy(alpha = 0.8f), style = Stroke(width = width, cap = StrokeCap.Round))
}

/** The adhesive pad some sensors are seen sitting on. */
private fun DrawScope.pad(tones: SensorTones, outline: Path, rotation: Float) {
    val lean = Matrix().apply {
        scale(1f, VIEW_SQUASH)
        rotateZ(rotation)
    }
    val flat = Path().apply {
        addPath(outline)
        transform(lean)
    }
    drawPath(flat, tones.highlight.copy(alpha = 0.22f))
    drawPath(flat, tones.highlight.copy(alpha = 0.22f), style = Stroke(width = 0.8f))
}

// ---- The sensors ----

/** Libre 3: a plain white disc, its one feature the ringed filament port near the edge. */
private fun DrawScope.drawLibre(tones: SensorTones, onPad: Boolean) {
    if (onPad) pad(tones, circlePath(44f), rotation = 0f)
    solid(tones, circlePath(33f), rotation = 0f, depth = 6f) {
        val port = Offset(-13f, 13f)
        drawCircle(tones.detail, 10.5f, port, style = Stroke(width = 2.2f))
        rotate(-35f, pivot = port) {
            drawOval(
                tones.ink,
                topLeft = port - Offset(6.4f, 4.2f),
                size = Size(12.8f, 8.4f),
            )
            drawOval(
                tones.accent.copy(alpha = 0.75f),
                topLeft = port - Offset(4f, 2.2f),
                size = Size(8f, 4.4f),
            )
        }
    }
}

/** G7: a soft three-cornered pebble, small cuts in its rim, a port and a pinhole light. */
private fun DrawScope.drawDexcom(tones: SensorTones) {
    val outline = polarPath(steps = 360) { angle ->
        36f + 2.6f * cos(3f * (angle + 0.5f)) -
            notch(angle, 0.62f, 2.8f, 0.032f) -
            notch(angle, 2.72f, 2.8f, 0.032f)
    }
    solid(tones, outline, rotation = -14f, depth = 7f) {
        well(tones, Offset(-7f, -13f), 5.8f)
        drawOval(
            tones.ink,
            topLeft = Offset(-15.2f, -2.4f),
            size = Size(2.6f, 4f),
        )
    }
}

/** GS1: a long white capsule with a keyed port in the middle. */
private fun DrawScope.drawSibionics(tones: SensorTones) {
    solid(tones, stadiumPath(46f, 76f, radius = 22f), rotation = 24f, depth = 6.5f) {
        groove(tones, stadiumPath(38f, 68f, radius = 18f), width = 0.8f)
        well(tones, Offset(0f, -1f), 8.4f, collar = 1.5f)
        val key = Path().apply {
            addArcOf(radius = 4.6f, from = 60f, sweep = 240f)
        }
        translate(0f, -1f) {
            drawPath(key, tones.highlight, style = Stroke(width = 2.6f, cap = StrokeCap.Round))
        }
    }
}

private fun Path.addArcOf(radius: Float, from: Float, sweep: Float) {
    arcTo(Rect(-radius, -radius, radius, radius), from, sweep, forceMoveTo = true)
}

/** AiDEX X: a flat disc with a ringed upright slot. */
private fun DrawScope.drawAiDex(tones: SensorTones) {
    solid(tones, circlePath(36f), rotation = 0f, depth = 5.5f) {
        val center = Offset(-10f, 13f)
        drawCircle(tones.detail.copy(alpha = 0.3f), 13f, center)
        drawCircle(tones.detail, 13f, center, style = Stroke(width = 1.4f))
        rotate(14f, pivot = center) {
            drawRoundRect(
                tones.ink,
                topLeft = center - Offset(4.6f, 9f),
                size = Size(9.2f, 18f),
                cornerRadius = CornerRadius(3.4f),
            )
            drawRoundRect(
                tones.accent.copy(alpha = 0.8f),
                topLeft = center - Offset(2.6f, 6.6f),
                size = Size(5.2f, 13.2f),
                cornerRadius = CornerRadius(2f),
            )
        }
    }
}

/** M8: a coin-sized disc, the smallest here, with a raised centre boss. */
private fun DrawScope.drawOttai(tones: SensorTones) {
    solid(tones, circlePath(30f), rotation = 0f, depth = 4f) {
        groove(tones, circlePath(24f), width = 0.8f)
        groove(tones, circlePath(12f), width = 0.9f)
        well(tones, Offset.Zero, 3.6f, collar = 1f)
    }
}

/** i3: a thick, domed disc: the deepest wall of the round sensors. */
private fun DrawScope.drawICan(tones: SensorTones) {
    solid(tones, circlePath(35f), rotation = 0f, depth = 10f) {
        drawCircle(
            Brush.radialGradient(
                0f to tones.glint.copy(alpha = 0.55f),
                1f to tones.glint.copy(alpha = 0f),
                center = Offset(-8f, -10f),
                radius = 26f,
            ),
            26f,
            Offset(-8f, -10f),
        )
        groove(tones, circlePath(27f), width = 1f)
        well(tones, Offset(0f, 11f), 4.4f, collar = 1.5f)
    }
}

/** Anytime CT: a disc crossed by a clear smile-shaped channel, a lens in the notch above it. */
private fun DrawScope.drawAnytime(tones: SensorTones) {
    solid(tones, circlePath(36f), rotation = 0f, depth = 6f) {
        val smile = Path().apply {
            moveTo(-30f, -13f)
            cubicTo(-22f, -10f, -14f, 3f, 0f, 2f)
            cubicTo(14f, 3f, 22f, -10f, 30f, -13f)
        }
        drawPath(smile, tones.accent.copy(alpha = 0.35f), style = Stroke(width = 4.4f, cap = StrokeCap.Round))
        groove(tones, smile, width = 1.1f)
        val rim = Path().apply {
            addArcOf(radius = 30.5f, from = -8f, sweep = 196f)
        }
        groove(tones, rim, width = 0.9f)
        well(tones, Offset(0f, -13.5f), 3.8f, collar = 1.8f)
        drawOval(tones.detail.copy(alpha = 0.5f), topLeft = Offset(-12.6f, -16f), size = Size(4f, 5.4f))
        drawOval(tones.detail.copy(alpha = 0.5f), topLeft = Offset(8.6f, -16f), size = Size(4f, 5.4f))
    }
}

/**
 * SmartGuide is recognised by its applicator rather than the small sensor inside it: a white
 * barrel on a ribbed twist cap, a long slot running down across both. Seen from the same height
 * as the flat sensors, so its top and its joint are ellipses.
 */
private fun DrawScope.drawAccuChekApplicator(tones: SensorTones) {
    val half = 26f
    val rise = 7.5f
    val top = -30f
    val joint = 6f
    val bottom = 32f
    fun band(upper: Float, lower: Float) = Path().apply {
        moveTo(-half, upper)
        lineTo(-half, lower)
        arcTo(Rect(-half, lower - rise, half, lower + rise), 180f, -180f, forceMoveTo = false)
        lineTo(half, upper)
        arcTo(Rect(-half, upper - rise, half, upper + rise), 0f, 180f, forceMoveTo = false)
        close()
    }
    rotate(10f, pivot = Offset.Zero) {
        for (pass in 6 downTo 1) {
            drawOval(
                tones.shadow,
                topLeft = Offset(-half - pass * 0.7f + 1.5f, bottom - rise + pass * 0.8f),
                size = Size(half * 2f + pass * 1.4f, rise * 2f + 1f),
            )
        }
        val capDeep = lerp(tones.cap, tones.ink, 0.55f)
        val capLit = lerp(tones.cap, tones.highlight, 0.3f)
        val capBand = band(joint, bottom)
        drawPath(
            capBand,
            Brush.horizontalGradient(
                0f to capDeep,
                0.3f to capLit,
                0.6f to tones.cap,
                1f to capDeep,
                startX = -half,
                endX = half,
            ),
        )
        // The ribs follow the barrel: bunched at the edges, wide at the front.
        clipPath(capBand) {
            for (rib in 1 until 16) {
                val x = -half * cos(rib / 16f * PI.toFloat())
                val curve = rise * sin(rib / 16f * PI.toFloat())
                drawLine(
                    capDeep.copy(alpha = 0.6f),
                    Offset(x, bottom - 15f + curve),
                    Offset(x, bottom + curve),
                    strokeWidth = 1f,
                )
            }
        }
        drawPath(
            band(top, joint),
            Brush.horizontalGradient(
                0f to tones.shade,
                0.3f to tones.highlight,
                0.62f to tones.body,
                1f to tones.wall,
                startX = -half,
                endX = half,
            ),
        )
        // The barrel's lid, rimmed where it catches the light.
        val lid = Rect(-half, top - rise, half, top + rise)
        drawOval(
            Brush.linearGradient(
                0f to tones.highlight,
                1f to tones.body,
                start = Offset(-half, top - rise),
                end = Offset(half, top + rise),
            ),
            topLeft = lid.topLeft,
            size = lid.size,
        )
        drawOval(tones.glint.copy(alpha = 0.7f), lid.topLeft, lid.size, style = Stroke(width = 1f))
        // Slot, pull tab and its finger hold.
        val slot = 6f
        drawRoundRect(
            tones.detail.copy(alpha = 0.5f),
            topLeft = Offset(slot, -9f),
            size = Size(13f, 40f),
            cornerRadius = CornerRadius(6.5f),
        )
        drawRoundRect(
            capDeep,
            topLeft = Offset(slot + 2f, -6.6f),
            size = Size(9f, 35f),
            cornerRadius = CornerRadius(4.5f),
        )
        drawCircle(tones.highlight, 3.2f, Offset(slot + 6.5f, -1.4f))
    }
}

/** CareSens Air: a grey capsule on a pale pad, a ribbed dial with a nub at one end. */
private fun DrawScope.drawCareSensAir(tones: SensorTones, onPad: Boolean) {
    val rotation = -24f
    if (onPad) pad(tones, stadiumPath(84f, 56f, radius = 22f), rotation)
    val slate = tones.slate()
    solid(slate, stadiumPath(70f, 38f), rotation, depth = 6f) {
        val dial = Offset(16f, 0f)
        val disc = Path().apply {
            addOval(Rect(dial.x - 14f, -14f, dial.x + 14f, 14f))
        }
        drawPath(disc, tones.detail.copy(alpha = 0.55f))
        drawPath(disc, slate.highlight.copy(alpha = 0.5f), style = Stroke(width = 0.9f))
        clipPath(disc) {
            var y = -14f
            while (y < 14f) {
                drawLine(
                    slate.wall.copy(alpha = 0.7f),
                    Offset(dial.x - 15f, y),
                    Offset(dial.x + 15f, y),
                    strokeWidth = 1.1f,
                )
                y += 3f
            }
        }
        drawCircle(
            Brush.radialGradient(
                0f to tones.highlight,
                1f to slate.shade,
                center = dial - Offset(1.6f, 1.8f),
                radius = 7f,
            ),
            6f,
            dial,
        )
        drawCircle(tones.ink, 1.3f, Offset(-14f, -5f))
    }
}

/** RGMS: a rounded square, kept plain. */
private fun DrawScope.drawMq(tones: SensorTones) {
    solid(tones, stadiumPath(62f, 62f, radius = 20f), rotation = 12f, depth = 7f) {
        groove(tones, stadiumPath(50f, 50f, radius = 14f), width = 0.8f)
        well(tones, Offset(0f, 0f), 6f, collar = 1.6f)
    }
}

/** TouchCare S9: very thin, a transmitter clipped onto a slightly wider base. */
private fun DrawScope.drawMedtrum(tones: SensorTones) {
    val rotation = -12f
    val base = stadiumPath(78f, 54f, radius = 24f)
    solid(tones.slate(), base, rotation, depth = 3f)
    translate(0f, -3f) {
        solid(tones, stadiumPath(64f, 44f, radius = 19f), rotation, depth = 3f) {
            groove(tones, Path().apply {
                moveTo(10f, -21f)
                cubicTo(16f, -8f, 16f, 8f, 10f, 21f)
            }, width = 1f)
            well(tones, Offset(20f, 0f), 2.8f, collar = 1.6f)
        }
    }
}

/** The dome that places a CareSens Air: white, a ribbed blue thumb slot with a button in it. */
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
