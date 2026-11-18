@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.components.AdaptiveTrailingRow
import tk.glucodata.ui.symbols.Symbols
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
internal fun GlycemicOverviewCard(
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    var selectedPart by rememberSaveable { mutableStateOf<DayPart?>(null) }
    val parts = summary.dayParts
    val active = parts.firstOrNull { it.part == selectedPart && it.readingCount > 0 }
    val tir = active?.tir ?: summary.tir
    val locale = LocalConfiguration.current.locales[0]
    val percent = remember(locale) {
        NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }
    val title = stringResource(R.string.stats_card_overview)
    val hours = if (active == null) 24 else 6
    val low = formatMgDl(targets.lowMgDl, unit)
    val high = formatMgDl(targets.highMgDl, unit)
    val veryLow = formatMgDl(targets.veryLowMgDl, unit)
    val veryHigh = formatMgDl(targets.veryHighMgDl, unit)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = StatsCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            AdaptiveTrailingRow(
                trailingContent = {
                    if (parts.any { it.readingCount > 0 }) {
                        DayPartPicker(parts, summary.tir, active?.part, percent) { selectedPart = it }
                    }
                }
            ) {
                Column {
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMediumEmphasized)
                    val minutes = (tir.inRangePercent / 100f * hours * 60).roundToInt()
                    Text(
                        text = "≈ ${durationText(minutes)} / ${durationText(hours * 60)}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val bands = listOf(
                OverviewBand(stringResource(R.string.very_high), "≥ $veryHigh", tir.veryHighPercent, TirVeryHighColor),
                OverviewBand(stringResource(R.string.high_range), "$high–$veryHigh", tir.highPercent, TirHighColor),
                OverviewBand(stringResource(R.string.in_range), "$low–$high", tir.inRangePercent, TirInRangeColor, inRange = true),
                OverviewBand(stringResource(R.string.low_range), "$veryLow–$low", tir.lowPercent, TirLowColor),
                OverviewBand(stringResource(R.string.very_low), "< $veryLow", tir.veryLowPercent, TirVeryLowColor)
            )
            RangeProfile(bands, percent)

            if (active == null) {
                summary.comparison?.takeIf { kotlin.math.abs(it.inRangeDelta) >= 1f }?.let { comparison ->
                    val delta = String.format(locale, "%+.1f", comparison.inRangeDelta)
                    Text(
                        text = stringResource(R.string.stats_tir_change, delta),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private data class OverviewBand(
    val label: String,
    val range: String,
    val share: Float,
    val color: Color,
    val inRange: Boolean = false
)

@Composable
private fun RangeProfile(bands: List<OverviewBand>, percent: NumberFormat) {
    val scheme = MaterialTheme.colorScheme
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val headingStyle = MaterialTheme.typography.headlineMediumEmphasized.copy(fontFeatureSettings = "tnum")
    val valueStyle = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum")
    val rangeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        fun widthOf(text: String, style: androidx.compose.ui.text.TextStyle) = with(density) {
            measurer.measure(text, style, softWrap = false).size.width.toDp()
        }
        val labelsWidth = bands.maxOf { widthOf(it.label, bodyStyle) }
        val rangesWidth = bands.maxOf { widthOf(it.range, rangeStyle) }
        val valuesWidth = 4.dp + bands.maxOf { widthOf(percent.format(it.share / 100.0), if (it.inRange) headingStyle else valueStyle) }
        val inline = 36.dp + 8.dp + labelsWidth + 8.dp + rangesWidth + 8.dp + valuesWidth <= maxWidth
        val rowHeights = remember(bands.size) { mutableStateListOf<Int>().apply { repeat(bands.size) { add(0) } } }
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(start = 44.dp)) {
                bands.forEachIndexed { index, band ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (inline) 32.dp else 40.dp)
                            .onSizeChanged { rowHeights[index] = it.height }
                            .semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(band.label, style = bodyStyle)
                            if (!inline) Text(band.range, style = rangeStyle, color = scheme.onSurfaceVariant)
                        }
                        if (inline) Text(band.range, style = rangeStyle, color = scheme.onSurfaceVariant)
                        val ink = remember(band.color, scheme.surfaceContainerHigh, scheme.onSurface) {
                            readableBandColor(band.color, scheme.surfaceContainerHigh, scheme.onSurface)
                        }
                        Box(
                            modifier = if (inline) Modifier.width(valuesWidth) else Modifier,
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = percent.format(band.share / 100.0),
                                style = if (band.inRange) headingStyle else valueStyle,
                                color = ink
                            )
                        }
                    }
                }
            }
            ProfileRail(bands, rowHeights, Modifier.matchParentSize().wrapContentWidth(Alignment.Start).width(36.dp))
        }
    }
}

