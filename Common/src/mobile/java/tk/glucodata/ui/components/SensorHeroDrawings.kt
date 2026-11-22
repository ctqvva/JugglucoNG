package tk.glucodata.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The things a wizard hero shows beside its sensor: the phone, and what the phone reads a code
// off. Same 100-unit square, camera and light as the sensors in SensorArt.kt.

/** What the code printed on a family's packaging looks like. */
internal enum class CodeKind { QR, DATA_MATRIX }

/**
 * What a family's code is printed on, drawn beside the sensor so the hero shows what to point
 * the camera at. [NONE] for families whose code is only on a box.
 */
internal enum class CodeCarrier(val code: CodeKind) {
    NONE(CodeKind.QR),
    DEXCOM(CodeKind.DATA_MATRIX),
    ICAN(CodeKind.QR),
    ACCUCHEK(CodeKind.DATA_MATRIX),
    CARESENS(CodeKind.DATA_MATRIX),
}

// ---- Codes ----

/** A code's dark modules, in cells: drawn the same way every time, never a real code. */
private class CodeModules(val cells: Int, val path: Path)

private val codes = HashMap<CodeKind, CodeModules>()

private fun modules(kind: CodeKind): CodeModules = codes.getOrPut(kind) {
    val cells = if (kind == CodeKind.QR) 21 else 16
    var seed = if (kind == CodeKind.QR) 7_919 else 104_729
    fun coin(): Boolean {
        seed = seed * 1_103_515_245 + 12_345
        return ((seed ushr 16) and 3) < 2
    }
    fun inFinder(row: Int, col: Int): Boolean {
        val far = cells - 8
        return (row <= 7 && col <= 7) || (row <= 7 && col >= far) || (row >= far && col <= 7)
    }
    fun finder(row: Int, col: Int): Boolean? {
        val corners = listOf(0 to 0, 0 to cells - 7, cells - 7 to 0)
        for ((top, left) in corners) {
            val r = row - top
            val c = col - left
            if (r in -1..7 && c in -1..7) {
                if (r !in 0..6 || c !in 0..6) return false
                return r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4)
            }
        }
        return null
    }
    val path = Path()
    for (row in 0 until cells) for (col in 0 until cells) {
        val on = if (kind == CodeKind.QR) {
            finder(row, col) ?: when {
                inFinder(row, col) -> false
                row == 6 -> col % 2 == 0
                col == 6 -> row % 2 == 0
                else -> coin()
            }
        } else {
            when {
                // The solid L along the left and bottom, the clock track along the top and right.
                col == 0 || row == cells - 1 -> true
                row == 0 -> col % 2 == 0
                col == cells - 1 -> row % 2 == 1
                else -> coin()
            }
        }
        if (on) path.addRect(Rect(col.toFloat(), row.toFloat(), col + 1.04f, row + 1.04f))
    }
    CodeModules(cells, path)
}

/** A code of [kind] filling [rect]. */
internal fun DrawScope.drawCode(kind: CodeKind, rect: Rect, ink: Color) {
    val code = modules(kind)
    val cell = rect.width / code.cells
    translate(rect.left, rect.top) {
        scale(cell, cell, pivot = Offset.Zero) { drawPath(code.path, ink) }
    }
}

/** Lines of small print, [rows] of them from [top], [gap] apart, for the text beside a code. */
private fun DrawScope.smallPrint(ink: Color, left: Float, top: Float, lengths: List<Float>, gap: Float, weight: Float) {
    lengths.forEachIndexed { row, length ->
        drawLine(ink, Offset(left, top + row * gap), Offset(left + length, top + row * gap), strokeWidth = weight, cap = StrokeCap.Round)
    }
}

// ---- The phone ----

/** What the hero's phone shows on its screen. */
internal sealed interface PhoneScreen {
    /** A glyph standing for the link: the NFC mark, or Bluetooth. [pulse] rings out behind it. */
    class Glyph(val painter: Painter, val pulse: Float) : PhoneScreen

