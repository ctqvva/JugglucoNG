package tk.glucodata.ui.overlay

import android.view.MotionEvent
import android.content.Intent
import android.graphics.Typeface // Added for Google Sans
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.Constraints
import tk.glucodata.service.FloatingGlucoseService.CutoutData
import tk.glucodata.service.FloatingGlucoseService.CutoutEdge
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.theme.MainFontFile
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.components.TrendIndicator
import tk.glucodata.logic.TrendEngine
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.ui.getDisplayValues
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun FloatingGlucoseOverlay(
    repository: FloatingSettingsRepository,
    historyFlow: Flow<List<GlucosePoint>>,
    onUpdatePosition: (Int, Int) -> Unit,
    onDragFinished: () -> Unit,
    cutoutDataFlow: Flow<tk.glucodata.service.FloatingGlucoseService.CutoutData>
) {
    val context = LocalContext.current

    // Settings State
    val isTransparent by repository.isTransparent.collectAsState(initial = false)
    val showSecondary by repository.showSecondary.collectAsState(initial = false)
    val fontSource by repository.fontSource.collectAsState(initial = "APP")
    val fontSize by repository.fontSize.collectAsState(initial = FloatingSettingsRepository.DEFAULT_FONT_SIZE)
    val fontWeightSetting by repository.fontWeight.collectAsState(initial = "REGULAR")
    val showArrow by repository.showArrow.collectAsState(initial = true)
    val cornerRadius by repository.cornerRadius.collectAsState(initial = 28f)
    val opacity by repository.backgroundOpacity.collectAsState(initial = FloatingSettingsRepository.DEFAULT_BACKGROUND_OPACITY)
    val isDynamicIsland by repository.isDynamicIslandEnabled.collectAsState(initial = false)
    val verticalOffset by repository.islandVerticalOffset.collectAsState(initial = FloatingSettingsRepository.DEFAULT_ISLAND_VERTICAL_OFFSET)
    val manualGap by repository.islandGap.collectAsState(initial = 0f)
    val useSubtleOutline by repository.useSubtleOutline.collectAsState(initial = false)

    // Metrics State (from Service WindowInsets)
    val cutoutData by cutoutDataFlow.collectAsState(initial = CutoutData(0.dp, CutoutEdge.NONE))
    val cutoutEdge = cutoutData.edge
    val cutoutSize = cutoutData.size

    // Data State: History List
    val history by historyFlow.collectAsState(initial = emptyList())
    val refreshRevision by UiRefreshBus.revision.collectAsState(initial = 0L)
    
    // Derived Data
    val glucosePoint = history.lastOrNull()
    val currentSensorId = SensorIdentity.resolveMainSensor()
    val currentSnapshot = remember(refreshRevision, currentSensorId, glucosePoint?.timestamp, history.size) {
        CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout, currentSensorId)
    }
    
    // View Mode & Calibration
    val viewData = remember(currentSnapshot, glucosePoint, currentSensorId) {
        val resolvedViewMode = currentSnapshot?.viewMode
        val viewMode = if (resolvedViewMode != null) {
            resolvedViewMode
        } else if (!currentSensorId.isNullOrEmpty()) {
            runCatching {
                val snapshot = Natives.getSensorUiSnapshot(currentSensorId)
                if (snapshot != null && snapshot.size >= 2) snapshot[1].toInt() else 0
            }.getOrDefault(0)
        } else 0
        Pair(viewMode, Natives.getunit())
    }
    val viewMode = viewData.first
    val unitInt = viewData.second
    
    // Same estimator, window and raw/smoothed choice as every other arrow; the
    // overlay used to omit useRaw, so in raw view modes it regressed over the
    // smoothed series and tilted differently from the hero it floats next to.
    val trendResult = remember(history, viewMode, unitInt) {
        if (history.isNotEmpty()) {
            TrendEngine.calculateTrend(
                history,
                useRaw = (viewMode == 1 || viewMode == 3),
                isMmol = (unitInt == 1)
            )
        } else {
            TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, 0f, 0f, 0f, 0f)
        }
    }
    
    // Gap Calculation
    // Use manual gap if > 0, otherwise detected width. Default 70dp if all else fails.
    val isVerticalIsland = cutoutEdge == CutoutEdge.LEFT || cutoutEdge == CutoutEdge.RIGHT
    val finalGap = if (manualGap > 0f) {
        manualGap.dp
    } else if (cutoutSize > 0.dp) {
        cutoutSize
    } else {
        70.dp
    }
    // Styles
    val isDarkTheme = isSystemInDarkTheme()
    val finalBgColor = if (isTransparent) Color.Transparent else Color.Black.copy(alpha = opacity)
    val finalShape = RoundedCornerShape(cornerRadius.dp)
    val finalTextColor = if (isTransparent && !isDarkTheme) {
        Color(0xFF27231F)
    } else {
        Color.White
    }
    val textOutlineColor = when {
        isTransparent && !isDarkTheme -> Color.White.copy(alpha = 0.88f)
        isTransparent -> Color.Black.copy(alpha = 0.58f)
        else -> Color.Black.copy(alpha = 0.28f)
    }
    val textShadow = Shadow(
        color = when {
            isTransparent && !isDarkTheme -> Color.White.copy(alpha = 1.0f)
            isTransparent -> Color.Black.copy(alpha = 0.62f)
            else -> Color.Black.copy(alpha = 0.42f)
        },
        offset = Offset(0f, if (isTransparent && !isDarkTheme) 0f else if (isTransparent) 1.2f else 1.5f),
        blurRadius = if (isTransparent && !isDarkTheme) 7f else if (isTransparent) 4.5f else 5f
    )
    val arrowOutlineColor = if (isTransparent && useSubtleOutline) textOutlineColor else null
    val arrowShadowColor = if (isTransparent && !useSubtleOutline) textShadow.color else null
    val overlayInteractionSource = remember { MutableInteractionSource() }
    val overlayIndication = ripple(
        bounded = true,
        color = if (isTransparent) {
            Color.White.copy(alpha = 0.24f)
        } else {
            Color.White.copy(alpha = 0.16f)
        }
    )
    var pendingDragX by remember { mutableFloatStateOf(0f) }
    var pendingDragY by remember { mutableFloatStateOf(0f) }
    
    // Drag Modifier
    val dragModifier = if (isDynamicIsland) Modifier else Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragEnd = {
                pendingDragX = 0f
                pendingDragY = 0f
                onDragFinished()
            },
            onDragCancel = {
                pendingDragX = 0f
                pendingDragY = 0f
                onDragFinished()
            }
        ) { change, dragAmount ->
            change.consume()
            pendingDragX += dragAmount.x
            pendingDragY += dragAmount.y
            val wholeX = pendingDragX.toInt()
            val wholeY = pendingDragY.toInt()
            if (wholeX != 0 || wholeY != 0) {
                pendingDragX -= wholeX
                pendingDragY -= wholeY
                onUpdatePosition(wholeX, wholeY)
            }
        }
    }

    // Font Logic
    val weightVal = when (fontWeightSetting) {
        "LIGHT" -> 300
        "MEDIUM" -> 500
        else -> 400
    }
    
    val fontFamily = if (fontSource == "APP") {
        try {
            FontFamily(
                Font(
                    MainFontFile,
                    variationSettings = FontVariation.Settings(FontVariation.weight(weightVal))
                )
            )
        } catch (th: Throwable) {
            android.util.Log.w("FloatingGlucoseOverlay", "Variable font fallback activated", th)
            FontFamily(Font(MainFontFile))
        }
    } else {
        remember(weightVal) {
             val familyName = if (weightVal >= 500) "google-sans-medium" else "google-sans"
             try {
                 FontFamily(Typeface.create(familyName, Typeface.NORMAL))
             } catch (e: Exception) { FontFamily.SansSerif }
        }
    }
    val fontWeight = FontWeight(weightVal)
    val inlineSpacing = (fontSize * 0.35f).coerceIn(4f, 10f).dp
    val pillHorizontalPadding = (fontSize * 0.85f).coerceIn(10f, 24f).dp
    val pillVerticalPadding = (fontSize * 0.45f).coerceIn(4f, 12f).dp
    val splitPadding = (fontSize * 0.28f).coerceIn(3f, 8f).dp
    val arrowSize = (fontSize * 0.9f).coerceIn(12f, 42f).dp
    val sideIslandHorizontalPadding = (fontSize * 0.55f).coerceIn(6f, 16f).dp
    val sideIslandVerticalPadding = (fontSize * 0.32f).coerceIn(3f, 8f).dp
    val sideIslandSplitPadding = (fontSize * 0.18f).coerceIn(2f, 6f).dp
    val sideIslandArrowSize = (fontSize * 0.78f).coerceIn(10f, 34f).dp
    val sideSecondarySpacing = (fontSize * 0.08f).coerceIn(1f, 4f).dp
    val sideSecondaryFontSize = fontSize * 0.58f

    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    val valueContent: @Composable () -> Unit = {
        if (glucosePoint != null) {
            val point = glucosePoint
            val unit = if (unitInt == 1) "mmol/L" else "mg/dL"
            val dvs = currentSnapshot?.displayValues ?: run {
                val isRawModeForCal = viewMode == 1 || viewMode == 3
                val hasCalibration = !CalibrationManager.shouldOverwriteSensorValues() &&
                    CalibrationManager.hasActiveCalibration(isRawModeForCal)
                val calibratedValue = if (hasCalibration) {
                    val baseValue = if (isRawModeForCal) point.rawValue else point.value
                    if (baseValue.isFinite() && baseValue > 0.1f) {
                        CalibrationManager.getCalibratedValue(baseValue, point.timestamp, isRawModeForCal)
                    } else {
                        null
                    }
                } else null
                getDisplayValues(point, viewMode, unit, calibratedValue)
            }

            if (isDynamicIsland && isVerticalIsland) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingStyledText(
                        text = dvs.primaryStr,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        fontWeight = fontWeight,
                        textColor = finalTextColor,
                        outlineColor = textOutlineColor,
                        shadow = textShadow,
                        useOutline = useSubtleOutline,
                        textAlign = TextAlign.Center
                    )
                    if (showSecondary && !dvs.secondaryStr.isNullOrEmpty()) {
                        Spacer(Modifier.height(sideSecondarySpacing))
                        FloatingStyledText(
                            text = dvs.secondaryStr,
                            fontSize = sideSecondaryFontSize,
                            fontFamily = fontFamily,
                            fontWeight = fontWeight,
                            textColor = finalTextColor.copy(alpha = 0.7f),
                            outlineColor = textOutlineColor,
                            shadow = textShadow,
                            useOutline = useSubtleOutline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatingStyledText(
                        text = dvs.primaryStr,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        fontWeight = fontWeight,
                        textColor = finalTextColor,
                        outlineColor = textOutlineColor,
                        shadow = textShadow,
                        useOutline = useSubtleOutline,
                        textAlign = TextAlign.End
                    )
                    if (showSecondary && !dvs.secondaryStr.isNullOrEmpty()) {
                        Spacer(Modifier.width(inlineSpacing))
                        FloatingStyledText(
                            text = dvs.secondaryStr,
                            fontSize = fontSize * 0.7f,
                            fontFamily = fontFamily,
                            fontWeight = fontWeight,
                            textColor = finalTextColor.copy(alpha = 0.7f),
                            outlineColor = textOutlineColor,
                            shadow = textShadow,
                            useOutline = useSubtleOutline
                        )
                    }
                }
            }
        } else {
            FloatingStyledText(
                text = "---",
                fontSize = fontSize,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                textColor = finalTextColor,
                outlineColor = textOutlineColor,
                shadow = textShadow,
                useOutline = useSubtleOutline
            )
        }
    }

    val arrowContent: @Composable () -> Unit = {
        if (showArrow && glucosePoint != null) {
            TrendIndicator(
                trendResult = trendResult,
                modifier = Modifier.size(if (isDynamicIsland && isVerticalIsland) sideIslandArrowSize else arrowSize),
                color = finalTextColor,
                outlineColor = arrowOutlineColor,
                shadowColor = arrowShadowColor
            )
        } else {
            Spacer(Modifier.size(1.dp))
        }
    }
    
    // ROOT LAYOUT CHANGE: Use Column just for Vertical Offset Spacer if Island
    // We don't use 'Surface' as root for Island anymore, because we want split layout.
    
    // ROOT LAYOUT: Column for vertical offset spacer
    if (isDynamicIsland) {
        // Clip and click the pill itself, not the offset wrapper: the wrapper is
        // taller by the offset spacer, so its corner radius clamps differently
        // from the pill's own Surface and its arc cut into the bottom corners.
        val pillModifier = Modifier
            .clip(finalShape)
            .clickable(
                interactionSource = overlayInteractionSource,
                indication = overlayIndication
            ) { launchIntent?.let { context.startActivity(it) } }
        CutoutOffsetLayout(
            edge = cutoutEdge,
            offset = verticalOffset.dp,
            modifier = Modifier
                .wrapContentSize()
                .then(dragModifier)
        ) {
            if (isVerticalIsland) {
                AsymmetricCenteringColumn(
                    modifier = pillModifier,
                    gap = finalGap,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    backgroundContent = {
                        Surface(
                            color = finalBgColor,
                            shape = finalShape,
                            modifier = Modifier.fillMaxSize()
                        ) {}
                    }
                ) {
                    Box(
                        modifier = Modifier.padding(
                            start = sideIslandHorizontalPadding,
                            end = sideIslandHorizontalPadding,
                            top = sideIslandVerticalPadding + sideIslandSplitPadding,
                            bottom = sideIslandSplitPadding
                        )
                    ) {
                        valueContent()
                    }
                    Box(
                        modifier = Modifier.padding(
                            start = sideIslandHorizontalPadding,
                            end = sideIslandHorizontalPadding,
                            top = sideIslandSplitPadding,
                            bottom = sideIslandVerticalPadding + sideIslandSplitPadding
                        )
                    ) {
                        arrowContent()
                    }
                }
            } else {
                AsymmetricCenteringRow(
                    modifier = pillModifier,
                    gap = finalGap,
                    verticalAlignment = Alignment.CenterVertically,
                    backgroundContent = {
                        Surface(
                            color = finalBgColor,
                            shape = finalShape,
                            modifier = Modifier.fillMaxSize()
                        ) {}
                    }
                ) {
                    Box(
                        modifier = Modifier.padding(
                            start = pillHorizontalPadding,
                            top = pillVerticalPadding,
                            bottom = pillVerticalPadding
                        )
                    ) {
                        valueContent()
                    }
                    Box(
                        modifier = Modifier.padding(
                            end = pillHorizontalPadding,
                            top = pillVerticalPadding,
                            bottom = pillVerticalPadding
                        )
                    ) {
                        arrowContent()
                    }
                }
            }
        }
    } else {
        // ORIGINAL FLOATING LAYOUT (Unified Pill)
        Surface(
            color = finalBgColor,
            shape = finalShape,
            modifier = Modifier
                .wrapContentSize()
                .then(dragModifier)
                .clip(finalShape)
                .clickable(
                    interactionSource = overlayInteractionSource,
                    indication = overlayIndication
                ) { launchIntent?.let { context.startActivity(it) } }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = pillHorizontalPadding, vertical = pillVerticalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(inlineSpacing)
            ) {
                valueContent()
                if (showArrow && glucosePoint != null) {
                    TrendIndicator(
                        trendResult,
                        Modifier.size(arrowSize),
                        finalTextColor,
                        outlineColor = arrowOutlineColor,
                        shadowColor = arrowShadowColor
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingStyledText(
    text: String, 
    fontSize: Float, 
    fontFamily: FontFamily, 
    fontWeight: FontWeight, 
    textColor: Color, 
    outlineColor: Color,
    shadow: Shadow,
    useOutline: Boolean,
    textAlign: TextAlign = TextAlign.Start
) {
    if (useOutline) {
        Box {
            Text(
                text = text,
                color = outlineColor,
                fontSize = fontSize.sp,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                textAlign = textAlign,
                style = TextStyle.Default.copy(
                    drawStyle = Stroke(
                        miter = 10f,
                        width = 2.6f,
                        join = StrokeJoin.Round
                    )
                )
            )
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize.sp,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                textAlign = textAlign
            )
        }
    } else {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize.sp,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            textAlign = textAlign,
            style = TextStyle(shadow = shadow)
        )
    }
}



@Composable
fun AsymmetricCenteringRow(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp,
    verticalAlignment: Alignment.Vertical,
    backgroundContent: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    // Layout that takes:
    // 1. Background (as a composable)
    // 2. Left and Right content
    // Calculations:
    // - Measure L, R.
    // - SymmetricWidth = 2 * max(L, R) + Gap.
    // - ActualContentWidth = L + Gap + R.
    // - OffsetX = if (L < R) (SymmetricWidth - ActualContentWidth) / 2 else 0? 
    //   Wait. Layout places children.
    //   If L=50, R=100, Gap=10.
    //   Max=100. SymW = 210.
    //   Center of SymW = 105.
    //   Gap must start at Center - Gap/2 = 100.
    //   So Left must end at 100. Left Start = 100 - 50 = 50.
    //   Gap ends at 110. Right starts at 110. Right ends at 210.
    //   
    //   Offset Calculation:
    //   LeftX = max(L, R) - L.
    //   RightX = max(L, R) + Gap.
    //   BgX = LeftX. BgWidth = L + Gap + R.
    
    Layout(
        contents = listOf(backgroundContent, content),
        modifier = modifier
    ) { (bgMeasurables, contentMeasurables), constraints ->
        
        // Measure Content first
        val contentPlaceables = contentMeasurables.take(2).map { it.measure(constraints.copy(minWidth = 0)) }
        val left = contentPlaceables.getOrNull(0)
        val right = contentPlaceables.getOrNull(1)
        
        val leftW = left?.width ?: 0
        val rightW = right?.width ?: 0
        val leftH = left?.height ?: 0
        val rightH = right?.height ?: 0
        
        val gapPx = gap.roundToPx()
        val maxSideWidth = maxOf(leftW, rightW)
        
        // Report Symmetric Size
        val totalWidth = maxSideWidth * 2 + gapPx
        val totalHeight = maxOf(leftH, rightH)
        
        // Measure Background to fit TIGHTLY around content (L + G + R)
        val bgWidth = leftW + gapPx + rightW
        val bgPlaceable = bgMeasurables.firstOrNull()?.measure(Constraints.fixed(bgWidth, totalHeight))
        
        fun getY(height: Int): Int {
             return when (verticalAlignment) {
                 Alignment.CenterVertically -> (totalHeight - height) / 2
                 Alignment.Bottom -> totalHeight - height
                 else -> 0
             }
        }
        
        layout(totalWidth, totalHeight) {
            val startX = maxSideWidth - leftW
            
            // Place Background
            bgPlaceable?.place(x = startX, y = 0)
            
            // Place Left
            left?.place(x = startX, y = getY(leftH))
            
            // Place Right
            right?.place(x = maxSideWidth + gapPx, y = getY(rightH))
        }
    }
}

@Composable
private fun CutoutOffsetLayout(
    edge: CutoutEdge,
    offset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    when (edge) {
        CutoutEdge.LEFT -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            if (offset > 0.dp) {
                Spacer(modifier = Modifier.width(offset))
            }
            content()
        }
        CutoutEdge.RIGHT -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            content()
            if (offset > 0.dp) {
                Spacer(modifier = Modifier.width(offset))
            }
        }
        CutoutEdge.BOTTOM -> Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            content()
            if (offset > 0.dp) {
                Spacer(modifier = Modifier.height(offset))
            }
        }
        else -> Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            if (offset > 0.dp) {
                Spacer(modifier = Modifier.height(offset))
            }
            content()
        }
    }
}

