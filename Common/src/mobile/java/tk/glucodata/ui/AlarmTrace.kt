package tk.glucodata.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One reading of the alarm's trace, in the display unit. */
@Immutable
data class AlarmTracePoint(val timeMs: Long, val value: Float)

/**
 * The past hours of the main line, drawn under the value on a glucose alarm. The points come
 * from the resolved chart model, so this is the dashboard's own curve, not a lookalike.
 */
@Immutable
data class AlarmTrace(
    val points: List<AlarmTracePoint>,
    val startMs: Long,
    val endMs: Long,
    /**
     * The smallest value span the height stands for, so a steady stretch reads as steady
     * instead of magnifying a 0.1 wobble into a mountain range.
     */
    val minSpan: Float,
)

/** Readings further apart than this are a gap in the record, and the line breaks there. */
internal const val ALARM_TRACE_GAP_MS = 15 * 60_000L

/** M3 emphasized-decelerate: the line leaves fast and eases into the present. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * The trace laid out in a [width] by [height] box: one list per unbroken stretch, a lone
 * reading between gaps included, so the last list always ends at the latest reading and the
 * marker stands on the value the alarm shows. The span runs from the left edge to
 * [endInset] short of the right one, so the present lands clear of the bezel; a reading
 * missing at either end leaves its part of the span empty rather than stretching what is
 * there.
 */
internal fun alarmTraceSegments(
    trace: AlarmTrace,
    width: Float,
    height: Float,
    padTop: Float,
    padBottom: Float,
    endInset: Float = 0f,
): List<List<Offset>> {
    val points = trace.points.filter { it.value.isFinite() && it.timeMs in trace.startMs..trace.endMs }
    if (points.size < 2 || width <= 0f || height <= 0f || trace.endMs <= trace.startMs) return emptyList()
    var low = points.minOf { it.value }
    var high = points.maxOf { it.value }
    if (high - low < trace.minSpan) {
        val mid = (high + low) / 2f
        low = mid - trace.minSpan / 2f
        high = mid + trace.minSpan / 2f
    }
    val drawable = (height - padTop - padBottom).coerceAtLeast(1f)
    val span = (trace.endMs - trace.startMs).toFloat()
    val runWidth = (width - endInset).coerceAtLeast(1f)
    val segments = ArrayList<List<Offset>>()
    var current = ArrayList<Offset>()
    var previousTime = Long.MIN_VALUE
    for (point in points) {
        if (previousTime != Long.MIN_VALUE && point.timeMs - previousTime > ALARM_TRACE_GAP_MS) {
            if (current.isNotEmpty()) segments.add(current)
            current = ArrayList()
        }
        val x = (point.timeMs - trace.startMs) / span * runWidth
        val y = padTop + (1f - (point.value - low) / (high - low)) * drawable
        current.add(Offset(x, y))
        previousTime = point.timeMs
    }
    if (current.isNotEmpty()) segments.add(current)
    return segments
}

/** Where the line is at [x], for the head that rides its tip while it draws. */
private fun lineYAt(segments: List<List<Offset>>, x: Float): Float? {
    var lastBefore: Offset? = null
    for (segment in segments) {
        if (x < segment.first().x) return lastBefore?.y
        for (i in 1 until segment.size) {
            val a = segment[i - 1]
            val b = segment[i]
            if (x <= b.x) {
                val f = if (b.x > a.x) (x - a.x) / (b.x - a.x) else 1f
                return a.y + (b.y - a.y) * f
            }
        }
        lastBefore = segment.last()
    }
    return lastBefore?.y
}

private val TraceStroke = 2.dp
/** Room above and below the line for the marker, so the present never sits on the trace's edge. */
private val TraceVerticalPad = 16.dp
/**
 * Where the span ends, from the right edge: far enough in that the marker's glow and ripples
 * finish on screen instead of being cut by the bezel.
 */
private val TraceEndInset = 40.dp
private val MarkerSize = 22.dp
private val MarkerGap = 3.dp
private val HaloRadius = 30.dp
private val RippleReach = 36.dp

