package tk.glucodata.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.components.AdaptiveTrailingRow
import java.text.NumberFormat
import java.util.Locale
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
    val unitLabel = stringResource(if (unit == GlucoseUnit.MMOL) R.string.unit_mmol else R.string.unit_mg)

    Card(modifier.fillMaxWidth(), shape = StatsCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                active?.let { "$title · ${stringResource(it.part.labelResId)}" } ?: title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMediumEmphasized
            )
            AdaptiveTrailingRow(
                modifier = Modifier.fillMaxWidth(),
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        val minutes = (tir.inRangePercent / 100f * hours * 60).roundToInt()
                        Text("≈ ${durationText(minutes)} / ${durationText(hours * 60)}",
                            style = MaterialTheme.typography.labelLargeEmphasized.copy(fontFeatureSettings = "tnum"))
                        Text("$low–$high $unitLabel", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            ) {
                Text(percent.format(tir.inRangePercent / 100.0),
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
            }

            Spacer(Modifier.height(8.dp))
            TirStackedBar(tir, height = 16.dp, cornerRadius = 8.dp)
            Spacer(Modifier.height(12.dp))
            val valueStyle = MaterialTheme.typography.labelLargeEmphasized.copy(fontFeatureSettings = "tnum")
            val measurer = rememberTextMeasurer()
            val percentWidth = with(LocalDensity.current) {
                measurer.measure(percent.format(1.0), valueStyle).size.width.toDp()
            }
            @Composable
            fun Band(label: String, range: String, share: Float, color: Color) {
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(range, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(percent.format(share / 100.0), Modifier.width(percentWidth),
                        textAlign = TextAlign.End, style = valueStyle)
                }
            }
            Band(stringResource(R.string.very_low), "< $veryLow", tir.veryLowPercent, TirVeryLowColor)
            Band(stringResource(R.string.low_range), "$veryLow–$low", tir.lowPercent, TirLowColor)
            Band(stringResource(R.string.high_range), "$high–$veryHigh", tir.highPercent, TirHighColor)
            Band(stringResource(R.string.very_high), "≥ $veryHigh", tir.veryHighPercent, TirVeryHighColor)

            if (parts.any { it.readingCount > 0 }) {
                Spacer(Modifier.height(12.dp))
                DayPartSelector(parts, summary.tir, active?.part, locale, percent) { selectedPart = it }
            }
            if (active == null) {
                summary.comparison?.takeIf { kotlin.math.abs(it.inRangeDelta) >= 1f }?.let { comparison ->
                    val delta = String.format(locale, "%+.1f", comparison.inRangeDelta)
                    Text(stringResource(R.string.stats_tir_change, delta), Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Each key is a small multiple of the larger distribution above it, over its own local hours. */
@Composable
private fun DayPartSelector(
    parts: List<DayPartStats>,
    overall: TimeInRangeBreakdown,
    selected: DayPart?,
    locale: Locale,
    percent: NumberFormat,
    onSelect: (DayPart?) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val choices = listOf(null) + DayPart.entries
        val labels = choices.map { part ->
            if (part == null) durationText(24 * 60)
            else String.format(locale, "%02d–%02d", part.startHour, part.startHour + 6)
        }
        val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
        val measurer = rememberTextMeasurer()
        val labelWidth = with(LocalDensity.current) {
            labels.maxOf { measurer.measure(it, style, softWrap = false).size.width }.toDp()
        }
        val minimumWidth = maxOf(48.dp, labelWidth + 8.dp)
        // Wrap the keys as a group on narrow windows so all five keep real 48dp targets.
        val capacity = ((maxWidth + 4.dp) / (minimumWidth + 4.dp)).toInt().coerceIn(1, 5)
        val columns = if (capacity == 4) 3 else capacity
        val keyWidth = (maxWidth - 4.dp * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { index, part ->
                        val split = if (part == null) overall else parts.firstOrNull {
                            it.part == part && it.readingCount > 0
                        }?.tir
                        val label = labels[choices.indexOf(part)]
                        val name = part?.let { "${stringResource(it.labelResId)} $label" } ?: label
                        val detail = split?.let { "${stringResource(R.string.in_range)} ${percent.format(it.inRangePercent / 100.0)}" }
                            ?: stringResource(R.string.stats_no_readings_in_range)
                        ToggleButton(
                            checked = selected == part,
                            onCheckedChange = { onSelect(part) },
                            enabled = split != null,
                            modifier = Modifier.width(keyWidth).heightIn(min = 48.dp)
                                .semantics { contentDescription = "$name · $detail" },
                            shapes = when {
                                row.size == 1 -> ToggleButtonDefaults.shapesFor(48.dp)
                                index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                index == row.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = ToggleButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            elevation = null,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(label, textAlign = TextAlign.Center, style = style)
                                if (split != null) {
                                    TirStackedBar(split, Modifier.padding(horizontal = 4.dp), height = 4.dp, cornerRadius = 4.dp)
                                } else {
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