/** Keep the user's hue while giving the text sufficient contrast against the actual card. */
private fun readableBandColor(band: Color, surface: Color, onSurface: Color): Color {
    val background = surface.luminance() + 0.05f
    for (step in 0..10) {
        val ink = lerp(band, onSurface, step / 10f)
        val foreground = ink.luminance() + 0.05f
        if (maxOf(foreground, background) / minOf(foreground, background) >= 4.5f) return ink
    }
    return onSurface
}

@Composable
private fun ProfileRail(bands: List<OverviewBand>, rowHeights: List<Int>, modifier: Modifier) {
    Spacer(modifier.drawWithCache {
        val width = 12.dp.toPx()
        val clip = Path().apply {
            addRoundRect(RoundRect(Rect(0f, 0f, width, size.height), CornerRadius(4.dp.toPx())))
        }
        val total = bands.sumOf { it.share.toDouble() }.toFloat()
        val heights = bands.map { if (total > 0f) size.height * it.share / total else 0f }
        var top = 0f
        var rowTop = 0f
        val leaders = heights.mapIndexed { index, height ->
            val startY = top + height / 2f
            top += height
            val endY = rowTop + rowHeights[index] / 2f
            rowTop += rowHeights[index]
            Path().apply {
                moveTo(width, startY)
                cubicTo(width + 8.dp.toPx(), startY, size.width - 8.dp.toPx(), endY, size.width, endY)
            }
        }
        val stroke = Stroke(width = 1.dp.toPx())
        onDrawBehind {
            scale(if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, 1f) {
                var y = 0f
                clipPath(clip) {
                    bands.forEachIndexed { index, band ->
                        drawRect(band.color, Offset(0f, y), Size(width, heights[index]))
                        y += heights[index]
                    }
                }
                bands.forEachIndexed { index, band ->
                    if (band.share > 0f) drawPath(leaders[index], band.color, style = stroke)
                }
            }
        }
    })
}

@Composable
private fun DayPartPicker(
    parts: List<DayPartStats>,
    overall: TimeInRangeBreakdown,
    selected: DayPart?,
    percent: NumberFormat,
    onSelect: (DayPart?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val allDay = stringResource(R.string.stats_all_day)
    val selection = selected?.let { stringResource(it.labelResId) } ?: allDay
    val start = selected?.startHour ?: 0
    val end = selected?.let { it.startHour + 6 } ?: 24
    val hoursLabel = String.format(LocalConfiguration.current.locales[0], "%02d–%02d", start, end)
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = selection }
        ) {
            Text(hoursLabel)
            Spacer(Modifier.width(4.dp))
            Icon(Symbols.ExpandMoreFilled, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                val choices = listOf(null) + DayPart.entries
                choices.forEachIndexed { index, part ->
                    val split = if (part == null) overall else parts.firstOrNull {
                        it.part == part && it.readingCount > 0
                    }?.tir
                    SelectableDropdownMenuItem(
                        selected = selected == part,
                        enabled = split != null,
                        shapes = MenuDefaults.itemShape(index, choices.size),
                        text = {
                            Column {
                                Text(part?.let { stringResource(it.labelResId) } ?: allDay)
                                if (part != null) {
                                    Text(
                                        "${formatHour(part.startHour)}–${formatHour(part.startHour + 6)}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Text(split?.let { percent.format(it.inRangePercent / 100.0) } ?: "—",
                                style = MaterialTheme.typography.labelLargeEmphasized.copy(fontFeatureSettings = "tnum"))
                        },
                        onClick = { onSelect(part); expanded = false }
                    )
                }
            }
        }
    }
}
