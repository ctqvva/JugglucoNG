package tk.glucodata.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.data.prediction.analysis.ForecastMoment
import tk.glucodata.data.prediction.analysis.Outcome
import tk.glucodata.data.prediction.analysis.PredictionAccuracy
import tk.glucodata.data.prediction.analysis.RecordedForecastEntity
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.viewmodel.DashboardViewModel
import tk.glucodata.ui.viewmodel.PredictionAnalysisViewModel
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val MINUTE_MS = 60_000L
private val InspectorCardShape = RoundedCornerShape(20.dp)

/**
 * One recorded forecast at a time, drawn over the readings that followed it: the answer to
 * "what did it say, and what happened" (#722), without stacking every old curve on the
 * dashboard.
 */
@Composable
fun PredictionInspectorScreen(navController: NavController, dashboardViewModel: DashboardViewModel) {
    val viewModel: PredictionAnalysisViewModel = viewModel()
    val origins by viewModel.origins.collectAsStateWithLifecycle()
    val selected by viewModel.selectedIndex.collectAsStateWithLifecycle()
    val moment by viewModel.moment.collectAsStateWithLifecycle()
    val targetLow by dashboardViewModel.targetLow.collectAsStateWithLifecycle()
    val targetHigh by dashboardViewModel.targetHigh.collectAsStateWithLifecycle()
    val isMmol = remember { GlucoseFormatter.isMmolApp() }

    LaunchedEffect(Unit) { viewModel.loadOrigins() }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.prediction_analysis_past_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        PredictionInspectorContent(
            origins = origins,
            selected = selected,
            moment = moment,
            isMmol = isMmol,
            targetLowMgdl = displayToMgdl(targetLow, isMmol) ?: 70f,
            targetHighMgdl = displayToMgdl(targetHigh, isMmol) ?: 180f,
            onSelect = viewModel::select,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The inspector without its data source, so it can be drawn from any recorded moment. */
@Composable
internal fun PredictionInspectorContent(
    origins: List<Long>?,
    selected: Int,
    moment: ForecastMoment?,
    isMmol: Boolean,
    targetLowMgdl: Float,
    targetHighMgdl: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val list = origins
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (list != null && list.isEmpty()) {
            Text(
                text = stringResource(R.string.prediction_inspector_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            return@Column
        }
        if (list == null || selected !in list.indices) return@Column
        val origin = list[selected]

        Row(verticalAlignment = Alignment.CenterVertically) {
            val previous = stringResource(R.string.prediction_inspector_previous)
            IconButtonTooltip(previous) {
                IconButton(onClick = { onSelect(selected - 1) }, enabled = selected > 0, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = previous)
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.prediction_inspector_forecast_at, timeFormat.format(Date(origin))),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = dateFormat.format(Date(origin)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val next = stringResource(R.string.prediction_inspector_next)
            IconButtonTooltip(next) {
                IconButton(onClick = { onSelect(selected + 1) }, enabled = selected < list.lastIndex, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = next)
                }
            }
        }

        if (list.size > 1) {
            var dragging by remember(selected) { mutableFloatStateOf(selected.toFloat()) }
            Slider(
                value = dragging,
                onValueChange = { dragging = it },
                onValueChangeFinished = { onSelect(dragging.roundToInt()) },
                valueRange = 0f..list.lastIndex.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val current = moment?.takeIf { it.originTime == origin }
        if (current != null) {
            Surface(shape = InspectorCardShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ForecastChart(current, isMmol, timeFormat, targetLowMgdl, targetHighMgdl)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LegendSwatch(stringResource(R.string.prediction_inspector_actual), MaterialTheme.colorScheme.onSurface)
                        current.standard?.let {
                            LegendSwatch(legendLabel(R.string.prediction_analysis_model_standard, it.shown), MaterialTheme.colorScheme.primary, ForecastLineStyle.DASHED)
                        }
                        current.adaptive?.let {
                            LegendSwatch(legendLabel(R.string.prediction_analysis_model_adaptive, it.shown), MaterialTheme.colorScheme.tertiary, ForecastLineStyle.DOTTED)
                        }
                    }
                }
            }
            OutcomeTable(current, isMmol)
            ContextLines(current, isMmol)
        }
    }
}

@Composable
private fun legendLabel(nameRes: Int, shown: Boolean): String {
    val name = stringResource(nameRes)
    return if (shown) name + " · " + stringResource(R.string.prediction_inspector_shown) else name
}

@Composable
private fun ForecastChart(
    moment: ForecastMoment,
    isMmol: Boolean,
    timeFormat: DateFormat,
    targetLow: Float,
    targetHigh: Float,
) {
    val actualColor = MaterialTheme.colorScheme.onSurface
    val standardColor = MaterialTheme.colorScheme.primary
    val adaptiveColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = axisColor, fontSize = 11.sp)

    val start = moment.originTime - 60 * MINUTE_MS
    val end = moment.originTime + 120 * MINUTE_MS
    fun curve(f: RecordedForecastEntity?): List<Pair<Long, Float>> = f?.let { forecast ->
        listOf(moment.originTime to forecast.baselineMgdl) + forecast.values.withIndex().mapNotNull { (k, v) ->
            val t = moment.originTime + (k + 1) * forecast.stepMinutes * MINUTE_MS
            if (t <= end && v.isFinite()) t to v else null
        }
    }.orEmpty()
    val standard = curve(moment.standard)
    val adaptive = curve(moment.adaptive)
    val actual = moment.actualTimes.indices.filter { moment.actualTimes[it] in start..end }
        .map { moment.actualTimes[it] to moment.actualValues[it] }
    val all = (standard + adaptive + actual).map { it.second } + listOf(targetLow, targetHigh)
    val yMin = (all.minOrNull() ?: 70f).coerceAtLeast(40f) - 10f
    val yMax = (all.maxOrNull() ?: 180f) + 10f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        val left = 36.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val width = size.width - left
        fun x(t: Long) = left + width * ((t - start).toFloat() / (end - start))
        fun y(v: Float) = bottom * (1f - (v - yMin) / (yMax - yMin))

        drawRect(bandColor, topLeft = Offset(left, y(targetHigh)), size = Size(width, y(targetLow) - y(targetHigh)))
        listOf(targetLow, targetHigh).forEach { v ->
            drawLine(gridColor, Offset(left, y(v)), Offset(size.width, y(v)), strokeWidth = 1.dp.toPx())
            val label = measurer.measure(GlucoseFormatter.formatFromMgDl(v, isMmol), labelStyle)
            drawText(label, topLeft = Offset(0f, y(v) - label.size.height / 2f))
        }
        drawLine(
            axisColor, Offset(x(moment.originTime), 0f), Offset(x(moment.originTime), bottom),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        listOf(start, moment.originTime, moment.originTime + 60 * MINUTE_MS, end).forEach { t ->
            val label = measurer.measure(timeFormat.format(Date(t)), labelStyle)
            val lx = (x(t) - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
            drawText(label, topLeft = Offset(lx, bottom + 4.dp.toPx()))
        }

        fun line(points: List<Pair<Long, Float>>, color: Color, style: ForecastLineStyle, breakAfterMs: Long = Long.MAX_VALUE) {
            if (points.size < 2) return
            val path = Path()
            var last: Long? = null
            for ((t, v) in points) {
                if (last == null || t - last > breakAfterMs) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v))
                last = t
            }
            drawPath(
                path, color,
                style = Stroke(
                    width = (if (style == ForecastLineStyle.DOTTED) 3.5f else 3f) * density,
                    pathEffect = style.pathEffect(density),
                    cap = style.cap,
                ),
            )
        }
        line(actual, actualColor, ForecastLineStyle.SOLID, breakAfterMs = 20 * MINUTE_MS)
        line(standard, standardColor, ForecastLineStyle.DASHED)
        line(adaptive, adaptiveColor, ForecastLineStyle.DOTTED)
        drawCircle(actualColor, radius = 4.dp.toPx(), center = Offset(x(moment.originTime), y((moment.standard ?: moment.adaptive)!!.baselineMgdl)))
    }
}

private fun displayToMgdl(value: Float, isMmol: Boolean): Float? =
    value.takeIf { it.isFinite() && it > 0f }?.let { if (isMmol) it * GlucoseFormatter.MGDL_PER_MMOL else it }

@Composable
private fun OutcomeTable(moment: ForecastMoment, isMmol: Boolean) {
    val forecasts = listOfNotNull(
        moment.standard?.let { stringResource(R.string.prediction_analysis_model_standard) to it },
        moment.adaptive?.let { stringResource(R.string.prediction_analysis_model_adaptive) to it },
    )
    Surface(shape = InspectorCardShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                HeaderCell(stringResource(R.string.prediction_inspector_in), Modifier.weight(0.8f))
                HeaderCell(stringResource(R.string.prediction_inspector_actual), Modifier.weight(1f))
                forecasts.forEach { (name, _) -> HeaderCell(name, Modifier.weight(1.3f)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PredictionAccuracy.DEFAULT_HORIZONS.forEach { h ->
                val (outcome, actual) = PredictionAccuracy.actualOutcome(
                    moment.sensorSerial, moment.lane, moment.originTime + h * MINUTE_MS, moment.actual,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BodyCell(stringResource(R.string.prediction_analysis_minutes, h), Modifier.weight(0.8f))
                    BodyCell(
                        when (outcome) {
                            Outcome.RESOLVED -> GlucoseFormatter.formatFromMgDl(actual, isMmol)
                            Outcome.PENDING -> stringResource(R.string.prediction_inspector_pending)
                            Outcome.MISSING -> stringResource(R.string.prediction_inspector_missing)
                        },
                        Modifier.weight(1f),
                    )
                    forecasts.forEach { (_, forecast) ->
                        val predicted = forecast.valueAt(h)
                        val text = when {
                            !predicted.isFinite() -> "–"
                            outcome != Outcome.RESOLVED -> GlucoseFormatter.formatFromMgDl(predicted, isMmol)
                            else -> {
                                val error = predicted - actual
                                val sign = if (error >= 0) "+" else "−"
                                GlucoseFormatter.formatFromMgDl(predicted, isMmol) + " (" + sign +
                                    GlucoseFormatter.formatFromMgDl(kotlin.math.abs(error), isMmol) + ")"
                            }
                        }
                        BodyCell(text, Modifier.weight(1.3f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier, maxLines = 2)
}

@Composable
private fun BodyCell(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), modifier = modifier)
}

@Composable
private fun ContextLines(moment: ForecastMoment, isMmol: Boolean) {
    val reference = moment.standard ?: moment.adaptive ?: return
    val lines = buildList {
        reference.trendMgdlPerMin?.let { trend ->
            val value = GlucoseFormatter.displayFromMgDl(trend, isMmol)
            val text = String.format(java.util.Locale.getDefault(), if (isMmol) "%+.2f" else "%+.1f", value) + " " +
                stringResource(if (isMmol) R.string.unit_mmol else R.string.unit_mg)
            add(stringResource(R.string.prediction_inspector_trend, text))
        }
        if (reference.plannedEntries > 0) {
            add(pluralStringResource(R.plurals.prediction_inspector_planned, reference.plannedEntries, reference.plannedEntries))
        }
        if (moment.adaptive?.fallback == true) add(stringResource(R.string.prediction_inspector_fallback))
    }
    if (lines.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
