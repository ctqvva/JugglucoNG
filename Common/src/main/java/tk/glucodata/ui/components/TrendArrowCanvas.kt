package tk.glucodata.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tk.glucodata.TrendArrowAngle

/**
 * Shared "optically correct arrow" — the single source of the app's trend
 * arrow, used by the phone dashboard (via TrendIndicator) and the wear hero
 * card. Rotation formula and animation are user-tuned; keep both surfaces
 * identical by editing only this file.
 */
@Composable
fun TrendArrowCanvas(
    velocity: Float,
    pulseKey: Any?,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    outlineColor: Color? = null,
    shadowColor: Color? = null,
) {
    val targetRotation = TrendArrowAngle.rotationDegrees(velocity)

    val rotation by animateFloatAsState(
        targetValue = targetRotation,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "TrendRotation"
    )

    // Dynamic Scale + "Active Reading" Pulse
    val speed = kotlin.math.abs(velocity)
    val baseScale = 1.0f + (speed * 0.12f).coerceAtMost(0.5f)

    // Pulse Animation: Triggered when pulseKey changes (New Reading)
    val pulseAnim = remember { Animatable(1f) }
    LaunchedEffect(pulseKey) {
        // 1. Initial "Kick" (Immediate Visual Feedback)
        pulseAnim.snapTo(1.25f)
        pulseAnim.animateTo(
            targetValue = 1.0f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
        )

        // 2. Decaying "Heartbeat" (Lingering Resonance)
        delay(250)
        pulseAnim.animateTo(1.15f, tween(300, easing = FastOutSlowInEasing))
        pulseAnim.animateTo(1.0f, tween(300, easing = FastOutSlowInEasing))

        delay(400)
        pulseAnim.animateTo(1.08f, tween(500, easing = LinearOutSlowInEasing))
        pulseAnim.animateTo(1.0f, tween(500, easing = LinearOutSlowInEasing))

        delay(600)
        pulseAnim.animateTo(1.03f, tween(800, easing = LinearOutSlowInEasing))
        pulseAnim.animateTo(1.0f, tween(800, easing = LinearOutSlowInEasing))
    }

    val totalScale = baseScale * pulseAnim.value

    Canvas(modifier = modifier.size(24.dp)) {
        val cx = size.width / 2
        val cy = size.height / 2

        val showDouble = speed > 2.0f

        val strokeWidth = size.width * 0.12f // 12% (Bold)

        val headSpan = size.width * 0.55f
        val headDepth = headSpan / 2
        val gap = headDepth * 0.5f // Gap between arrow tip and 2nd head

        val arrowLenFactor = if (showDouble) 0.35f else 0.6f
        val arrowLen = size.width * arrowLenFactor * totalScale
        val totalVisualLen = if (showDouble) arrowLen + gap + headDepth else arrowLen

        rotate(rotation, pivot = Offset(cx, cy)) {
            val arrStyle = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            val outlineStyle = Stroke(
                width = strokeWidth * 1.35f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            val shadowStyle = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )

            // Centering Logic: center of totalVisualLen sits at cx
            val startX = cx - totalVisualLen / 2

            val arrowTipX = startX + arrowLen
            val arrowWingX = arrowTipX - headDepth

            val pArrow = Path().apply {
                moveTo(startX, cy)
                lineTo(arrowTipX, cy)

                moveTo(arrowWingX, cy - headSpan / 2)
                lineTo(arrowTipX, cy)
                lineTo(arrowWingX, cy + headSpan / 2)
            }
            if (outlineColor == null) {
                shadowColor?.let {
                    translate(top = strokeWidth * 0.38f) {
                        drawPath(path = pArrow, color = it.copy(alpha = it.alpha * 0.58f), style = shadowStyle)
                    }
                }
            }
            outlineColor?.let { drawPath(path = pArrow, color = it, style = outlineStyle) }
            drawPath(path = pArrow, color = color, style = arrStyle)

            if (showDouble) {
                val secondTipX = arrowTipX + gap + headDepth
                val secondWingX = arrowTipX + gap

                val pSecond = Path().apply {
                    moveTo(secondWingX, cy - headSpan / 2)
                    lineTo(secondTipX, cy)
                    lineTo(secondWingX, cy + headSpan / 2)
                }
                if (outlineColor == null) {
                    shadowColor?.let {
                        translate(top = strokeWidth * 0.38f) {
                            drawPath(path = pSecond, color = it.copy(alpha = it.alpha * 0.58f), style = shadowStyle)
                        }
                    }
                }
                outlineColor?.let { drawPath(path = pSecond, color = it, style = outlineStyle) }
                drawPath(path = pSecond, color = color, style = arrStyle)
            }
        }
    }
}
