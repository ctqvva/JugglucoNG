package tk.glucodata.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.ui.util.ExpressiveMotion
import java.time.LocalDate
import java.util.Locale

// The five band colours follow the active glucose palette (including per-band
// overrides). Read on access so switching palettes shows up on re-entry, and light
// variant only, matching every other stats surface.
internal val TirVeryLowColor get() = Color(GlucoseRangeColors.veryLow(false))
internal val TirLowColor get() = Color(GlucoseRangeColors.low(false))
internal val TirInRangeColor get() = Color(GlucoseRangeColors.inRange(false))
internal val TirHighColor get() = Color(GlucoseRangeColors.high(false))
internal val TirVeryHighColor get() = Color(GlucoseRangeColors.veryHigh(false))

/**
 * Maps a time-in-range percentage onto the palette: at target it is the in-range
 * colour, and it walks through high into very-high as the day gets worse. One
 * consistent scale for the calendar grid, the day sheet and the pattern rows.
 */
internal fun tirHeatColor(inRangePercent: Float): Color {
    val percent = inRangePercent.coerceIn(0f, 100f)
    return when {
        percent >= 70f -> TirInRangeColor
        percent >= 50f -> lerp(TirHighColor, TirInRangeColor, (percent - 50f) / 20f)
        percent >= 25f -> lerp(TirVeryHighColor, TirHighColor, (percent - 25f) / 25f)
        else -> TirVeryHighColor
    }
}

/**
 * The five bands as one bar. Reads left-to-right worst-low to worst-high, which is
 * the same order as the overview ring, so the two never disagree visually.
 */
@Composable
internal fun TirStackedBar(
    tir: TimeInRangeBreakdown,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    cornerRadius: Dp = 6.dp
) {
    val segments = listOf(
        tir.veryLowPercent to TirVeryLowColor,
        tir.lowPercent to TirLowColor,
        tir.inRangePercent to TirInRangeColor,
        tir.highPercent to TirHighColor,
        tir.veryHighPercent to TirVeryHighColor
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(trackColor)
    ) {
        val total = segments.sumOf { it.first.toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        var x = 0f
        segments.forEach { (percent, color) ->
            val width = size.width * (percent / total)
            if (width > 0.4f) {
                drawRect(color = color, topLeft = Offset(x, 0f), size = Size(width, size.height))
            }
            x += width
        }
    }
}

/**
 * The five bands stacked vertically, worst-high at the top down to worst-low at the
 * bottom — the orientation glucose reads in everywhere else in the app.
 *
 * Meant for the empty space beside a number rather than underneath it: it costs width
 * that was going spare and no height at all.
 */
@Composable
internal fun TirVerticalBar(
    tir: TimeInRangeBreakdown,
    modifier: Modifier = Modifier,
    width: Dp = 4.dp
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    // Animated so that changing the strip's window redistributes the bands instead of
    // redrawing them, which at this size was indistinguishable from a flicker.
    val veryHigh by animateFloatAsState(tir.veryHighPercent, ExpressiveMotion.slowSpatial(), label = "tirVeryHigh")
    val high by animateFloatAsState(tir.highPercent, ExpressiveMotion.slowSpatial(), label = "tirHigh")
    val inRange by animateFloatAsState(tir.inRangePercent, ExpressiveMotion.slowSpatial(), label = "tirInRange")
    val low by animateFloatAsState(tir.lowPercent, ExpressiveMotion.slowSpatial(), label = "tirLow")
    val veryLow by animateFloatAsState(tir.veryLowPercent, ExpressiveMotion.slowSpatial(), label = "tirVeryLow")
    val segments = listOf(
        veryHigh to TirVeryHighColor,
        high to TirHighColor,
        inRange to TirInRangeColor,
        low to TirLowColor,
        veryLow to TirVeryLowColor
    )
    Canvas(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(width / 2))
            .background(trackColor)
    ) {
        val total = segments.sumOf { it.first.toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        var y = 0f
        segments.forEachIndexed { index, (percent, color) ->
            val height = if (index == segments.lastIndex) {
                size.height - y
            } else {
                size.height * (percent / total)
            }
            if (height > 0.4f) {
                drawRect(color = color, topLeft = Offset(0f, y), size = Size(size.width, height))
            }
            y += height
        }
    }
}

/**
 * The GRI zone strip: five equal blocks A–E with a marker where this window landed.
 * Shows the score in context instead of asking anyone to remember what 34 means.
 */
@Composable
internal fun GriZoneTrack(
    value: Float,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp
) {
    val animated by animateFloatAsState(
        targetValue = value.coerceIn(0f, 100f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "griMarker"
    )
    val zoneColors = listOf(
        TirInRangeColor,
        lerp(TirInRangeColor, TirHighColor, 0.55f),
        TirHighColor,
        lerp(TirHighColor, TirVeryHighColor, 0.6f),
        TirVeryHighColor
    )
    val markerColor = MaterialTheme.colorScheme.onSurface
    val markerRing = MaterialTheme.colorScheme.surfaceContainerLow
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val gap = 3.dp.toPx()
        val trackHeight = 10.dp.toPx()
        val trackTop = (size.height - trackHeight) / 2f
        val blockWidth = (size.width - gap * 4f) / 5f
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        zoneColors.forEachIndexed { index, color ->
            drawRoundRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(index * (blockWidth + gap), trackTop),
                size = Size(blockWidth, trackHeight),
                cornerRadius = radius
            )
        }
        val markerX = (animated / 100f) * size.width
        drawCircle(
            color = markerRing,
            radius = 9.dp.toPx(),
            center = Offset(markerX.coerceIn(9.dp.toPx(), size.width - 9.dp.toPx()), size.height / 2f)
        )
        drawCircle(
            color = markerColor,
            radius = 5.dp.toPx(),
            center = Offset(markerX.coerceIn(9.dp.toPx(), size.width - 9.dp.toPx()), size.height / 2f)
        )
    }
}

