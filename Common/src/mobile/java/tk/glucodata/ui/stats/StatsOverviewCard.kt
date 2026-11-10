package tk.glucodata.ui.stats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Shared by drawing, hit testing, and the center text's circular fit calculation.
private val OverviewRingStroke = 12.dp

internal enum class TirBand {
    VERY_LOW,
    LOW,
    IN_RANGE,
    HIGH,
    VERY_HIGH
}

private data class TirRowDescriptor(
    val band: TirBand,
    val label: String,
    val rangeLabel: String,
    val percent: Float,
    val color: Color
)

/** The ring keeps its footprint; translated labels adapt within the table beside it. */
@Composable
internal fun GlycemicOverviewCard(
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit,
    selectedRange: StatsTimeRange?,
    selectedBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        TirRowDescriptor(TirBand.VERY_HIGH, stringResource(R.string.very_high),
            "≥ ${formatMgDl(targets.veryHighMgDl, unit)}", summary.tir.veryHighPercent, TirVeryHighColor),
        TirRowDescriptor(TirBand.HIGH, stringResource(R.string.high_range),
            "${formatMgDl(targets.highMgDl, unit)}–${formatMgDl(targets.veryHighMgDl, unit)}", summary.tir.highPercent, TirHighColor),
        TirRowDescriptor(TirBand.IN_RANGE, stringResource(R.string.in_range),
            "${formatMgDl(targets.lowMgDl, unit)}–${formatMgDl(targets.highMgDl, unit)}", summary.tir.inRangePercent, TirInRangeColor),
        TirRowDescriptor(TirBand.LOW, stringResource(R.string.low_range),
            "${formatMgDl(targets.veryLowMgDl, unit)}–${formatMgDl(targets.lowMgDl, unit)}", summary.tir.lowPercent, TirLowColor),
        TirRowDescriptor(TirBand.VERY_LOW, stringResource(R.string.very_low),
            "< ${formatMgDl(targets.veryLowMgDl, unit)}", summary.tir.veryLowPercent, TirVeryLowColor)
    )
    val labelStyle = MaterialTheme.typography.bodySmall
    val rangeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val percentStyle = MaterialTheme.typography.labelMediumEmphasized.copy(fontFeatureSettings = "tnum")
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    fun width(text: String, style: TextStyle) = with(density) {
        measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp()
    }
    val rangeWidth = rows.maxOf { width(it.rangeLabel, rangeStyle) }.onStatsGrid()
    val percentWidth = (rows.map { formatLocalePercent(it.percent, 1) } + formatLocalePercent(100f, 1))
        .maxOf { width(it, percentStyle) }.onStatsGrid()
    // A useful label column, rather than the longest translation forcing the entire card to stack.
    val labelWidth = maxOf(96.dp * density.fontScale, rows.maxOf { width(it.label, labelStyle) })
    val threeColumnWidth = labelWidth + rangeWidth + percentWidth + 16.dp
    val minimumTableWidth = maxOf(112.dp, rangeWidth + percentWidth + 8.dp)

    Card(
        onClick = { onBandSelected(null) },
        modifier = modifier.fillMaxWidth(),
        shape = StatsCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val ringSize = (maxWidth * 0.32f).onStatsGrid().coerceIn(128.dp, 160.dp).coerceAtMost(maxWidth)
            val inline = maxWidth >= ringSize + 16.dp + minimumTableWidth
            val tableWidth = if (inline) maxWidth - ringSize - 16.dp else maxWidth
            val columns = tableWidth >= threeColumnWidth
            val table: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth()) {
                    rows.forEach { row ->
                        TirBandRow(
                            row, rangeWidth, percentWidth, columns, labelStyle, rangeStyle, percentStyle,
                            selected = selectedBand == row.band,
                            onClick = { onBandSelected(if (selectedBand == row.band) null else row.band) }
                        )
                    }
                }
            }
            val ring: @Composable () -> Unit = {
                OverviewRing(summary.tir, summary.comparison, selectedBand, onBandSelected, Modifier.width(ringSize))
            }
            if (inline) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ring()
                    Box(Modifier.weight(1f)) { table() }
                }
            } else {
                // Very narrow windows still get a bounded ring, never a screen-sized circle.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ring()
                    table()
                }
            }
        }
    }
}

