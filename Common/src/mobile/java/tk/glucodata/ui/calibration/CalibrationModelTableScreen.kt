package tk.glucodata.ui.calibration

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.ui.theme.titleMediumEmphasized
import tk.glucodata.ui.theme.titleLargeEmphasized
import tk.glucodata.ui.theme.labelMediumEmphasized
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.data.calibration.CalibrationEntity
import tk.glucodata.data.calibration.CalibrationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private data class CalibrationChartPoint(
    val x: Float,
    val y: Float
)

private enum class CalibrationModelRowStatus {
    ACTIVE,
    DISABLED,
    INCOMPLETE
}

private data class CalibrationModelRow(
    val calibration: CalibrationEntity,
    val sourceValue: Float,
    val secondaryValue: Float,
    val referenceValue: Float,
    val predictedValue: Float?,
    val residualValue: Float?,
    val status: CalibrationModelRowStatus
)

private data class CalibrationModelUiState(
    val rows: List<CalibrationModelRow>,
    val activeRows: List<CalibrationModelRow>,
    val fitLine: List<CalibrationChartPoint>,
    val effectiveSlope: Float?,
    val effectiveIntercept: Float?,
    val midOffset: Float?,
    val meanAbsResidual: Float?,
    val maxAbsResidual: Float?,
    val sourceSpan: Float?,
    val validPointCount: Int
)

private enum class CalibrationLegendMarker {
    DOT,
    LINE,
    AGE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationModelTableScreen(
    navController: NavController,
    isMmol: Boolean,
    viewMode: Int = 0,
    sensorId: String,
    onEdit: (CalibrationEntity) -> Unit
) {
    val isRawMode = viewMode == 1 || viewMode == 3
    val modeTitle = if (isRawMode) stringResource(R.string.raw) else stringResource(R.string.auto)
    val currentSensor = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId
    val allCalibrations by CalibrationManager
        .getCalibrationsFlow()
        .collectAsState(initial = CalibrationManager.getCachedCalibrations())
    val calibrationRevision by CalibrationManager.revision.collectAsState()
    val algorithmForRaw by CalibrationManager.algorithmForRaw.collectAsState()
    val algorithmForAuto by CalibrationManager.algorithmForAuto.collectAsState()
    val applyToPast by CalibrationManager.applyToPast.collectAsState()
    val lockPastHistory by CalibrationManager.lockPastHistory.collectAsState()
    val keepDisabledHistory by CalibrationManager.keepDisabledHistory.collectAsState()
    val weightMode by CalibrationManager.weightMode.collectAsState()

    val isCalibrationEnabled = remember(isRawMode, currentSensor, calibrationRevision) {
        CalibrationManager.isEnabledForMode(isRawMode, currentSensor)
    }
    val selectedAlgorithm = if (isRawMode) algorithmForRaw else algorithmForAuto
    val calibrations = remember(allCalibrations, isRawMode, currentSensor) {
        allCalibrations
            .asSequence()
            .filter { it.isRawMode == isRawMode }
            .filter { currentSensor.isNotBlank() && CalibrationManager.calibrationMatchesSensor(it.sensorId, currentSensor) }
            .sortedByDescending { it.timestamp }
            .toList()
    }
    val modelState = remember(
        calibrations,
        isRawMode,
        isCalibrationEnabled,
        selectedAlgorithm,
        currentSensor,
        applyToPast,
        lockPastHistory,
        keepDisabledHistory,
        weightMode
    ) {
        buildCalibrationModelUiState(
            calibrations = calibrations,
            isRawMode = isRawMode,
            isCalibrationEnabled = isCalibrationEnabled,
            sensorId = currentSensor
        )
    }
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.calibration_model_table_title),
                subtitle = stringResource(
                    R.string.calibration_model_table_subtitle,
                    modeTitle,
                    selectedAlgorithm.title
                ),
                onNavigateBack = { navController.navigateUp() },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CalibrationModelChart(
                    modelState = modelState,
                    isCalibrationEnabled = isCalibrationEnabled,
                    isMmol = isMmol,
                    sourceLabel = modeTitle,
                    dateFormatter = dateFormatter,
                    onEdit = onEdit
                )
            }

            item {
                CalibrationModelSummaryCard(
                    algorithm = selectedAlgorithm,
                    modelState = modelState,
                    isCalibrationEnabled = isCalibrationEnabled,
                    isMmol = isMmol
                )
            }
        }
    }
}

