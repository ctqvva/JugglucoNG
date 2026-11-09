@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import tk.glucodata.R
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.components.TrendIndicator
import tk.glucodata.ui.theme.AppTypography
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics

enum class AlarmSeverity { LOW, HIGH, NEUTRAL }

private const val ALARM_MOTION_ACTIVE_MS = 8_000L

private enum class TrendDirection { UP, DOWN, FLAT, UNKNOWN }

private data class Trend(
    val direction: TrendDirection,
    val verticalSign: Int
)

private data class AlarmTypographyChoice(
    val fontFamily: FontFamily,
    val fontWeight: FontWeight
)

@Composable
fun AlarmScreen(
    primaryGlucose: String,
    secondaryGlucose: String?,
    alarmLabel: String,
    supportingText: String,
    severity: AlarmSeverity,
    trendResult: TrendEngine.TrendResult,
    timeText: String,
    onSnooze: () -> Unit,
    /** Null on a restricted phone that may only snooze: Snooze becomes the main action. */
    onDismiss: (() -> Unit)?,
    /** The past hour under the value, for alarms about glucose; null draws none. */
    trace: AlarmTrace? = null
) {
    val colorScheme = remember(severity) { severityColorScheme(severity) }
    val trend = remember(trendResult) { resolveTrend(trendResult) }

    // Expressive motion, as in the app: the trace and the buttons move on its springs.
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography
    ) {
        val typographyChoice = rememberAlarmTypographyChoice()
        PixelAlarmContent(
            primaryGlucose = primaryGlucose,
            alarmLabel = alarmLabel,
            supportingText = supportingText,
            trend = trend,
            trendResult = trendResult,
            typographyChoice = typographyChoice,
            trace = trace,
            onSnooze = onSnooze,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun PixelAlarmContent(
    primaryGlucose: String,
    alarmLabel: String,
    supportingText: String,
    trend: Trend,
    trendResult: TrendEngine.TrendResult,
    typographyChoice: AlarmTypographyChoice,
    trace: AlarmTrace?,
    onSnooze: () -> Unit,
    onDismiss: (() -> Unit)?
) {
    BackHandler(enabled = true) {}

    var visible by remember { mutableStateOf(false) }
    var motionActive by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        visible = true
        // This activity can sit on the lockscreen; keep frame-producing motion finite.
        delay(ALARM_MOTION_ACTIVE_MS)
        motionActive = false
    }

    val compact = rememberAdaptiveWindowMetrics().isCompact
    val settledArrowScale = if (compact) 4.72f else 5.72f
    val arrowScale = if (motionActive) {
        val infiniteTransition = rememberInfiniteTransition(label = "alarm-motion")
        val animatedArrowScale by infiniteTransition.animateFloat(
            initialValue = if (compact) 4.6f else 5.6f,
            targetValue = if (compact) 4.85f else 5.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alarm-arrow-scale"
        )
        animatedArrowScale
    } else {
        settledArrowScale
    }
    val heroOffsetY by animateFloatAsState(
        targetValue = if (visible) 0f else (-trend.verticalSign * 72f),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 210f),
        label = "hero-entry"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        // Snooze and Stop are measured first and keep their size; the title and value share what
        // is left, and scroll when large text or a short (landscape) window leaves too little.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        // The spacers' weights share the height that fits on screen.
                        .heightIn(min = maxHeight)
                        .statusBarsPadding()
                        .padding(horizontal = 24.dp)
                ) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(animationSpec = tween(240)) +
                            slideInVertically(animationSpec = tween(320), initialOffsetY = { -it / 5 })
                    ) {
                        AlarmHeader(
                            alarmLabel = alarmLabel,
                            supportingText = supportingText,
                            compact = compact,
                            fontFamily = typographyChoice.fontFamily,
                            modifier = Modifier.padding(top = if (compact) 28.dp else 40.dp)
                        )
                    }

                    // With a trace below it the value sits higher, so the two read as one block.
                    val aboveHero = when {
                        trace != null -> if (compact) 0.36f else 0.44f
                        compact -> 0.58f
                        else -> 0.68f
                    }
                    Spacer(modifier = Modifier.weight(aboveHero))

                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(animationSpec = tween(320, delayMillis = 60)) +
                            slideInVertically(animationSpec = tween(420, delayMillis = 60), initialOffsetY = { -trend.verticalSign * it / 4 })
                    ) {
                        HeroBlock(
                            primaryGlucose = primaryGlucose,
                            trend = trend,
                            trendResult = trendResult,
                            compact = compact,
                            typographyChoice = typographyChoice,
                            motionActive = motionActive,
                            arrowScale = arrowScale,
                            modifier = Modifier.offset { IntOffset(0, heroOffsetY.roundToInt()) }
                        )
                    }

                    if (trace != null) {
                        AlarmTraceLine(
                            trace = trace,
                            motionActive = motionActive,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                // Edge to edge: out through the column's 24dp sides.
                                .bleedHorizontally(24.dp)
                                .fillMaxWidth()
                                .height(if (compact) 112.dp else 144.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(if (compact) 0.9f else 1.08f))
                }
            }

            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.padding(horizontal = 24.dp),
                enter = fadeIn(animationSpec = tween(360, delayMillis = 150)) +
                    slideInVertically(animationSpec = tween(400, delayMillis = 150), initialOffsetY = { it / 3 })
            ) {
                ActionDock(
                    compact = compact,
                    onSnooze = onSnooze,
                    onDismiss = onDismiss,
                    modifier = Modifier.padding(bottom = if (compact) 16.dp else 24.dp)
                )
            }
        }
    }
}

