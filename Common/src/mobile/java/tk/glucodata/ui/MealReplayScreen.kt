@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import tk.glucodata.data.prediction.PredictionModelProfile
import tk.glucodata.data.prediction.analysis.MealReplay
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.viewmodel.DashboardViewModel
import tk.glucodata.ui.viewmodel.MealReplayState
import tk.glucodata.ui.viewmodel.MealReplayViewModel
import tk.glucodata.ui.viewmodel.ReplayBlock
import tk.glucodata.ui.viewmodel.ReplayedMeal
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MINUTE_MS = 60_000L
private val ReplayCardShape = RoundedCornerShape(20.dp)
private val FigureShape = RoundedCornerShape(12.dp)

/**
 * Past meals replayed with the saved profile and with trial values (#722, plan PR 1). Trial
 * values live in [MealReplayViewModel] only: nothing on this screen writes the profile.
 */
@Composable
fun MealReplayScreen(navController: NavController, dashboardViewModel: DashboardViewModel) {
    val viewModel: MealReplayViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val targetLow by dashboardViewModel.targetLow.collectAsStateWithLifecycle()
    val targetHigh by dashboardViewModel.targetHigh.collectAsStateWithLifecycle()
    val isMmol = remember { GlucoseFormatter.isMmolApp() }

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.meal_replay_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        MealReplayContent(
            state = state,
            selected = selected,
            isMmol = isMmol,
            targetLowMgdl = toMgdl(targetLow, isMmol) ?: 70f,
            targetHighMgdl = toMgdl(targetHigh, isMmol) ?: 180f,
            onSelect = viewModel::select,
            onCarbRatio = { start, value -> viewModel.setTrial(start, carbRatio = value) },
            onSensitivity = { start, mgdl -> viewModel.setTrial(start, sensitivityMgdl = mgdl) },
            onReset = viewModel::resetTrial,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Past meals without their data source. */
@Composable
internal fun MealReplayContent(
    state: MealReplayState?,
    selected: Int,
    isMmol: Boolean,
    targetLowMgdl: Float,
    targetHighMgdl: Float,
    onSelect: (Int) -> Unit,
    onCarbRatio: (Int, Float) -> Unit,
    onSensitivity: (Int, Float) -> Unit,
    onReset: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            state == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
            state.meals.isEmpty() -> item(key = "empty") {
                Text(
                    text = stringResource(R.string.meal_replay_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            else -> {
                items(state.blocks, key = { "block_${it.startMinute}" }) { block ->
                    ReplayBlockCard(
                        block = block,
                        isMmol = isMmol,
                        onCarbRatio = { onCarbRatio(block.startMinute, it) },
                        onSensitivity = { onSensitivity(block.startMinute, it) },
                        onReset = { onReset(block.startMinute) },
                    )
                }
                if (selected in state.meals.indices) {
                    item(key = "meal") {
                        ReplayMealCard(state.meals, selected, isMmol, targetLowMgdl, targetHighMgdl, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayBlockCard(
    block: ReplayBlock,
    isMmol: Boolean,
    onCarbRatio: (Float) -> Unit,
    onSensitivity: (Float) -> Unit,
    onReset: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = ReplayCardShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${formatMinuteOfDay(block.startMinute)} – ${formatMinuteOfDay(block.endMinute)}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.meal_replay_meal_count, block.scoredMeals),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.meal_replay_miss_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MissFigure(stringResource(R.string.meal_replay_saved), block.savedMissMgdl, MaterialTheme.colorScheme.primary, isMmol, Modifier.weight(1f))
                MissFigure(stringResource(R.string.meal_replay_trial), block.trialMissMgdl, MaterialTheme.colorScheme.tertiary, isMmol, Modifier.weight(1f))
                MissFigure(stringResource(R.string.meal_replay_flat), block.flatMissMgdl, MaterialTheme.colorScheme.onSurfaceVariant, isMmol, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            PredictiveSimulationParameterRow(
                title = stringResource(R.string.predictive_carb_ratio),
                valueLabel = stringResource(R.string.predictive_carb_ratio_value, formatCarbRatio(block.trial.carbRatioGramsPerUnit)),
                value = block.trial.carbRatioGramsPerUnit,
                valueRange = PredictionModelProfile.CARB_RATIO_MIN..PredictionModelProfile.CARB_RATIO_MAX,
                enabled = true,
                onValueChange = { onCarbRatio(snapCarbRatio(it)) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
            val sensitivityDisplay = GlucoseFormatter.displayFromMgDl(block.trial.insulinSensitivityMgDlPerUnit, isMmol)
            PredictiveSimulationParameterRow(
                title = stringResource(R.string.predictive_insulin_sensitivity),
                valueLabel = if (isMmol) {
                    stringResource(R.string.predictive_sensitivity_value_mmol, sensitivityDisplay)
                } else {
                    stringResource(R.string.predictive_sensitivity_value_mgdl, block.trial.insulinSensitivityMgDlPerUnit)
                },
                value = sensitivityDisplay,
                valueRange = if (isMmol) 0.6f..10f else 10f..180f,
                enabled = true,
                onValueChange = { display ->
                    onSensitivity(
                        if (isMmol) GlucoseFormatter.mmolToMg((display * 10f).roundToInt() / 10f) else display.roundToInt().toFloat()
                    )
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.meal_replay_trial_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onReset,
                    enabled = block.trial != block.saved,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(R.string.meal_replay_reset)) }
            }
        }
    }
}

@Composable
private fun MissFigure(label: String, mgdl: Float?, color: Color, isMmol: Boolean, modifier: Modifier) {
    Surface(modifier = modifier, shape = FigureShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Text(
                text = mgdl?.let { GlucoseFormatter.formatFromMgDl(it, isMmol) } ?: "–",
                style = MaterialTheme.typography.titleLargeEmphasized.copy(fontFeatureSettings = "tnum"),
                color = color,
            )
        }
    }
}

@Composable
private fun ReplayMealCard(
    meals: List<ReplayedMeal>,
    selected: Int,
    isMmol: Boolean,
    targetLowMgdl: Float,
    targetHighMgdl: Float,
    onSelect: (Int) -> Unit,
) {
    val context = LocalContext.current
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val meal = meals[selected]
    Surface(modifier = Modifier.fillMaxWidth(), shape = ReplayCardShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val previous = stringResource(R.string.meal_replay_previous)
                IconButtonTooltip(previous) {
                    IconButton(onClick = { onSelect(selected - 1) }, enabled = selected > 0, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = previous)
                    }
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.meal_replay_meal_at, timeFormat.format(Date(meal.meal.timestamp))),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = dateFormat.format(Date(meal.meal.timestamp)) + " · " + mealAmounts(meal),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                val next = stringResource(R.string.meal_replay_next)
                IconButtonTooltip(next) {
                    IconButton(onClick = { onSelect(selected + 1) }, enabled = selected < meals.lastIndex, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = next)
                    }
                }
            }
            if (meals.size > 1) {
                var dragging by remember(selected) { mutableFloatStateOf(selected.toFloat()) }
                Slider(
                    value = dragging,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { onSelect(dragging.roundToInt()) },
                    valueRange = 0f..meals.lastIndex.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            MealChart(meal, isMmol, timeFormat, targetLowMgdl, targetHighMgdl)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendSwatch(stringResource(R.string.prediction_inspector_actual), MaterialTheme.colorScheme.onSurface)
                LegendSwatch(stringResource(R.string.meal_replay_saved), MaterialTheme.colorScheme.primary, ForecastLineStyle.DASHED)
                if (meal.trial != null) {
                    LegendSwatch(stringResource(R.string.meal_replay_trial), MaterialTheme.colorScheme.tertiary, ForecastLineStyle.DOTTED)
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            MealOutcome(meal, isMmol)
        }
    }
}

@Composable
private fun mealAmounts(meal: ReplayedMeal): String {
    val grams = formatAmount(meal.meal.grams)
    return if (meal.insulinUnits > 0f) {
        stringResource(R.string.meal_replay_carbs_insulin, grams, formatAmount(meal.insulinUnits))
    } else {
        stringResource(R.string.meal_replay_carbs_only, grams)
    }
}

@Composable
private fun MealOutcome(meal: ReplayedMeal, isMmol: Boolean) {
    val actual = meal.saved.actualAtScoreMgdl
    if (actual == null) {
        Text(
            stringResource(R.string.meal_replay_no_outcome),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutcomeLine(stringResource(R.string.meal_replay_reading_at_4h), GlucoseFormatter.formatFromMgDl(actual, isMmol))
        OutcomeLine(stringResource(R.string.meal_replay_saved), withMiss(meal.saved.forecastAtScoreMgdl, actual, isMmol))
        meal.trial?.let { OutcomeLine(stringResource(R.string.meal_replay_trial), withMiss(it.forecastAtScoreMgdl, actual, isMmol)) }
    }
}

@Composable
private fun OutcomeLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
    }
}

private fun withMiss(forecast: Float, actual: Float, isMmol: Boolean): String {
    val error = forecast - actual
    val sign = if (error >= 0) "+" else "−"
    return GlucoseFormatter.formatFromMgDl(forecast, isMmol) + " (" + sign + GlucoseFormatter.formatFromMgDl(abs(error), isMmol) + ")"
}

@Composable
private fun MealChart(
    meal: ReplayedMeal,
    isMmol: Boolean,
    timeFormat: DateFormat,
    targetLow: Float,
    targetHigh: Float,
) {
    val actualColor = MaterialTheme.colorScheme.onSurface
    val savedColor = MaterialTheme.colorScheme.primary
    val trialColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = axisColor, fontSize = 11.sp)

    val mealTime = meal.meal.timestamp
    val start = mealTime - MealReplay.DRAW_BEFORE_MS
    val end = mealTime + MealReplay.DRAW_AFTER_MINUTES * MINUTE_MS
    val scoreMark = mealTime + MealReplay.SCORE_AFTER_MINUTES * MINUTE_MS
    fun curve(replay: MealReplay.Replay?) = replay?.points?.filter { it.timestamp in start..end }?.map { it.timestamp to it.value }.orEmpty()
    val saved = curve(meal.saved)
    val trial = curve(meal.trial)
    val actual = meal.readingsMgdl.filter { it.first in start..end && it.second.isFinite() && it.second > 0f }
    val all = (saved + trial + actual).map { it.second } + listOf(targetLow, targetHigh)
    val yMin = (all.minOrNull() ?: 70f).coerceAtLeast(40f) - 10f
    val yMax = (all.maxOrNull() ?: 180f) + 10f

    Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
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
            axisColor, Offset(x(mealTime), 0f), Offset(x(mealTime), bottom),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        // Where the figures above are scored.
        drawLine(gridColor, Offset(x(scoreMark), 0f), Offset(x(scoreMark), bottom), strokeWidth = 1.dp.toPx())
        listOf(mealTime, mealTime + 120 * MINUTE_MS, scoreMark, end).forEach { t ->
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
        line(saved, savedColor, ForecastLineStyle.DASHED)
        line(trial, trialColor, ForecastLineStyle.DOTTED)
    }
}

/** Ratios are written to the half gram, as on the Model profile screen. */
private fun snapCarbRatio(value: Float): Float =
    ((value / PredictionModelProfile.CARB_RATIO_STEP).roundToInt() * PredictionModelProfile.CARB_RATIO_STEP)
        .coerceIn(PredictionModelProfile.CARB_RATIO_MIN, PredictionModelProfile.CARB_RATIO_MAX)

private fun formatAmount(value: Float): String =
    java.text.NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 1
        isGroupingUsed = false
    }.format(value)

private fun toMgdl(value: Float, isMmol: Boolean): Float? =
    value.takeIf { it.isFinite() && it > 0f }?.let { if (isMmol) it * GlucoseFormatter.MGDL_PER_MMOL else it }