@Composable
private fun AsymmetricCenteringColumn(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp,
    horizontalAlignment: Alignment.Horizontal,
    backgroundContent: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Layout(
        contents = listOf(backgroundContent, content),
        modifier = modifier
    ) { (bgMeasurables, contentMeasurables), constraints ->
        val contentPlaceables = contentMeasurables.take(2).map { it.measure(constraints.copy(minHeight = 0)) }
        val top = contentPlaceables.getOrNull(0)
        val bottom = contentPlaceables.getOrNull(1)

        val topW = top?.width ?: 0
        val bottomW = bottom?.width ?: 0
        val topH = top?.height ?: 0
        val bottomH = bottom?.height ?: 0

        val gapPx = gap.roundToPx()
        val maxSideHeight = maxOf(topH, bottomH)

        val totalWidth = maxOf(topW, bottomW)
        val totalHeight = maxSideHeight * 2 + gapPx

        val bgHeight = topH + gapPx + bottomH
        val bgPlaceable = bgMeasurables.firstOrNull()?.measure(Constraints.fixed(totalWidth, bgHeight))

        fun getX(width: Int): Int {
            return when (horizontalAlignment) {
                Alignment.CenterHorizontally -> (totalWidth - width) / 2
                Alignment.End -> totalWidth - width
                else -> 0
            }
        }

        layout(totalWidth, totalHeight) {
            val startY = maxSideHeight - topH

            bgPlaceable?.place(x = 0, y = startY)
            top?.place(x = getX(topW), y = startY)
            bottom?.place(x = getX(bottomW), y = maxSideHeight + gapPx)
        }
    }
}