private fun buildCalibrationModelUiState(
    calibrations: List<CalibrationEntity>,
    isRawMode: Boolean,
    isCalibrationEnabled: Boolean,
    sensorId: String
): CalibrationModelUiState {
    val rows = calibrations.map { calibration ->
        val sourceValue = if (isRawMode) calibration.sensorValueRaw else calibration.sensorValue
        val secondaryValue = if (isRawMode) calibration.sensorValue else calibration.sensorValueRaw
        val isValid = sourceValue.isFinite() && sourceValue > 0f &&
            calibration.userValue.isFinite() && calibration.userValue > 0f
        val participates = isCalibrationEnabled && calibration.isEnabled && isValid
        val predicted = if (isCalibrationEnabled && isValid) {
            CalibrationManager.getCalibratedValue(
                value = sourceValue,
                timestamp = calibration.timestamp,
                isRawMode = isRawMode,
                emitDiagnostics = false,
                sensorIdOverride = sensorId
            )
        } else {
            null
        }
        val status = when {
            !isValid -> CalibrationModelRowStatus.INCOMPLETE
            !participates -> CalibrationModelRowStatus.DISABLED
            else -> CalibrationModelRowStatus.ACTIVE
        }

        CalibrationModelRow(
            calibration = calibration,
            sourceValue = sourceValue,
            secondaryValue = secondaryValue,
            referenceValue = calibration.userValue,
            predictedValue = predicted,
            residualValue = predicted?.let { it - calibration.userValue },
            status = status
        )
    }

    val activeRows = rows.filter { it.status == CalibrationModelRowStatus.ACTIVE }
    val activeSourceValues = activeRows.map { it.sourceValue }
    val sourceMin = activeSourceValues.minOrNull()
    val sourceMax = activeSourceValues.maxOrNull()
    val sourceSpan = if (sourceMin != null && sourceMax != null && activeRows.size >= 2) {
        sourceMax - sourceMin
    } else {
        null
    }

    val fitLine = if (
        isCalibrationEnabled &&
        activeRows.size >= 2 &&
        sourceMin != null &&
        sourceMax != null &&
        sourceMax - sourceMin > 0.01f
    ) {
        val fitTimestamp = activeRows.maxOf { it.calibration.timestamp }
        (0..40).map { index ->
            val x = sourceMin + (sourceMax - sourceMin) * (index / 40f)
            CalibrationChartPoint(
                x = x,
                y = CalibrationManager.getCalibratedValue(
                    value = x,
                    timestamp = fitTimestamp,
                    isRawMode = isRawMode,
                    emitDiagnostics = false,
                    sensorIdOverride = sensorId
                )
            )
        }
    } else {
        emptyList()
    }

    val effectiveSlope = if (fitLine.size >= 2) {
        val first = fitLine.first()
        val last = fitLine.last()
        val dx = last.x - first.x
        if (abs(dx) > 0.0001f) {
            (last.y - first.y) / dx
        } else {
            null
        }
    } else {
        null
    }
    val effectiveIntercept = effectiveSlope?.let { slope ->
        fitLine.firstOrNull()?.let { first -> first.y - slope * first.x }
    }
    val midOffset = when {
        fitLine.isNotEmpty() -> fitLine[fitLine.size / 2].let { it.y - it.x }
        activeRows.size == 1 -> activeRows.first().referenceValue - activeRows.first().sourceValue
        else -> null
    }
    val absResiduals = activeRows.mapNotNull { it.residualValue?.let(::abs) }
    return CalibrationModelUiState(
        rows = rows,
        activeRows = activeRows,
        fitLine = fitLine,
        effectiveSlope = effectiveSlope,
        effectiveIntercept = effectiveIntercept,
        midOffset = midOffset,
        meanAbsResidual = absResiduals.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
        maxAbsResidual = absResiduals.maxOrNull(),
        sourceSpan = sourceSpan,
        validPointCount = rows.count { it.status != CalibrationModelRowStatus.INCOMPLETE }
    )
}