    /**
     * The camera on a code: the viewfinder's corners, the code inside them, a line sweeping down
     * while [sweep] runs to 1, and the corners closing on the code as [lock] does.
     */
    class Viewfinder(val code: CodeKind, val sweep: Float, val lock: Float) : PhoneScreen
}

/**
 * The hero's phone: a flat slab with a bright screen, the way Material draws devices rather than
 * a photograph of one, so its screen can say plainly what is happening.
 */
internal fun DrawScope.drawHeroPhone(tones: SensorTones, screenColor: Color, accent: Color, rim: Color, screen: PhoneScreen) = inUnitSquare {
    val outline = RoundRect(Rect(-24f, -46f, 24f, 46f), CornerRadius(10f))
    val slab = Path().apply { addRoundRect(outline) }
    translate(2.4f, 3f) { drawPath(slab, tones.shadow.copy(alpha = tones.shadow.alpha * 2.2f)) }
    drawPath(slab, tones.ink)
    // A rim in the theme's outline colour, so the phone keeps its edge on any backdrop.
    drawPath(slab, rim, style = Stroke(width = 1.6f))
    val glass = RoundRect(Rect(-21.2f, -43.2f, 21.2f, 43.2f), CornerRadius(7.6f))
    val face = Path().apply { addRoundRect(glass) }
    drawPath(face, screenColor)
    clipPath(face) {
        when (screen) {
            is PhoneScreen.Glyph -> {
                val centre = Offset(0f, -2f)
                if (screen.pulse in 0f..0.999f) {
                    for (ring in 0..1) {
                        val local = (screen.pulse * 1.4f - ring * 0.4f).coerceIn(0f, 1f)
                        if (local > 0f && local < 1f) {
                            drawCircle(accent.copy(alpha = 0.5f * (1f - local)), 14f + 16f * local, centre, style = Stroke(width = 1.6f))
                        }
                    }
                }
                drawCircle(accent.copy(alpha = 0.16f), 15f, centre)
                // Drawn at screen pixels, not in the scaled drawing units: a vector painter
                // rasterises at the size it is given, and scaling that up blurs it.
                val unit = minOf(size.width, size.height) / 100f
                val glyph = 18f
                translate(centre.x, centre.y) {
                    scale(1f / unit, 1f / unit, pivot = Offset.Zero) {
                        translate(-glyph * unit / 2f, -glyph * unit / 2f) {
                            with(screen.painter) { draw(Size(glyph * unit, glyph * unit), colorFilter = ColorFilter.tint(accent)) }
                        }
                    }
                }
            }
            is PhoneScreen.Viewfinder -> {
                val centre = Offset(0f, -3f)
                val lock = screen.lock.coerceIn(0f, 1.2f)
                // A patch of label under the camera, so the code reads dark on light in both themes.
                val label = 11f
                drawRoundRect(
                    tones.highlight,
                    topLeft = centre - Offset(label, label),
                    size = Size(label * 2f, label * 2f),
                    cornerRadius = CornerRadius(2.5f),
                )
                val code = 8.6f
                drawCode(screen.code, Rect(centre - Offset(code, code), Size(code * 2f, code * 2f)), lerp(tones.ink, Color.Black, 0.3f))
                val half = 16.5f - 3.5f * lock
                val arm = 5.5f
                val width = 2.2f + 0.8f * (lock * (1.2f - lock)).coerceAtLeast(0f) * 3f
                for (corner in listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(1f, 1f), Offset(-1f, 1f))) {
                    val at = centre + Offset(corner.x * half, corner.y * half)
                    val bracket = Path().apply {
                        moveTo(at.x, at.y - corner.y * arm)
                        lineTo(at.x, at.y)
                        lineTo(at.x - corner.x * arm, at.y)
                    }
                    drawPath(bracket, accent, style = Stroke(width = width, cap = StrokeCap.Round))
                }
                if (screen.sweep < 1f) {
                    val y = centre.y - half + 2f * half * screen.sweep
                    drawLine(
                        accent.copy(alpha = 0.9f * (1f - screen.sweep * screen.sweep)),
                        Offset(centre.x - half - 1f, y),
                        Offset(centre.x + half + 1f, y),
                        strokeWidth = 1.8f,
                        cap = StrokeCap.Round,
                    )
                }
                // The read: a flash over the code as the corners close on it.
                val flash = (1f - kotlin.math.abs(lock - 0.6f) / 0.4f).coerceIn(0f, 1f)
                if (flash > 0f) {
                    drawRoundRect(
                        accent.copy(alpha = 0.22f * flash),
                        topLeft = centre - Offset(half, half),
                        size = Size(half * 2f, half * 2f),
                        cornerRadius = CornerRadius(3f),
                    )
                }
            }
        }
        // The pill the system draws at the foot of the screen.
        drawLine(tones.ink.copy(alpha = 0.35f), Offset(-7f, 38.5f), Offset(7f, 38.5f), strokeWidth = 1.4f, cap = StrokeCap.Round)
    }
    drawCircle(tones.ink, 1.7f, Offset(0f, -38.5f))
}

