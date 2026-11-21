package tk.glucodata.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.graphics.Shape
import tk.glucodata.service.FloatingGlucoseService.CutoutData
import tk.glucodata.service.FloatingGlucoseService.CutoutEdge
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.theme.MainFontFile
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.components.TrendIndicator
import tk.glucodata.logic.TrendEngine
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.ui.getDisplayValues
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus
import tk.glucodata.MultiSensorSelection
import tk.glucodata.ui.PeerCurrentReading
import tk.glucodata.ui.SecondarySensorDisplay

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
    val showDelta by repository.showDelta.collectAsState(initial = false)
    val deltaIntervalMinutes by repository.deltaIntervalMinutes.collectAsState(initial = tk.glucodata.GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
    val showArrow by repository.showArrow.collectAsState(initial = true)
    val cornerRadius by repository.cornerRadius.collectAsState(initial = 28f)
    val opacity by repository.backgroundOpacity.collectAsState(initial = FloatingSettingsRepository.DEFAULT_BACKGROUND_OPACITY)
    val isDynamicIsland by repository.isDynamicIslandEnabled.collectAsState(initial = false)
    val verticalOffset by repository.islandVerticalOffset.collectAsState(initial = FloatingSettingsRepository.DEFAULT_ISLAND_VERTICAL_OFFSET)
    val manualGap by repository.islandGap.collectAsState(initial = 0f)
    val useSubtleOutline by repository.useSubtleOutline.collectAsState(initial = false)
    val layoutOrder by repository.layoutOrder.collectAsState(initial = FloatingLayoutOrder.DEFAULT)

    // Metrics State (from Service WindowInsets)
    val cutoutData by cutoutDataFlow.collectAsState(initial = CutoutData(0.dp, CutoutEdge.NONE))
    val cutoutEdge = cutoutData.edge
    val cutoutSize = cutoutData.size

    // Data State: History List
    val history by historyFlow.collectAsState(initial = emptyList())
    val refreshRevision by UiRefreshBus.revision.collectAsState(initial = 0L)
    
    // Derived Data
    val requestedSensorId = SensorIdentity.resolveMainSensor()
    val selectionRevision by MultiSensorSelection.revision.collectAsState()
    val readings by rememberOverlayReadings(
        requestedSensorId, showSecondary, listOf(refreshRevision, selectionRevision, history, showDelta, deltaIntervalMinutes),
    ) { sensorId, includePeer ->
        withContext(Dispatchers.IO) {
            val snapshot = CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout, sensorId)
            val resolvedSensorId = snapshot?.sensorId ?: sensorId
            val peers = if (includePeer && snapshot?.secondaryStr.isNullOrBlank()) {
                SecondarySensorDisplay.resolvePeers(SecondarySensorDisplay.selectedPeers(resolvedSensorId))
            } else emptyList()
            val deltaText = tk.glucodata.DisplayDeltaSource.resolve(showDelta, snapshot, deltaIntervalMinutes)
            OverlayReadings(resolvedSensorId, snapshot, peers, deltaText, deltaIntervalMinutes)
        }
    }
    // Retain the previous complete frame until the next main/peer pair is resolved.
    val currentSensorId = readings.sensorId
    val currentSnapshot = readings.snapshot
    val peerReadings = readings.peers
    val sensorHistory = remember(history, currentSensorId) {
        history.filter { currentSensorId != null && SensorIdentity.matches(it.sensorSerial, currentSensorId) }
    }
    val glucosePoint = sensorHistory.lastOrNull() ?: currentSnapshot?.let {
        GlucosePoint(it.autoValue, "", it.timeMillis, it.rawValue, it.rate, it.sensorId)
    }

    // The overlay only recomposes on new data, so once readings stop nothing would
    // ever notice the last one aging out. Re-read the clock until it crosses the
    // same timeout the widget and dashboard use, then show the no-data state.
    val latestReadingMillis = maxOf(currentSnapshot?.timeMillis ?: 0L, glucosePoint?.timestamp ?: 0L)
    val freshnessNow by produceState(System.currentTimeMillis(), latestReadingMillis, currentSnapshot?.timeMillis, peerReadings) {
        while (true) {
            value = System.currentTimeMillis()
            // A peer can expire before the main reading, without another sensor update.
            val wait = (listOf(latestReadingMillis, currentSnapshot?.timeMillis ?: 0L) + peerReadings.map { it.timeMillis })
                .mapNotNull { nextOverlayFreshnessCheckDelay(it, value) }.minOrNull() ?: break
            delay(wait)
        }
    }
    // Every layout (pill, side and top island) reads value and arrow from this.
    val displayPoint = overlayDisplayPoint(glucosePoint, currentSnapshot?.timeMillis ?: 0L, freshnessNow)
    
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
    val trendResult = remember(sensorHistory, viewMode, unitInt, currentSnapshot) {
        if (sensorHistory.isNotEmpty()) {
            TrendEngine.calculateTrend(
                sensorHistory,
                useRaw = (viewMode == 1 || viewMode == 3),
                isMmol = (unitInt == 1)
            )
        } else {
            TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, currentSnapshot?.rate ?: 0f, 0f, 0f, 0f)
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
    val style = rememberFloatingOverlayStyle(
        isTransparent = isTransparent,
        darkBackdrop = isSystemInDarkTheme(),
        fontSource = fontSource,
        fontSize = fontSize,
        fontWeightSetting = fontWeightSetting,
        cornerRadius = cornerRadius,
        opacity = opacity,
        useSubtleOutline = useSubtleOutline,
    )
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

    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    val content = if (displayPoint != null) {
        val point = displayPoint
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
        val secondaryText = dvs.secondaryStr ?: SecondarySensorDisplay.fallback(
            dvs, currentSensorId, peerReadings, freshnessNow
        )?.primaryStr
        FloatingOverlayContent(
            primary = dvs.primaryStr,
            secondary = secondaryText?.takeIf { showSecondary && it.isNotEmpty() },
            delta = overlayDeltaText(readings, showDelta, requestedSensorId, deltaIntervalMinutes, freshnessNow)
                .takeIf { it.isNotEmpty() },
            trend = trendResult.takeIf { showArrow },
        )
    } else {
        FloatingOverlayContent(primary = "---", secondary = null, delta = null, trend = null)
    }

    // Clip and click the pill itself, never a wrapper around it: the island's offset
    // wrapper is taller by the offset spacer, so its corner radius clamps differently
    // from the pill's own Surface and its arc cut into the bottom corners.
    val pillModifier = Modifier
        .clip(style.shape)
        .clickable(
            interactionSource = overlayInteractionSource,
            indication = overlayIndication
        ) { launchIntent?.let { context.startActivity(it) } }

    if (isDynamicIsland) {
        CutoutOffsetLayout(
            edge = cutoutEdge,
            offset = verticalOffset.dp,
            modifier = Modifier
                .wrapContentSize()
                .then(dragModifier)
        ) {
            FloatingOverlayBody(
                order = layoutOrder,
                mode = if (isVerticalIsland) FloatingOverlayMode.ISLAND_COLUMN else FloatingOverlayMode.ISLAND_ROW,
                gap = finalGap,
                style = style,
                content = content,
                modifier = pillModifier,
                stackLimit = cutoutData.barHeight.takeIf { cutoutEdge == CutoutEdge.TOP && it > 0.dp } ?: Dp.Unspecified,
            )
        }
    } else {
        FloatingOverlayBody(
            order = layoutOrder,
            mode = FloatingOverlayMode.PILL,
            gap = 0.dp,
            style = style,
            content = content,
            modifier = Modifier
                .wrapContentSize()
                .then(dragModifier)
                .then(pillModifier),
        )
    }
}

