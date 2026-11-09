package tk.glucodata.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** One reading of the alarm's trace, in the display unit. */
@Immutable
data class AlarmTracePoint(val timeMs: Long, val value: Float)

/**
 * The past hour of the main line, drawn under the value on a glucose alarm. The points come
 * from the resolved chart model, so this is the dashboard's own curve, not a lookalike.
 */
@Immutable
data class AlarmTrace(
    val points: List<AlarmTracePoint>,
    val startMs: Long,
    val endMs: Long,
    /**
     * The smallest value span the height stands for, so a steady hour reads as steady
     * instead of magnifying a 0.1 wobble into a mountain range.
     */
    val minSpan: Float,
)

/** Readings further apart than this are a gap in the record, and the line breaks there. */
internal const val ALARM_TRACE_GAP_MS = 15 * 60_000L

/** M3 emphasized-decelerate: the line leaves fast and eases into the present. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * The trace laid out in a [width] by [height] box: one list per unbroken stretch. The hour
 * runs from the left edge to [endInset] short of the right one, so the present lands on the
 * content's edge rather than under the bezel; a reading missing at either end leaves its
 * part of the hour empty rather than stretching what is there.
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
            if (current.size >= 2) segments.add(current)
            current = ArrayList()
        }
        val x = (point.timeMs - trace.startMs) / span * runWidth
        val y = padTop + (1f - (point.value - low) / (high - low)) * drawable
        current.add(Offset(x, y))
        previousTime = point.timeMs
    }
    if (current.size >= 2) segments.add(current)
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

/**
 * The trace itself: a thin line that draws itself across the hour, eases into the present
 * reading, and lands as a dot that ripples while the alarm's motion window is open.
 */
@Composable
fun AlarmTraceLine(
    trace: AlarmTrace,
    motionActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val reveal = remember { Animatable(0f) }
    val headScale = remember { Animatable(0f) }
    val popSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(trace) {
        // After the value has landed, so the eye goes value first, then where it came from.
        delay(320)
        reveal.animateTo(1f, tween(durationMillis = 1_400, easing = EmphasizedDecelerate))
        // The present reading lands with a spring, the one expressive beat of the trace.
        headScale.snapTo(1.9f)
        headScale.animateTo(1f, popSpec)
    }

    val ripple = if (motionActive) {
        val transition = rememberInfiniteTransition(label = "alarm-trace-ripple")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_800, easing = EmphasizedDecelerate),
                repeatMode = RepeatMode.Restart
            ),
            label = "alarm-trace-ripple-phase"
        )
        phase
    } else {
        null
    }

    Canvas(
        modifier = modifier
            // Offscreen, so the left-edge fade can mask what is already drawn.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val strokeWidth = 2.dp.toPx()
        val headRadius = 4.dp.toPx()
        val pad = headRadius * 2f + strokeWidth
        val segments = alarmTraceSegments(trace, size.width, size.height, pad, pad, endInset = 24.dp.toPx())
        if (segments.isEmpty()) return@Canvas

        val lastPoint = segments.last().last()
        val progress = reveal.value
        val headX = lastPoint.x * progress

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
        }

        // The hour comes in from the edge of the screen rather than starting at a hard cut.
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.22f to Color.Black,
                startX = 0f,
                endX = size.width
            ),
            blendMode = BlendMode.DstIn
        )

        if (progress <= 0f) return@Canvas
        if (progress < 1f) {
            // A soft head rides the tip while the line is being drawn.
            val y = lineYAt(segments, headX) ?: return@Canvas
            drawCircle(color = lineColor.copy(alpha = 0.28f), radius = headRadius * 2f, center = Offset(headX, y))
            drawCircle(color = lineColor, radius = headRadius * 0.75f, center = Offset(headX, y))
            return@Canvas
        }

        if (ripple != null) {
            drawCircle(
                color = lineColor.copy(alpha = 0.4f * (1f - ripple)),
                radius = headRadius + headRadius * 4f * ripple,
                center = lastPoint,
                style = Stroke(width = strokeWidth * (1f - ripple * 0.5f))
            )
        }
        drawCircle(color = lineColor, radius = headRadius * headScale.value, center = lastPoint)
    }
}
