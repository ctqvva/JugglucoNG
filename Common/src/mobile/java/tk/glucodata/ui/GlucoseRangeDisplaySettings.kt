package tk.glucodata.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import tk.glucodata.GlucoseRangeColors.Band
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.ExpandableSettingsCard
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.util.GlucoseFormatter
import tk.glucodata.ui.viewmodel.DashboardViewModel

@Composable
fun GlucoseRangeDisplaySettings(viewModel: DashboardViewModel) {
    val unit by viewModel.unit.collectAsState()
    val isMmol = GlucoseFormatter.isMmol(unit)
    val chartLowValue by viewModel.graphLow.collectAsState()
    val chartHighValue by viewModel.graphHigh.collectAsState()
    val targetLowValue by viewModel.targetLow.collectAsState()
    val targetHighValue by viewModel.targetHigh.collectAsState()
    val veryLowValue by viewModel.veryLowThreshold.collectAsState()
    val veryHighValue by viewModel.veryHighThreshold.collectAsState()

    val valueStep = if (isMmol) 0.1f else 1f
    val targetLowBounds = if (isMmol) 2.0f..8.0f else 40f..140f
    val targetHighBounds = if (isMmol) 6.0f..16.0f else 100f..350f
    val chartLowBounds = if (isMmol) 0.0f..12.0f else 0f..216f
    val chartHighBounds = if (isMmol) 4.0f..30.0f else 72f..540f
    val veryLowBounds = if (isMmol) 2.0f..4.0f else 36f..70f
    val veryHighBounds = if (isMmol) 10.0f..20.0f else 180f..360f

    val normalizedTargetLow = clampLowerRangeValue(
        targetLowValue, targetHighValue, targetLowBounds, valueStep, isMmol
    )
    val normalizedTargetHigh = clampUpperRangeValue(
        targetHighValue, normalizedTargetLow, targetHighBounds, valueStep, isMmol
    )
    val normalizedChartLow = clampLowerRangeValue(
        chartLowValue, chartHighValue, chartLowBounds, valueStep, isMmol
    )
    val normalizedChartHigh = clampUpperRangeValue(
        chartHighValue, normalizedChartLow, chartHighBounds, valueStep, isMmol
    )
    val normalizedVeryLow = clampLowerRangeValue(
        veryLowValue, veryHighValue, veryLowBounds, valueStep, isMmol
    )
    val normalizedVeryHigh = clampUpperRangeValue(
        veryHighValue, normalizedVeryLow, veryHighBounds, valueStep, isMmol
    )

    var targetLowSlider by remember(targetLowValue, targetHighValue, isMmol) {
        mutableFloatStateOf(normalizedTargetLow)
    }
    var targetHighSlider by remember(targetLowValue, targetHighValue, isMmol) {
        mutableFloatStateOf(normalizedTargetHigh)
    }
    var chartLowSlider by remember(chartLowValue, chartHighValue, isMmol) {
        mutableFloatStateOf(normalizedChartLow)
    }
    var chartHighSlider by remember(chartLowValue, chartHighValue, isMmol) {
        mutableFloatStateOf(normalizedChartHigh)
    }
    var veryLowSlider by remember(veryLowValue, veryHighValue, isMmol) {
        mutableFloatStateOf(normalizedVeryLow)
    }
    var veryHighSlider by remember(veryLowValue, veryHighValue, isMmol) {
        mutableFloatStateOf(normalizedVeryHigh)
    }
    var chartExpanded by rememberSaveable { mutableStateOf(false) }
    var glucoseExpanded by rememberSaveable { mutableStateOf(false) }

    val targetSummary = formatRangeSummary(targetLowSlider, targetHighSlider, isMmol)
    val chartSummary = formatRangeSummary(chartLowSlider, chartHighSlider, isMmol)
    val verySummary = formatRangeSummary(veryLowSlider, veryHighSlider, isMmol)
    val glucoseSummary =
        "${stringResource(R.string.target_short_title)} $targetSummary • " +
            "${stringResource(R.string.very_range_short_title)} $verySummary"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ExpandableSettingsCard(
            title = stringResource(R.string.chart_limits_title),
            summary = chartSummary,
            icon = Icons.AutoMirrored.Filled.ShowChart,
            expanded = chartExpanded,
            onExpandedChange = { chartExpanded = it },
            position = CardPosition.TOP
        ) {
            GlucoseRangeSliderSection(
                lowLabel = stringResource(R.string.low_label),
                highLabel = stringResource(R.string.high_label),
                lowValue = chartLowSlider,
                highValue = chartHighSlider,
                lowBounds = chartLowBounds,
                highBounds = chartHighBounds,
                isMmol = isMmol,
                onLowValueChange = { candidate ->
                    chartLowSlider = clampLowerRangeValue(
                        candidate, chartHighSlider, chartLowBounds, valueStep, isMmol
                    )
                },
                onHighValueChange = { candidate ->
                    chartHighSlider = clampUpperRangeValue(
                        candidate, chartLowSlider, chartHighBounds, valueStep, isMmol
                    )
                },
                onLowValueChangeFinished = {
                    viewModel.setGraphRange(chartLowSlider, chartHighSlider)
                },
                onHighValueChangeFinished = {
                    viewModel.setGraphRange(chartLowSlider, chartHighSlider)
                }
            )
        }
        ExpandableSettingsCard(
            title = stringResource(R.string.glucose_range_title),
            summary = glucoseSummary,
            icon = Icons.Default.TrackChanges,
            expanded = glucoseExpanded,
            onExpandedChange = { glucoseExpanded = it },
            position = CardPosition.BOTTOM
        ) {
            GlucosePalettePresetSelector()
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            GlucoseThresholdSliderRow(
                label = stringResource(R.string.very_high_label),
                value = veryHighSlider,
                bounds = veryHighBounds,
                isMmol = isMmol,
                colorBand = Band.VERY_HIGH,
                onValueChange = { candidate ->
                    veryHighSlider = clampUpperRangeValue(
                        candidate, veryLowSlider, veryHighBounds, valueStep, isMmol
                    )
                },
                onValueChangeFinished = {
                    viewModel.setVeryLowHighThresholds(veryLowSlider, veryHighSlider)
                }
            )
            GlucoseThresholdSliderRow(
                label = stringResource(R.string.high_label),
                value = targetHighSlider,
                bounds = targetHighBounds,
                isMmol = isMmol,
                colorBand = Band.HIGH,
                onValueChange = { candidate ->
                    targetHighSlider = clampUpperRangeValue(
                        candidate, targetLowSlider, targetHighBounds, valueStep, isMmol
                    )
                },
                onValueChangeFinished = {
                    viewModel.setTargetRange(targetLowSlider, targetHighSlider)
                }
            )
            TargetRangeColorRow()
            GlucoseThresholdSliderRow(
                label = stringResource(R.string.low_label),
                value = targetLowSlider,
                bounds = targetLowBounds,
                isMmol = isMmol,
                colorBand = Band.LOW,
                onValueChange = { candidate ->
                    targetLowSlider = clampLowerRangeValue(
                        candidate, targetHighSlider, targetLowBounds, valueStep, isMmol
                    )
                },
                onValueChangeFinished = {
                    viewModel.setTargetRange(targetLowSlider, targetHighSlider)
                }
            )
            GlucoseThresholdSliderRow(
                label = stringResource(R.string.very_low_label),
                value = veryLowSlider,
                bounds = veryLowBounds,
                isMmol = isMmol,
                colorBand = Band.VERY_LOW,
                onValueChange = { candidate ->
                    veryLowSlider = clampLowerRangeValue(
                        candidate, veryHighSlider, veryLowBounds, valueStep, isMmol
                    )
                },
                onValueChangeFinished = {
                    viewModel.setVeryLowHighThresholds(veryLowSlider, veryHighSlider)
                }
            )
            GlucosePaletteResetAllButton()
        }
    }
}

