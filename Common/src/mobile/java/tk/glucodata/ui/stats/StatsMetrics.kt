package tk.glucodata.ui.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tk.glucodata.R
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.util.AdaptiveLayoutDensity
import tk.glucodata.ui.util.AdaptiveWindowWidthClass
import tk.glucodata.ui.util.ExpressiveMotion
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics
import java.util.Locale

/**
 * One metric, formatted for display. The same spec drives the statistics grid and the
 * pinned dashboard chips, so a number can only be formatted one way.
 */
internal data class MetricSpec(
    val metric: StatsMetric,
    val title: String,
    val value: String,
    val status: String,
    val meta: String,
    val tone: Color,
    val infoText: String? = null
)

@Composable
internal fun ScoreTile(
    title: String,
    value: String,
    status: String,
    meta: String,
    tone: Color,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    infoText: String? = null,
    forceStatusOwnRow: Boolean? = null,
    forceTitleOwnRow: Boolean? = null,
    // Keep the line even when this tile has nothing for it, so a tile that does have
    // one does not end up taller than its neighbour.
    reserveStatus: Boolean = false,
    reserveMeta: Boolean = false
) {
    val expandable = !infoText.isNullOrBlank()
    val hasStatus = status.isNotBlank() || reserveStatus
    val hasMeta = meta.isNotBlank() || reserveMeta
    val tileShape = RoundedCornerShape(topStart = 20.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 20.dp)
    val tileColor = tone.copy(alpha = 0.09f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
    val titleStyle = MaterialTheme.typography.titleMedium.copy(lineHeight = 22.sp)
    val statusStyle = MaterialTheme.typography.titleSmall.copy(lineHeight = 20.sp)
    val valueStyle = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")
    Box(
        modifier = modifier
            .animateContentSize()
            .graphicsLayer {
                shape = tileShape
                clip = true
            }
            .background(
                color = tileColor,
                shape = tileShape
            )
            .then(
                if (expandable) Modifier.clickable(onClick = onToggleExpanded) else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (hasMeta) 6.dp else 4.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()
                val titleGapPx = with(density) { 12.dp.roundToPx() }
                val sharedWidthPx = with(density) { maxWidth.roundToPx() }
                val autoStatusNeedsOwnRow = remember(
                    forceStatusOwnRow,
                    value,
                    status,
                    textMeasurer,
                    density,
                    valueStyle,
                    statusStyle,
                    hasStatus,
                    sharedWidthPx
                ) {
                    if (forceStatusOwnRow != null || !hasStatus) {
                        false
                    } else {
                        val valueWidthPx = textMeasurer.measure(
                            text = AnnotatedString(value),
                            style = valueStyle,
                            maxLines = 1
                        ).size.width
                        val statusWidthPx = textMeasurer.measure(
                            text = AnnotatedString(status),
                            style = statusStyle,
                            maxLines = 1
                        ).size.width
                        statusWidthPx > (sharedWidthPx - valueWidthPx - titleGapPx).coerceAtLeast(0)
                    }
                }
                val statusNeedsOwnRow = forceStatusOwnRow ?: autoStatusNeedsOwnRow
                val autoTitleNeedsOwnRow = remember(
                    forceTitleOwnRow, title, value, expandable, textMeasurer, density,
                    valueStyle, titleStyle, sharedWidthPx
                ) {
                    if (forceTitleOwnRow != null) {
                        false
                    } else {
                        titleOverflows(
                            title = title,
                            value = value,
                            expandable = expandable,
                            widthPx = sharedWidthPx,
                            density = density,
                            textMeasurer = textMeasurer,
                            titleStyle = titleStyle,
                            valueStyle = valueStyle
                        )
                    }
                }
                val titleNeedsOwnRow = forceTitleOwnRow ?: autoTitleNeedsOwnRow

                if (titleNeedsOwnRow) {
                    // Nothing left to shrink: the label alone needs the width. Long
                    // translations and small screens both land here, and truncating the
                    // label to keep the number on the same line loses the more important
                    // half — a number nobody can name is no use.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TileTitle(
                            title = title,
                            expandable = expandable,
                            titleStyle = titleStyle,
                            maxLines = 2
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = status,
                                style = statusStyle,
                                color = tone,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = value,
                                style = valueStyle,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.End
                            )
                        }
                    }
                } else

                if (statusNeedsOwnRow) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            TileTitle(
                                title = title,
                                expandable = expandable,
                                titleStyle = titleStyle,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = value,
                                style = valueStyle,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 12.dp),
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.End
                            )
                        }
                        Text(
                            text = status,
                            style = statusStyle,
                            color = tone,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(if (hasStatus) 4.dp else 0.dp)
                        ) {
                            TileTitle(
                                title = title,
                                expandable = expandable,
                                titleStyle = titleStyle
                            )
                            if (hasStatus) {
                                Text(
                                    text = status,
                                    style = statusStyle,
                                    color = tone,
                                    modifier = Modifier.padding(top = 2.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Text(
                            text = value,
                            style = valueStyle,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 12.dp),
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
            if (hasMeta) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFeatureSettings = "tnum",
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(
                visible = expandable && expanded,
                enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(animationSpec = tween(180))
            ) {
                Text(
                    text = infoText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * The tile's label, with its info affordance.
 *
 * The label is the weighted child so that the icon is measured first and always gets its
 * 14 dp. Unweighted, the label took the whole row and the icon was laid out at zero width —
 * which is why a metric with a long name appeared to have no way to ask what it means.
 */
@Composable
private fun TileTitle(
    title: String,
    expandable: Boolean,
    titleStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = titleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
        if (expandable) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** True when the label and the value cannot share a line. */
private fun titleOverflows(
    title: String,
    value: String,
    expandable: Boolean,
    widthPx: Int,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    titleStyle: androidx.compose.ui.text.TextStyle,
    valueStyle: androidx.compose.ui.text.TextStyle
): Boolean {
    val gapPx = with(density) { 12.dp.roundToPx() }
    val iconPx = if (expandable) with(density) { 18.dp.roundToPx() } else 0
    val titlePx = textMeasurer.measure(
        text = AnnotatedString(title),
        style = titleStyle,
        maxLines = 1
    ).size.width
    val valuePx = textMeasurer.measure(
        text = AnnotatedString(value),
        style = valueStyle,
        maxLines = 1
    ).size.width
    return titlePx + iconPx > (widthPx - valuePx - gapPx).coerceAtLeast(0)
}

/**
 * Same question as [rememberScoreTileNeedsOwnRow], asked about the label, so both tiles in
 * a row drop the value onto its own line together rather than one of them looking taller.
 */
@Composable
internal fun rememberScoreTileTitleNeedsOwnRow(
    contentWidth: Dp,
    title: String,
    value: String,
    expandable: Boolean
): Boolean {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.titleMedium.copy(lineHeight = 22.sp)
    val valueStyle = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")
    return remember(contentWidth, title, value, expandable, density, textMeasurer, titleStyle, valueStyle) {
        titleOverflows(
            title = title,
            value = value,
            expandable = expandable,
            widthPx = with(density) { maxOf(contentWidth, 0.dp).roundToPx() },
            density = density,
            textMeasurer = textMeasurer,
            titleStyle = titleStyle,
            valueStyle = valueStyle
        )
    }
}

/**
 * Measures whether a tile's status still fits beside its value, so both tiles in a row
 * can agree on one layout. Kept from the original tile; only its visibility changed.
 */
@Composable
internal fun rememberScoreTileNeedsOwnRow(
    contentWidth: Dp,
    value: String,
    status: String
): Boolean {
    if (status.isBlank()) return false
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val statusStyle = MaterialTheme.typography.titleSmall.copy(lineHeight = 20.sp)
    val valueStyle = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")
    return remember(contentWidth, value, status, density, textMeasurer, statusStyle, valueStyle) {
        val widthPx = with(density) { maxOf(contentWidth, 0.dp).roundToPx() }
        val titleGapPx = with(density) { 12.dp.roundToPx() }
        val valueWidthPx = textMeasurer.measure(
            text = AnnotatedString(value),
            style = valueStyle,
            maxLines = 1
        ).size.width
        val statusWidthPx = textMeasurer.measure(
            text = AnnotatedString(status),
            style = statusStyle,
            maxLines = 1
        ).size.width
        statusWidthPx > (widthPx - valueWidthPx - titleGapPx).coerceAtLeast(0)
    }
}

/**
 * Formats one metric from the current summary.
 *
 * Every glucose number goes through [formatMgDl], so a metric can never print mg/dL to
 * someone reading mmol/L — including the bounds quoted in the explanations, which come
 * from the user's own targets rather than fixed constants.
 */
@Composable
internal fun metricSpec(
    metric: StatsMetric,
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit
): MetricSpec {
    val title = stringResource(metric.titleResId)
    val targetRange = "${formatMgDl(targets.lowMgDl, unit)}-${formatMgDl(targets.highMgDl, unit)}"

    val lowWord = stringResource(R.string.low_range)
    val highWord = stringResource(R.string.high_range)
    val inRangeWord = stringResource(R.string.in_range)
    val steadyWord = stringResource(R.string.gvi_good)
    val middlingWord = stringResource(R.string.gvi_moderate)
    val swingyWord = stringResource(R.string.gvi_poor)
    val typicalWord = stringResource(R.string.typical)
    val noneWord = stringResource(R.string.stats_metric_none)
    val rangeWord = stringResource(R.string.range)
    val targetWord = stringResource(R.string.gmi_target)
    val targetValue = stringResource(R.string.gmi_target_value)
    val tirWord = stringResource(R.string.tir)
    val stabilityWord = stringResource(R.string.stability)
    val trendWord = stringResource(R.string.stats_trend)

    fun bandTone(valueMgDl: Float): Color = when {
        valueMgDl < targets.lowMgDl || valueMgDl > targets.highMgDl -> TirVeryHighColor
        valueMgDl <= targets.lowMgDl + 8f || valueMgDl >= targets.highMgDl - 8f -> TirHighColor
        else -> TirInRangeColor
    }

    fun bandStatus(valueMgDl: Float): String = when {
        valueMgDl < targets.lowMgDl -> lowWord
        valueMgDl > targets.highMgDl -> highWord
        else -> inRangeWord
    }

    fun spec(
        value: String,
        status: String,
        meta: String = "",
        tone: Color,
        infoText: String? = null
    ) = MetricSpec(metric, title, value, status, meta, tone, infoText)

    return when (metric) {
        StatsMetric.TIME_IN_RANGE -> spec(
            value = String.format(Locale.getDefault(), "%.0f%%", summary.tir.inRangePercent),
            status = if (summary.tir.inRangePercent >= 70f) steadyWord else middlingWord,
            meta = "$rangeWord $targetRange",
            tone = tirHeatColor(summary.tir.inRangePercent)
        )

        StatsMetric.AVERAGE -> spec(
            value = formatMgDl(summary.avgMgDl, unit),
            status = bandStatus(summary.avgMgDl),
            meta = "$rangeWord $targetRange",
            tone = bandTone(summary.avgMgDl)
        )

        StatsMetric.GMI -> {
            // Status word and tone both read [GmiBand], so the tile cannot call a
            // number "target" and colour it like the worst one at the same time.
            val band = GmiBand.of(summary.gmiPercent)
            spec(
                value = String.format(Locale.getDefault(), "%.1f%%", summary.gmiPercent),
                status = if (band == GmiBand.AT_TARGET) targetWord else highWord,
                meta = "$targetWord $targetValue",
                tone = when (band) {
                    GmiBand.AT_TARGET -> TirInRangeColor
                    GmiBand.ABOVE_TARGET -> TirHighColor
                    GmiBand.WELL_ABOVE_TARGET -> TirVeryHighColor
                }
            )
        }

        StatsMetric.CV -> spec(
            value = String.format(Locale.getDefault(), "%.1f%%", summary.cvPercent),
            status = when {
                summary.cvPercent < 32f -> steadyWord
                summary.cvPercent < 40f -> middlingWord
                else -> swingyWord
            },
            // The consensus threshold, the same way A1c states its own. The tile reserved
            // this line for its neighbour's benefit and then left it blank, which is a
            // hole where a number every CV reading is judged against could sit.
            meta = "$targetWord ${String.format(Locale.getDefault(), "<%.0f%%", 36f)}",
            tone = when {
                summary.cvPercent < 32f -> TirInRangeColor
                summary.cvPercent < 40f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.cv_description)
        )

        StatsMetric.TIGHT_RANGE -> {
            val (low, high) = StatsAnalytics.tightRangeBounds(targets)
            val bounds = "${formatMgDl(low, unit)}-${formatMgDl(high, unit)}"
            spec(
                value = String.format(Locale.getDefault(), "%.0f%%", summary.tightRangePercent),
                status = if (summary.tightRangePercent >= 50f) steadyWord else middlingWord,
                meta = bounds,
                tone = when {
                    summary.tightRangePercent >= 50f -> TirInRangeColor
                    summary.tightRangePercent >= 30f -> TirHighColor
                    else -> TirVeryHighColor
                },
                infoText = stringResource(R.string.stats_tight_range_description, bounds)
            )
        }

        StatsMetric.MEDIAN -> spec(
            value = formatMgDl(summary.medianMgDl, unit),
            status = bandStatus(summary.medianMgDl),
            meta = "$typicalWord · ${String.format(Locale.getDefault(), "%.0f%% %s", summary.tir.inRangePercent, tirWord)}",
            tone = bandTone(summary.medianMgDl)
        )

        StatsMetric.IQR -> spec(
            value = formatMgDl((summary.p75MgDl - summary.p25MgDl).coerceAtLeast(0f), unit),
            status = typicalWord,
            meta = "${formatMgDl(summary.p25MgDl, unit)}-${formatMgDl(summary.p75MgDl, unit)}",
            tone = when {
                summary.cvPercent < 32f -> TirInRangeColor
                summary.cvPercent < 40f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.iqr_description)
        )

        StatsMetric.STD_DEV -> spec(
            value = formatMgDl(summary.stdDevMgDl, unit),
            // Judged as a share of the mean rather than against fixed mg/dL, because that
            // is what a spread means. On the old absolutes a run averaging 5.1 mmol was
            // called moderate at the exact same reading CV called steady — two tiles side
            // by side, same underlying number, opposite verdicts.
            status = when {
                summary.cvPercent < 32f -> steadyWord
                summary.cvPercent < 40f -> middlingWord
                else -> swingyWord
            },
            // One deviation either side of the mean, stated the way IQR states its own
            // bounds. A spread is only meaningful against the level it is a spread around,
            // and the tile was reserving this line and leaving it empty.
            meta = "${formatMgDl((summary.avgMgDl - summary.stdDevMgDl).coerceAtLeast(0f), unit)}-" +
                formatMgDl(summary.avgMgDl + summary.stdDevMgDl, unit),
            tone = when {
                summary.cvPercent < 32f -> TirInRangeColor
                summary.cvPercent < 40f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.std_dev_description)
        )

        StatsMetric.LOW_EPISODES -> spec(
            value = summary.lowEpisodes.count.toString(),
            status = if (summary.lowEpisodes.count == 0) noneWord else lowWord,
            meta = episodeMeta(summary.lowEpisodes),
            tone = if (summary.lowEpisodes.count == 0) TirInRangeColor else TirVeryLowColor,
            infoText = stringResource(R.string.episodes_subtitle)
        )

        StatsMetric.HIGH_EPISODES -> spec(
            value = summary.highEpisodes.count.toString(),
            status = if (summary.highEpisodes.count == 0) noneWord else highWord,
            meta = episodeMeta(summary.highEpisodes),
            tone = if (summary.highEpisodes.count == 0) TirInRangeColor else TirVeryHighColor,
            infoText = stringResource(R.string.episodes_subtitle)
        )

        StatsMetric.COVERAGE -> spec(
            value = String.format(Locale.getDefault(), "%.0f%%", summary.coverage.percent),
            status = if (summary.coverage.percent >= 85f) steadyWord else middlingWord,
            meta = stringResource(R.string.stats_metric_readings, summary.coverage.readingCount),
            tone = when {
                summary.coverage.percent >= 85f -> TirInRangeColor
                summary.coverage.percent >= 70f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.stats_card_coverage_description)
        )

        StatsMetric.LBGI -> spec(
            value = String.format(Locale.getDefault(), "%.1f", summary.risk.lbgi),
            status = stringResource(
                when {
                    summary.risk.lbgi < 1.1f -> R.string.risk_minimal
                    summary.risk.lbgi < 2.5f -> R.string.risk_low
                    summary.risk.lbgi < 5f -> R.string.risk_moderate
                    else -> R.string.risk_high
                }
            ),
            meta = "$targetWord <2.5",
            tone = if (summary.risk.lbgi < 2.5f) TirInRangeColor else TirVeryLowColor,
            infoText = stringResource(R.string.lbgi_description)
        )

        StatsMetric.HBGI -> spec(
            value = String.format(Locale.getDefault(), "%.1f", summary.risk.hbgi),
            status = stringResource(
                when {
                    summary.risk.hbgi < 4.5f -> R.string.risk_low
                    summary.risk.hbgi < 9f -> R.string.risk_moderate
                    else -> R.string.risk_high
                }
            ),
            meta = "$targetWord <4.5",
            tone = if (summary.risk.hbgi < 4.5f) TirInRangeColor else TirVeryHighColor,
            infoText = stringResource(R.string.hbgi_description)
        )

        StatsMetric.GRI -> spec(
            value = String.format(Locale.getDefault(), "%.0f", summary.gri.value),
            status = stringResource(summary.gri.zone.labelResId),
            meta = "${stringResource(R.string.gri_from_lows, String.format(Locale.getDefault(), "%.0f", summary.gri.hypoComponent))} · ${stringResource(R.string.gri_from_highs, String.format(Locale.getDefault(), "%.0f", summary.gri.hyperComponent))}",
            tone = when (summary.gri.zone) {
                GriZone.A, GriZone.B -> TirInRangeColor
                GriZone.C -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.gri_description)
        )

        StatsMetric.GVI -> spec(
            value = String.format(Locale.getDefault(), "%.2f", summary.gvi.value),
            status = stringResource(summary.gvi.labelResId),
            // Rate of change dropped: it never fitted on one line in any language, and
            // GVI is already a rate-of-change score.
            meta = "$stabilityWord ${String.format(Locale.getDefault(), "%.0f%%", summary.gvi.stability)}",
            tone = when {
                summary.gvi.value < 1.55f -> TirInRangeColor
                summary.gvi.value < 1.90f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.gvi_description)
        )

        StatsMetric.DAWN_RISE -> spec(
            value = if (summary.dawnRiseMgDl > 0f) {
                "+${formatMgDl(summary.dawnRiseMgDl, unit)}"
            } else {
                "—"
            },
            status = when {
                summary.dawnRiseMgDl <= 0f -> noneWord
                summary.dawnRiseMgDl < 30f -> steadyWord
                summary.dawnRiseMgDl < 55f -> middlingWord
                else -> swingyWord
            },
            meta = "00:00-09:00",
            tone = when {
                summary.dawnRiseMgDl < 30f -> TirInRangeColor
                summary.dawnRiseMgDl < 55f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.stats_metric_dawn_description)
        )

        StatsMetric.MAGE -> spec(
            value = formatMgDl(summary.mageMgDl, unit),
            status = when {
                summary.mageMgDl < 45f -> steadyWord
                summary.mageMgDl < 90f -> middlingWord
                else -> swingyWord
            },
            meta = "$targetWord <${formatMgDl(45f, unit)}",
            tone = when {
                summary.mageMgDl < 45f -> TirInRangeColor
                summary.mageMgDl < 90f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.stats_metric_mage_description)
        )

        StatsMetric.MODD -> spec(
            value = formatMgDl(summary.moddMgDl, unit),
            status = when {
                summary.moddMgDl <= 0f -> noneWord
                summary.moddMgDl < 36f -> steadyWord
                summary.moddMgDl < 60f -> middlingWord
                else -> swingyWord
            },
            meta = "$targetWord <${formatMgDl(36f, unit)}",
            tone = when {
                summary.moddMgDl < 36f -> TirInRangeColor
                summary.moddMgDl < 60f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.stats_metric_modd_description)
        )

        StatsMetric.STREAK -> spec(
            value = summary.bestStreakDays.toString(),
            status = if (summary.bestStreakDays == 0) noneWord else steadyWord,
            meta = stringResource(R.string.stats_metric_streak_meta),
            tone = if (summary.bestStreakDays >= 3) TirInRangeColor else TirHighColor,
            infoText = stringResource(R.string.stats_metric_streak_description)
        )

        StatsMetric.PSG -> spec(
            value = formatMgDl(summary.psg.baselineMgDl, unit),
            status = stringResource(summary.psg.labelResId),
            meta = "${String.format(Locale.getDefault(), "%.0f%%", summary.psg.confidence)} · $trendWord ${if (summary.psg.trend >= 0f) "+" else ""}${String.format(Locale.getDefault(), "%.0f%%", summary.psg.trend * 100f)}",
            tone = when (summary.psg.labelResId) {
                R.string.psg_stable -> TirInRangeColor
                R.string.psg_low -> TirLowColor
                R.string.psg_elevated -> TirVeryHighColor
                else -> TirHighColor
            },
            infoText = stringResource(R.string.psg_description)
        )
    }
}

@Composable
private fun episodeMeta(summary: EpisodeSummary): String {
    if (summary.count == 0) return ""
    return "${stringResource(R.string.episodes_typical)} ${durationText(summary.medianDurationMinutes)}"
}

/**
 * Packs metrics into rows.
 *
 * A metric marked wide takes a whole row; the rest pair up, and a metric left over at
 * the end widens to fill its row instead of leaving a hole beside it. That hole was
 * the gap — the tile design itself is unchanged.
 */
internal fun packMetricRows(
    metrics: List<StatsMetric>,
    wide: Set<StatsMetric>
): List<Pair<StatsMetric, StatsMetric?>> {
    val rows = ArrayList<Pair<StatsMetric, StatsMetric?>>()
    var index = 0
    while (index < metrics.size) {
        val first = metrics[index]
        val second = metrics.getOrNull(index + 1)
        if (first in wide || second == null || second in wide) {
            rows += first to null
            index += 1
        } else {
            rows += first to second
            index += 2
        }
    }
    return rows
}

@Composable
internal fun MetricsGrid(
    metrics: List<StatsMetric>,
    wideMetrics: Set<StatsMetric>,
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit,
    expanded: Set<StatsMetric>,
    onToggleExpanded: (StatsMetric) -> Unit,
    dragState: MetricDragState?,
    modifier: Modifier = Modifier
) {
    val rows = remember(metrics, wideMetrics) { packMetricRows(metrics, wideMetrics) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { (left, right) ->
            MetricRow(
                left = metricSpec(left, summary, targets, unit),
                right = right?.let { metricSpec(it, summary, targets, unit) },
                expanded = expanded,
                onToggleExpanded = onToggleExpanded,
                dragState = dragState
            )
        }
    }
}

/**
 * Two tiles share a row.
 *
 * They come out the same height without any layout trickery: both are given the same
 * structure — same status row decision, and a reserved meta line when either of them
 * has one — so a plain Row is enough. Two earlier attempts at stretching them are why
 * this is now deliberately boring: `height(IntrinsicSize.Min)` throws because the tile
 * contains a `BoxWithConstraints`, and measuring the children twice to find the tallest
 * throws because a Measurable may only be measured once.
 *
 * When one tile is open the other keeps its own height and the row ends short, which is
 * the intended behaviour — stretching it would make an unrelated tile look expanded.
 */
@Composable
private fun MetricRow(
    left: MetricSpec,
    right: MetricSpec?,
    expanded: Set<StatsMetric>,
    onToggleExpanded: (StatsMetric) -> Unit,
    dragState: MetricDragState?,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp
) {
    // A blank placeholder string does not work here: " ".isBlank() is true, so the
    // tile still dropped the line. The reservation has to be an explicit flag.
    val reserveStatus = left.status.isNotBlank() || right?.status?.isNotBlank() == true
    val reserveMeta = left.meta.isNotBlank() || right?.meta?.isNotBlank() == true
    val leftSpec = left
    val rightSpec = right

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tileContentWidth = if (rightSpec == null) {
            maxWidth - 28.dp
        } else {
            ((maxWidth - spacing) / 2f) - 28.dp
        }
        val useOwnStatusRow =
            rememberScoreTileNeedsOwnRow(tileContentWidth, leftSpec.value, leftSpec.status) ||
                (rightSpec != null &&
                    rememberScoreTileNeedsOwnRow(tileContentWidth, rightSpec.value, rightSpec.status))
        val useOwnTitleRow = rememberScoreTileTitleNeedsOwnRow(
            tileContentWidth, leftSpec.title, leftSpec.value, leftSpec.infoText != null
        ) || (
            rightSpec != null && rememberScoreTileTitleNeedsOwnRow(
                tileContentWidth, rightSpec.title, rightSpec.value, rightSpec.infoText != null
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing)
        ) {
            ScoreTile(
                title = leftSpec.title,
                value = leftSpec.value,
                status = leftSpec.status,
                meta = leftSpec.meta,
                tone = leftSpec.tone,
                expanded = leftSpec.metric in expanded,
                onToggleExpanded = { onToggleExpanded(leftSpec.metric) },
                infoText = leftSpec.infoText,
                forceStatusOwnRow = useOwnStatusRow,
                forceTitleOwnRow = useOwnTitleRow,
                reserveStatus = reserveStatus,
                reserveMeta = reserveMeta,
                modifier = Modifier
                    .weight(1f)
                    .metricDrag(leftSpec.metric, dragState)
            )
            if (rightSpec != null) {
                ScoreTile(
                    title = rightSpec.title,
                    value = rightSpec.value,
                    status = rightSpec.status,
                    meta = rightSpec.meta,
                    tone = rightSpec.tone,
                    expanded = rightSpec.metric in expanded,
                    onToggleExpanded = { onToggleExpanded(rightSpec.metric) },
                    infoText = rightSpec.infoText,
                    forceStatusOwnRow = useOwnStatusRow,
                    forceTitleOwnRow = useOwnTitleRow,
                    reserveStatus = reserveStatus,
                    reserveMeta = reserveMeta,
                    modifier = Modifier
                        .weight(1f)
                        .metricDrag(rightSpec.metric, dragState)
                )
            }
        }
    }
}

/**
 * Lift-and-drop for one tile. Nothing is applied when no drag state is supplied, so the
 * dashboard chips and any other reuse stay inert.
 */
@Composable
private fun Modifier.metricDrag(metric: StatsMetric, dragState: MetricDragState?): Modifier {
    if (dragState == null) return this
    val view = LocalView.current
    val isDragging = dragState.dragging == metric
    val lift by animateFloatAsState(if (isDragging) 1f else 0f, label = "metricLift")
    // A tile that has been hidden must stop offering itself as a drop target.
    DisposableEffect(metric, dragState) {
        onDispose { dragState.forget(metric) }
    }
    return this
        .reportMetricBounds(metric, dragState)
        .zIndex(if (isDragging) 1f else 0f)
        .graphicsLayer {
            translationX = if (isDragging) dragState.offset.x else 0f
            translationY = if (isDragging) dragState.offset.y else 0f
            scaleX = 1f + 0.03f * lift
            scaleY = 1f + 0.03f * lift
            shadowElevation = 10.dp.toPx() * lift
        }
        .draggableMetric(
            metric = metric,
            state = dragState,
            onLift = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
            onTick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        )
}

/**
 * Compact form used by the dashboard strip, where vertical space is precious.
 *
 * Time in range gets its band split in the space beside the number — the number alone
 * says 92% without saying whether the missing 8% went high or low. Beside rather than
 * underneath: underneath made the chip taller, and since the row matches heights, every
 * other chip grew a band of dead space to match it.
 */
@Composable
internal fun PinnedMetricChip(
    spec: MetricSpec,
    modifier: Modifier = Modifier,
    tir: TimeInRangeBreakdown? = null,
    onClick: (() -> Unit)? = null,
    contentScale: Float = 1f
) {
    // Changing the window swaps every number in the strip at once. Sliding them out and
    // the replacements in says "this is the same metric over a different window"; a hard
    // swap of four numbers at once just reads as a glitch.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = ExpressiveMotion.fastSpatial(),
        label = "pinnedMetricPress"
    )
    val tone by animateColorAsState(
        targetValue = spec.tone,
        animationSpec = ExpressiveMotion.defaultEffects(),
        label = "pinnedMetricTone"
    )
    // The click has to be applied after the clip, not by the caller before it: a
    // `clickable` outside the chip's own shape draws a rectangular ripple, which is why
    // pressing the chip lit up square corners around a rounded card.
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(statsCardShape(16.dp * contentScale, 10.dp * contentScale))
            .background(tone.copy(alpha = 0.11f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 10.dp * contentScale, vertical = 8.dp * contentScale),
        horizontalArrangement = Arrangement.spacedBy(8.dp * contentScale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp * contentScale)
        ) {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.labelSmall.scalePinnedStyle(contentScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AnimatedContent(
                targetState = spec.value,
                transitionSpec = { verticalValueSwap() },
                label = "pinnedMetricValue"
            ) { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.SemiBold
                    ).scalePinnedStyle(contentScale),
                    color = tone,
                    maxLines = 1
                )
            }
        }
        if (tir != null) {
            TirVerticalBar(
                tir = tir,
                modifier = Modifier.fillMaxHeight(),
                width = 4.dp * contentScale
            )
        }
    }
}

private fun TextStyle.scalePinnedStyle(scale: Float): TextStyle = copy(
    fontSize = if (fontSize.isSp) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight
)

/**
 * The strip's house transition for a value replacing another in the same slot: the old
 * one leaves upwards, the new one arrives from below, and the slot's width morphs between
 * them instead of snapping.
 *
 * Spatial and effects run on different springs on purpose — see [ExpressiveMotion].
 */
private fun AnimatedContentTransitionScope<String>.verticalValueSwap(): ContentTransform {
    val enter = slideInVertically(ExpressiveMotion.defaultSpatial()) { it / 2 } +
        fadeIn(ExpressiveMotion.defaultEffects())
    val exit = slideOutVertically(ExpressiveMotion.defaultSpatial()) { -it / 2 } +
        fadeOut(ExpressiveMotion.fastEffects())
    // Clipped to the slot, so the departing number is cut off at its own line box rather
    // than sliding up across the label above it.
    return enter togetherWith exit using SizeTransform(clip = true) { _, _ ->
        ExpressiveMotion.defaultSpatial()
    }
}

/**
 * Windows the dashboard strip can summarise, cycled by tapping the leading pill.
 *
 * The strip owns these outright rather than borrowing the statistics screen's range: it
 * used to drive [StatsViewModel.setTimeRange], which meant cycling the pill on the
 * dashboard silently changed — and persisted — whichever range the user had chosen on the
 * statistics screen.
 */
// internal, not private: the Dashboard owns the strip's selection so that its portrait and
// landscape call sites share one window. See PinnedStatsStrip's windowState parameter.
internal enum class PinnedWindow(@get:StringRes val labelResId: Int, private val days: Int) {
    TODAY(R.string.stats_window_today, 0),
    D1(R.string.range_1d, 1),
    D3(R.string.stats_window_3d, 3),
    D14(R.string.range_14d, 14),
    D30(R.string.range_30d, 30);

    /**
     * Open-ended at the top: pinning the end to "now" froze the chips between
     * recompositions, because a reading that arrived after the range was computed fell
     * outside it.
     */
    fun resolveRange(): StatsDateRange {
        val startMillis = if (this == TODAY) {
            val zone = java.time.ZoneId.systemDefault()
            java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            (System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L).coerceAtLeast(0L)
        }
        return StatsDateRange(startMillis = startMillis, endMillis = Long.MAX_VALUE)
    }
}

/** True when the user has pinned anything, so the dashboard can skip the row entirely. */
@Composable
fun hasPinnedStats(): Boolean {
    val context = LocalContext.current
    LaunchedEffect(context) { StatsLayoutStore.ensureLoaded(context) }
    val layout by StatsLayoutStore.state.collectAsState()
    return layout.dashboardMetrics.isNotEmpty()
}

/**
 * Metrics pinned from Statistics → Arrange, over a window the user can change in place.
 *
 * Reads its numbers from [StatsViewModel] — the same source, projection and calibration
 * handling as the Statistics screen. Computing them from the Dashboard's own history
 * instead was cheaper but produced a different series: that history carries no
 * calibration projection, so the strip reported 54% time in range against the
 * Statistics screen's 92% for the same window. Sharing the source is the only way the
 * two can be guaranteed to agree.
 *
 * [windowState] is hoisted because the Dashboard composes this strip from two mutually
 * exclusive call sites — the portrait column and the landscape left column, which asks for
 * two rows. Owning the state here gave each call site its own copy, so rotating swapped one
 * for the other and the user's window silently reverted to 24h. The activity is never
 * recreated on rotation (`configChanges` covers `orientation|screenSize`), so a caller-level
 * `rememberSaveable` outlives the swap and both call sites read the same one.
 */
@Composable
internal fun PinnedStatsStrip(
    modifier: Modifier = Modifier,
    rows: Int = 1,
    windowState: MutableState<PinnedWindow> = rememberSaveable { mutableStateOf(PinnedWindow.TODAY) },
) {
    val context = LocalContext.current
    LaunchedEffect(context) { StatsLayoutStore.ensureLoaded(context) }
    val layout by StatsLayoutStore.state.collectAsState()
    val pinned = layout.dashboardMetrics
    val statsViewModel: StatsViewModel = rememberStatsViewModel()
    var window by windowState
    // Tells the view model how far back to read. Without this the strip showed numbers
    // for whatever range the statistics screen was last left on, whatever the pill said.
    LaunchedEffect(window) { statsViewModel.setPinnedWindow(window) }
    val pinnedState by statsViewModel.pinnedState.collectAsState()
    if (pinned.isEmpty() || pinnedState.summary.readingCount == 0) return
    val adaptiveMetrics = rememberAdaptiveWindowMetrics()
    val view = LocalView.current

    // -1 means the picker is choosing a metric for a new slot.
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    val dragState = rememberMetricDragState(
        order = pinned,
        onReordered = StatsLayoutStore::setDashboardMetrics
    )

    val onCycleWindow: () -> Unit = {
        val entries = PinnedWindow.entries
        window = entries[(entries.indexOf(window) + 1) % entries.size]
    }

    // Cells in reading order: the window pill, then each pinned metric. Adding a
    // fourth metric belongs to the picker, so the strip itself remains all data.
    val windowLabel = stringResource(window.labelResId)
    val pinnedSpecs = pinned.map { metric ->
        metricSpec(metric, pinnedState.summary, pinnedState.targets, pinnedState.unit)
    }
    val cells = buildList<@Composable (Modifier, Float) -> Unit> {
        add { cellModifier, contentScale ->
            PinnedWindowPill(
                label = windowLabel,
                onClick = onCycleWindow,
                modifier = cellModifier,
                contentScale = contentScale
            )
        }
        pinned.forEachIndexed { index, metric ->
            val spec = pinnedSpecs[index]
            add { cellModifier, contentScale ->
                PinnedMetricChip(
                    spec = spec,
                    tir = pinnedState.summary.tir.takeIf { metric == StatsMetric.TIME_IN_RANGE },
                    modifier = cellModifier.metricDrag(metric, dragState),
                    contentScale = contentScale,
                    onClick = {
                        // A long press that became a drag must not also open the picker.
                        if (dragState.dragging == null) editingSlot = index
                    }
                )
            }
        }
    }

    if (rows <= 1) {
        BoxWithConstraints(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            fun textWidth(text: String, style: TextStyle): Dp = with(density) {
                textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = 1
                ).size.width.toDp()
            }

            val compactWindowCellWidth = (
                textWidth(windowLabel, MaterialTheme.typography.labelMedium) + 31.dp
            ).coerceAtLeast(62.dp)
            val metricCellWidth = pinnedSpecs.maxOfOrNull { spec ->
                maxOf(
                    textWidth(spec.title, MaterialTheme.typography.labelSmall),
                    textWidth(spec.value, MaterialTheme.typography.titleMedium)
                ) + 28.dp
            }?.coerceIn(96.dp, 124.dp) ?: 96.dp
            val baseGap = 8.dp
            val useEstablishedPhoneLayout = shouldUseEstablishedPinnedStatsPhoneLayout(
                widthClass = adaptiveMetrics.widthClass,
                layoutDensity = adaptiveMetrics.layoutDensity
            )

            if (useEstablishedPhoneLayout) {
                // Preserve the dashboard's established phone composition: the window pill
                // takes its natural width and the metric cards share every remaining pixel.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(baseGap)
                ) {
                    cells.forEachIndexed { index, cell ->
                        cell(
                            if (index == 0) {
                                Modifier.fillMaxHeight()
                            } else {
                                Modifier.weight(1f).fillMaxHeight()
                            },
                            1f
                        )
                    }
                }
            } else {
                // Only constrained accessibility layouts and wider dp canvases use the
                // range-picker-like content width. Scale the composition as one unit when
                // even that preferred width does not fit.
                val preferredWidth = compactWindowCellWidth +
                    metricCellWidth * pinnedSpecs.size +
                    baseGap * (cells.size - 1).coerceAtLeast(0)
                val contentScale = if (preferredWidth > 0.dp) {
                    (maxWidth / preferredWidth).coerceIn(0.64f, 1f)
                } else {
                    1f
                }
                val cellWidths = buildList {
                    add(compactWindowCellWidth)
                    repeat(pinnedSpecs.size) { add(metricCellWidth) }
                }

                Row(
                    modifier = Modifier
                        .width((preferredWidth * contentScale).coerceAtMost(maxWidth))
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(baseGap * contentScale)
                ) {
                    cells.forEachIndexed { index, cell ->
                        cell(
                            Modifier
                                .width(cellWidths[index] * contentScale)
                                .fillMaxHeight(),
                            contentScale
                        )
                    }
                }
            }
        }
    } else {
        // Landscape: two rows of two, so the strip is as tall as the left column is
        // narrow rather than squeezing four cells across it.
        val perRow = ((cells.size + rows - 1) / rows).coerceAtLeast(1)
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            cells.chunked(perRow).forEach { rowCells ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowCells.forEach { cell ->
                        cell(Modifier.weight(1f).fillMaxHeight(), 1f)
                    }
                    repeat(perRow - rowCells.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    editingSlot?.let { slot ->
        PinnedMetricPickerSheet(
            current = pinned.getOrNull(slot),
            alreadyPinned = pinned,
            summary = pinnedState.summary,
            targets = pinnedState.targets,
            unit = pinnedState.unit,
            onPick = { metric ->
                StatsLayoutStore.replaceDashboardMetric(slot, metric)
                editingSlot = null
            },
            onRemove = if (slot in pinned.indices) {
                {
                    StatsLayoutStore.replaceDashboardMetric(slot, null)
                    editingSlot = null
                }
            } else {
                null
            },
            onAdd = { editingSlot = -1 },
            canAdd = slot in pinned.indices &&
                pinned.size < StatsLayoutStore.MAX_DASHBOARD_METRICS,
            onTogglePinned = { metric ->
                val currentMetric = pinned.getOrNull(slot)
                val wasPinned = metric in pinned
                val accepted = StatsLayoutStore.setPinnedToDashboard(
                    metric = metric,
                    pinned = !wasPinned
                )
                if (!accepted) {
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                } else if (wasPinned) {
                    // Keep editing the same card when an earlier pin is removed. If the
                    // selected card itself was unpinned, naturally continue in add mode.
                    editingSlot = currentMetric
                        ?.takeIf { it != metric }
                        ?.let { StatsLayoutStore.state.value.dashboardMetrics.indexOf(it) }
                        ?.takeIf { it >= 0 }
                        ?: -1
                }
            },
            onDismiss = { editingSlot = null }
        )
    }
}

internal fun shouldUseEstablishedPinnedStatsPhoneLayout(
    widthClass: AdaptiveWindowWidthClass,
    layoutDensity: AdaptiveLayoutDensity
): Boolean = widthClass == AdaptiveWindowWidthClass.Compact &&
    layoutDensity != AdaptiveLayoutDensity.Compact

/**
 * Slot editor.
 *
 * Every metric with its live value, so the choice is made against the real number rather
 * than a name alone. Selection is carried by the row's own container — a filled,
 * asymmetrically rounded surface in the same shape language as the tiles it is choosing
 * between — rather than by a radio button bolted to the left of a plain list. Remove
 * sits at the top, next to the title, because scrolling twenty rows to unpin something
 * is the one thing this sheet should never make you do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinnedMetricPickerSheet(
    current: StatsMetric?,
    alreadyPinned: List<StatsMetric>,
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit,
    onPick: (StatsMetric) -> Unit,
    onRemove: (() -> Unit)?,
    onAdd: () -> Unit,
    canAdd: Boolean,
    onTogglePinned: (StatsMetric) -> Unit,
    onDismiss: () -> Unit
) {
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { CompactSheetDragHandle() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.stats_pinned_pick_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                onRemove?.let { remove ->
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                                .clickable(onClick = remove)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.stats_pinned_remove_short),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(40.dp)
                                .background(
                                    if (canAdd) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    }
                                )
                                .clickable(enabled = canAdd, onClick = onAdd),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.stats_pinned_add),
                                tint = if (canAdd) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatsMetric.entries.forEach { metric ->
                    val selected = metric == current
                    val pinned = metric in alreadyPinned
                    val pinEnabled = pinned ||
                        alreadyPinned.size < StatsLayoutStore.MAX_DASHBOARD_METRICS
                    MetricSheetRow(
                        spec = metricSpec(metric, summary, targets, unit),
                        selected = selected,
                        pinned = pinned,
                        pinEnabled = pinEnabled,
                        onTogglePinned = { onTogglePinned(metric) },
                        onClick = { onPick(metric) }
                    )
                }
            }
        }
    }
}

/**
 * One row of a metric sheet: name, live value, and selection carried by the row's own
 * container in the same shape language as the tiles it stands for.
 */
@Composable
private fun MetricSheetRow(
    spec: MetricSpec,
    selected: Boolean,
    pinned: Boolean,
    pinEnabled: Boolean,
    onTogglePinned: () -> Unit,
    onClick: () -> Unit
) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            spec.tone.copy(alpha = 0.20f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
        },
        label = "metricSheetRow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(statsCardShape(20.dp, 12.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = spec.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = spec.value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.SemiBold
            ),
            color = spec.tone.copy(alpha = if (selected) 1f else 0.55f),
            maxLines = 1
        )
        IconButton(
            onClick = onTogglePinned,
            enabled = pinEnabled,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = stringResource(R.string.stats_arrange_pin),
                tint = when {
                    pinned -> MaterialTheme.colorScheme.primary
                    pinEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PinnedWindowPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentScale: Float = 1f
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = ExpressiveMotion.fastSpatial(),
        label = "pinnedWindowPress"
    )
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(statsCardShape(16.dp * contentScale, 10.dp * contentScale))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 8.dp * contentScale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp * contentScale)
    ) {
        // "Today" and "3d" are very different widths, and the metric chips share out
        // whatever the pill leaves. Morphing that width is what stops a tap on the pill
        // from jolting the whole strip sideways.
        AnimatedContent(
            targetState = label,
            transitionSpec = { verticalValueSwap() },
            label = "pinnedWindowLabel"
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold
                ).scalePinnedStyle(contentScale),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false
            )
        }
        Icon(
            imageVector = Icons.Default.UnfoldMore,
            contentDescription = stringResource(R.string.stats_window_label),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp * contentScale)
        )
    }
}