// ---- What the code is printed on ----

/** The thing a family's code is printed on, standing on the same ground as the sensor. */
internal fun DrawScope.drawCodeCarrier(carrier: CodeCarrier, tones: SensorTones) = inUnitSquare {
    when (carrier) {
        CodeCarrier.DEXCOM -> drawDexcomApplicator(tones.warm())
        CodeCarrier.ICAN -> drawICanPacks(tones)
        CodeCarrier.ACCUCHEK -> drawAccuChekApplicator(tones)
        CodeCarrier.CARESENS -> drawCareSensPouch(tones)
        CodeCarrier.NONE -> Unit
    }
}

/** The shading round a white cylinder, lit from the upper left. */
private fun barrelBrush(tones: SensorTones, half: Float) = Brush.horizontalGradient(
    0f to tones.shade,
    0.3f to tones.highlight,
    0.62f to tones.body,
    1f to tones.wall,
    startX = -half,
    endX = half,
)

/** The side of a cylinder [half] wide from [top] to [bottom], its ends ellipses [rise] deep. */
private fun band(half: Float, top: Float, bottom: Float, rise: Float, bottomHalf: Float = half) = Path().apply {
    moveTo(-half, top)
    lineTo(-bottomHalf, bottom)
    arcTo(Rect(-bottomHalf, bottom - rise, bottomHalf, bottom + rise), 180f, -180f, forceMoveTo = false)
    lineTo(half, top)
    arcTo(Rect(-half, top - rise, half, top + rise), 0f, 180f, forceMoveTo = false)
    close()
}

/** The soft shadow a round thing [half] wide casts where it stands at [ground]. */
private fun DrawScope.footShadow(tones: SensorTones, half: Float, ground: Float, rise: Float) {
    for (pass in 6 downTo 1) {
        drawOval(
            tones.shadow,
            topLeft = Offset(-half - pass * 0.7f + 2f, ground - rise + pass * 0.8f),
            size = Size(half * 2f + pass * 1.4f, rise * 2f + 1.5f),
        )
    }
}

/**
 * The Dexcom G7 applicator, its code printed on its side: a white barrel with a warm grey top,
 * a small hole in its middle, on a wider foot with a straight notch and the curved release slot.
 */