@Composable
private fun GlucoseRangeSliderSection(
    lowLabel: String,
    highLabel: String,
    lowValue: Float,
    highValue: Float,
    lowBounds: ClosedFloatingPointRange<Float>,
    highBounds: ClosedFloatingPointRange<Float>,
    isMmol: Boolean,
    onLowValueChange: (Float) -> Unit,
    onHighValueChange: (Float) -> Unit,
    onLowValueChangeFinished: () -> Unit,
    onHighValueChangeFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlucoseRangeSliderRow(
            label = highLabel,
            value = highValue,
            bounds = highBounds,
            isMmol = isMmol,
            onValueChange = onHighValueChange,
            onValueChangeFinished = onHighValueChangeFinished
        )
        GlucoseRangeSliderRow(
            label = lowLabel,
            value = lowValue,
            bounds = lowBounds,
            isMmol = isMmol,
            onValueChange = onLowValueChange,
            onValueChangeFinished = onLowValueChangeFinished
        )
    }
}

@Composable
private fun GlucoseThresholdSliderRow(
    label: String,
    value: Float,
    bounds: ClosedFloatingPointRange<Float>,
    isMmol: Boolean,
    colorBand: Band,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    GlucoseRangeSliderRow(
        label = label,
        value = value,
        bounds = bounds,
        isMmol = isMmol,
        colorBand = colorBand,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished
    )
}

