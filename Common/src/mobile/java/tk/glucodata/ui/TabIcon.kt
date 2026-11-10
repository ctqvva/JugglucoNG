package tk.glucodata.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Which navigation glyph a [TabIcon] draws, and so which arrival it plays.
 *
 * The geometry below is the Material glyph's own, in its 24-unit viewport; the Filled and
 * Outlined variants of these four share one path, so the regions fit both.
 */
enum class TabGlyph { STATISTICS, JOURNAL, DASHBOARD, SENSORS, SETTINGS, OTHER }

/**
 * A navigation icon that acts out its own glyph when its tab is chosen: the bars grow,
 * the chart line draws itself, the sensor waves radiate, the gear turns, the pen writes.
 *
 * The arrival plays once per switch and never for the selection the icon first sees, so
 * opening the app or rotating it is still. Until the navigation graph has a route,
 * [selectionKnown] is false and nothing is recorded: the first real route is not a switch. Leaving a tab only cross-fades back to the outlined glyph; the
 * eye belongs on the tab being entered. The pieces move on under-damped springs and the
 * fades on critically damped ones, the split [tk.glucodata.ui.util.ExpressiveMotion] keeps.
 */
@Composable
fun TabIcon(
    isSelected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    description: String,
    glyph: TabGlyph,
    pressed: Boolean = false,
    selectionKnown: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val outlined = rememberVectorPainter(unselectedIcon)
    val filled = rememberVectorPainter(selectedIcon)
    val tint = LocalContentColor.current
    val colorFilter = remember(tint) { ColorFilter.tint(tint) }

    // Each glyph's moving pieces, 0 = about to arrive, 1 = at rest. At rest they draw the
    // stock glyph exactly, so an interrupted or skipped arrival never leaves a wrong icon.
    val parts = remember { List(PART_COUNT) { Animatable(1f) } }
    val pop = remember { Animatable(1f) }
    val fill by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1600f),
        label = "TabIconFill"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = if (pressed) {
            spring(dampingRatio = 1f, stiffness = 1600f)
        } else {
            spring(dampingRatio = 0.4f, stiffness = 700f)
        },
        label = "TabIconPress"
    )

    val shown = remember { arrayOfNulls<Boolean>(1) }
    LaunchedEffect(isSelected, selectionKnown) {
        if (!selectionKnown) return@LaunchedEffect
        val previous = shown[0]
        shown[0] = isSelected
        if (previous == null || previous == isSelected) return@LaunchedEffect
        if (isSelected) {
            playArrival(glyph, parts, pop)
        } else {
            // Leaving mid-arrival: settle the pieces quickly instead of finishing the show.
            coroutineScope {
                parts.forEach { launch { it.animateTo(1f, spring(dampingRatio = 1f, stiffness = 900f)) } }
                launch { pop.animateTo(1f, spring(dampingRatio = 1f, stiffness = 900f)) }
            }
        }
    }

    Spacer(
        modifier = modifier
            .size(24.dp)
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            .drawWithCache {
                val u = size.width / VIEWPORT
                val clips = GlyphClips(glyph, u, size.width, size.height, Offset(size.width / 2f, size.height / 2f))
                val layer = GlyphLayer(outlined, filled, colorFilter)
                onDrawBehind {
                    // Animation values are read here, in the draw phase, so a running arrival
                    // redraws the icon without recomposing it.
                    layer.fill = fill
                    scale(pop.value * pressScale, pivot = center) {
                        when (glyph) {
                            TabGlyph.STATISTICS -> drawStatistics(layer, u, parts)
                            TabGlyph.DASHBOARD -> drawDashboard(layer, u, parts)
                            TabGlyph.SENSORS -> drawSensors(layer, u, parts, clips, tint)
                            TabGlyph.SETTINGS -> drawSettings(layer, parts)
                            TabGlyph.JOURNAL -> drawJournal(layer, u, parts, clips)
                            TabGlyph.OTHER -> with(layer) { drawGlyph() }
                        }
                    }
                }
            }
    )
}

private const val VIEWPORT = 24f
private const val PART_COUNT = 4

