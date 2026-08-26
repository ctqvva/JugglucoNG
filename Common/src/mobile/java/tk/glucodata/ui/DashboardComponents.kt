package tk.glucodata.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import tk.glucodata.ui.theme.displayLargeExpressive
import tk.glucodata.ui.theme.labelSmallPrim
import tk.glucodata.ui.theme.labelLargeExpressive
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.StableModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.key
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tk.glucodata.ui.theme.displayLargeExpressive
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.rotate as modifierRotate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.lerp
import kotlin.math.cos
import kotlin.math.sin
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState
import androidx.compose.ui.graphics.toArgb
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.TrendProjection
import tk.glucodata.Notify
import tk.glucodata.UiRefreshBus
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.util.AdaptiveContentWidthClass
import tk.glucodata.ui.util.adaptiveContentWidthClass
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics

private data class TrendCornerWeights(
    val topStart: Float,
    val topEnd: Float,
    val bottomEnd: Float,
    val bottomStart: Float
)

private data class GlucoseHeroTone(
    val tint: Color,
    val blendFraction: Float
)

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)

private fun fallbackLowThreshold(isMmol: Boolean): Float = GlucoseRangeColors.defaultLow(isMmol)

private fun fallbackHighThreshold(isMmol: Boolean): Float = GlucoseRangeColors.defaultHigh(isMmol)

private fun fallbackVeryLowThreshold(isMmol: Boolean): Float = GlucoseRangeColors.defaultVeryLow(isMmol)

private fun fallbackVeryHighThreshold(isMmol: Boolean): Float = GlucoseRangeColors.defaultVeryHigh(isMmol)

// The band/severity maths lives in tk.glucodata.GlucoseValueTone so the watch
// paints from the same computation instead of its own approximation.
private fun glucoseHeroTone(
    value: Float?,
    isFreshData: Boolean,
    isDark: Boolean,
    isMmol: Boolean,
    targetLow: Float,
    targetHigh: Float,
    veryLowThreshold: Float,
    veryHighThreshold: Float
): GlucoseHeroTone? = tk.glucodata.GlucoseValueTone.heroTone(
    value = value,
    isDark = isDark,
    isMmol = isMmol,
    targetLow = targetLow,
    targetHigh = targetHigh,
    veryLowThreshold = veryLowThreshold,
    veryHighThreshold = veryHighThreshold,
    isFreshData = isFreshData
)?.let { tone ->
    GlucoseHeroTone(tint = Color(tone.tintArgb), blendFraction = tone.blendFraction)
}

private fun trendCornerWeightsFromVelocity(velocity: Float): TrendCornerWeights {
    val angleDeg = (-velocity * 25f).coerceIn(-90f, 90f)
    val angleRad = Math.toRadians(angleDeg.toDouble())
    val dirX = cos(angleRad).toFloat()
    val dirY = sin(angleRad).toFloat()

    fun cornerRoundWeight(cornerX: Float, cornerY: Float): Float {
        val dot = ((dirX * cornerX + dirY * cornerY) * 0.70710677f).coerceIn(-1f, 1f)
        return if (dot >= 0f) {
            // Toward direction: sharper as dot increases.
            lerpFloat(0.88f, 0.08f, dot)
        } else {
            // Opposite side: extra rounded.
            lerpFloat(0.88f, 1f, -dot)
        }
    }

    return TrendCornerWeights(
        topStart = cornerRoundWeight(-1f, -1f),
        topEnd = cornerRoundWeight(1f, -1f),
        bottomEnd = cornerRoundWeight(1f, 1f),
        bottomStart = cornerRoundWeight(-1f, 1f)
    )
}

private fun directionalRadius(weight: Float, min: Dp, max: Dp): Dp = lerp(min, max, weight.coerceIn(0f, 1f))

private const val CalibrationDeleteSwipeThresholdFraction = 0.4f

private suspend fun androidx.compose.material3.SnackbarHostState?.showCalibrationUndoSnackbar(
    message: String,
    actionLabel: String
): androidx.compose.material3.SnackbarResult? {
    val hostState = this ?: return null
    hostState.currentSnackbarData?.dismiss()
    val result = hostState.showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = androidx.compose.material3.SnackbarDuration.Short
    )
    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
        hostState.currentSnackbarData?.dismiss()
    }
    return result
}