/** How the overlay sits: a free pill, or split around a camera above it or beside it. */
internal enum class FloatingOverlayMode { PILL, ISLAND_ROW, ISLAND_COLUMN }

/** What the overlay draws, resolved; null for a part that is not shown right now. */
internal data class FloatingOverlayContent(
    val primary: String,
    val secondary: String?,
    val delta: String?,
    val trend: TrendEngine.TrendResult?,
)

/** The overlay's look, from its appearance and typography settings. */
@Immutable
internal data class FloatingOverlayStyle(
    val fontSize: Float,
    val fontFamily: FontFamily,
    val fontWeight: FontWeight,
    val textColor: Color,
    val outlineColor: Color,
    val shadow: Shadow,
    val useOutline: Boolean,
    val arrowOutlineColor: Color?,
    val arrowShadowColor: Color?,
    val background: Color,
    val shape: Shape,
)

/**
 * [darkBackdrop] is what the overlay is drawn over: the system theme on screen, the app
 * theme in the settings preview, so a transparent overlay keeps readable text in both.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
internal fun rememberFloatingOverlayStyle(
    isTransparent: Boolean,
    darkBackdrop: Boolean,
    fontSource: String,
    fontSize: Float,
    fontWeightSetting: String,
    cornerRadius: Float,
    opacity: Float,
    useSubtleOutline: Boolean,
): FloatingOverlayStyle {
    val weightVal = when (fontWeightSetting) {
        "LIGHT" -> 300
        "MEDIUM" -> 500
        else -> 400
    }
    val fontFamily = remember(fontSource, weightVal) {
        if (fontSource == "APP") {
            try {
                FontFamily(Font(MainFontFile,
                    variationSettings = FontVariation.Settings(FontVariation.weight(weightVal))))
            } catch (th: Throwable) {
                android.util.Log.w("FloatingGlucoseOverlay", "Variable font fallback activated", th)
                FontFamily(Font(MainFontFile))
            }
        } else {
            val familyName = if (weightVal >= 500) "google-sans-medium" else "google-sans"
            try { FontFamily(Typeface.create(familyName, Typeface.NORMAL)) }
            catch (e: Exception) { FontFamily.SansSerif }
        }
    }
    return remember(isTransparent, darkBackdrop, fontFamily, weightVal, fontSize, cornerRadius, opacity, useSubtleOutline) {
        val textColor = if (isTransparent && !darkBackdrop) {
            // ui-guardrails: allow hex_color - drawn straight onto other apps, outside the app theme
            Color(0xFF27231F)
        } else {
            Color.White
        }
        val outlineColor = when {
            isTransparent && !darkBackdrop -> Color.White.copy(alpha = 0.88f)
            isTransparent -> Color.Black.copy(alpha = 0.58f)
            else -> Color.Black.copy(alpha = 0.28f)
        }
        val shadow = Shadow(
            color = when {
                isTransparent && !darkBackdrop -> Color.White.copy(alpha = 1.0f)
                isTransparent -> Color.Black.copy(alpha = 0.62f)
                else -> Color.Black.copy(alpha = 0.42f)
            },
            offset = Offset(0f, if (isTransparent && !darkBackdrop) 0f else if (isTransparent) 1.2f else 1.5f),
            blurRadius = if (isTransparent && !darkBackdrop) 7f else if (isTransparent) 4.5f else 5f
        )
        FloatingOverlayStyle(
            fontSize = fontSize,
            fontFamily = fontFamily,
            fontWeight = FontWeight(weightVal),
            textColor = textColor,
            outlineColor = outlineColor,
            shadow = shadow,
            useOutline = useSubtleOutline,
            arrowOutlineColor = if (isTransparent && useSubtleOutline) outlineColor else null,
            arrowShadowColor = if (isTransparent && !useSubtleOutline) shadow.color else null,
            background = if (isTransparent) Color.Transparent else Color.Black.copy(alpha = opacity),
            shape = RoundedCornerShape(cornerRadius.dp),
        )
    }
}

/**
 * The overlay itself, laid out in [order] (#981): one pill, or the items before the camera
 * on one side of a [gap] the camera's width and the rest on the other. The settings preview
 * draws this same body, so what it shows is what floats.
 *
 * The delta goes under its neighbour unless that would stand taller than [stackLimit] (the
 * status bar, for an island at the top); then it goes beside it.
 */