/** The outlined and filled glyphs, cross-faded by [fill]. */
private class GlyphLayer(
    private val outlined: Painter,
    private val filled: Painter,
    private val colorFilter: ColorFilter,
) {
    var fill = 0f

    fun DrawScope.drawGlyph(alpha: Float = 1f) {
        if (alpha <= 0f) return
        if (fill < 1f) with(outlined) { draw(size, alpha * (1f - fill), colorFilter) }
        if (fill > 0f) with(filled) { draw(size, alpha * fill, colorFilter) }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

private fun bouncy(dampingRatio: Float, stiffness: Float): AnimationSpec<Float> =
    spring(dampingRatio = dampingRatio, stiffness = stiffness)

private suspend fun playArrival(glyph: TabGlyph, parts: List<Animatable<Float, *>>, pop: Animatable<Float, *>) {
    coroutineScope {
        // Every arrival starts from nothing in the same frame, so no piece flashes at rest first.
        parts.forEach { it.snapTo(0f) }
        pop.snapTo(if (glyph == TabGlyph.SETTINGS) 0.9f else 0.82f)
        launch { pop.animateTo(1f, bouncy(0.45f, 520f)) }

        fun stagger(index: Int, delayMillis: Long, spec: AnimationSpec<Float>) = launch {
            delay(delayMillis)
            parts[index].animateTo(1f, spec)
        }

        when (glyph) {
            TabGlyph.STATISTICS -> {
                // Bars spring up from the baseline, left to right.
                for (i in 0 until 3) stagger(i, i * 55L, bouncy(0.42f, 420f))
                parts[3].snapTo(1f)
            }
            TabGlyph.DASHBOARD -> {
                // The two legend rules stretch out, then the trend line draws itself across.
                stagger(0, 0L, bouncy(0.6f, 520f))
                stagger(1, 45L, bouncy(0.6f, 520f))
                stagger(2, 70L, bouncy(0.88f, 240f))
                parts[3].snapTo(1f)
            }
            TabGlyph.SENSORS -> {
                // The dot pops, each wave follows outward, and a faint ping rolls off the edge.
                stagger(0, 0L, bouncy(0.38f, 700f))
                stagger(1, 70L, bouncy(0.5f, 520f))
                stagger(2, 140L, bouncy(0.5f, 520f))
                stagger(3, 150L, tween(durationMillis = 620, easing = FastOutSlowInEasing))
            }
            TabGlyph.SETTINGS -> {
                // A half turn that overshoots and settles like a ratchet.
                stagger(0, 0L, bouncy(0.42f, 210f))
                for (i in 1 until PART_COUNT) parts[i].snapTo(1f)
            }
            TabGlyph.JOURNAL -> {
                // The note's lines are written top to bottom while the pen scribbles.
                for (i in 0 until 3) stagger(i, 40L + i * 75L, bouncy(0.9f, 300f))
                stagger(3, 0L, bouncy(0.22f, 330f))
            }
            TabGlyph.OTHER -> parts.forEach { it.snapTo(1f) }
        }
    }
}

// BarChart: three bars, x 4–8 / 10–14 / 16–20, standing on y = 20.
private val statisticsBars = floatArrayOf(4f, 8f, 10f, 14f, 16f, 20f)

private fun DrawScope.drawStatistics(layer: GlyphLayer, u: Float, parts: List<Animatable<Float, *>>) {
    val baseline = 20f * u
    for (i in 0 until 3) {
        val left = statisticsBars[i * 2] * u
        val right = statisticsBars[i * 2 + 1] * u
        val height = lerp(0.1f, 1f, parts[i].value).coerceAtLeast(0f)
        clipRect(left = left - 0.5f * u, top = 0f, right = right + 0.5f * u, bottom = size.height) {
            scale(scaleX = 1f, scaleY = height, pivot = Offset((left + right) / 2f, baseline)) {
                with(layer) { drawGlyph() }
            }
        }
    }
}

// LegendToggle: a trend line in y 5–11 above two rules at y 13–15 and 17–19, all x 4–20.
private fun DrawScope.drawDashboard(layer: GlyphLayer, u: Float, parts: List<Animatable<Float, *>>) {
    val start = 4f * u
    val rules = floatArrayOf(12f, 16f, 16f, 20f)
    for (i in 0 until 2) {
        val stretch = parts[i].value.coerceAtLeast(0f)
        clipRect(left = 0f, top = rules[i * 2] * u, right = size.width, bottom = rules[i * 2 + 1] * u) {
            scale(scaleX = stretch, scaleY = 1f, pivot = Offset(start, center.y)) {
                with(layer) { drawGlyph() }
            }
        }
    }
    val draw = parts[2].value
    val reveal = start + (16f * u + u) * draw.coerceIn(0f, 1f)
    clipRect(left = 0f, top = 0f, right = reveal, bottom = 12f * u) {
        translate(top = (1f - draw) * 3f * u) {
            with(layer) { drawGlyph() }
        }
    }
}

// Sensors: a dot of radius 2 at the centre and two pairs of arcs, in rings 4–6 and 8–10.
private val sensorRingRadii = floatArrayOf(0f, 3f, 3f, 7f, 7f, 12f)

/** Clip shapes that depend only on the icon's size, built once per size. */
private class GlyphClips(glyph: TabGlyph, u: Float, width: Float, height: Float, center: Offset) {
    val sensorRings: List<Path> = if (glyph != TabGlyph.SENSORS) emptyList() else List(3) { i ->
        Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(center, sensorRingRadii[i * 2 + 1] * u))
            if (i > 0) addOval(Rect(center, sensorRingRadii[i * 2] * u))
        }
    }

    // EditNote's pen fits {x ≥ 11, y ≥ 12.5} ∪ {x ≥ 14.5, y ≥ 11}, clear of the lines.
    val journalPen: Path? = if (glyph != TabGlyph.JOURNAL) null else Path().apply {
        moveTo(14.5f * u, 11f * u)
        lineTo(width, 11f * u)
        lineTo(width, height)
        lineTo(11f * u, height)
        lineTo(11f * u, 12.5f * u)
        lineTo(14.5f * u, 12.5f * u)
        close()
    }
}