internal fun Dp.onStatsGrid(): Dp = (ceil(value / 4f) * 4f).dp

private fun Modifier.statsGridHeight() = layout { measurable, constraints ->
    val child = measurable.measure(constraints)
    val height = constraints.constrainHeight(child.height.toDp().onStatsGrid().roundToPx())
    layout(child.width, height) { child.placeRelative(0, 0) }
}

@Composable
private fun TirBandRow(
    row: TirRowDescriptor,
    rangeWidth: Dp,
    percentWidth: Dp,
    threeColumns: Boolean,
    labelStyle: TextStyle,
    rangeStyle: TextStyle,
    percentStyle: TextStyle,
    selected: Boolean,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val oneLine = with(density) { labelStyle.lineHeight.toDp() }
    val numberLine = with(density) { maxOf(rangeStyle.lineHeight.toDp(), percentStyle.lineHeight.toDp()) }
    // Shared row rhythm follows the text and lands on the 4dp grid. Clickable supplies
    // Compose's expanded minimum touch target around the visually dense band rows.
    val rowHeight = (if (threeColumns) maxOf(oneLine, numberLine) + 8.dp
        else oneLine + numberLine + 12.dp).onStatsGrid()
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick)
            .statsGridHeight()
            .heightIn(min = rowHeight)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (threeColumns) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, Modifier.weight(1f).alignByBaseline(), style = labelStyle,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                Text(row.rangeLabel, Modifier.width(rangeWidth).alignByBaseline(), style = rangeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1)
                Text(formatLocalePercent(row.percent, 1), Modifier.width(percentWidth).alignByBaseline(),
                    style = percentStyle, color = row.color, textAlign = TextAlign.End, maxLines = 1)
            }
        } else {
            Text(row.label, style = labelStyle, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.rangeLabel, Modifier.weight(1f).alignByBaseline(), style = rangeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(formatLocalePercent(row.percent, 1), Modifier.width(percentWidth).alignByBaseline(),
                    style = percentStyle, color = row.color, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

/** Select a type-scale step whose whole text rectangle fits inside the circular opening. */
@Composable
private fun ringValueStyle(diameter: Dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val smallStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val label = measurer.measure(stringResource(R.string.tir), smallStyle).size
    val styles = listOf(MaterialTheme.typography.headlineLarge, MaterialTheme.typography.headlineMedium,
        MaterialTheme.typography.headlineSmall, MaterialTheme.typography.titleLarge,
        MaterialTheme.typography.titleMediumEmphasized, MaterialTheme.typography.titleSmallEmphasized)
    val available = with(density) { (diameter - OverviewRingStroke * 2 - 8.dp).toPx() }
    return styles.firstOrNull { style ->
        val percent = measurer.measure(formatLocalePercent(100f), style.copy(fontFeatureSettings = "tnum")).size
        val width = maxOf(percent.width, label.width).toFloat()
        val height = (percent.height + label.height).toFloat()
        sqrt(width * width + height * height) <= available
    }?.copy(fontFeatureSettings = "tnum") ?: styles.last().copy(fontFeatureSettings = "tnum")
}

@Composable
internal fun OverviewRing(
    tir: TimeInRangeBreakdown,
    comparison: StatsComparison?,
    selectedBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit,
    modifier: Modifier = Modifier
) {
    val veryLow by animateFloatAsState(tir.veryLowPercent.coerceIn(0f, 100f), label = "ringVeryLow")
    val low by animateFloatAsState(tir.lowPercent.coerceIn(0f, 100f), label = "ringLow")
    val inRange by animateFloatAsState(tir.inRangePercent.coerceIn(0f, 100f), label = "ringInRange")
    val high by animateFloatAsState(tir.highPercent.coerceIn(0f, 100f), label = "ringHigh")
    val veryHigh by animateFloatAsState(tir.veryHighPercent.coerceIn(0f, 100f), label = "ringVeryHigh")

    val inRangeTone by animateColorAsState(
        targetValue = when {
            inRange >= 70f -> TirInRangeColor
            inRange >= 55f -> TirHighColor
            else -> TirVeryHighColor
        },
        label = "ringCenterTone"
    )

    val segments = listOf(
        Triple(TirBand.VERY_LOW, veryLow, TirVeryLowColor),
        Triple(TirBand.LOW, low, TirLowColor),
        Triple(TirBand.IN_RANGE, inRange, TirInRangeColor),
        Triple(TirBand.HIGH, high, TirHighColor),
        Triple(TirBand.VERY_HIGH, veryHigh, TirVeryHighColor)
    )

    val label = stringResource(R.string.tir)
    val formattedPercent = formatLocalePercent(inRange)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(
            modifier = Modifier.aspectRatio(1f).semantics { contentDescription = "$formattedPercent $label" }.pointerInput(veryLow, low, inRange, high, veryHigh, selectedBand) {
                detectTapGestures { tapOffset ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dx = tapOffset.x - center.x
                    val dy = tapOffset.y - center.y
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    val outerRadius = minOf(size.width, size.height) / 2f
                    val strokePx = OverviewRingStroke.toPx()
                    val innerRadius = (outerRadius - strokePx * 1.35f).coerceAtLeast(0f)
                    if (distance < innerRadius || distance > outerRadius + strokePx * 0.35f) {
                        onBandSelected(null)
                        return@detectTapGestures
                    }

                    val angle = ((atan2(dy, dx) * 180f / PI.toFloat()) + 450f) % 360f
                    var start = 0f
                    for ((band, percent, _) in segments) {
                        val sweep = 360f * (percent / 100f)
                        if (sweep > 0f && angle >= start && angle < start + sweep) {
                            onBandSelected(if (selectedBand == band) null else band)
                            return@detectTapGestures
                        }
                        start += sweep
                    }
                    onBandSelected(null)
                }
            },
            contentAlignment = Alignment.Center
        ) {
            val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = OverviewRingStroke.toPx()
                val diameter = size.minDimension - stroke
                drawCircle(
                    color = trackColor,
                    radius = diameter / 2f,
                    center = center,
                    style = Stroke(width = stroke)
                )

                var startAngle = -90f
                segments.forEach { (band, percent, color) ->
                    val sweepAngle = 360f * (percent / 100f)
                    if (sweepAngle > 0.8f) {
                        val gap = 1.2f
                        val drawColor = if (selectedBand == null || selectedBand == band) {
                            color
                        } else {
                            color.copy(alpha = 0.35f)
                        }
                        val drawStroke = if (selectedBand == band) stroke + 2.dp.toPx() else stroke
                        drawArc(
                            color = drawColor,
                            startAngle = startAngle + (gap / 2f),
                            sweepAngle = (sweepAngle - gap).coerceAtLeast(0.2f),
                            useCenter = false,
                            topLeft = Offset(center.x - diameter / 2f, center.y - diameter / 2f),
                            size = Size(diameter, diameter),
                            style = Stroke(width = drawStroke, cap = StrokeCap.Butt)
                        )
                    }
                    startAngle += sweepAngle
                }
            }

            val valueStyle = ringValueStyle(minOf(maxWidth, maxHeight))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = buildAnnotatedString {
                        append(formattedPercent)
                        val number = NumberFormat.getIntegerInstance().format(inRange.roundToInt())
                        val start = formattedPercent.indexOf(number)
                        if (start >= 0) addStyle(SpanStyle(color = inRangeTone), start, start + number.length)
                    },
                    style = valueStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        // The change remains next to the headline without competing for its circular opening.
        comparison?.takeIf { abs(it.inRangeDelta) >= 1f }?.let { delta ->
            val rising = delta.inRangeDelta >= 0f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Icon(
                    imageVector = if (rising) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = if (rising) TirInRangeColor else TirHighColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = NumberFormat.getIntegerInstance().format(abs(delta.inRangeDelta).roundToInt()),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = if (rising) TirInRangeColor else TirHighColor,
                    maxLines = 1
                )
            }
        }
    }
}