/**
 * Splits one number into its low-driven and high-driven halves. Used for the GRI
 * components, where the split is the actionable part — the same score can be all
 * lows or all highs and those need opposite responses.
 */
@Composable
internal fun ComponentSplitBar(
    lowShare: Float,
    highShare: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(trackColor)
    ) {
        // Drawn against the full 0–100 scale, not normalised against each other:
        // normalising made an index of 4 look identical to an index of 90 whenever one
        // side was zero, so a good window rendered as a solid bar of alarm.
        val lowWidth = size.width * (lowShare.coerceIn(0f, 100f) / 100f)
        val highWidth = size.width * (highShare.coerceIn(0f, 100f) / 100f)
        if (lowWidth > 0.5f) {
            drawRect(color = TirVeryLowColor, size = Size(lowWidth, size.height))
        }
        if (highWidth > 0.5f) {
            drawRect(
                color = TirVeryHighColor,
                topLeft = Offset(lowWidth, 0f),
                size = Size(highWidth.coerceAtMost((size.width - lowWidth).coerceAtLeast(0f)), size.height)
            )
        }
    }
}

/**
 * Twenty-four stacked columns, one per hour of the clock, each showing that hour's
 * five-band split pooled over the window.
 *
 * Reading percentiles off an AGP takes practice; this answers "when does the day go
 * wrong" at a glance, and scrubbing across it reads out the hour underneath. Adapted
 * from the parallel stats redesign on `feature/stats-redesign-sol`.
 */