@Composable
private fun CalibrationModelSummaryCard(
    algorithm: CalibrationManager.CalibrationAlgorithm,
    modelState: CalibrationModelUiState,
    isCalibrationEnabled: Boolean,
    isMmol: Boolean
) {
    val disabledCount = (modelState.validPointCount - modelState.activeRows.size).coerceAtLeast(0)
    val summarySubtitle = when {
        !isCalibrationEnabled -> stringResource(R.string.calibration_model_disabled_desc)
        modelState.activeRows.size == 1 -> stringResource(R.string.calibration_model_single_point)
        modelState.activeRows.size < 2 -> stringResource(R.string.calibration_model_not_enough_points)
        else -> algorithm.title
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.calibration_model_summary_title),
                style = MaterialTheme.typography.titleMediumEmphasized,)
            Text(
                text = summarySubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalibrationMetricTile(
                    label = stringResource(R.string.slope),
                    value = modelState.effectiveSlope?.let(::formatCoefficientValue) ?: "-",
                    modifier = Modifier.weight(1f)
                )
                CalibrationMetricTile(
                    label = stringResource(R.string.intercept),
                    value = modelState.effectiveIntercept?.let { formatCalibrationValue(it, isMmol) } ?: "-",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_mid_offset),
                    value = modelState.midOffset?.let { formatSignedCalibrationValue(it, isMmol) } ?: "-",
                    modifier = Modifier.weight(1f)
                )
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_source_span),
                    value = modelState.sourceSpan?.let { formatCalibrationValue(it, isMmol) } ?: "-",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_mean_error),
                    value = modelState.meanAbsResidual?.let { formatCalibrationValue(it, isMmol) } ?: "-",
                    modifier = Modifier.weight(1f)
                )
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_max_error),
                    value = modelState.maxAbsResidual?.let { formatCalibrationValue(it, isMmol) } ?: "-",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_active_points),
                    value = modelState.activeRows.size.toString(),
                    modifier = Modifier.weight(1f)
                )
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_disabled_points),
                    value = disabledCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CalibrationMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLargeEmphasized,)
        }
    }
}