@Composable
internal fun FloatingOverlayBody(
    order: List<FloatingLayoutItem>,
    mode: FloatingOverlayMode,
    gap: Dp,
    style: FloatingOverlayStyle,
    content: FloatingOverlayContent,
    modifier: Modifier = Modifier,
    stackLimit: Dp = Dp.Unspecified,
) {
    val fontSize = style.fontSize
    val inlineSpacing = (fontSize * 0.35f).coerceIn(4f, 10f).dp
    val pillHorizontalPadding = (fontSize * 0.85f).coerceIn(10f, 24f).dp
    val pillVerticalPadding = (fontSize * 0.45f).coerceIn(4f, 12f).dp
    val sideIslandHorizontalPadding = (fontSize * 0.55f).coerceIn(6f, 16f).dp
    val sideIslandVerticalPadding = (fontSize * 0.32f).coerceIn(3f, 8f).dp
    val sideIslandSplitPadding = (fontSize * 0.18f).coerceIn(2f, 6f).dp

    fun blocks(items: List<FloatingLayoutItem>) = floatingLayoutBlocks(
        items,
        showSecondary = content.secondary != null,
        showArrow = content.trend != null,
        showDelta = content.delta != null,
    )

    val stacked = mode == FloatingOverlayMode.ISLAND_COLUMN
    val side: @Composable (List<FloatingLayoutItem>) -> Unit = { items ->
        if (stacked) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(sideIslandSplitPadding)
            ) {
                blocks(items).forEach { FloatingLayoutBlockContent(it, stacked = true, style, content, Dp.Unspecified, inlineSpacing) }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(inlineSpacing)
            ) {
                blocks(items).forEach { FloatingLayoutBlockContent(it, stacked = false, style, content, stackLimit, inlineSpacing) }
            }
        }
    }
    val background: @Composable () -> Unit = {
        Surface(color = style.background, shape = style.shape, modifier = Modifier.fillMaxSize()) {}
    }

    when (mode) {
        FloatingOverlayMode.PILL -> Surface(
            color = style.background,
            shape = style.shape,
            modifier = modifier
        ) {
            Box(Modifier.padding(horizontal = pillHorizontalPadding, vertical = pillVerticalPadding)) {
                side(order)
            }
        }
        FloatingOverlayMode.ISLAND_ROW -> {
            val sides = FloatingLayoutOrder.sides(order)
            AsymmetricCenteringRow(
                modifier = modifier,
                gap = gap,
                verticalAlignment = Alignment.CenterVertically,
                backgroundContent = background
            ) {
                Box(Modifier.padding(start = pillHorizontalPadding, top = pillVerticalPadding, bottom = pillVerticalPadding)) {
                    side(sides.before)
                }
                Box(Modifier.padding(end = pillHorizontalPadding, top = pillVerticalPadding, bottom = pillVerticalPadding)) {
                    side(sides.after)
                }
            }
        }
        FloatingOverlayMode.ISLAND_COLUMN -> {
            val sides = FloatingLayoutOrder.sides(order)
            AsymmetricCenteringColumn(
                modifier = modifier,
                gap = gap,
                horizontalAlignment = Alignment.CenterHorizontally,
                backgroundContent = background
            ) {
                Box(
                    modifier = Modifier.padding(
                        start = sideIslandHorizontalPadding,
                        end = sideIslandHorizontalPadding,
                        top = sideIslandVerticalPadding + sideIslandSplitPadding,
                        bottom = sideIslandSplitPadding
                    )
                ) {
                    side(sides.before)
                }
                Box(
                    modifier = Modifier.padding(
                        start = sideIslandHorizontalPadding,
                        end = sideIslandHorizontalPadding,
                        top = sideIslandSplitPadding,
                        bottom = sideIslandVerticalPadding + sideIslandSplitPadding
                    )
                ) {
                    side(sides.after)
                }
            }
        }
    }
}