@Composable
private fun AlarmHeader(
    alarmLabel: String,
    supportingText: String,
    compact: Boolean,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val normalizedLabel = remember(alarmLabel) { alarmLabel.trim().replace(Regex("\\s+"), " ") }
    val labelWords = remember(normalizedLabel) {
        normalizedLabel.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val displayLabel = remember(labelWords) { formatAlarmLabel(labelWords) }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val titleFontSize = remember(displayLabel, labelWords, compact, maxWidth) {
            adaptiveAlarmTitleSize(displayLabel, labelWords, compact, maxWidth)
        }
        val titleLineHeight = (titleFontSize.value + if (displayLabel.contains('\n')) 5f else 7f).sp

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = displayLabel,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = fontFamily,
                    fontSize = titleFontSize,
                    lineHeight = titleLineHeight,
                    // A step up from hairline: the alarm's name has to read at arm's length.
                    fontWeight = FontWeight.W300,
                    letterSpacing = 0.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // What the alert actually says beyond its type name: expiry lead
            // time, missed-reading duration, forecast horizon.
            if (supportingText.isNotBlank()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.W300,
                        letterSpacing = 0.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HeroBlock(
    primaryGlucose: String,
    trend: Trend,
    trendResult: TrendEngine.TrendResult,
    compact: Boolean,
    typographyChoice: AlarmTypographyChoice,
    motionActive: Boolean,
    arrowScale: Float,
    modifier: Modifier = Modifier
) {
    val motionPhase = if (motionActive) {
        val motionTransition = rememberInfiniteTransition(label = "alarm-trend-motion")
        val animatedMotionPhase by motionTransition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1450),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alarm-trend-phase"
        )
        animatedMotionPhase
    } else {
        0f
    }
    // The arrow moves along the way it points: a 45-degree arrow bobbing straight up and down
    // (or a flat one sliding sideways while tilted) contradicts its own direction.
    val arrowAngle = remember(trendResult.velocity) {
        Math.toRadians(tk.glucodata.TrendArrowAngle.rotationDegrees(trendResult.velocity).toDouble())
    }
    val bob = if (trend.direction == TrendDirection.UNKNOWN) 0f else motionPhase * if (compact) 7f else 9f
    val arrowOffsetX = bob * kotlin.math.cos(arrowAngle).toFloat()
    val arrowOffsetY = bob * kotlin.math.sin(arrowAngle).toFloat()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val textMeasurer = rememberTextMeasurer()
            val density = androidx.compose.ui.platform.LocalDensity.current
            val availableWidthPx = with(density) { maxWidth.roundToPx() }
            val candidateSizes = remember(compact) {
                if (compact) {
                    listOf(120.sp, 112.sp, 104.sp, 96.sp, 88.sp, 80.sp, 72.sp, 64.sp, 56.sp, 48.sp, 40.sp, 32.sp)
                } else {
                    listOf(146.sp, 136.sp, 126.sp, 116.sp, 106.sp, 96.sp, 86.sp, 76.sp, 66.sp, 56.sp, 48.sp, 40.sp, 32.sp)
                }
            }
            val baseStyle = MaterialTheme.typography.displayLarge.copy(
                fontFamily = typographyChoice.fontFamily,
                fontWeight = typographyChoice.fontWeight,
                letterSpacing = 0.sp,
                fontFeatureSettings = "tnum"
            )
            val valueFontSize = remember(primaryGlucose, availableWidthPx, candidateSizes, baseStyle) {
                candidateSizes.firstOrNull { size ->
                    textMeasurer.measure(
                        text = AnnotatedString(primaryGlucose),
                        style = baseStyle.copy(
                            fontSize = size,
                            lineHeight = (size.value + 2f).sp
                        ),
                        maxLines = 1
                    ).size.width <= availableWidthPx
                } ?: candidateSizes.last()
            }

            Text(
                text = primaryGlucose,
                style = baseStyle.copy(
                    fontSize = valueFontSize,
                    lineHeight = (valueFontSize.value + 2f).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(
            modifier = Modifier
                .padding(start = if (compact) 8.dp else 12.dp)
                .size(if (compact) 116.dp else 144.dp)
                .offset { IntOffset(arrowOffsetX.roundToInt(), arrowOffsetY.roundToInt()) },
            contentAlignment = Alignment.Center
        ) {
            TrendIndicator(
                trendResult = trendResult,
                modifier = Modifier
                    .size(24.dp)
                    .scale(arrowScale),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ActionDock(
    compact: Boolean,
    onSnooze: () -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onDismiss != null) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    // Half of Stop at least, and as wide as its label needs: on a narrow
                    // (low-dp) screen a fixed share was too small and broke the word.
                    FilledTonalButton(
                        onClick = onSnooze,
                        modifier = Modifier
                            .widthIn(min = maxWidth * 0.5f, max = maxWidth)
                            .heightIn(min = if (compact) 58.dp else 62.dp),
                        shapes = ButtonDefaults.shapes(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.snooze),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            // The one large button stops the alarm; where stopping is not allowed it
            // snoozes, with the same weight, so the alarm is still answered in one tap.
            Button(
                onClick = onDismiss ?: onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (compact) 82.dp else 90.dp),
                shapes = ButtonDefaults.shapes(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)),
                contentPadding = ButtonDefaults.ContentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(if (onDismiss != null) R.string.stop else R.string.snooze),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        letterSpacing = (-0.1).sp
                    )
                )
            }
        }
    }
}