private fun DrawScope.drawDexcomApplicator(tones: SensorTones) {
    val half = 25f
    val foot = 31f
    val rise = 7.5f
    val top = -34f
    val joint = 14f
    val bottom = 40f
    footShadow(tones, foot, bottom, rise * 1.2f)
    // The foot, and the ledge where the barrel stands on it.
    drawPath(band(foot, joint, bottom, rise * 1.2f), barrelBrush(tones, foot))
    drawOval(tones.body, topLeft = Offset(-foot, joint - rise * 1.2f), size = Size(foot * 2f, rise * 2.4f))
    drawLine(tones.wall, Offset(-6f, 26f), Offset(-6f, 35f), strokeWidth = 1.6f, cap = StrokeCap.Round)
    val release = Path().apply {
        moveTo(14f, 20f)
        cubicTo(14f, 26f, 20f, 26f, 20f, 32f)
        lineTo(20f, 38f)
    }
    drawPath(release, tones.wall, style = Stroke(width = 1.6f, cap = StrokeCap.Round))
    drawPath(band(half, top, joint, rise), barrelBrush(tones, half))
    val lid = lerp(tones.wall, tones.tint, 0.18f)
    drawOval(tones.highlight, topLeft = Offset(-half, top - rise), size = Size(half * 2f, rise * 2f))
    drawOval(
        Brush.linearGradient(0f to lerp(lid, tones.highlight, 0.35f), 1f to lid, start = Offset(-half, top), end = Offset(half, top)),
        topLeft = Offset(-half + 2.2f, top - rise + 1.2f),
        size = Size(half * 2f - 4.4f, rise * 2f - 2.4f),
    )
    drawCircle(lerp(lid, tones.ink, 0.35f), 1.1f, Offset(0f, top))
    // The printed code, its four-digit pairing code beside it and the GS1 lines under both.
    val ink = lerp(tones.ink, Color.Black, 0.2f).copy(alpha = 0.85f)
    drawCode(CodeKind.DATA_MATRIX, Rect(-18f, -10f, -4f, 4f), ink)
    smallPrint(ink, -1f, -8f, listOf(15f), 0f, 3.4f)
    smallPrint(ink, -1f, -1.4f, listOf(13f, 13f), 2.6f, 0.9f)
    smallPrint(ink, -18f, 7.4f, listOf(31f, 31f, 22f), 2.4f, 0.8f)
}

/**
 * The SmartGuide applicator turned cap up, as it is held to scan its code: the hero's earlier
 * drawing of it, flipped. The blue twist cap, ribbed at its end, carries the white label with
 * the code; the pull tab runs from the U cut in the cap down the slot in the white barrel.
 */
private fun DrawScope.drawAccuChekApplicator(tones: SensorTones) {
    val half = 25f
    val rise = 7f
    val top = -38f
    val joint = -4f
    val bottom = 38f
    footShadow(tones, half, bottom, rise)
    drawPath(band(half, joint, bottom, rise), barrelBrush(tones, half))
    val capDeep = lerp(tones.cap, tones.ink, 0.45f)
    val capLit = lerp(tones.cap, tones.highlight, 0.2f)
    val cap = band(half, top, joint, rise)
    drawPath(
        cap,
        Brush.horizontalGradient(0f to capDeep, 0.28f to capLit, 0.6f to tones.cap, 1f to capDeep, startX = -half, endX = half),
    )
    // The ribs follow the cap round: bunched at the edges, wide at the front.
    clipPath(cap) {
        for (rib in 1 until 26) {
            val angle = rib / 26f * PI.toFloat()
            val x = -half * cos(angle)
            val curve = rise * sin(angle)
            drawLine(capDeep.copy(alpha = 0.5f), Offset(x, top + curve), Offset(x, top + 17f + curve), strokeWidth = 0.6f)
        }
        // The U cut the tab comes out of.
        drawRoundRect(
            capDeep.copy(alpha = 0.55f),
            topLeft = Offset(-9f, joint - 24f),
            size = Size(18f, 26f),
            cornerRadius = CornerRadius(9f),
        )
    }
    // Slot and pull tab, front and centre.
    val slot = Path().apply { addRoundRect(RoundRect(Rect(-7f, -22f, 7f, 20f), CornerRadius(7f))) }
    drawPath(slot, Brush.verticalGradient(0f to tones.shade, 1f to tones.wall, startY = 0f, endY = 20f))
    val tab = Path().apply { addRoundRect(RoundRect(Rect(-5f, -24f, 5f, 8f), CornerRadius(5f))) }
    drawPath(tab, Brush.horizontalGradient(0f to capLit, 1f to capDeep, startX = -5f, endX = 5f))
    drawPath(tab, tones.glint.copy(alpha = 0.35f), style = Stroke(width = 0.5f))
    // The cap's end: a blue rim round the white label, the code on the label.
    drawOval(capLit, topLeft = Offset(-half, top - rise), size = Size(half * 2f, rise * 2f))
    val label = Rect(-half + 3f, top - rise + 1f, half - 3f, top + rise - 1f)
    drawOval(tones.highlight, topLeft = label.topLeft, size = label.size)
    translate(0f, top) {
        scale(1f, rise / half, pivot = Offset.Zero) {
            val ink = lerp(tones.ink, Color.Black, 0.2f).copy(alpha = 0.85f)
            drawCode(CodeKind.DATA_MATRIX, Rect(-13f, -5f, -1f, 7f), ink)
            smallPrint(ink, -10f, -13f, listOf(12f), 0f, 2.2f)
            smallPrint(ink, 1.5f, -4f, listOf(11f, 9f, 10f, 10f), 2.8f, 1.2f)
            smallPrint(ink, -10f, 12f, listOf(8f), 0f, 2.2f)
        }
    }
}

