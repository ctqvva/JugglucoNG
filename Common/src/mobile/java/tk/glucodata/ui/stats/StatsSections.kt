@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.StableModalBottomSheet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Expressive card shape used across the stats screen: the large radius sits on the
 * leading-top and trailing-bottom corners so stacked cards lean the same way.
 */
internal fun statsCardShape(large: Dp, small: Dp) = RoundedCornerShape(
    topStart = large,
    topEnd = small,
    bottomStart = small,
    bottomEnd = large
)

// ------------------------------------------------------------ risk index card

/**
 * Glycemia Risk Index. One number for the window, placed on its zone scale, split
 * into the part driven by lows and the part driven by highs.
 */
@Composable
internal fun RiskIndexCard(
    gri: GlycemiaRiskIndex,
    risk: RiskIndices,
    comparison: StatsComparison?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = statsCardShape(30.dp, 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.gri_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = stringResource(gri.zone.labelResId),
                        style = MaterialTheme.typography.titleSmall,
                        color = griZoneTone(gri.zone)
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.0f", gri.value),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "/100",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
                    )
                }
            }

            GriZoneTrack(value = gri.value)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GriZone.entries.forEach { zone ->
                    Text(
                        text = zone.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (zone == gri.zone) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        fontWeight = if (zone == gri.zone) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ComponentSplitBar(lowShare = gri.hypoComponent, highShare = gri.hyperComponent)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatsChip(
                        text = stringResource(
                            R.string.gri_from_lows,
                            String.format(Locale.getDefault(), "%.0f", gri.hypoComponent)
                        ),
                        color = TirVeryLowColor
                    )
                    StatsChip(
                        text = stringResource(
                            R.string.gri_from_highs,
                            String.format(Locale.getDefault(), "%.0f", gri.hyperComponent)
                        ),
                        color = TirVeryHighColor
                    )
                }
            }

            comparison?.let { delta ->
                if (abs(delta.griDelta) >= 1f) {
                    Text(
                        text = stringResource(
                            R.string.gri_vs_previous,
                            formatSignedNumber(delta.griDelta)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (delta.griDelta <= 0f) TirInRangeColor else TirHighColor
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.gri_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    RiskIndexRow(
                        label = stringResource(R.string.lbgi),
                        value = String.format(Locale.getDefault(), "%.1f", risk.lbgi),
                        status = stringResource(lbgiStatusRes(risk.lbgi)),
                        tone = TirVeryLowColor,
                        description = stringResource(R.string.lbgi_description)
                    )
                    RiskIndexRow(
                        label = stringResource(R.string.hbgi),
                        value = String.format(Locale.getDefault(), "%.1f", risk.hbgi),
                        status = stringResource(hbgiStatusRes(risk.hbgi)),
                        tone = TirVeryHighColor,
                        description = stringResource(R.string.hbgi_description)
                    )
                }
            }
        }
    }
}