/**
 * One block: [stacked] beside a side cutout, where the pieces sit under each other. A block
 * with the delta draws it underneath, or [inlineSpacing] beside it past [stackLimit].
 */
@Composable
private fun FloatingLayoutBlockContent(
    block: FloatingLayoutBlock,
    stacked: Boolean,
    style: FloatingOverlayStyle,
    content: FloatingOverlayContent,
    stackLimit: Dp,
    inlineSpacing: Dp,
) {
    val fontSize = style.fontSize
    val secondaryFontSize = if (stacked) fontSize * 0.58f else fontSize * 0.7f
    val text: @Composable (String, Float, Color, TextAlign) -> Unit = { value, size, color, align ->
        FloatingStyledText(
            text = value,
            fontSize = size,
            fontFamily = style.fontFamily,
            fontWeight = style.fontWeight,
            textColor = color,
            outlineColor = style.outlineColor,
            shadow = style.shadow,
            useOutline = style.useOutline,
            textAlign = align
        )
    }
    val secondaryColor = style.textColor.copy(alpha = 0.7f)
    val main: @Composable () -> Unit = {
        when (block) {
            is FloatingLayoutBlock.Reading -> {
                val secondary = content.secondary.takeIf { block.withSecondary }
                if (stacked) {
                    val spacing = (fontSize * 0.08f).coerceIn(1f, 4f).dp
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (secondary != null && block.secondaryFirst) {
                            text(secondary, secondaryFontSize, secondaryColor, TextAlign.Center)
                            Spacer(Modifier.height(spacing))
                        }
                        text(content.primary, fontSize, style.textColor, TextAlign.Center)
                        if (secondary != null && !block.secondaryFirst) {
                            Spacer(Modifier.height(spacing))
                            text(secondary, secondaryFontSize, secondaryColor, TextAlign.Center)
                        }
                    }
                } else {
                    val spacing = (fontSize * 0.35f).coerceIn(4f, 10f).dp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (secondary != null && block.secondaryFirst) {
                            text(secondary, secondaryFontSize, secondaryColor, TextAlign.Start)
                            Spacer(Modifier.width(spacing))
                        }
                        text(content.primary, fontSize, style.textColor, TextAlign.End)
                        if (secondary != null && !block.secondaryFirst) {
                            Spacer(Modifier.width(spacing))
                            text(secondary, secondaryFontSize, secondaryColor, TextAlign.Start)
                        }
                    }
                }
            }
            is FloatingLayoutBlock.Secondary -> content.secondary?.let { secondary ->
                text(secondary, secondaryFontSize, secondaryColor, if (stacked) TextAlign.Center else TextAlign.Start)
            }
            is FloatingLayoutBlock.Arrow -> content.trend?.let { trend ->
                val arrowSize = if (stacked) (fontSize * 0.78f).coerceIn(10f, 34f) else (fontSize * 0.9f).coerceIn(12f, 42f)
                TrendIndicator(
                    trendResult = trend,
                    modifier = Modifier.size(arrowSize.dp),
                    color = style.textColor,
                    outlineColor = style.arrowOutlineColor,
                    shadowColor = style.arrowShadowColor
                )
            }
            FloatingLayoutBlock.Delta -> Unit
        }
    }
    val delta = content.delta
    val deltaText: @Composable () -> Unit = {
        if (delta != null) text(delta, fontSize * 0.65f, style.textColor, TextAlign.Center)
    }
    when {
        block == FloatingLayoutBlock.Delta -> deltaText()
        block.withDelta && delta != null -> DeltaBeneath(stackLimit, inlineSpacing, main, deltaText)
        else -> main()
    }
}