@Composable
fun DashboardCombinedHeader(

    currentGlucose: String,
    currentRate: Float,
    viewMode: Int,
    latestPoint: GlucosePoint?,
    sensorName: String,
    daysRemaining: String,
    sensorStatus: String,
    activeSensors: List<String> = emptyList(),
    sensorProgress: Float = 0f,
    sensorHoursRemaining: Long = 999L,
    currentDay: Int = 0,
    history: List<GlucosePoint> = emptyList(),
    calibratedValue: Float? = null,
    currentSnapshot: CurrentDisplaySource.Snapshot? = null,
    dataState: DisplayDataState.Status? = null,
    isMmol: Boolean,
    targetLow: Float = fallbackLowThreshold(isMmol),
    targetHigh: Float = fallbackHighThreshold(isMmol),
    veryLowThreshold: Float = fallbackVeryLowThreshold(isMmol),
    veryHighThreshold: Float = fallbackVeryHighThreshold(isMmol),
    valueRangeColorsEnabled: Boolean = false,
    arrowForecastColorsEnabled: Boolean = false,
    showDelta: Boolean = false,
    deltaIntervalMinutes: Int = tk.glucodata.GlucoseDelta.DEFAULT_INTERVAL_MINUTES,
    peerReadings: List<tk.glucodata.ui.viewmodel.DashboardViewModel.PeerCurrentReading> = emptyList(),
    onPeerReadingClick: (String) -> Unit = {},
    onHeroClick: () -> Unit = {}
) {
    // Determine Colors based on logic
    // Glucose: primary tonal surface with a very light range tint when fresh data is out of range.
    val glucoseBaseContainerColor = MaterialTheme.colorScheme.primaryContainer
    val glucoseContentColor = MaterialTheme.colorScheme.onPrimaryContainer

    // Sensor: Surface Variant (Tonal) vs Tertiary Container (Expiring)
    // FIX: Replaced fragile string matching (failed in Dutch, false positive in English for "1 days in use")
    // with robust progress-based logic. >90% progress (approx <1.4 days left) triggers Expiring state.
    val isExpiring = sensorProgress > 0.90f
    // FIX: User reported "Silly" contrast in Dark Mode with surfaceVariant (too bright/green).
    // Switched to primaryContainer to match the Glucose Card (Left) for a cohesive dark look.
    val sensorContainerColor = if (isExpiring) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) // Darker variant
    val sensorContentColor = if (isExpiring) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer

    // Advanced Trend
    val trendResult = remember(history, latestPoint, currentSnapshot) {
        if (history.isNotEmpty()) {
             // Map Kotlin UI points to Native Java points for shared TrendEngine
             val nativeList = history.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
             // The canonical trend list (live-augmented, newest-anchored window) —
             // the same points the notification and broadcast arrows regress over.
             val trendPoints = tk.glucodata.DisplayTrendSource.resolveTrendPoints(nativeList, currentSnapshot, null)
             tk.glucodata.logic.TrendEngine.calculateTrend(trendPoints, useRaw = (viewMode == 1 || viewMode == 3), isMmol = isMmol)
        } else if (latestPoint != null) {
            // Fallback
             val nativeList = listOf(tk.glucodata.GlucosePoint(latestPoint.timestamp, latestPoint.value, latestPoint.rawValue))
             tk.glucodata.logic.TrendEngine.calculateTrend(nativeList, useRaw = (viewMode == 1 || viewMode == 3), isMmol = isMmol)
        } else {
            tk.glucodata.logic.TrendEngine.TrendResult(tk.glucodata.logic.TrendEngine.TrendState.Unknown, 0f, 0f, 0f, 0f)
        }
    }
    // "Δ" readout: the measured change over the last ~5 minutes — a raw
    // number to sanity-check the estimated arrow against. Same computation as
    // the per-row deltas in the readings list, anchored at the newest point.
    val heroDeltaText = if (showDelta) {
        remember(history, isMmol, deltaIntervalMinutes) {
            history.lastOrNull()?.let { newest ->
                readingDeltaTexts(listOf(newest.timestamp), history, isMmol, deltaIntervalMinutes)
                    .first()
            }
        }
    } else {
        null
    }
    val adaptiveMetrics = rememberAdaptiveWindowMetrics()
    val isLandscape = adaptiveMetrics.isLandscape
    val cornerWeights = remember(trendResult.velocity) { trendCornerWeightsFromVelocity(trendResult.velocity) }
    val cornerAnimSpec = spring<Dp>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow
    )

    val heroTopStart by animateDpAsState(
        targetValue = directionalRadius(cornerWeights.topStart, 22.dp, 52.dp),
        animationSpec = cornerAnimSpec,
        label = "HeroTopStartRadius"
    )
    val heroTopEnd by animateDpAsState(
        targetValue = directionalRadius(cornerWeights.topEnd, 8.dp, 24.dp),
        animationSpec = cornerAnimSpec,
        label = "HeroTopEndRadius"
    )
    val heroBottomEnd by animateDpAsState(
        targetValue = directionalRadius(cornerWeights.bottomEnd, 8.dp, 24.dp),
        animationSpec = cornerAnimSpec,
        label = "HeroBottomEndRadius"
    )
    val heroBottomStart by animateDpAsState(
        targetValue = directionalRadius(cornerWeights.bottomStart, 22.dp, 46.dp),
        animationSpec = cornerAnimSpec,
        label = "HeroBottomStartRadius"
    )

    val sensorTopStart by animateDpAsState(
        targetValue = directionalRadius(cornerWeights.topStart, 8.dp, 24.dp),
        animationSpec = cornerAnimSpec,
        label = "SensorTopStartRadius"
    )
    val sensorTopEnd by animateDpAsState(
        targetValue = directionalRadius(cornerWeights.topEnd, 20.dp, 46.dp),
        animationSpec = cornerAnimSpec,
        label = "SensorTopEndRadius"
    )
    val sensorBottomEnd by animateDpAsState(
        targetValue = directionalRadius(cornerWeights.bottomEnd, 22.dp, 52.dp),
        animationSpec = cornerAnimSpec,
        label = "SensorBottomEndRadius"
    )
    val sensorBottomStart by animateDpAsState(
        targetValue = directionalRadius(cornerWeights.bottomStart, 8.dp, 24.dp),
        animationSpec = cornerAnimSpec,
        label = "SensorBottomStartRadius"
    )

    // 1. Resolve Values using shared logic (with calibration if active)
    val refreshRevision by UiRefreshBus.revision.collectAsState(initial = 0L)
    val resolvedCurrentSnapshot = currentSnapshot ?: remember(refreshRevision, sensorName, currentGlucose, currentRate, latestPoint?.timestamp, viewMode) {
        CurrentDisplaySource.resolveCurrent(
            maxAgeMillis = Notify.glucosetimeout,
            preferredSensorId = sensorName.ifBlank { null }
        )
    }
    val sensorPresent = sensorName.isNotBlank() || activeSensors.isNotEmpty()
    val sensorDisplayName = remember(refreshRevision, sensorName) {
        tk.glucodata.drivers.ManagedSensorRuntime.resolveUiSnapshot(sensorName)
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: sensorName
    }
    val resolvedDataState = dataState ?: remember(
        resolvedCurrentSnapshot?.timeMillis,
        latestPoint?.timestamp,
        sensorPresent
    ) {
        DisplayDataState.resolve(
            sensorPresent = sensorPresent,
            currentTimestampMillis = resolvedCurrentSnapshot?.timeMillis ?: 0L,
            latestHistoryTimestampMillis = latestPoint?.timestamp ?: 0L
        )
    }
    val statusCopy = rememberDashboardDataStatusText(
        dataState = resolvedDataState,
        sensorStatus = sensorStatus
    )
    val isFreshData = resolvedDataState.isFresh
    val dvs = remember(resolvedCurrentSnapshot, latestPoint, viewMode, calibratedValue, isFreshData) {
        if (!isFreshData) {
            null
        } else {
            resolvedCurrentSnapshot?.displayValues ?: latestPoint?.let {
                getDisplayValues(it, viewMode, "", calibratedValue)
            }
        }
    }

    val primaryText = dvs?.primaryStr ?: currentGlucose
    val secondaryText = dvs?.secondaryStr
    val tertiaryText = dvs?.tertiaryStr
    val hasSecondary = secondaryText != null
    val hasTertiary = tertiaryText != null
    val hasThreeValues = hasSecondary && hasTertiary
    val isDark = isSystemInDarkTheme()
    val glucoseTone = remember(
        dvs?.primaryValue,
        isFreshData,
        isDark,
        isMmol,
        targetLow,
        targetHigh,
        veryLowThreshold,
        veryHighThreshold,
        // Recompose the band tint when the user switches palette / edits a band.
        GlucosePaletteState.revision
    ) {
        glucoseHeroTone(
            value = dvs?.primaryValue,
            isFreshData = isFreshData,
            isDark = isDark,
            isMmol = isMmol,
            targetLow = targetLow,
            targetHigh = targetHigh,
            veryLowThreshold = veryLowThreshold,
            veryHighThreshold = veryHighThreshold
        )
    }
    // Optional GDH-style traffic coloring of the value itself: green in
    // target range, yellow up to the alarm bounds, red beyond.
    val heroValueColor = if (valueRangeColorsEnabled && isFreshData) {
        val fallbackArgb = glucoseContentColor.toArgb()
        remember(dvs?.primaryValue, isDark, isMmol, targetLow, targetHigh, veryLowThreshold, veryHighThreshold, fallbackArgb) {
            Color(
                GlucoseRangeColors.trafficColorForValue(
                    dvs?.primaryValue ?: Float.NaN,
                    targetLow,
                    targetHigh,
                    veryLowThreshold,
                    veryHighThreshold,
                    isDark,
                    isMmol,
                    fallbackArgb
                )
            )
        }
    } else {
        glucoseContentColor
    }
    // Arrow: optionally colored by the 30-minute linear forecast (the value
    // says where you are, the arrow where you're heading). isFreshData uses
    // the display timeout, which is too lenient for a forecast: after a
    // reconnect gap the trend only describes the pre-gap drop, so the
    // classifier additionally caps the data age.
    val heroArrowColor = if (arrowForecastColorsEnabled && isFreshData) {
        val forecastRisk = remember(
            dvs?.primaryValue, trendResult, isMmol, resolvedDataState,
            targetLow, targetHigh, veryLowThreshold, veryHighThreshold
        ) {
            val toMgdl = if (isMmol) 18.016f else 1f
            TrendProjection.classify(
                (dvs?.primaryValue ?: Float.NaN) * toMgdl,
                trendResult.velocity,
                resolvedDataState.ageMillis,
                TrendProjection.DEFAULT_HORIZON_MINUTES,
                targetLow * toMgdl,
                targetHigh * toMgdl,
                veryLowThreshold * toMgdl,
                veryHighThreshold * toMgdl
            )
        }
        when (forecastRisk) {
            TrendProjection.OUT -> Color(GlucoseRangeColors.valueOut(isDark))
            TrendProjection.BORDERLINE -> Color(GlucoseRangeColors.valueBorderline(isDark))
            else -> heroValueColor
        }
    } else {
        heroValueColor
    }
    val targetGlucoseContainerColor = glucoseTone?.let { tone ->
        lerpColor(glucoseBaseContainerColor, tone.tint, tone.blendFraction)
    } ?: glucoseBaseContainerColor
    val glucoseContainerColor by animateColorAsState(
        targetValue = targetGlucoseContainerColor,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "HeroRangeContainerColor"
    )

    val heroCardContent: @Composable () -> Unit = {
            var heroContentWidthPx by remember { mutableStateOf(0) }
            val density = LocalDensity.current

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { heroContentWidthPx = it.width }
            ) {
                val heroWidthDp = with(density) { heroContentWidthPx.toDp() }
                val heroWidthClass = if (adaptiveMetrics.isCompact && !isLandscape) {
                    // A low-density / large-font phone reports abundant dp width even though
                    // the physical canvas is still a phone. Width alone would select the
                    // largest display type and make the header dominate the viewport.
                    AdaptiveContentWidthClass.Compact
                } else if (heroContentWidthPx > 0) {
                    adaptiveContentWidthClass(
                        width = heroWidthDp,
                        compactMax = 180.dp,
                        mediumMax = 260.dp
                    )
                } else {
                    AdaptiveContentWidthClass.Medium
                }
                val resolvedStartPadding = when {
                    isLandscape -> 20.dp
                    heroWidthClass == AdaptiveContentWidthClass.Compact -> 16.dp
                    heroWidthClass == AdaptiveContentWidthClass.Medium -> 20.dp
                    else -> 28.dp
                }
                val resolvedEndPadding = if (heroWidthClass == AdaptiveContentWidthClass.Compact) 12.dp else 16.dp
                // Multi-sensor mode: the value + chip are scaled down (so they
                // read as a balanced cluster), and the hero CARD is pinned to the
                // single-sensor content height (heroContentMinHeight below) so it
                // never grows or shrinks — the scaled cluster just centers inside it.
                val isMultiHero = peerReadings.isNotEmpty()
                val heroMultiSensorScale = if (isMultiHero) 0.74f else 1f
                // Single-sensor padding defines the target card height. Multi mode
                // uses a smaller inner padding so the scaled value + chip cluster
                // fills the SAME pinned height (heroContentMinHeight) below.
                val singleVerticalPadding = if (heroWidthClass == AdaptiveContentWidthClass.Compact) 10.dp else 12.dp
                val resolvedVerticalPadding = if (isMultiHero) 4.dp else singleVerticalPadding
                // Full (single-sensor) value style. The card height is pinned to
                // the MEASURED height of this style (incl. font padding) + padding,
                // so multi-sensor mode renders at the exact same card height — not
                // the lineHeight estimate, which understated it and made multi smaller.
                val fullPrimaryStyle = (
                    if (heroWidthClass == AdaptiveContentWidthClass.Compact) MaterialTheme.typography.displayMedium
                    else MaterialTheme.typography.displayLargeExpressive
                ).copy(fontFeatureSettings = "tnum")
                val heroTextMeasurer = rememberTextMeasurer()
                val heroContentMinHeight = remember(fullPrimaryStyle, density, singleVerticalPadding) {
                    with(density) {
                        heroTextMeasurer.measure("0,0", style = fullPrimaryStyle, maxLines = 1)
                            .size.height.toDp()
                    } + singleVerticalPadding * 2
                }
                val resolvedTrendIconSize = (when (heroWidthClass) {
                    AdaptiveContentWidthClass.Compact -> 30.dp
                    AdaptiveContentWidthClass.Medium -> 38.dp
                    AdaptiveContentWidthClass.Expanded -> 42.dp
                } * heroMultiSensorScale)
                val resolvedClusterGap = if (heroWidthClass == AdaptiveContentWidthClass.Compact) 8.dp else 12.dp
                val primaryValueStyle = fullPrimaryStyle.scaleForHero(heroMultiSensorScale)
                val secondaryInlineStyle = when (heroWidthClass) {
                    AdaptiveContentWidthClass.Compact -> MaterialTheme.typography.headlineSmall
                    AdaptiveContentWidthClass.Medium -> MaterialTheme.typography.headlineMedium
                    AdaptiveContentWidthClass.Expanded -> MaterialTheme.typography.headlineLarge
                }.copy(fontFeatureSettings = "tnum").scaleForHero(heroMultiSensorScale)
                val slashStyle = when (heroWidthClass) {
                    AdaptiveContentWidthClass.Compact -> MaterialTheme.typography.headlineSmall
                    AdaptiveContentWidthClass.Medium -> MaterialTheme.typography.headlineMedium
                    AdaptiveContentWidthClass.Expanded -> MaterialTheme.typography.headlineLarge
                }.copy(fontFeatureSettings = "tnum").scaleForHero(heroMultiSensorScale)
                val secondaryThreeValueStyle = (
                    when (heroWidthClass) {
                        AdaptiveContentWidthClass.Compact -> MaterialTheme.typography.titleLarge.copy(
                            letterSpacing = 0.18.sp,
                            lineHeight = 24.sp
                        )
                        AdaptiveContentWidthClass.Medium -> MaterialTheme.typography.headlineMedium.copy(
                            letterSpacing = 0.10.sp,
                            lineHeight = 34.sp
                        )
                        AdaptiveContentWidthClass.Expanded -> MaterialTheme.typography.headlineLarge.copy(
                            letterSpacing = 0.06.sp,
                            lineHeight = 40.sp
                        )
                    }
                ).copy(fontFeatureSettings = "tnum").scaleForHero(heroMultiSensorScale)
                val tertiaryThreeValueStyle = (
                    when (heroWidthClass) {
                        AdaptiveContentWidthClass.Compact -> MaterialTheme.typography.titleSmall.copy(
                            letterSpacing = 0.22.sp,
                            lineHeight = 18.sp
                        )
                        AdaptiveContentWidthClass.Medium -> MaterialTheme.typography.titleMedium.copy(
                            letterSpacing = 0.16.sp,
                            lineHeight = 24.sp
                        )
                        AdaptiveContentWidthClass.Expanded -> MaterialTheme.typography.titleLarge.copy(
                            letterSpacing = 0.10.sp,
                            lineHeight = 30.sp
                        )
                    }
                ).copy(fontFeatureSettings = "tnum").scaleForHero(heroMultiSensorScale)
                val statusTitleStyle = when (heroWidthClass) {
                    AdaptiveContentWidthClass.Compact -> MaterialTheme.typography.titleMedium
                    AdaptiveContentWidthClass.Medium -> MaterialTheme.typography.titleLarge
                    AdaptiveContentWidthClass.Expanded -> MaterialTheme.typography.headlineSmall
                }.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.Medium
                )

                if (statusCopy != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = resolvedStartPadding,
                                end = resolvedEndPadding,
                                top = resolvedVerticalPadding,
                                bottom = resolvedVerticalPadding
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusCopy.title,
                            style = statusTitleStyle,
                            color = glucoseContentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )

                        Spacer(modifier = Modifier.width(resolvedClusterGap))

                        DataStatusPulse(
                            color = glucoseContentColor,
                            subdued = statusCopy.isStale
                        )
                    }
                } else if (isLandscape) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = resolvedStartPadding,
                                end = resolvedEndPadding,
                                top = resolvedVerticalPadding,
                                bottom = resolvedVerticalPadding
                            ),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                DashboardHeroPrimaryText(
                                    value = primaryText,
                                    style = primaryValueStyle,
                                    color = heroValueColor
                                )
                            }

                            HeroTrendWithDelta(
                                trendResult = trendResult,
                                arrowColor = heroArrowColor,
                                deltaText = heroDeltaText,
                                contentColor = glucoseContentColor,
                                iconSize = resolvedTrendIconSize
                            )
                        }

                        if (hasThreeValues) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = secondaryText ?: "",
                                    style = secondaryThreeValueStyle,
                                    color = glucoseContentColor.copy(alpha = 0.90f),
                                    softWrap = false,
                                    maxLines = 1
                                )
                                Text(
                                    text = "·",
                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                    color = glucoseContentColor.copy(alpha = 0.40f),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                                Text(
                                    text = tertiaryText ?: "",
                                    style = tertiaryThreeValueStyle,
                                    color = glucoseContentColor.copy(alpha = 0.66f),
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        } else if (hasSecondary) {
                            Text(
                                text = secondaryText ?: "",
                                style = secondaryInlineStyle,
                                color = glucoseContentColor.copy(alpha = 0.80f),
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        } else if (hasTertiary) {
                            Text(
                                text = tertiaryText ?: "",
                                style = tertiaryThreeValueStyle,
                                color = glucoseContentColor.copy(alpha = 0.60f),
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        if (peerReadings.isNotEmpty()) {
                            DashboardHeroPeerStrip(
                                peerReadings = peerReadings,
                                selectedSensorIds = activeSensors,
                                contentColor = glucoseContentColor,
                                onPeerClick = onPeerReadingClick,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .heightIn(min = heroContentMinHeight)
                            .padding(
                                start = resolvedStartPadding,
                                end = resolvedEndPadding,
                                top = resolvedVerticalPadding,
                                bottom = resolvedVerticalPadding
                            ),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DashboardHeroValueCluster(
                                modifier = Modifier.weight(1f),
                                primaryText = primaryText,
                                secondaryText = secondaryText,
                                tertiaryText = tertiaryText,
                                primaryStyle = primaryValueStyle,
                                secondaryInlineStyle = secondaryInlineStyle,
                                separatorStyle = slashStyle,
                                secondaryStackStyle = secondaryThreeValueStyle,
                                tertiaryStackStyle = tertiaryThreeValueStyle,
                                contentColor = glucoseContentColor,
                                primaryColor = heroValueColor
                            )

                            Spacer(modifier = Modifier.width(resolvedClusterGap))

                            HeroTrendWithDelta(
                                trendResult = trendResult,
                                arrowColor = heroArrowColor,
                                deltaText = heroDeltaText,
                                contentColor = glucoseContentColor,
                                iconSize = resolvedTrendIconSize
                            )
                        }

                        if (peerReadings.isNotEmpty()) {
                            DashboardHeroPeerStrip(
                                peerReadings = peerReadings,
                                selectedSensorIds = activeSensors,
                                contentColor = glucoseContentColor,
                                onPeerClick = onPeerReadingClick,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
    }

    val heroCard: @Composable (Modifier) -> Unit = { modifier ->
        val heroShape = RoundedCornerShape(
            topStart = heroTopStart,
            topEnd = heroTopEnd,
            bottomEnd = heroBottomEnd,
            bottomStart = heroBottomStart
        )
        val heroColors = CardDefaults.cardColors(
            containerColor = glucoseContainerColor,
            contentColor = glucoseContentColor
        )
        val stabilizedModifier = if (statusCopy != null) {
            modifier.heightIn(min = 96.dp)
        } else {
            modifier
        }
        if (statusCopy == null) {
            Card(
                onClick = onHeroClick,
                modifier = stabilizedModifier,
                colors = heroColors,
                shape = heroShape
            ) {
                heroCardContent()
            }
        } else {
            Card(
                modifier = stabilizedModifier,
                colors = heroColors,
                shape = heroShape
            ) {
                heroCardContent()
            }
        }
    }

    val sensorCard: @Composable (Modifier) -> Unit = { modifier ->
        var sensorContentWidthPx by remember { mutableStateOf(0) }
        val sensorDensity = LocalDensity.current
        Card(
            modifier = modifier,
             colors = CardDefaults.cardColors(
                containerColor = sensorContainerColor,
                contentColor = sensorContentColor
            ),
            shape = RoundedCornerShape(
                topStart = sensorTopStart,
                topEnd = sensorTopEnd,
                bottomEnd = sensorBottomEnd,
                bottomStart = sensorBottomStart
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { sensorContentWidthPx = it.width }
            ) {
                val lifecycleText = if (sensorHoursRemaining <= 24) "$sensorHoursRemaining" + "h" else daysRemaining
                val lifecycleStyle = MaterialTheme.typography.labelLarge
                val lifecycleTextMeasurer = rememberTextMeasurer()
                val lifecycleTextWidthPx = remember(lifecycleText, lifecycleStyle) {
                    lifecycleTextMeasurer.measure(
                        text = lifecycleText,
                        style = lifecycleStyle,
                        maxLines = 1
                    ).size.width
                }
                val lifecycleCalendarChromePx = with(sensorDensity) {
                    // Card padding on both sides, the text/icon gap and the icon itself.
                    (16.dp * 2 + 8.dp + 14.dp).roundToPx()
                }
                // Hide decorative line art only when this exact text/icon composition cannot
                // fit. Window density classes are intentionally irrelevant: they describe the
                // whole screen, not the real space inside this card.
                val showLifecycleCalendar = sensorContentWidthPx == 0 ||
                    lifecycleTextWidthPx + lifecycleCalendarChromePx <= sensorContentWidthPx
                // Dynamic Fill Layer
                if (sensorProgress > 0f) {
                    val fillColor = when {
                        sensorProgress > 0.95f -> MaterialTheme.colorScheme.error
                        sensorProgress > 0.80f -> MaterialTheme.colorScheme.tertiary
                        else -> androidx.compose.ui.graphics.Color(0xFF66BB6A) // Muted Green (400) - "Make it Green"
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(sensorProgress)
                            .background(fillColor.copy(alpha = 0.25f)) // Slightly increased opacity for visibility
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp), // Matched Vertical Padding (12dp)
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start
                ) {
                    // 0. Signal Quality Indicator (above sensor name)
                    if (trendResult.noiseLevel > 0f) {
                        SignalQualityIndicator(
                            noiseLevel = trendResult.noiseLevel,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    
                    // 1. Sensor Name (Top Label)
                    if (sensorDisplayName.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = sensorDisplayName,
                                style = MaterialTheme.typography.labelMedium, // M3 Standard
                                color = sensorContentColor.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false) // Allow shrinking for dots
                            )
                            
                            // Active Indicators (Dots) - Right of Name
                            // Hidden if only 1 active sensor
                            if (activeSensors.size > 1) {
                                val selectedSensorColors = remember(activeSensors) {
                                    tk.glucodata.SensorVisuals.distinctColorArgbMap(activeSensors)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                activeSensors.forEach { serial ->
                                     Box(
                                         modifier = Modifier
                                             .size(6.dp)
                                             .background(
                                                 Color(
                                                     selectedSensorColors.entries.firstOrNull { (selected, _) ->
                                                         tk.glucodata.SensorIdentity.matches(selected, serial)
                                                     }?.value ?: tk.glucodata.SensorVisuals.colorArgb(serial)
                                                 ),
                                                 androidx.compose.foundation.shape.CircleShape
                                             )
                                     )
                                     Spacer(modifier = Modifier.width(4.dp))
                                }
                            }
                        }
                    }


                    // 2. Main Status / Days (Hero Value)
                    // Priority: Status if critical, else Days "1 / 14"
                    val heroOwnsAwaitingStatus = resolvedDataState.isAwaitingData &&
                        statusCopy != null &&
                        lifecycleText.isNotEmpty()
                    val showingStatus = sensorStatus.isNotEmpty() &&
                        sensorStatus != "Ready" &&
                        !heroOwnsAwaitingStatus
                    val mainText = if (showingStatus) sensorStatus else lifecycleText
                    if (mainText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                             // Text First
                             Text(
                                text = mainText,
                                style = MaterialTheme.typography.labelLarge,
                                color = sensorContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (!showingStatus &&
                                    (sensorHoursRemaining <= 24 || showLifecycleCalendar)
                                ) {
                                    Modifier.weight(1f, fill = false)
                                } else {
                                    Modifier
                                }
                            )

                             if (!showingStatus &&
                                 (sensorHoursRemaining <= 24 || showLifecycleCalendar)
                             ) {
                                 Spacer(modifier = Modifier.width(8.dp))

                                 if (sensorHoursRemaining <= 24) {
                                      // Urgent: Hourglass with Dynamic Speed
                                      val duration = (500 + (1500 * (sensorHoursRemaining / 24f))).toInt().coerceAtLeast(500)
                                      AnimatedHourglassIcon(
                                          color = sensorContentColor.copy(alpha = 0.8f),
                                          modifier = Modifier.size(14.dp),
                                          cycleDuration = duration
                                      )
                                 } else if (showLifecycleCalendar) {
                                      // Normal: Custom Calendar with Page Flip
                                      CustomCalendarIcon(
                                          color = sensorContentColor.copy(alpha = 0.6f),
                                          modifier = Modifier.size(14.dp),
                                          currentDay = currentDay
                                      )
                                 }
                             }
                         }
                     }
                }
            }
        }
    }

    if (isLandscape) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            heroCard(Modifier.fillMaxWidth())
            sensorCard(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            heroCard(
                Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
            )
            sensorCard(
                Modifier
                    .weight(0.3f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun HeroTrendWithDelta(
    trendResult: tk.glucodata.logic.TrendEngine.TrendResult,
    arrowColor: Color,
    deltaText: String?,
    contentColor: Color,
    iconSize: androidx.compose.ui.unit.Dp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        tk.glucodata.ui.components.TrendIndicator(
            trendResult = trendResult,
            modifier = Modifier.size(iconSize),
            color = arrowColor
        )
        if (!deltaText.isNullOrEmpty()) {
            val deltaReadout = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = contentColor.copy(alpha = 0.45f),
                        fontWeight = FontWeight.Normal
                    )
                ) {
                    append("Δ")
                }
                append(" ")
                append(deltaText)
            }
            Text(
                text = deltaReadout,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = contentColor.copy(alpha = 0.75f),
                softWrap = false,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DashboardHeroPrimaryText(
    value: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (slideInVertically(
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { -it / 2 } + fadeIn(tween(200)))
                .togetherWith(
                    slideOutVertically(
                        spring(stiffness = Spring.StiffnessMedium)
                    ) { it / 2 } + fadeOut(tween(100))
                )
        },
        label = "GlucoseHeroAnimation"
    ) { animatedValue ->
        Text(
            text = animatedValue,
            style = style,
            color = color,
            softWrap = false,
            maxLines = 1,
            modifier = modifier
        )
    }
}

/**
 * Compact per-peer value chips inside the hero card. Each chip shows the
 * peer sensor's current value (and secondary lane when present) with its
 * subtle identity tint plus a small trend arrow; tapping promotes the peer
 * to the primary display sensor.
 */
@Composable
private fun DashboardHeroPeerStrip(
    peerReadings: List<tk.glucodata.ui.viewmodel.DashboardViewModel.PeerCurrentReading>,
    selectedSensorIds: List<String>,
    contentColor: Color,
    onPeerClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val selectedColors = remember(selectedSensorIds, peerReadings) {
            tk.glucodata.SensorVisuals.distinctColorArgbMap(
                selectedSensorIds.ifEmpty { peerReadings.map { it.sensorId } }
            )
        }
        peerReadings.forEach { peer ->
            val identityColor = Color(
                selectedColors.entries.firstOrNull { (selected, _) ->
                    tk.glucodata.SensorIdentity.matches(selected, peer.sensorId)
                }?.value ?: tk.glucodata.SensorVisuals.colorArgb(peer.sensorId)
            )
            val textColor = lerpColor(contentColor, identityColor, tk.glucodata.SensorVisuals.PEER_TEXT_BLEND)
            val velocity = if (peer.rate.isFinite()) peer.rate else 0f
            val peerTrend = remember(peer.sensorId, velocity) {
                tk.glucodata.logic.TrendEngine.TrendResult(
                    state = tk.glucodata.logic.TrendEngine.TrendState.Unknown,
                    velocity = velocity,
                    acceleration = 0f,
                    confidence = 1f
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(identityColor.copy(alpha = 0.14f))
                    .clickable { onPeerClick(peer.sensorId) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(identityColor.copy(alpha = 0.9f), CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = peer.primaryStr,
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = textColor,
                    softWrap = false,
                    maxLines = 1
                )
                peer.secondaryStr?.let { secondary ->
                    Text(
                        text = " · $secondary",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = textColor.copy(alpha = 0.72f),
                        softWrap = false,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                tk.glucodata.ui.components.TrendIndicator(
                    trendResult = peerTrend,
                    color = textColor,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

private enum class DashboardHeroValueLayoutMode {
    PrimaryOnly,
    InlinePair,
    InlineStack
}

private fun TextStyle.scaleForHero(factor: Float): TextStyle = copy(
    fontSize = if (fontSize != TextUnit.Unspecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight * factor else lineHeight
)

private fun fitHeroScale(availableWidthPx: Int, requiredWidthPx: Int, minScale: Float = 0.72f): Float {
    if (availableWidthPx <= 0 || requiredWidthPx <= 0 || requiredWidthPx <= availableWidthPx) return 1f
    return (availableWidthPx.toFloat() / requiredWidthPx.toFloat()).coerceIn(minScale, 1f)
}

@Composable
private fun DashboardHeroValueCluster(
    primaryText: String,
    secondaryText: String?,
    tertiaryText: String?,
    primaryStyle: TextStyle,
    secondaryInlineStyle: TextStyle,
    separatorStyle: TextStyle,
    secondaryStackStyle: TextStyle,
    tertiaryStackStyle: TextStyle,
    contentColor: Color,
    modifier: Modifier = Modifier,
    primaryColor: Color = contentColor
) {
    val hasSecondary = secondaryText != null
    val hasTertiary = tertiaryText != null
    val hasThreeValues = hasSecondary && hasTertiary
    val pairText = secondaryText ?: tertiaryText
    val inlinePairColor = if (hasSecondary) {
        contentColor.copy(alpha = 0.80f)
    } else {
        contentColor.copy(alpha = 0.60f)
    }
    val dotBaseStyle = separatorStyle.scaleForHero(1.12f)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var availableWidthPx by remember { mutableStateOf(0) }

    Box(
        modifier = modifier.onSizeChanged { availableWidthPx = it.width },
        contentAlignment = Alignment.CenterStart
    ) {
        val primaryWidthPx = textMeasurer.measure(
            text = primaryText,
            style = primaryStyle,
            maxLines = 1
        ).size.width
        val dotWidthPx = textMeasurer.measure(
            text = "·",
            style = dotBaseStyle,
            maxLines = 1
        ).size.width
        val inlinePairWidthPx = if (!hasThreeValues && pairText != null) {
            primaryWidthPx +
                with(density) { 6.dp.roundToPx() } +
                dotWidthPx +
                with(density) { 4.dp.roundToPx() } +
                textMeasurer.measure(
                    text = pairText,
                    style = secondaryInlineStyle,
                    maxLines = 1
                ).size.width
        } else {
            0
        }
        val inlineStackWidthPx = if (hasThreeValues) {
            val secondaryWidth = textMeasurer.measure(
                text = secondaryText.orEmpty(),
                style = secondaryStackStyle,
                maxLines = 1
            ).size.width
            val tertiaryWidth = textMeasurer.measure(
                text = tertiaryText.orEmpty(),
                style = tertiaryStackStyle,
                maxLines = 1
            ).size.width
            primaryWidthPx +
                with(density) { 8.dp.roundToPx() } +
                dotWidthPx +
                with(density) { 4.dp.roundToPx() } +
                maxOf(secondaryWidth, tertiaryWidth)
        } else {
            0
        }
        val pairScale = fitHeroScale(availableWidthPx, inlinePairWidthPx)
        val stackScale = fitHeroScale(availableWidthPx, inlineStackWidthPx)
        val layoutMode = when {
            hasThreeValues && inlineStackWidthPx <= availableWidthPx -> DashboardHeroValueLayoutMode.InlineStack
            hasThreeValues -> DashboardHeroValueLayoutMode.InlineStack
            pairText != null && inlinePairWidthPx <= availableWidthPx -> DashboardHeroValueLayoutMode.InlinePair
            pairText != null -> DashboardHeroValueLayoutMode.InlinePair
            else -> DashboardHeroValueLayoutMode.PrimaryOnly
        }

        when (layoutMode) {
            DashboardHeroValueLayoutMode.PrimaryOnly -> {
                DashboardHeroPrimaryText(
                    value = primaryText,
                    style = primaryStyle,
                    color = primaryColor
                )
            }

            DashboardHeroValueLayoutMode.InlinePair -> {
                val scaledPrimaryStyle = primaryStyle.scaleForHero(pairScale)
                val scaledDotStyle = dotBaseStyle.scaleForHero(pairScale)
                val scaledPairStyle = secondaryInlineStyle.scaleForHero(pairScale)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DashboardHeroPrimaryText(
                        value = primaryText,
                        style = scaledPrimaryStyle,
                        color = primaryColor
                    )
                    Spacer(modifier = Modifier.width(6.dp * pairScale))
                    Text(
                        text = "·",
                        style = scaledDotStyle,
                        color = contentColor.copy(alpha = 0.72f),
                        softWrap = false,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(4.dp * pairScale))
                    Text(
                        text = pairText.orEmpty(),
                        style = scaledPairStyle,
                        color = inlinePairColor,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            DashboardHeroValueLayoutMode.InlineStack -> {
                val scaledPrimaryStyle = primaryStyle.scaleForHero(stackScale)
                val scaledDotStyle = dotBaseStyle.scaleForHero(stackScale)
                val scaledSecondaryStyle = secondaryStackStyle.scaleForHero(stackScale)
                val scaledTertiaryStyle = tertiaryStackStyle.scaleForHero(stackScale)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DashboardHeroPrimaryText(
                        value = primaryText,
                        style = scaledPrimaryStyle,
                        color = primaryColor
                    )

                    Spacer(modifier = Modifier.width(8.dp * stackScale))

                    Text(
                        text = "·",
                        style = scaledDotStyle,
                        color = contentColor.copy(alpha = 0.72f),
                        softWrap = false,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.width(4.dp * stackScale))

                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = secondaryText.orEmpty(),
                            style = scaledSecondaryStyle,
                            color = contentColor.copy(alpha = 0.90f),
                            textAlign = TextAlign.Start,
                            softWrap = false,
                            maxLines = 1
                        )
                        Text(
                            text = tertiaryText.orEmpty(),
                            style = scaledTertiaryStyle,
                            color = contentColor.copy(alpha = 0.70f),
                            textAlign = TextAlign.Start,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomCalendarIcon(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    currentDay: Int = 0
) {
    // 1. Subtle Idle "Breathing" Flip (0 -> -6 -> 0)
    val infiniteTransition = rememberInfiniteTransition(label = "CalendarFlip")
    val subtleFlip by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.keyframes {
                durationMillis = 8000 // Occasional flip
                0f at 0
                -8f at 200 using androidx.compose.animation.core.EaseOut // Flip up
                0f at 500 using androidx.compose.animation.core.EaseIn   // Settle
                0f at 8000
            }
        ),
        label = "CalendarPageFlip"
    )

    // 2. Prominent "Day Change" Flip (Triggered by currentDay)
    // 0 -> -25 -> 0 (Big Nod)
    val prominentFlip = remember { androidx.compose.animation.core.Animatable(0f) }
    
    androidx.compose.runtime.LaunchedEffect(currentDay) {
        if (currentDay > 0) { // Don't animate on initial 0
             prominentFlip.animateTo(
                 targetValue = -25f,
                 animationSpec = tween(300, easing = androidx.compose.animation.core.EaseOutBack)
             )
             prominentFlip.animateTo(
                 targetValue = 0f,
                 animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
             )
        }
    }

    val totalRotation = subtleFlip + prominentFlip.value

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Refine stroke: 1.5dp might be too thick for 13dp size. Let's try 1.25dp visual weight equivalence.
        // Actually, user said minimalist style. 1.2dp is fine.
        
        // 1. Body
        val cornerRadius = 2.dp.toPx()
        
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.25f),
            size = androidx.compose.ui.geometry.Size(w, h * 0.75f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx())
        )
        
        // "Substance in the middle": Draw 2 small horizontal lines (rows)
        // Positioned in the center of the body area.
        val bodyTop = h * 0.25f
        val bodyHeight = h * 0.75f
        val row1Y = bodyTop + (bodyHeight * 0.4f)
        val row2Y = bodyTop + (bodyHeight * 0.7f)
        val margin = w * 0.25f
        val lineWidth = w * 0.5f // Centered line
        
        drawLine(
            color = color.copy(alpha = 0.5f), // Fainter lines
            start = androidx.compose.ui.geometry.Offset(margin, row1Y),
            end = androidx.compose.ui.geometry.Offset(margin + lineWidth, row1Y),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
             color = color.copy(alpha = 0.5f),
             start = androidx.compose.ui.geometry.Offset(margin, row2Y),
             end = androidx.compose.ui.geometry.Offset(margin + lineWidth, row2Y),
             strokeWidth = 1.dp.toPx()
        )

        // 2. Header (Top Binding Bar) with "Page Flip" Rotation
        rotate(degrees = totalRotation, pivot = androidx.compose.ui.geometry.Offset(w / 2, 0f)) {
            // Filled Header
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(w, h * 0.25f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
                style = androidx.compose.ui.graphics.drawscope.Fill
            )
            
            // Rings (Cutouts)
            val ringX1 = w * 0.25f
            val ringX2 = w * 0.75f
            val ringH = h * 0.3f
            
            drawLine(
                color = androidx.compose.ui.graphics.Color.Transparent,
                start = androidx.compose.ui.geometry.Offset(ringX1, 0f),
                end = androidx.compose.ui.geometry.Offset(ringX1, ringH),
                strokeWidth = 1.dp.toPx(),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear 
            )
             drawLine(
                color = androidx.compose.ui.graphics.Color.Transparent, 
                start = androidx.compose.ui.geometry.Offset(ringX2, 0f),
                end = androidx.compose.ui.geometry.Offset(ringX2, ringH),
                strokeWidth = 1.dp.toPx(),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear
            )
        }
    }
}

@Composable
private fun AnimatedHourglassIcon(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    cycleDuration: Int = 10000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "HourglassFlip")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.keyframes {
                durationMillis = cycleDuration
                0f at 0
                180f at (cycleDuration * 0.4f).toInt() using androidx.compose.animation.core.EaseInOut
                180f at cycleDuration
            }
        ),
        label = "HourglassRotation"
    )

    Icon(
        imageVector = Icons.Rounded.HourglassEmpty,
        contentDescription = null,
        tint = color,
        modifier = modifier.modifierRotate(rotation)
    )
}




@Composable
fun RecentReadingsCard(
    recentReadings: List<GlucosePoint>,
    unit: String,
    viewMode: Int,
    footerLabel: String,
    onViewHistory: (() -> Unit)? = null,
    content: @Composable (Int, GlucosePoint) -> Unit // Rendering the row with Index
) {
    if (recentReadings.isNotEmpty()) {
        // Do not animate rows on initial dashboard open.
        // Only animate truly new readings that arrive afterward.
        val seenTimestamps = remember { mutableSetOf<Long>() }
        var initialized by remember { mutableStateOf(false) }

        if (!initialized) {
            seenTimestamps.clear()
            seenTimestamps.addAll(recentReadings.map { it.timestamp })
            initialized = true
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            // User Request: "readings row card 2 bottom edges 4dp radius"
            // Top: 16dp (Standard), Bottom: 4dp (Continuity)
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
            // User Request: "kill it" (shadows)
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column {
                recentReadings.forEachIndexed { index, item ->
                    key(item.timestamp) {
                        val isNewReading = !seenTimestamps.contains(item.timestamp)
                        val shouldAnimateNewReading = isNewReading
                        if (isNewReading) {
                            seenTimestamps.add(item.timestamp)
                        }
                        if (shouldAnimateNewReading) {
                            AnimatedVisibility(
                                visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
                                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn()
                            ) {
                                content(index, item)
                            }
                        } else {
                            content(index, item)
                        }
                    }
                }
                if (onViewHistory != null) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onViewHistory() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = footerLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Swipeable row using Material 3 SwipeToDismissBox
 * - Swipe LEFT to DELETE (red background, trash icon)
 * - Swipe RIGHT to DISABLE/ENABLE
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableDeleteRow(
    onDelete: () -> Unit,
    onDisable: () -> Unit,
    isDisabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    var deleteArmHapticSent by remember(isDisabled) { mutableStateOf(false) }
    var rowWidthPx by remember(isDisabled) { mutableStateOf(0) }

    // Key on isDisabled so state resets when toggled
    val dismissState = key(isDisabled) {
        androidx.compose.material3.rememberSwipeToDismissBoxState(
            // Require a more deliberate swipe before destructive actions fire.
            positionalThreshold = { it * CalibrationDeleteSwipeThresholdFraction },
            confirmValueChange = { dismissValue ->
                when (dismissValue) {
                    androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> {
                        onDelete()
                        true
                    }
                    androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> {
                        onDisable()
                        false
                    }
                    androidx.compose.material3.SwipeToDismissBoxValue.Settled -> false
                }
            }
        )
    }

    val currentOffset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
    val deleteArmed =
        rowWidthPx > 0 &&
            dismissState.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart &&
            -currentOffset >= rowWidthPx * CalibrationDeleteSwipeThresholdFraction

    LaunchedEffect(deleteArmed) {
        if (deleteArmed) {
            if (!deleteArmHapticSent) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                deleteArmHapticSent = true
            }
        } else {
            deleteArmHapticSent = false
        }
    }
    
    androidx.compose.material3.SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.onSizeChanged { rowWidthPx = it.width },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> if (isDisabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                else -> androidx.compose.ui.graphics.Color.Transparent
            }
            val alignment = when (direction) {
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.Center
            }
            val icon = when (direction) {
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Delete
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> if (isDisabled) Icons.Filled.Check else Icons.Filled.Close
                else -> Icons.Filled.Delete
            }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        content = {
            Box(modifier = Modifier.clickable(onClick = onClick)) {
                content()
            }
        }
    )
}

/**
 * Calibrations Card - Displays calibration list on dashboard with enable toggle and Calibrate button.
 * Only shown when calibrations exist. M3 Compliant: tap-to-edit, swipe-to-delete with undo.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CalibrationsCard(
    viewMode: Int,
    isMmol: Boolean,
    sensorId: String,
    showEmptyAction: Boolean = true,
    onAddCalibration: () -> Unit,
    onEditCalibration: (tk.glucodata.data.calibration.CalibrationEntity) -> Unit,
    onViewHistory: () -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState? = null
) {
    val isRawMode = viewMode == 1 || viewMode == 3
    
    // Collect calibrations and enable state
    val allCalibrations by tk.glucodata.data.calibration.CalibrationManager.getCalibrationsFlow()?.collectAsState(initial = tk.glucodata.data.calibration.CalibrationManager.getCachedCalibrations())
        ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(tk.glucodata.data.calibration.CalibrationManager.getCachedCalibrations()) }
    val currentSensor = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId
    val calibrations = allCalibrations.filter {
        currentSensor.isNotBlank() &&
            it.isRawMode == isRawMode &&
            tk.glucodata.data.calibration.CalibrationManager.calibrationMatchesSensor(it.sensorId, currentSensor)
    }
    
    val calibrationRevision by tk.glucodata.data.calibration.CalibrationManager.revision.collectAsState()
    val isCalibrationEnabled = remember(isRawMode, currentSensor, calibrationRevision) {
        tk.glucodata.data.calibration.CalibrationManager.isEnabledForMode(isRawMode, currentSensor)
    }
    
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dateFormatter = androidx.compose.runtime.remember { java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()) }
    val format = if (isMmol) "%.1f" else "%.0f"
    
    // Pending delete for undo
    var pendingDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<tk.glucodata.data.calibration.CalibrationEntity?>(null) }
    var showClearConfirmation by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    
    // Local disabled state (until DB field added) - tracks disabled calibration IDs
    // Track items being deleted for animation
    val animatingOutIds = remember { mutableStateListOf<Int>() }
    // Track items being restored for animation
    val animatingInIds = remember { mutableStateListOf<Int>() }
    
    // EMPTY STATE: No card shown at all - just the button floating (Full Width, Tonal)
    if (calibrations.isEmpty()) {
        if (!showEmptyAction) {
            return
        }
        androidx.compose.material3.FilledTonalButton(
            onClick = onAddCalibration,
            enabled = isCalibrationEnabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
//            elevation = androidx.compose.material3.ButtonDefaults.filledTonalButtonElevation(defaultElevation = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.WaterDrop,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.calibrate_action),
                style = MaterialTheme.typography.titleMedium
            )
        }
        return
    }
    
    // POPULATED STATE: Card with button at top, switch, list
    // Sensor card styling: primaryContainer with transparency
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            // Dual-Action Header (Matching CalibrationListScreen)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                 // Left: Clear All (Visible only if items exist)
                 if (calibrations.isNotEmpty()) {
                     androidx.compose.material3.FilledTonalButton(
                        onClick = { showClearConfirmation = true },
//                        modifier = Modifier
//                            .weight(1f),
//                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(
                            topStart = 12.dp,
                            bottomStart = 12.dp,
                            topEnd = 4.dp,
                            bottomEnd = 4.dp
                        ),
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.clear))
                    }
                 } else {
//                     Spacer(modifier = Modifier.weight(8.dp))
                 }

                 // Right: Calibrate
                 androidx.compose.material3.Button(
                    onClick = onAddCalibration,
                    enabled = isCalibrationEnabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(
                        topStart = 4.dp, 
                        bottomStart = 4.dp,
                        topEnd = 12.dp, 
                        bottomEnd = 12.dp
                    ),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
//                    elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.WaterDrop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.calibrate_action),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            
            // Compact Enable Switch Row - clickable entire row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        tk.glucodata.data.calibration.CalibrationManager.setEnabledForMode(isRawMode, !isCalibrationEnabled, currentSensor)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.enable_calibration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.adjust_sensor_readings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                androidx.compose.material3.Switch(
                    checked = isCalibrationEnabled,
                    onCheckedChange = { enabled ->
                        tk.glucodata.data.calibration.CalibrationManager.setEnabledForMode(isRawMode, enabled, currentSensor)
                    },
                    thumbContent = if (isCalibrationEnabled) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(androidx.compose.material3.SwitchDefaults.IconSize),
                            )
                        }
                    } else null
                )
            }
            
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            
            // Calibration List (Gmail-style swipe-to-delete)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = if (isCalibrationEnabled) 1f else 0.38f }
                    .animateContentSize() // Animate height changes (e.g. deletion)
            ) {
                // Limit to 8 items for dashboard
                val visibleCalibrations = calibrations.take(8)
                
                visibleCalibrations.forEachIndexed { index: Int, cal: tk.glucodata.data.calibration.CalibrationEntity ->
                    key(cal.id) {
                        val visibleState = remember {
                            MutableTransitionState(!animatingInIds.contains(cal.id))
                        }
                        visibleState.targetState = cal.id !in animatingOutIds

                        AnimatedVisibility(
                            visibleState = visibleState,
                            exit = shrinkVertically() + fadeOut(),
                            enter = expandVertically() + fadeIn()
                        ) {
                            val sensorValue = if (isRawMode) cal.sensorValueRaw else cal.sensorValue
                        
                        Column {
                            // Track if this calibration is disabled (Persistent)
                            val isRowDisabled = !cal.isEnabled
                            
                            // Gmail-style swipeable row
                            SwipeableDeleteRow(
                                 onDelete = {
                                     // Trigger animation first
                                     animatingOutIds.add(cal.id)
                                     
                                     scope.launch {
                                         delay(300) // Wait for animation
                                         tk.glucodata.data.calibration.CalibrationManager.deleteCalibration(cal)
                                         animatingOutIds.removeAll { it == cal.id } // Cleanup
                                         
                                         // Show Undo Snackbar
                                         val result = snackbarHostState.showCalibrationUndoSnackbar(
                                             message = Applic.app.getString(R.string.calibration_deleted),
                                             actionLabel = Applic.app.getString(R.string.undo)
                                         )
                                         
                                         // Handle Undo
                                         if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                                             animatingInIds.add(cal.id)
                                             tk.glucodata.data.calibration.CalibrationManager.restoreCalibration(cal)
                                             delay(300)
                                             animatingInIds.removeAll { it == cal.id }
                                         }
                                     }
                                 },
                                onDisable = {
                                    // Persistent Update: Toggle enabled state in DB
                                    scope.launch {
                                        tk.glucodata.data.calibration.CalibrationManager.updateCalibration(cal.copy(isEnabled = !cal.isEnabled))
                                    }
                                },
                                isDisabled = isRowDisabled,
                                onClick = {
                                    if (isCalibrationEnabled) {
                                        onEditCalibration(cal)
                                    }
                                }
                        ) {
                                // Row content with disabled visual state
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp) // Enforce M3 Touch Target
                                        .background(
                                            if (isRowDisabled) 
                                                MaterialTheme.colorScheme.surfaceVariant
                                            else 
                                                MaterialTheme.colorScheme.surfaceContainerLow
                                        )
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dateFormatter.format(java.util.Date(cal.timestamp)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isRowDisabled) 
                                            MaterialTheme.colorScheme.outlineVariant
                                        else 
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format(java.util.Locale.getDefault(), format, sensorValue)} → ${String.format(java.util.Locale.getDefault(), format, cal.userValue)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isRowDisabled)
                                            MaterialTheme.colorScheme.outlineVariant
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            
                            if (index < visibleCalibrations.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
                }
                }

                // Footer: If more than 8 items, show "Previous calibrations" button
                if (calibrations.isNotEmpty()) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = onViewHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.previous_calibrations))
                    }
                }
            }
        }
    
    // Clear Options Bottom Sheet
    if (showClearConfirmation) {
        DashboardClearOptionsBottomSheet(
            onDismiss = { showClearConfirmation = false },
            onClearDisabled = {
                scope.launch {
                    val disabled = calibrations.filter { !it.isEnabled }
                    disabled.forEach { tk.glucodata.data.calibration.CalibrationManager.deleteCalibration(it) }
                    snackbarHostState?.currentSnackbarData?.dismiss()
                    snackbarHostState?.showSnackbar(
                        message = Applic.app.getString(R.string.disabled_calibrations_cleared, disabled.size),
                        duration = androidx.compose.material3.SnackbarDuration.Short
                    )
                }
                showClearConfirmation = false
            },
            onClearAll = {
                scope.launch {
                    val backup = calibrations
                    tk.glucodata.data.calibration.CalibrationManager.clearAll()
                    
                    val result = snackbarHostState.showCalibrationUndoSnackbar(
                        message = Applic.app.getString(R.string.all_calibrations_cleared),
                        actionLabel = Applic.app.getString(R.string.undo)
                    )
                    
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                        tk.glucodata.data.calibration.CalibrationManager.restoreAll(backup)
                    }
                }
                showClearConfirmation = false
            },
            disabledCount = calibrations.count { !it.isEnabled },
            totalCount = calibrations.size
        )
    }
}



/**
 * Animated Signal Quality Indicator using xDrip-style noise (Poly Fit Error Variance).
 * Shows actual noise value - calculated via 2nd-degree polynomial regression.
 */
@Composable
fun SignalQualityIndicator(
    noiseLevel: Float, // Error Variance from Poly Fit
    modifier: Modifier = Modifier
) {
    // Color thresholds (Standard xDrip 0-200+ scale)
    // <10: Clean (Green), 10-25: Light (Lt Green), 25-60: Medium (Amber), >60: Heavy (Red)
    val color = when {
        noiseLevel < 10f -> androidx.compose.ui.graphics.Color(0xB34CAF50)  // Green
        noiseLevel < 25f -> androidx.compose.ui.graphics.Color(0xB38BC34A)  // Light Green
        noiseLevel < 60f -> androidx.compose.ui.graphics.Color(0xB3FFC107)  // Amber
        else -> androidx.compose.ui.graphics.Color(0xB3F44336)               // Red
    }
    
    // Animation: Pulse for medium+, Shake for heavy.
    // The transitions are created only once a threshold is actually crossed. Encoding the
    // decision in the target value instead (1f -> 1f) still registers a live animation, so a
    // clean signal — the common case, and this indicator shows for any noiseLevel > 0 — would
    // drive the frame clock and recompose forever for no visible motion.
    val pulses = noiseLevel >= 25f
    val shakes = noiseLevel >= 60f

    val pulseScale = if (pulses) {
        val pulseTransition = rememberInfiniteTransition(label = "signalPulse")
        val animated by pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (shakes) 300 else 600,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
        animated
    } else {
        1f
    }

    val shakeRotation = if (shakes) {
        val shakeTransition = rememberInfiniteTransition(label = "signalShake")
        val animated by shakeTransition.animateFloat(
            initialValue = 0f,
            targetValue = 8f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shakeRotation"
        )
        animated
    } else {
        0f
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Animated Icon
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(14.dp)
                .scale(pulseScale)
                .modifierRotate(shakeRotation)
        )
        
        Spacer(modifier = Modifier.width(6.dp))
        
        // Raw noise value (xDrip-style, 1 decimal)
        Text(
            text = String.format(java.util.Locale.getDefault(), "%.1f", noiseLevel),
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.9f)
        )
    }
}


/**
 * Bottom sheet for clear options in Dashboard
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DashboardClearOptionsBottomSheet(
    onDismiss: () -> Unit,
    onClearDisabled: () -> Unit,
    onClearAll: () -> Unit,
    disabledCount: Int,
    totalCount: Int
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp) // Extra padding for nav bar
                .padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.clear_calibrations_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.choose_what_to_clear), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            
            if (disabledCount > 0) {
                androidx.compose.material3.Surface(onClick = onClearDisabled, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.clear_disabled_only), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.disabled_count, disabledCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            androidx.compose.material3.Surface(onClick = onClearAll, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.clear), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.calibrations_count, totalCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}