/**
 * The iCan packs, each with its code on the lid: the i3's sensor pack, a cup with a printed lid,
 * a blue band round its serial and a blue pull tab, and in front of it the smaller i6 kit with
 * its code on a ribbed cap.
 */
private fun DrawScope.drawICanPacks(tones: SensorTones) {
    translate(9f, -4f) {
        scale(0.86f, 0.86f, pivot = Offset.Zero) { drawICanI3Pack(tones) }
    }
    translate(-24f, 18f) {
        scale(0.56f, 0.56f, pivot = Offset.Zero) { drawICanI6Kit(tones) }
    }
}

private fun DrawScope.drawICanI3Pack(tones: SensorTones) {
    val lid = 34f
    val rise = 17f
    val top = -24f
    footShadow(tones, 30f, 44f, 8f)
    // The foot ring, the cup narrowing down to it, the lid's rim.
    drawPath(band(30f, 26f, 44f, 8f), barrelBrush(tones, 30f))
    drawOval(tones.body, topLeft = Offset(-30f, 26f - 8f), size = Size(60f, 16f))
    drawLine(lerp(tones.ink, tones.wall, 0.4f), Offset(-29f, 27f), Offset(29f, 27f), strokeWidth = 1.2f)
    drawPath(band(30f, top + 4f, 27f, rise * 0.55f, bottomHalf = 23f), barrelBrush(tones, 30f))
    drawPath(band(lid, top, top + 4f, rise), barrelBrush(tones, lid))
    drawOval(tones.highlight, topLeft = Offset(-lid, top - rise), size = Size(lid * 2f, rise * 2f))
    // The pull tab, sticking out past the rim on the right.
    val tab = Path().apply {
        moveTo(lid - 6f, top - 9f)
        quadraticTo(lid + 8f, top - 6f, lid + 6f, top + 2f)
        lineTo(lid - 3f, top + 6f)
        close()
    }
    drawPath(tab, tones.cap)
    val squash = rise / lid
    translate(0f, top) {
        scale(1f, squash, pivot = Offset.Zero) {
            val ink = lerp(tones.ink, Color.Black, 0.2f).copy(alpha = 0.85f)
            smallPrint(ink, -14f, -24f, listOf(26f), 0f, 1.8f)
            // The serial in its blue band.
            drawRoundRect(tones.cap, topLeft = Offset(-26f, -15f), size = Size(8f, 7f), cornerRadius = CornerRadius(1.5f))
            drawRoundRect(tones.cap, topLeft = Offset(-26f, -15f), size = Size(48f, 7f), cornerRadius = CornerRadius(1.5f), style = Stroke(width = 1f))
            smallPrint(ink, -15f, -11.5f, listOf(32f), 0f, 1f)
            drawCode(CodeKind.QR, Rect(-20f, -3f, -6f, 11f), ink)
            smallPrint(ink, -2f, -2f, listOf(16f, 16f, 14f), 4.4f, 1f)
            smallPrint(ink, -14f, 17f, listOf(28f), 0f, 1f)
        }
    }
}