/**
 * [main] with [delta] centred under it, or beside it, [spacing] apart and centred on its
 * height, when the two stacked would stand taller than [limit].
 */
@Composable
private fun DeltaBeneath(
    limit: Dp,
    spacing: Dp,
    main: @Composable () -> Unit,
    delta: @Composable () -> Unit,
) {
    Layout(contents = listOf(main, delta)) { (mainMeasurables, deltaMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val top = mainMeasurables.firstOrNull()?.measure(loose)
        val bottom = deltaMeasurables.firstOrNull()?.measure(loose)
        val mainWidth = top?.width ?: 0
        val mainHeight = top?.height ?: 0
        val deltaWidth = bottom?.width ?: 0
        val deltaHeight = bottom?.height ?: 0
        val beside = limit.isSpecified && mainHeight + deltaHeight > limit.roundToPx()
        if (beside) {
            val gap = if (top != null && bottom != null) spacing.roundToPx() else 0
            val height = maxOf(mainHeight, deltaHeight)
            layout(mainWidth + gap + deltaWidth, height) {
                top?.place(0, (height - mainHeight) / 2)
                bottom?.place(mainWidth + gap, (height - deltaHeight) / 2)
            }
        } else {
            val width = maxOf(mainWidth, deltaWidth)
            layout(width, mainHeight + deltaHeight) {
                top?.place((width - mainWidth) / 2, 0)
                bottom?.place((width - deltaWidth) / 2, mainHeight)
            }
        }
    }
}

@Composable
internal fun FloatingStyledText(
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
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(200)) + slideInVertically(tween(220)) { -it / 3 })
                .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(220)) { it / 3 })
        },
        label = "FloatingReadingValue"
    ) { shownText ->
        if (useOutline) {
            Box {
                Text(
                    text = shownText,
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
                    text = shownText,
                    color = textColor,
                    fontSize = fontSize.sp,
                    fontFamily = fontFamily,
                    fontWeight = fontWeight,
                    textAlign = textAlign
                )
            }
        } else {
            Text(
                text = shownText,
                color = textColor,
                fontSize = fontSize.sp,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                textAlign = textAlign,
                style = TextStyle(shadow = shadow)
            )
        }
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

private const val OVERLAY_FRESHNESS_POLL_MS = 15_000L

/**
 * How long to wait before re-checking whether the reading at [latestReadingMillis]
 * has gone stale, or null once it has (or there is none) and nothing is left to
 * watch. Capped at [OVERLAY_FRESHNESS_POLL_MS] because coroutine delays run on
 * uptime, which stops during deep sleep: one long wait would wake far too late.
 */
internal fun nextOverlayFreshnessCheckDelay(
    latestReadingMillis: Long,
    nowMillis: Long,
    freshnessWindowMillis: Long = Notify.glucosetimeout
): Long? {
    if (latestReadingMillis <= 0L) return null
    val untilStale = latestReadingMillis + freshnessWindowMillis - nowMillis
    if (untilStale < 0L) return null
    return (untilStale + 1L).coerceAtMost(OVERLAY_FRESHNESS_POLL_MS)
}

/**
 * The point the overlay may show at [nowMillis]: the latest one while it is within
 * the widget/dashboard freshness window, null (the no-data state) once it is not.
 */
internal fun overlayDisplayPoint(
    latestPoint: GlucosePoint?,
    snapshotMillis: Long,
    nowMillis: Long,
    freshnessWindowMillis: Long = Notify.glucosetimeout
): GlucosePoint? {
    val isFresh = DisplayDataState.resolve(
        sensorPresent = true,
        currentTimestampMillis = snapshotMillis,
        latestHistoryTimestampMillis = latestPoint?.timestamp ?: 0L,
        freshnessWindowMillis = freshnessWindowMillis,
        nowMillis = nowMillis
    ).isFresh
    return latestPoint?.takeIf { isFresh }
}