@Composable
internal fun HourlyExposureRibbon(
    hourlyStats: List<HourlyGlucoseStats>,
    selectedHour: Int,
    onSelectedHourChange: (Int) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = 68.dp
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val selectorColor = MaterialTheme.colorScheme.onSurface
    val byHour = remember(hourlyStats) {
        Array(24) { hour -> hourlyStats.firstOrNull { it.hour == hour } ?: HourlyGlucoseStats(hour) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(16.dp))
                .background(trackColor)
                .semantics { this.contentDescription = contentDescription }
                .pointerInput(hourlyStats) {
                    detectTapGestures { offset ->
                        onSelectedHourChange(((offset.x / size.width) * 24f).toInt().coerceIn(0, 23))
                    }
                }
                .pointerInput(hourlyStats) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            onSelectedHourChange(((offset.x / size.width) * 24f).toInt().coerceIn(0, 23))
                        },
                        onHorizontalDrag = { change, _ ->
                            onSelectedHourChange(
                                ((change.position.x / size.width) * 24f).toInt().coerceIn(0, 23)
                            )
                            change.consume()
                        }
                    )
                }
        ) {
            val cellWidth = size.width / 24f
            val gap = 1.dp.toPx()
            // Worst-high on top down to worst-low at the bottom, the way glucose reads
            // on every other chart in the app.
            val bands = listOf(
                TirVeryHighColor,
                TirHighColor,
                TirInRangeColor,
                TirLowColor,
                TirVeryLowColor
            )
            byHour.forEachIndexed { hour, stats ->
                if (stats.sampleCount == 0) return@forEachIndexed
                val left = hour * cellWidth
                val width = (cellWidth - gap).coerceAtLeast(1f)
                val percentages = listOf(
                    stats.tir.veryHighPercent,
                    stats.tir.highPercent,
                    stats.tir.inRangePercent,
                    stats.tir.lowPercent,
                    stats.tir.veryLowPercent
                )
                var top = 0f
                percentages.forEachIndexed { index, percent ->
                    val segment = if (index == percentages.lastIndex) {
                        size.height - top
                    } else {
                        size.height * (percent.coerceIn(0f, 100f) / 100f)
                    }
                    if (segment > 0f) {
                        drawRect(
                            color = bands[index],
                            topLeft = Offset(left, top),
                            size = Size(width, segment)
                        )
                    }
                    top += segment
                }
            }

            val safeHour = selectedHour.coerceIn(0, 23)
            drawRoundRect(
                color = selectorColor,
                topLeft = Offset(safeHour * cellWidth + 1.dp.toPx(), 1.dp.toPx()),
                size = Size(
                    width = (cellWidth - gap - 2.dp.toPx()).coerceAtLeast(1f),
                    height = (size.height - 2.dp.toPx()).coerceAtLeast(1f)
                ),
                cornerRadius = CornerRadius(7.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(0, 6, 12, 18, 23).forEachIndexed { index, hour ->
                Text(
                    text = String.format(Locale.getDefault(), "%02d", hour),
                    modifier = Modifier.weight(if (index == 0 || index == 4) 0.5f else 1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = when (index) {
                        0 -> TextAlign.Start
                        4 -> TextAlign.End
                        else -> TextAlign.Center
                    }
                )
            }
        }
    }
}

/**
 * Day-by-day grid. Weeks run down, weekdays across, so the eye picks up "every
 * Saturday" or "the week we travelled" without any extra chart.
 */
@Composable
internal fun CalendarHeatGrid(
    days: List<DayBreakdown>,
    modifier: Modifier = Modifier,
    selectedDate: LocalDate? = null,
    onDayClick: (DayBreakdown) -> Unit
) {
    if (days.isEmpty()) return
    val byDate = remember(days) { days.associateBy { it.date } }
    val firstDate = days.first().date
    val lastDate = days.last().date
    // Pad to whole weeks so columns stay aligned to weekdays.
    val gridStart = firstDate.minusDays((firstDate.dayOfWeek.value - 1).toLong())
    val gridEnd = lastDate.plusDays((7 - lastDate.dayOfWeek.value).toLong())
    val totalDays = (java.time.temporal.ChronoUnit.DAYS.between(gridStart, gridEnd) + 1).toInt()
    val weeks = (totalDays + 6) / 7

    val emptyCell = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
    val outsideCell = Color.Transparent
    val selectedRing = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(weeks) { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(7) { weekday ->
                    val date = gridStart.plusDays((week * 7 + weekday).toLong())
                    val day = byDate[date]
                    val inWindow = !date.isBefore(firstDate) && !date.isAfter(lastDate)
                    val color = when {
                        day != null -> tirHeatColor(day.tir.inRangePercent)
                        inWindow -> emptyCell
                        else -> outsideCell
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color)
                            .then(
                                if (date == selectedDate) {
                                    Modifier.border(2.dp, selectedRing, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (day != null) Modifier.clickable { onDayClick(day) } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // The day of the month, so a square can be found by date rather
                        // than by counting along a row. Drawn in the ink the square's own
                        // fill was picked to carry, so it stays legible from the palest
                        // out-of-range orange to the deepest in-range green.
                        if (inWindow) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFeatureSettings = "tnum"
                                ),
                                color = if (day != null) {
                                    Color.Black.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                },
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One day's curve against the target band. Deliberately axis-free — it lives in a
 * drill-down where the numbers are printed right above it.
 */
@Composable
internal fun DayCurveChart(
    points: List<Pair<Long, Float>>,
    targets: StatsTargets,
    windowStartMillis: Long,
    windowEndMillis: Long,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val bandColor = TirInRangeColor.copy(alpha = 0.14f)
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    val minValue = minOf(points.minOf { it.second }, targets.lowMgDl) - 10f
    val maxValue = maxOf(points.maxOf { it.second }, targets.highMgDl) + 10f
    // Anchored to the full window, so a day that only has readings from 08:00 does not
    // stretch to look like a full day of coverage.
    val startMillis = windowStartMillis
    val span = (windowEndMillis - windowStartMillis).coerceAtLeast(1L)

    Canvas(modifier = modifier.fillMaxWidth()) {
        fun y(value: Float): Float {
            val ratio = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
            return size.height - ratio * size.height
        }

        fun x(timestamp: Long): Float =
            ((timestamp - startMillis).toFloat() / span.toFloat()) * size.width

        drawRect(
            color = bandColor,
            topLeft = Offset(0f, y(targets.highMgDl)),
            size = Size(size.width, (y(targets.lowMgDl) - y(targets.highMgDl)).coerceAtLeast(1f))
        )
        listOf(targets.lowMgDl, targets.highMgDl).forEach { level ->
            drawLine(
                color = gridColor,
                start = Offset(0f, y(level)),
                end = Offset(size.width, y(level)),
                strokeWidth = 1f
            )
        }

        // Break the path where the sensor stopped reporting for over 25 minutes.
        val gapMillis = 25L * 60_000L
        val path = Path()
        var penDown = false
        points.forEachIndexed { index, (timestamp, value) ->
            val gap = index > 0 && timestamp - points[index - 1].first > gapMillis
            if (!penDown || gap) {
                path.moveTo(x(timestamp), y(value))
                penDown = true
            } else {
                path.lineTo(x(timestamp), y(value))
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Label · stacked bar · value. Shared by the time-of-day and weekday pattern views so
 * the two read as the same chart cut two ways.
 */
@Composable
internal fun TirDistributionRow(
    label: String,
    valueLabel: String,
    tir: TimeInRangeBreakdown,
    hasData: Boolean,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 74.dp,
    emphasised: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasised) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.width(labelWidth),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(modifier = Modifier.weight(1f)) {
            if (hasData) {
                TirStackedBar(tir = tir, height = 14.dp, cornerRadius = 7.dp)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                )
            }
        }
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.labelLargeEmphasized.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(58.dp),
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}

/** Small colour key used under the calendar grid. */
@Composable
internal fun HeatScaleLegend(
    lowLabel: String,
    highLabel: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = lowLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(20f, 45f, 60f, 72f, 90f).forEach { percent ->
                Box(
                    modifier = Modifier
                        .size(width = 16.dp, height = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(tirHeatColor(percent))
                )
            }
        }
        Text(
            text = highLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Tiny filled dot + text, for compact legends inside the new cards. */
@Composable
internal fun StatsChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = color
        )
    }
}

/** Placeholder used while a section has nothing to draw yet. */
@Composable
internal fun SectionEmptyLine(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