private fun DrawScope.drawICanI6Kit(tones: SensorTones) {
    val half = 30f
    val rise = 17f
    val top = -18f
    footShadow(tones, half - 3f, 46f, 9f)
    drawPath(band(half - 3f, 10f, 46f, 9f), barrelBrush(tones, half))
    val cap = band(half, top, 12f, rise * 0.6f)
    drawPath(cap, barrelBrush(tones, half))
    clipPath(cap) {
        for (rib in 1 until 16) {
            val angle = rib / 16f * PI.toFloat()
            val x = -half * cos(angle)
            val curve = rise * 0.6f * sin(angle)
            drawLine(tones.wall, Offset(x, top + curve + 4f), Offset(x, 12f + curve - 2f), strokeWidth = 2.4f, cap = StrokeCap.Round)
        }
    }
    drawOval(tones.highlight, topLeft = Offset(-half, top - rise), size = Size(half * 2f, rise * 2f))
    drawOval(tones.wall, topLeft = Offset(-half + 2f, top - rise + 1.2f), size = Size(half * 2f - 4f, rise * 2f - 2.4f), style = Stroke(width = 0.8f))
    val squash = rise / half
    translate(0f, top) {
        scale(1f, squash, pivot = Offset.Zero) {
            val ink = lerp(tones.ink, Color.Black, 0.2f).copy(alpha = 0.85f)
            drawRoundRect(ink, topLeft = Offset(-18f, -18f), size = Size(34f, 6f), cornerRadius = CornerRadius(1.5f), style = Stroke(width = 1f))
            drawCode(CodeKind.QR, Rect(-18f, -8f, -2f, 8f), ink)
            smallPrint(ink, 2f, -6f, listOf(14f, 14f, 12f), 4.4f, 1.1f)
        }
    }
}

/**
 * The CareSens Air's foil pouch, lying down, with its label: a heading, the code and the lines
 * of its GS1 data beside it. The foil is quilted and catches the light in a band.
 */
private fun DrawScope.drawCareSensPouch(tones: SensorTones) {
    rotate(-9f, pivot = Offset.Zero) {
        scale(1f, 0.84f, pivot = Offset.Zero) {
            val pouch = RoundRect(Rect(-42f, -40f, 42f, 44f), CornerRadius(10f))
            val outline = Path().apply { addRoundRect(pouch) }
            translate(2f, 4f) { drawPath(outline, tones.shadow.copy(alpha = tones.shadow.alpha * 2f)) }
            val foil = lerp(tones.wall, tones.wallDeep, 0.35f)
            drawPath(
                outline,
                Brush.linearGradient(
                    0f to foil,
                    0.42f to lerp(foil, tones.highlight, 0.7f),
                    0.55f to foil,
                    1f to lerp(foil, tones.ink, 0.15f),
                    start = Offset(-42f, -40f),
                    end = Offset(42f, 44f),
                ),
            )
            clipPath(outline) {
                var y = -38f
                while (y < 44f) {
                    var x = -40f
                    while (x < 42f) {
                        drawCircle(tones.glint.copy(alpha = 0.18f), 0.55f, Offset(x, y))
                        x += 3.2f
                    }
                    y += 3.2f
                }
            }
            drawPath(outline, lerp(foil, tones.ink, 0.25f), style = Stroke(width = 0.8f))
            val label = RoundRect(Rect(-28f, -24f, 32f, 34f), CornerRadius(4f))
            drawPath(Path().apply { addRoundRect(label) }, tones.highlight)
            val ink = lerp(tones.ink, Color.Black, 0.2f).copy(alpha = 0.85f)
            smallPrint(ink, -10f, -18f, listOf(36f), 0f, 3.2f)
            smallPrint(ink, -4f, -12.5f, listOf(30f, 30f), 2.2f, 0.8f)
            smallPrint(tones.accent.copy(alpha = 0.75f), -23f, -6.5f, listOf(48f, 48f, 30f), 2.2f, 0.8f)
            drawCode(CodeKind.DATA_MATRIX, Rect(-23f, 1f, -9f, 15f), ink)
            smallPrint(ink, -6f, 2f, listOf(22f, 13f, 18f, 13f, 26f), 3f, 1f)
            smallPrint(ink, -22f, 21f, listOf(6f, 6f, 6f), 3.4f, 1.3f)
            smallPrint(ink, -6f, 21f, listOf(18f, 16f, 10f), 3.4f, 1.1f)
        }
    }
}