@Composable
private fun CalibrationModelChart(
    modelState: CalibrationModelUiState,
    isCalibrationEnabled: Boolean,
    isMmol: Boolean,
    sourceLabel: String,
    dateFormatter: SimpleDateFormat,
    onEdit: (CalibrationEntity) -> Unit
) {
    val isDarkTheme = tk.glucodata.ui.isAppInDarkTheme()
    val activeColor = if (isDarkTheme) Color(0xFFDFFF78) else Color(0xFF496900)
    val disabledColor = if (isDarkTheme) Color(0xFF8B8A83) else Color(0xFF77746D)
    val fitColor = if (isDarkTheme) Color(0xFF77C8FF) else Color(0xFF00649F)
    val residualColor = if (isDarkTheme) Color(0xFFFFC857) else Color(0xFF8F5600)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val crosshairColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val density = LocalDensity.current
    val axisPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
        }
    }
    val chartRows = modelState.rows.filter {
        it.sourceValue.isFinite() && it.sourceValue > 0f &&
            it.referenceValue.isFinite() && it.referenceValue > 0f
    }
    val oldestTimestamp = chartRows.minOfOrNull { it.calibration.timestamp } ?: 0L
    val newestTimestamp = chartRows.maxOfOrNull { it.calibration.timestamp } ?: oldestTimestamp
    val timestampSpan = (newestTimestamp - oldestTimestamp).coerceAtLeast(1L).toFloat()
    val chartIdentity = remember(chartRows) {
        chartRows.joinToString(separator = "|") { it.calibration.id.toString() }
    }
    var selectedCalibrationId by rememberSaveable(chartIdentity) {
        mutableIntStateOf(-1)
    }
    var zoom by rememberSaveable(chartIdentity) { mutableFloatStateOf(1f) }
    var panFraction by rememberSaveable(chartIdentity) { mutableFloatStateOf(0.5f) }
    val selectedRow = chartRows.firstOrNull { it.calibration.id == selectedCalibrationId }
    val xValues = chartRows.map { it.sourceValue } + modelState.fitLine.map { it.x }
    val yValues = chartRows.flatMap { row ->
        listOfNotNull(row.referenceValue, row.predictedValue)
    } + modelState.fitLine.map { it.y }
    val hasChartData = xValues.isNotEmpty() && yValues.isNotEmpty()
    val minSpan = if (isMmol) 0.6f else 12f
    val xMinRaw = xValues.minOrNull() ?: 0f
    val xMaxRaw = xValues.maxOrNull() ?: 1f
    val yMinRaw = yValues.minOrNull() ?: 0f
    val yMaxRaw = yValues.maxOrNull() ?: 1f
    val xPad = max((xMaxRaw - xMinRaw) * 0.12f, minSpan)
    val yPad = max((yMaxRaw - yMinRaw) * 0.12f, minSpan)
    val fullXMin = xMinRaw - xPad
    val fullXMax = xMaxRaw + xPad
    val yMin = yMinRaw - yPad
    val yMax = yMaxRaw + yPad
    val fullXSpan = (fullXMax - fullXMin).coerceAtLeast(minSpan)
    val zoomLevel = zoom.coerceIn(1f, 6f)
    val visibleXSpan = fullXSpan / zoomLevel
    val panSpan = (fullXSpan - visibleXSpan).coerceAtLeast(0f)
    val xMin = fullXMin + panSpan * panFraction.coerceIn(0f, 1f)
    val xMax = xMin + visibleXSpan

    fun ageScale(row: CalibrationModelRow): Float {
        if (chartRows.size <= 1) return 1f
        val ageFraction = ((newestTimestamp - row.calibration.timestamp).toFloat() / timestampSpan).coerceIn(0f, 1f)
        return 1f - ageFraction * 0.38f
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(surfaceColor),
            contentAlignment = Alignment.Center
        ) {
            if (!hasChartData) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.calibration_model_no_points),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.calibration_model_no_points_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(chartRows, xMin, xMax, yMin, yMax) {
                            fun nearestRowForOffset(offset: Offset): CalibrationModelRow? {
                                val left = 48.dp.toPx()
                                val top = 18.dp.toPx()
                                val right = size.width - 14.dp.toPx()
                                val bottom = size.height - 38.dp.toPx()
                                val chartWidth = (right - left).coerceAtLeast(1f)
                                val chartHeight = (bottom - top).coerceAtLeast(1f)

                                fun mapX(value: Float): Float {
                                    return left + ((value - xMin) / (xMax - xMin).coerceAtLeast(0.01f)) * chartWidth
                                }

                                fun mapY(value: Float): Float {
                                    return bottom - ((value - yMin) / (yMax - yMin).coerceAtLeast(0.01f)) * chartHeight
                                }

                                return chartRows
                                    .mapNotNull { row ->
                                        val x = mapX(row.sourceValue)
                                        val y = mapY(row.referenceValue)
                                        if (
                                            x in (left - 32.dp.toPx())..(right + 32.dp.toPx()) &&
                                            y in (top - 32.dp.toPx())..(bottom + 32.dp.toPx())
                                        ) {
                                            row to hypot((offset.x - x).toDouble(), (offset.y - y).toDouble()).toFloat()
                                        } else {
                                            null
                                        }
                                    }
                                    .minByOrNull { it.second }
                                    ?.takeIf { it.second <= 56.dp.toPx() }
                                    ?.first
                            }

                            detectTapGestures { offset ->
                                val row = nearestRowForOffset(offset)
                                selectedCalibrationId = when {
                                    row == null -> -1
                                    row.calibration.id == selectedCalibrationId -> -1
                                    else -> row.calibration.id
                                }
                            }
                        }
                        .pointerInput(xMin, xMax, yMin, yMax, zoomLevel) {
                            awaitEachGesture {
                                var keepTracking: Boolean
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    keepTracking = pressed.any { it.pressed }
                                    if (pressed.size >= 2) {
                                        val nextZoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                                        val chartWidth = (size.width - 62.dp.toPx()).coerceAtLeast(1f)
                                        panFraction = if (nextZoom <= 1.01f) {
                                            0.5f
                                        } else {
                                            val panDelta = -event.calculatePan().x / chartWidth / (nextZoom - 1f).coerceAtLeast(0.05f)
                                            (panFraction + panDelta).coerceIn(0f, 1f)
                                        }
                                        zoom = nextZoom
                                        pressed.forEach { it.consume() }
                                    }
                                } while (keepTracking)
                            }
                        }
                ) {
                    val left = 48.dp.toPx()
                    val top = 18.dp.toPx()
                    val right = size.width - 14.dp.toPx()
                    val bottom = size.height - 38.dp.toPx()
                    val chartWidth = (right - left).coerceAtLeast(1f)
                    val chartHeight = (bottom - top).coerceAtLeast(1f)

                    fun mapX(value: Float): Float {
                        return left + ((value - xMin) / (xMax - xMin).coerceAtLeast(0.01f)) * chartWidth
                    }

                    fun mapY(value: Float): Float {
                        return bottom - ((value - yMin) / (yMax - yMin).coerceAtLeast(0.01f)) * chartHeight
                    }

                    repeat(6) { index ->
                        val t = index / 5f
                        val y = top + chartHeight * t
                        drawLine(
                            color = gridColor,
                            start = Offset(left, y),
                            end = Offset(right, y),
                            strokeWidth = 1.dp.toPx()
                        )
                        val value = yMax - (yMax - yMin) * t
                        axisPaint.color = labelColor
                        axisPaint.textSize = 11.dp.toPx()
                        axisPaint.textAlign = Paint.Align.RIGHT
                        drawContext.canvas.nativeCanvas.drawText(
                            formatCalibrationValue(value, isMmol),
                            left - 8.dp.toPx(),
                            y + 4.dp.toPx(),
                            axisPaint
                        )
                    }

                    repeat(5) { index ->
                        val t = index / 4f
                        val x = left + chartWidth * t
                        drawLine(
                            color = gridColor.copy(alpha = 0.65f),
                            start = Offset(x, top),
                            end = Offset(x, bottom),
                            strokeWidth = 1.dp.toPx()
                        )
                        val value = xMin + (xMax - xMin) * t
                        axisPaint.color = labelColor
                        axisPaint.textSize = 11.dp.toPx()
                        axisPaint.textAlign = Paint.Align.CENTER
                        drawContext.canvas.nativeCanvas.drawText(
                            formatCalibrationValue(value, isMmol),
                            x,
                            bottom + 24.dp.toPx(),
                            axisPaint
                        )
                    }

                    drawLine(
                        color = axisColor.copy(alpha = 0.70f),
                        start = Offset(left, top),
                        end = Offset(left, bottom),
                        strokeWidth = 1.2.dp.toPx()
                    )
                    drawLine(
                        color = axisColor.copy(alpha = 0.70f),
                        start = Offset(left, bottom),
                        end = Offset(right, bottom),
                        strokeWidth = 1.2.dp.toPx()
                    )

                    clipRect(left = left, top = top, right = right, bottom = bottom) {
                        if (modelState.fitLine.size >= 2 && isCalibrationEnabled) {
                            val path = Path()
                            modelState.fitLine.forEachIndexed { index, point ->
                                val x = mapX(point.x)
                                val y = mapY(point.y)
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(
                                path = path,
                                color = fitColor,
                                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        modelState.activeRows.forEach { row ->
                            val predicted = row.predictedValue ?: return@forEach
                            drawLine(
                                color = residualColor.copy(alpha = 0.36f),
                                start = Offset(mapX(row.sourceValue), mapY(row.referenceValue)),
                                end = Offset(mapX(row.sourceValue), mapY(predicted)),
                                strokeWidth = 1.4.dp.toPx()
                            )
                        }

                        selectedRow?.let { row ->
                            val selectedX = mapX(row.sourceValue)
                            val selectedY = mapY(row.referenceValue)
                            drawLine(
                                color = crosshairColor.copy(alpha = 0.38f),
                                start = Offset(selectedX, top),
                                end = Offset(selectedX, bottom),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = crosshairColor.copy(alpha = 0.28f),
                                start = Offset(left, selectedY),
                                end = Offset(right, selectedY),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        chartRows.forEach { row ->
                            val color = if (row.status == CalibrationModelRowStatus.ACTIVE) activeColor else disabledColor
                            // Recency is encoded by area: older calibrations stay visible but compete less.
                            val radius = (if (row.status == CalibrationModelRowStatus.ACTIVE) 7.2.dp.toPx() else 5.6.dp.toPx()) *
                                ageScale(row)
                            val center = Offset(mapX(row.sourceValue), mapY(row.referenceValue))
                            drawCircle(
                                color = color.copy(alpha = if (row.status == CalibrationModelRowStatus.ACTIVE) 1f else 0.62f),
                                radius = radius,
                                center = center
                            )
                            if (row.calibration.id == selectedCalibrationId) {
                                drawCircle(
                                    color = color.copy(alpha = 0.24f),
                                    radius = radius * 2.3f,
                                    center = center,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                            if (row.status != CalibrationModelRowStatus.ACTIVE) {
                                drawCircle(
                                    color = surfaceColor,
                                    radius = radius * 0.48f,
                                    center = center
                                )
                            }
                        }
                    }
                }

                selectedRow?.let { row ->
                    val tooltipWidth = 176.dp
                    val tooltipHeight = 86.dp
                    val gap = 22.dp
                    val leftPadding = 48.dp
                    val topPadding = 18.dp
                    val rightPadding = 14.dp
                    val bottomPadding = 38.dp
                    val chartWidthDp = (maxWidth - leftPadding - rightPadding).coerceAtLeast(1.dp)
                    val chartHeightDp = (maxHeight - topPadding - bottomPadding).coerceAtLeast(1.dp)
                    val pointX = leftPadding + chartWidthDp * ((row.sourceValue - xMin) / (xMax - xMin).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                    val pointY = topPadding + chartHeightDp * (1f - ((row.referenceValue - yMin) / (yMax - yMin).coerceAtLeast(0.01f)).coerceIn(0f, 1f))
                    val roomRight = maxWidth - pointX
                    val roomLeft = pointX
                    val roomTop = pointY
                    val roomBottom = maxHeight - pointY
                    val placeRight = roomRight >= tooltipWidth + gap || roomRight >= roomLeft
                    val placeAbove = roomTop >= tooltipHeight + gap || roomTop >= roomBottom
                    val desiredX = if (placeRight) pointX + gap else pointX - tooltipWidth - gap
                    val desiredY = if (placeAbove) pointY - tooltipHeight - gap else pointY + gap
                    val x = desiredX.coerceIn(8.dp, maxWidth - tooltipWidth - 8.dp)
                    val y = desiredY.coerceIn(8.dp, maxHeight - tooltipHeight - 8.dp)

                    SelectedCalibrationTooltip(
                        row = row,
                        isMmol = isMmol,
                        sourceLabel = sourceLabel,
                        dateFormatter = dateFormatter,
                        onEdit = onEdit,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                with(density) {
                                    IntOffset(x.roundToPx(), y.roundToPx())
                                }
                            }
                    )
                }
            }
        }

        CalibrationModelLegend(
            activeColor = activeColor,
            disabledColor = disabledColor,
            fitColor = fitColor
        )
    }
}

@Composable
private fun CalibrationModelLegend(
    activeColor: Color,
    disabledColor: Color,
    fitColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalibrationLegendItem(
                color = activeColor,
                label = stringResource(R.string.calibration_model_row_active),
                description = stringResource(R.string.calibration_model_legend_active_desc),
                marker = CalibrationLegendMarker.DOT,
                modifier = Modifier.weight(1f)
            )
            CalibrationLegendItem(
                color = fitColor,
                label = stringResource(R.string.calibration_model_fit),
                description = stringResource(R.string.calibration_model_legend_fit_desc),
                marker = CalibrationLegendMarker.LINE,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalibrationLegendItem(
                color = disabledColor,
                label = stringResource(R.string.calibration_model_row_disabled),
                description = stringResource(R.string.calibration_model_legend_disabled_desc),
                marker = CalibrationLegendMarker.DOT,
                modifier = Modifier.weight(1f)
            )
            CalibrationLegendItem(
                color = activeColor,
                label = stringResource(R.string.calibration_model_legend_age),
                description = stringResource(R.string.calibration_model_legend_age_desc),
                marker = CalibrationLegendMarker.AGE,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CalibrationLegendItem(
    color: Color,
    label: String,
    description: String,
    marker: CalibrationLegendMarker,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 42.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.54f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CalibrationLegendSwatch(
                color = color,
                marker = marker,
                modifier = Modifier.size(width = 30.dp, height = 18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CalibrationLegendSwatch(
    color: Color,
    marker: CalibrationLegendMarker,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        when (marker) {
            CalibrationLegendMarker.DOT -> {
                drawCircle(
                    color = color,
                    radius = 5.5.dp.toPx(),
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
            CalibrationLegendMarker.LINE -> {
                drawLine(
                    color = color,
                    start = Offset(2.dp.toPx(), size.height / 2f),
                    end = Offset(size.width - 2.dp.toPx(), size.height / 2f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            CalibrationLegendMarker.AGE -> {
                drawCircle(
                    color = color.copy(alpha = 0.72f),
                    radius = 3.5.dp.toPx(),
                    center = Offset(9.dp.toPx(), size.height / 2f)
                )
                drawCircle(
                    color = color,
                    radius = 6.5.dp.toPx(),
                    center = Offset(size.width - 9.dp.toPx(), size.height / 2f)
                )
            }
        }
    }
}

@Composable
private fun SelectedCalibrationTooltip(
    row: CalibrationModelRow,
    isMmol: Boolean,
    sourceLabel: String,
    dateFormatter: SimpleDateFormat,
    modifier: Modifier = Modifier,
    onEdit: (CalibrationEntity) -> Unit
) {
    Surface(
        onClick = { onEdit(row.calibration) },
        modifier = modifier.width(176.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
//        tonalElevation = 2.dp,
//        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$sourceLabel ${formatCalibrationValue(row.sourceValue, isMmol)} \u2192 ${formatCalibrationValue(row.referenceValue, isMmol)}",
                style = MaterialTheme.typography.titleMediumEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${stringResource(R.string.calibration_model_table_model)} ${row.predictedValue?.let { formatCalibrationValue(it, isMmol) } ?: "-"}  ·  Δ ${row.residualValue?.let { formatSignedCalibrationValue(it, isMmol) } ?: "-"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = dateFormatter.format(Date(row.calibration.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun formatCalibrationValue(value: Float, isMmol: Boolean): String {
    return if (!value.isFinite()) {
        "-"
    } else if (isMmol) {
        String.format(Locale.getDefault(), "%.1f", value)
    } else {
        String.format(Locale.getDefault(), "%.0f", value)
    }
}

private fun formatSignedCalibrationValue(value: Float, isMmol: Boolean): String {
    if (!value.isFinite()) return "-"
    val prefix = if (value > 0f) "+" else ""
    return prefix + formatCalibrationValue(value, isMmol)
}

private fun formatCoefficientValue(value: Float): String {
    return if (!value.isFinite()) {
        "-"
    } else {
        String.format(Locale.getDefault(), "%.2f", value)
    }
}