@Composable
private fun rememberAlarmTypographyChoice(): AlarmTypographyChoice {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
    }
    val fontFamilyPref = prefs.getInt("notification_font_family", 0)
    val fontWeightPref = prefs.getInt("notification_font_weight", 400).coerceIn(300, 500)
    val fontFamily = if (fontFamilyPref == 1) {
        FontFamily.SansSerif
    } else {
        AppTypography.displayLarge.fontFamily ?: FontFamily.SansSerif
    }
    return remember(fontFamily, fontWeightPref) {
        AlarmTypographyChoice(
            fontFamily = fontFamily,
            fontWeight = FontWeight(fontWeightPref)
        )
    }
}

private fun severityColorScheme(severity: AlarmSeverity) = when (severity) {
    AlarmSeverity.LOW -> darkColorScheme(
        primary = Color(0xFFFFB4A8),
        onPrimary = Color(0xFF561E10),
        primaryContainer = Color(0xFF733428),
        onPrimaryContainer = Color(0xFFFFDAD3),
        secondary = Color(0xFFE7BDB5),
        onSecondary = Color(0xFF442A24),
        secondaryContainer = Color(0xFF5D3F39),
        onSecondaryContainer = Color(0xFFFFDAD3),
        surface = Color(0xFF1A1110),
        onSurface = Color(0xFFF1DFDB),
        surfaceVariant = Color(0xFF534340),
        onSurfaceVariant = Color(0xFFD8C2BD),
        surfaceContainerHigh = Color(0xFF2D201E),
        surfaceContainerHighest = Color(0xFF392B28),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        outline = Color(0xFFA08C88)
    )
    AlarmSeverity.HIGH -> darkColorScheme(
        primary = Color(0xFFFFB870),
        onPrimary = Color(0xFF4A2800),
        primaryContainer = Color(0xFF6A3C00),
        onPrimaryContainer = Color(0xFFFFDDB8),
        secondary = Color(0xFFDEC3A2),
        onSecondary = Color(0xFF3D2E17),
        secondaryContainer = Color(0xFF55442B),
        onSecondaryContainer = Color(0xFFFBDEBC),
        surface = Color(0xFF1B1610),
        onSurface = Color(0xFFF0E0D0),
        surfaceVariant = Color(0xFF52453A),
        onSurfaceVariant = Color(0xFFD6C4B5),
        surfaceContainerHigh = Color(0xFF2E251C),
        surfaceContainerHighest = Color(0xFF3A3026),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        outline = Color(0xFF9E8E81)
    )
    AlarmSeverity.NEUTRAL -> darkColorScheme(
        primary = Color(0xFFA4C9FF),
        onPrimary = Color(0xFF003060),
        primaryContainer = Color(0xFF0E4882),
        onPrimaryContainer = Color(0xFFD3E4FF),
        secondary = Color(0xFFBCC7DC),
        onSecondary = Color(0xFF273141),
        secondaryContainer = Color(0xFF3D4758),
        onSecondaryContainer = Color(0xFFD8E3F8),
        surface = Color(0xFF111418),
        onSurface = Color(0xFFE0E2E8),
        surfaceVariant = Color(0xFF43474E),
        onSurfaceVariant = Color(0xFFC3C6CF),
        surfaceContainerHigh = Color(0xFF1F2228),
        surfaceContainerHighest = Color(0xFF2A2D33),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        outline = Color(0xFF8D9199)
    )
}