@Composable
private fun GlucoseRangeSliderRow(
    label: String,
    value: Float,
    bounds: ClosedFloatingPointRange<Float>,
    isMmol: Boolean,
    colorBand: Band? = null,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatRangeValue(value, isMmol),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = bounds,
                modifier = Modifier.fillMaxWidth()
            )
        }
        colorBand?.let { band ->
            Spacer(Modifier.width(16.dp))
            RangeColorButton(band)
        }
    }
}

@Composable
private fun TargetRangeColorRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.target_range_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        GlucoseTargetBackgroundColorButton(
            modifier = Modifier.size(56.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        Spacer(Modifier.width(8.dp))
        RangeColorButton(Band.IN_RANGE)
    }
}

@Composable
private fun RangeColorButton(band: Band) {
    GlucoseBandColorButton(
        band = band,
        modifier = Modifier.size(56.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    )
}

private fun formatRangeSummary(lowValue: Float, highValue: Float, isMmol: Boolean): String {
    return "${formatRangeValue(lowValue, isMmol)}-${formatRangeValue(highValue, isMmol)}"
}

private fun formatRangeValue(value: Float, isMmol: Boolean): String {
    return if (isMmol) {
        String.format(Locale.getDefault(), "%.1f", value)
    } else {
        value.roundToInt().toString()
    }
}

private fun clampLowerRangeValue(
    value: Float,
    upperValue: Float,
    bounds: ClosedFloatingPointRange<Float>,
    step: Float,
    isMmol: Boolean
): Float {
    val maxValue = minOf(bounds.endInclusive, upperValue - step)
    if (maxValue < bounds.start) {
        return snapRangeValue(bounds.start, isMmol)
    }
    return snapRangeValue(value, isMmol).coerceIn(bounds.start, maxValue)
}

private fun clampUpperRangeValue(
    value: Float,
    lowerValue: Float,
    bounds: ClosedFloatingPointRange<Float>,
    step: Float,
    isMmol: Boolean
): Float {
    val minValue = maxOf(bounds.start, lowerValue + step)
    if (minValue > bounds.endInclusive) {
        return snapRangeValue(bounds.endInclusive, isMmol)
    }
    return snapRangeValue(value, isMmol).coerceIn(minValue, bounds.endInclusive)
}

private fun snapRangeValue(value: Float, isMmol: Boolean): Float {
    return if (isMmol) {
        (value * 10f).roundToInt() / 10f
    } else {
        value.roundToInt().toFloat()
    }
}