/**
 * The trace itself: a thin line that draws itself across the span and eases into the present.
 * The present is the one vivid thing on it: an M3 shape that morphs from a dot into a cookie
 * as it lands and turns slowly, with a breathing glow, ripples and a faint guide down the
 * minute it stands for. Everything that moves stops with the alarm's motion window.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AlarmTraceLine(
    trace: AlarmTrace,
    motionActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val reveal = remember { Animatable(0f) }
    val landed = remember { Animatable(0f) }
    val shapeMorph = remember { Animatable(0f) }
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val landSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val morphSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    // Keyed on the window too: when it closes mid-draw (a trace that arrived late in it), the
    // draw stops where it is and settles rather than running on past it.
    LaunchedEffect(trace, motionActive) {
        if (!motionActive) {
            // Arrived after the motion window closed (a slow history read), or the window
            // closed mid-draw: appear settled, since the screen can sit on the lockscreen and
            // its motion is meant to be finite.
            reveal.snapTo(1f)
            landed.snapTo(1f)
            shapeMorph.snapTo(1f)
            return@LaunchedEffect
        }
        // After the value has landed, so the eye goes value first, then where it came from.
        delay(320)
        reveal.animateTo(1f, tween(durationMillis = 1_400, easing = EmphasizedDecelerate))
        coroutineScope {
            launch { landed.animateTo(1f, landSpec) }
            launch { shapeMorph.animateTo(1f, morphSpec) }
        }
    }

    var spin = 0f
    var ripple = 0f
    var breath = 0.5f
    if (motionActive) {
        val transition = rememberInfiniteTransition(label = "alarm-trace-now")
        val spinValue by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 6_000, easing = LinearEasing)),
            label = "alarm-trace-spin"
        )
        val rippleValue by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_800, easing = EmphasizedDecelerate)),
            label = "alarm-trace-ripple"
        )
        val breathValue by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_200), repeatMode = RepeatMode.Reverse),
            label = "alarm-trace-breath"
        )
        spin = spinValue
        ripple = rippleValue
        breath = breathValue
    }

    Box(modifier = modifier) {
        // The line, offscreen so the edge fade and the gap around the marker can cut into it.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            val segments = segmentsFor(trace) ?: return@Canvas
            val strokeWidth = TraceStroke.toPx()
            val now = segments.last().last()
            val headX = now.x * reveal.value

            val line = Path()
            for (segment in segments) {
                line.moveTo(segment.first().x, segment.first().y)
                for (i in 1 until segment.size) line.lineTo(segment[i].x, segment[i].y)
            }
            clipRect(right = headX + strokeWidth) {
                drawPath(
                    path = line,
                    color = lineColor,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // A lone reading between gaps has no line to be part of; it is still a reading.
                for (segment in segments) {
                    if (segment.size == 1) drawCircle(lineColor, radius = strokeWidth, center = segment[0])
                }
            }
            // The past comes in from the edge of the screen rather than starting at a hard cut.
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.22f to Color.Black,
                    startX = 0f,
                    endX = size.width
                ),
                blendMode = BlendMode.DstIn
            )
            // A clear ring round the marker, so the line stops short of it instead of running under.
            if (landed.value > 0f) {
                drawMorph(
                    morph, shapeMorph.value, now,
                    (MarkerSize + MarkerGap * 2).toPx() * landed.value, spin,
                    Color.Black, BlendMode.Clear
                )
            }
        }

        // The present: not clipped to the line's layer, so the glow and ripples can spread.
        Canvas(modifier = Modifier.matchParentSize()) {
            val segments = segmentsFor(trace) ?: return@Canvas
            val now = segments.last().last()
            val progress = reveal.value
            if (progress <= 0f) return@Canvas

            if (progress < 1f) {
                // A soft head rides the tip while the line is being drawn.
                val headX = now.x * progress
                val y = lineYAt(segments, headX) ?: return@Canvas
                drawCircle(lineColor.copy(alpha = 0.28f), radius = 8.dp.toPx(), center = Offset(headX, y))
                drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(headX, y))
                return@Canvas
            }

            val land = landed.value.coerceIn(0f, 1.2f)
            // Down the minute it stands for, brightest at the reading.
            val guideTop = 0f
            val guideBottom = size.height
            val atReading = ((now.y - guideTop) / (guideBottom - guideTop)).coerceIn(0.05f, 0.95f)
            drawLine(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    atReading to lineColor.copy(alpha = 0.45f * land.coerceAtMost(1f)),
                    1f to Color.Transparent,
                    startY = guideTop,
                    endY = guideBottom
                ),
                start = Offset(now.x, guideTop),
                end = Offset(now.x, guideBottom),
                strokeWidth = 1.dp.toPx()
            )

            // Breathing glow behind the marker.
            val haloRadius = HaloRadius.toPx() * (0.8f + 0.3f * breath) * land.coerceAtMost(1f)
            if (haloRadius > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to lineColor.copy(alpha = 0.42f),
                        1f to lineColor.copy(alpha = 0f),
                        center = now,
                        radius = haloRadius
                    ),
                    radius = haloRadius,
                    center = now
                )
            }

            // Two ripples, half a beat apart, while the motion window is open.
            if (motionActive) {
                val markerRadius = MarkerSize.toPx() / 2f
                val reach = RippleReach.toPx()
                for (offset in floatArrayOf(0f, 0.5f)) {
                    val phase = (ripple + offset) % 1f
                    drawCircle(
                        color = lineColor.copy(alpha = 0.5f * (1f - phase)),
                        radius = markerRadius + (reach - markerRadius) * phase,
                        center = now,
                        style = Stroke(width = TraceStroke.toPx() * (1.5f - phase))
                    )
                }
            }

            drawMorph(morph, shapeMorph.value, now, MarkerSize.toPx() * land, spin, lineColor)
        }
    }
}

private fun DrawScope.segmentsFor(trace: AlarmTrace): List<List<Offset>>? {
    val pad = TraceVerticalPad.toPx()
    return alarmTraceSegments(trace, size.width, size.height, pad, pad, endInset = TraceEndInset.toPx())
        .takeIf { it.isNotEmpty() }
}

/** The marker's shape at [progress] of [morph], [size] across, centred on [center] and turned [degrees]. */
private fun DrawScope.drawMorph(
    morph: Morph,
    progress: Float,
    center: Offset,
    size: Float,
    degrees: Float,
    color: Color,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
) {
    if (size <= 0f) return
    val androidPath = android.graphics.Path()
    morph.toPath(progress.coerceIn(0f, 1f), androidPath)
    val bounds = android.graphics.RectF()
    androidPath.computeBounds(bounds, true)
    val extent = maxOf(bounds.width(), bounds.height())
    if (extent <= 0f) return
    val path = androidPath.asComposePath()
    withTransform({
        translate(center.x, center.y)
        rotate(degrees, pivot = Offset.Zero)
        scale(size / extent, size / extent, pivot = Offset.Zero)
        translate(-bounds.centerX(), -bounds.centerY())
    }) {
        drawPath(path, color, blendMode = blendMode)
    }
}