@Composable
private fun RiskIndexRow(
    label: String,
    value: String,
    status: String,
    tone: Color,
    description: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = tone
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun griZoneTone(zone: GriZone): Color = when (zone) {
    GriZone.A -> TirInRangeColor
    GriZone.B -> TirInRangeColor
    GriZone.C -> TirHighColor
    GriZone.D -> TirVeryHighColor
    GriZone.E -> TirVeryHighColor
}

private fun lbgiStatusRes(value: Float): Int = when {
    value < 1.1f -> R.string.risk_minimal
    value < 2.5f -> R.string.risk_low
    value < 5f -> R.string.risk_moderate
    else -> R.string.risk_high
}

private fun hbgiStatusRes(value: Float): Int = when {
    value < 4.5f -> R.string.risk_low
    value < 9f -> R.string.risk_moderate
    else -> R.string.risk_high
}

// --------------------------------------------------------------- episodes card

/**
 * Lows and highs as events rather than percentages. Four percent below range is a
 * different problem when it is one long night than when it is a dozen short dips.
 */
@Composable
internal fun EpisodesCard(
    lows: EpisodeSummary,
    highs: EpisodeSummary,
    episodes: List<GlucoseEpisode>,
    unit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    var showAll by remember { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = statsCardShape(30.dp, 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.episodes_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = stringResource(R.string.episodes_subtitle),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EpisodeTile(
                    title = stringResource(R.string.episodes_lows),
                    summary = lows,
                    tone = TirVeryLowColor,
                    modifier = Modifier.weight(1f)
                )
                EpisodeTile(
                    title = stringResource(R.string.episodes_highs),
                    summary = highs,
                    tone = TirVeryHighColor,
                    modifier = Modifier.weight(1f)
                )
            }

            if (episodes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showAll = !showAll }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showAll) {
                            stringResource(R.string.episodes_hide)
                        } else {
                            stringResource(R.string.episodes_show_all, episodes.size)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (showAll) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(
                    visible = showAll,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        episodes.sortedByDescending { it.startMillis }.forEach { episode ->
                            EpisodeRow(episode = episode, unit = unit)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeTile(
    title: String,
    summary: EpisodeSummary,
    tone: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(statsCardShape(20.dp, 12.dp))
            .background(tone.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = tone
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = summary.count.toString(),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold
                )
            )
            if (summary.severeCount > 0) {
                Text(
                    text = stringResource(R.string.episodes_severe_count, summary.severeCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = tone,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
        }
        if (summary.count == 0) {
            Text(
                text = stringResource(R.string.episodes_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            EpisodeStatLine(
                label = stringResource(R.string.episodes_typical),
                value = durationText(summary.medianDurationMinutes)
            )
            EpisodeStatLine(
                label = stringResource(R.string.episodes_longest),
                value = durationText(summary.longestDurationMinutes)
            )
            EpisodeStatLine(
                label = stringResource(R.string.episodes_total),
                value = durationText(summary.totalMinutes)
            )
            if (summary.busiestBlockCount >= 2) {
                Text(
                    text = stringResource(
                        R.string.episodes_peak_window,
                        formatHour(summary.busiestBlockStartHour),
                        formatHour(summary.busiestBlockStartHour + 4)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun EpisodeStatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EpisodeRow(episode: GlucoseEpisode, unit: GlucoseUnit) {
    val tone = if (episode.kind == EpisodeKind.LOW) TirVeryLowColor else TirVeryHighColor
    val zone = ZoneId.systemDefault()
    val formatter = remember { DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.getDefault()) }
    val start = Instant.ofEpochMilli(episode.startMillis).atZone(zone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 22.dp)
                .clip(CircleShape)
                .background(if (episode.severe) tone else tone.copy(alpha = 0.45f))
        )
        Text(
            text = formatter.format(start),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatMgDl(episode.extremeMgDl, unit),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = tone,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = durationText(episode.durationMinutes),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End
        )
    }
}

// -------------------------------------------------------------- day-by-day card

/** Calendar grid of the window, one square per day, shaded by time in range. */
@Composable
internal fun DayByDayCard(
    days: List<DayBreakdown>,
    selectedDate: LocalDate?,
    onDaySelected: (DayBreakdown) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = statsCardShape(30.dp, 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.stats_day_by_day),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = stringResource(R.string.stats_day_by_day_hint),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                (1..7).forEach { day ->
                    Text(
                        text = java.time.DayOfWeek.of(day)
                            .getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            CalendarHeatGrid(
                days = days,
                selectedDate = selectedDate,
                onDayClick = onDaySelected
            )

            HeatScaleLegend(
                lowLabel = stringResource(R.string.stats_heat_worse),
                highLabel = stringResource(R.string.stats_heat_better),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Drill-down for a single day: the curve, the split, and the episodes that landed
 * on that date. Opened from the calendar grid.
 */
@Composable
internal fun DayDetailSheet(
    day: DayBreakdown,
    readings: List<GlucosePoint>,
    episodes: List<GlucoseEpisode>,
    targets: StatsTargets,
    unit: GlucoseUnit,
    onDismiss: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val dayPoints = remember(day.date, readings) {
        readings
            .filter { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == day.date }
            .map { it.timestamp to it.value }
    }
    val dayEpisodes = remember(day.date, episodes) {
        episodes.filter { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == day.date }
    }
    val hourly = remember(day.date, dayPoints, targets) {
        StatsAnalytics.hourlyStats(
            history = dayPoints.map { (timestamp, value) ->
                GlucosePoint(value = value, time = "", timestamp = timestamp)
            },
            targets = targets
        )
    }
    var selectedHour by remember(day.date) { mutableStateOf(-1) }
    val titleFormatter = remember { DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()) }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = titleFormatter.format(day.date),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format(Locale.getDefault(), "%.0f%%", day.tir.inRangePercent),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = tirHeatColor(day.tir.inRangePercent)
                )
            }

            TirStackedBar(tir = day.tir, height = 14.dp, cornerRadius = 7.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DaySheetStat(
                    label = stringResource(R.string.average_glucose),
                    value = formatMgDl(day.averageMgDl, unit),
                    modifier = Modifier.weight(1f)
                )
                DaySheetStat(
                    label = stringResource(R.string.stats_day_lowest),
                    value = formatMgDl(day.minMgDl, unit),
                    modifier = Modifier.weight(1f)
                )
                DaySheetStat(
                    label = stringResource(R.string.stats_day_highest),
                    value = formatMgDl(day.maxMgDl, unit),
                    modifier = Modifier.weight(1f)
                )
            }

            DayCurveChart(
                points = dayPoints,
                targets = targets,
                windowStartMillis = day.date.atStartOfDay(zone).toInstant().toEpochMilli(),
                windowEndMillis = day.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf(0, 6, 12, 18, 24).forEach { hour ->
                    Text(
                        text = formatHour(hour),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.stats_day_coverage,
                    day.coveragePercent.roundToInt(),
                    day.readingCount
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (hourly.any { it.sampleCount > 0 }) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = stringResource(R.string.stats_hourly_exposure),
                            style = MaterialTheme.typography.titleMedium
                        )
                        // The curve says where the day went; the ribbon says how each
                        // hour was actually split between the bands.
                        hourly.getOrNull(selectedHour)?.takeIf { it.sampleCount > 0 }?.let { hour ->
                            Text(
                                text = String.format(
                                    Locale.getDefault(),
                                    "%02d:00 · %s · %.0f%% %s",
                                    hour.hour,
                                    formatMgDl(hour.averageMgDl, unit),
                                    hour.tir.inRangePercent,
                                    stringResource(R.string.tir)
                                ),
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HourlyExposureRibbon(
                        hourlyStats = hourly,
                        selectedHour = selectedHour,
                        onSelectedHourChange = { selectedHour = it },
                        contentDescription = stringResource(R.string.stats_hourly_exposure),
                        height = 56.dp
                    )
                }
            }

            if (dayEpisodes.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                Text(
                    text = stringResource(R.string.episodes_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Column(modifier = Modifier.heightIn(max = 260.dp)) {
                    dayEpisodes.forEach { episode ->
                        EpisodeRow(episode = episode, unit = unit)
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySheetStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(statsCardShape(16.dp, 10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1
        )
    }
}

// ------------------------------------------------------- pattern tab contents

/** Time-of-day cut: four six-hour blocks, each as a range split plus its average. */
@Composable
internal fun DayPartPatterns(
    dayParts: List<DayPartStats>,
    unit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    if (dayParts.isEmpty()) {
        SectionEmptyLine(text = stringResource(R.string.stats_no_daily_data), modifier = modifier)
        return
    }
    val best = dayParts.filter { it.readingCount > 0 }.maxByOrNull { it.tir.inRangePercent }
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        dayParts.forEach { part ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TirDistributionRow(
                    label = stringResource(part.part.labelResId),
                    valueLabel = if (part.readingCount > 0) formatMgDl(part.averageMgDl, unit) else "—",
                    tir = part.tir,
                    hasData = part.readingCount > 0,
                    emphasised = part == best
                )
                Text(
                    text = String.format(
                        Locale.getDefault(),
                        "%02d:00–%02d:00 · %.0f%%",
                        part.part.startHour,
                        part.part.startHour + 6,
                        part.tir.inRangePercent
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 84.dp)
                )
            }
        }
    }
}

/** Weekday cut: seven rows, same encoding as the time-of-day view. */
@Composable
internal fun WeekdayPatterns(
    weekdays: List<WeekdayStats>,
    unit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    val withData = weekdays.filter { it.readingCount > 0 }
    if (withData.isEmpty()) {
        SectionEmptyLine(text = stringResource(R.string.stats_no_daily_data), modifier = modifier)
        return
    }
    val best = withData.maxByOrNull { it.tir.inRangePercent }
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        weekdays.forEach { weekday ->
            TirDistributionRow(
                label = weekday.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                valueLabel = if (weekday.readingCount > 0) formatMgDl(weekday.averageMgDl, unit) else "—",
                tir = weekday.tir,
                hasData = weekday.readingCount > 0,
                labelWidth = 52.dp,
                emphasised = weekday == best
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

// -------------------------------------------------------------- shared format

/** Block boundaries read better as 24:00 than as a wrapped 00:00. */
internal fun formatHour(hour: Int): String =
    String.format(Locale.getDefault(), "%02d:00", hour.coerceIn(0, 24))

@Composable
internal fun durationText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.stats_duration_minutes, rest)
        rest == 0 -> stringResource(R.string.stats_duration_hours, hours)
        else -> stringResource(R.string.stats_duration_hours_minutes, hours, rest)
    }
}

internal fun formatSignedNumber(value: Float): String {
    val rounded = value.roundToInt()
    return if (rounded > 0) "+$rounded" else rounded.toString()
}