private fun DrawScope.drawSensors(
    layer: GlyphLayer,
    u: Float,
    parts: List<Animatable<Float, *>>,
    clips: GlyphClips,
    tint: Color,
) {
    val ping = parts[3].value
    if (ping < 1f) {
        drawCircle(
            color = tint,
            radius = lerp(2f, 13f, ping) * u,
            center = center,
            alpha = 0.45f * (1f - ping),
            style = Stroke(width = lerp(2f, 0.6f, ping) * u)
        )
    }
    for (i in 0 until 3) {
        val p = parts[i].value
        clipPath(clips.sensorRings[i]) {
            scale(lerp(if (i == 0) 0f else 0.55f, 1f, p).coerceAtLeast(0f), pivot = center) {
                with(layer) { drawGlyph(alpha = if (i == 0) 1f else p.coerceIn(0f, 1f)) }
            }
        }
    }
}

private fun DrawScope.drawSettings(layer: GlyphLayer, parts: List<Animatable<Float, *>>) {
    rotate(degrees = (1f - parts[0].value) * -180f, pivot = center) {
        with(layer) { drawGlyph() }
    }
}

// EditNote: lines at y 6–8, 10–12 (x 3–14) and 14–16 (x 3–10); the pen lies from its tip at
// (12, 21) up to its cap near (21, 12).
private val journalLines = floatArrayOf(5.5f, 8.5f, 9.5f, 12.25f, 13.5f, 16.5f)

private fun DrawScope.drawJournal(layer: GlyphLayer, u: Float, parts: List<Animatable<Float, *>>, clips: GlyphClips) {
    val pen = clips.journalPen ?: return
    clipPath(pen, clipOp = ClipOp.Difference) {
        for (i in 0 until 3) {
            val reveal = 3f * u + 11.5f * u * parts[i].value.coerceIn(0f, 1f)
            clipRect(left = 0f, top = journalLines[i * 2] * u, right = reveal, bottom = journalLines[i * 2 + 1] * u) {
                with(layer) { drawGlyph() }
            }
        }
    }
    // The clip moves with the pen, so a swing never cuts it off against the lines.
    val scribble = 1f - parts[3].value
    translate(left = scribble * -2.5f * u, top = scribble * 1f * u) {
        rotate(degrees = scribble * -24f, pivot = Offset(12f * u, 21f * u)) {
            clipPath(pen) {
                with(layer) { drawGlyph() }
            }
        }
    }
}