private fun resolveTrend(trendResult: TrendEngine.TrendResult): Trend {
    val velocity = trendResult.velocity
    if (!velocity.isFinite() || trendResult.state == TrendEngine.TrendState.Unknown) {
        return Trend(TrendDirection.UNKNOWN, 0)
    }

    return when (trendResult.state) {
        TrendEngine.TrendState.DoubleUp,
        TrendEngine.TrendState.SingleUp,
        TrendEngine.TrendState.FortyFiveUp -> Trend(TrendDirection.UP, -1)

        TrendEngine.TrendState.Flat -> Trend(TrendDirection.FLAT, 0)

        TrendEngine.TrendState.FortyFiveDown,
        TrendEngine.TrendState.SingleDown,
        TrendEngine.TrendState.DoubleDown -> Trend(TrendDirection.DOWN, 1)

        TrendEngine.TrendState.Unknown -> Trend(TrendDirection.UNKNOWN, 0)
    }
}

private fun formatAlarmLabel(words: List<String>): String {
    return when (words.size) {
        0 -> ""
        1 -> words.first()
        2 -> words.joinToString(separator = "\n")
        else -> {
            val trailingGroupSize = if ((words.lastOrNull()?.length ?: 0) <= 2) 2 else 1
            val firstLine = words.dropLast(trailingGroupSize).joinToString(" ")
            val secondLine = words.takeLast(trailingGroupSize).joinToString(" ")
            listOf(firstLine, secondLine).filter { it.isNotBlank() }.joinToString("\n")
        }
    }
}

private fun adaptiveAlarmTitleSize(
    displayLabel: String,
    words: List<String>,
    compact: Boolean,
    availableWidth: Dp
) = (
    when {
        availableWidth < 280.dp -> 58
        availableWidth < 320.dp -> 64
        availableWidth < 360.dp -> 72
        else -> 80
    } -
        (if (compact) 4 else 0) -
        when ((words.maxOfOrNull { it.length } ?: 0)) {
            in 12..Int.MAX_VALUE -> 16
            in 9..11 -> 11
            in 7..8 -> 6
            in 6..6 -> 3
            else -> 0
        } -
        when {
            displayLabel.contains('\n') -> 6
            words.size >= 2 -> 3
            else -> 0
        }
    ).coerceAtLeast(44).sp

/** Lays the content out [side] wider on each end than its slot, reaching through the parent's padding. */
private fun Modifier.bleedHorizontally(side: Dp) = layout { measurable, constraints ->
    val sidePx = side.roundToPx()
    val wide = constraints.copy(
        minWidth = constraints.minWidth + sidePx * 2,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + sidePx * 2 else constraints.maxWidth
    )
    val placeable = measurable.measure(wide)
    layout(placeable.width - sidePx * 2, placeable.height) {
        placeable.place(-sidePx, 0)
    }
}
