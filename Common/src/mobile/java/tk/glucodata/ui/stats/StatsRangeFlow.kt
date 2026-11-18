package tk.glucodata.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import tk.glucodata.R
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.util.ExpressiveMotion

/*
 * The overview card: where the window's time went, band by band.
 *
 * A stacked bar on the left is the honest picture: each band's segment is as tall as its
 * share of the time, so 88% in range dwarfs a 2% low the way it should. Five rows on the
 * right make every band readable, however small: each segment flows into its own row
 * through a ribbon in the band's colour, so a 2% sliver fans out into a full row and the
 * in-range mass narrows into the headline. Bar, ribbon and row are one shape per band.
 *
 * Rows run high to low, the way glucose reads on every chart in the app, and in range sits
 * in the middle as the larger row carrying the headline percentage and its change, so the
 * card needs no separate title or ring.
 *
 * It replaces a ring: a circle had to share a row with the band list, which
 * left it either too small to read or stranded on its own line, and its sweep gave a 2%
 * band a sliver nobody could tap.
 */

/** Bar + ribbon = the 40dp an icon tile takes, so the row text lands on the app's 68dp inset. */
private val FlowBarWidth = 12.dp
private val FlowRibbonWidth = 28.dp
private val FlowRowGap = 4.dp
private val FlowSegmentGap = 2.dp
/** A non-zero band never disappears from the bar; under 1% it grows towards this. */
private val FlowMinSegment = 4.dp
private val FlowRowCorner = 12.dp
private val FlowSegmentInnerCorner = 2.dp

private const val TintAlpha = 0.14f
private const val TintSelectedAlpha = 0.32f
private const val TintDimmedAlpha = 0.06f

private const val MinutesPerDay = 24 * 60

private data class TirBandRow(
    val band: TirBand,
    val label: String,
    val rangeLabel: String,
    val percent: Float,
    val color: Color
)

@Immutable
private data class FlowGeometry(val rowTops: List<Float>, val rowBottoms: List<Float>)

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
        TirBandRow(
            band = TirBand.VERY_HIGH,
            label = stringResource(R.string.very_high),
            rangeLabel = "≥ ${formatMgDl(targets.veryHighMgDl, unit)}",
            percent = summary.tir.veryHighPercent,
            color = TirVeryHighColor
        ),
        TirBandRow(
            band = TirBand.HIGH,
            label = stringResource(R.string.high_range),
            rangeLabel = "${formatMgDl(targets.highMgDl, unit)}–${formatMgDl(targets.veryHighMgDl, unit)}",
            percent = summary.tir.highPercent,
            color = TirHighColor
        ),
        TirBandRow(
            band = TirBand.IN_RANGE,
            label = stringResource(R.string.in_range),
            rangeLabel = "${formatMgDl(targets.lowMgDl, unit)}–${formatMgDl(targets.highMgDl, unit)}",
            percent = summary.tir.inRangePercent,
            color = TirInRangeColor
        ),
        TirBandRow(
            band = TirBand.LOW,
            label = stringResource(R.string.low_range),
            rangeLabel = "${formatMgDl(targets.veryLowMgDl, unit)}–${formatMgDl(targets.lowMgDl, unit)}",
            percent = summary.tir.lowPercent,
            color = TirLowColor
        ),
        TirBandRow(
            band = TirBand.VERY_LOW,
            label = stringResource(R.string.very_low),
            rangeLabel = "< ${formatMgDl(targets.veryLowMgDl, unit)}",
            percent = summary.tir.veryLowPercent,
            color = TirVeryLowColor
        )
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            // A tap anywhere off the rows clears the highlight, as the ring's centre did.
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onBandSelected(null)
            },
        shape = StatsCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        RangeFlow(
            rows = rows,
            inRangeDelta = summary.comparison?.inRangeDelta,
            selectedBand = selectedBand,
            onBandSelected = onBandSelected,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}

@Composable
private fun RangeFlow(
    rows: List<TirBandRow>,
    inRangeDelta: Float?,
    selectedBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit,
    modifier: Modifier = Modifier
) {
    // Read in the draw phase only: a range change re-pours the bar without recomposing.
    val shares = rows.map { row ->
        animateFloatAsState(
            targetValue = row.percent.coerceIn(0f, 100f),
            animationSpec = ExpressiveMotion.slowEffects(),
            label = "flowShare"
        )
    }
    val tints = rows.map { row ->
        animateFloatAsState(
            targetValue = when {
                row.percent <= 0f -> 0f
                selectedBand == null -> TintAlpha
                selectedBand == row.band -> TintSelectedAlpha
                else -> TintDimmedAlpha
            },
            animationSpec = ExpressiveMotion.defaultEffects(),
            label = "flowTint"
        )
    }
    val colors = rows.map { it.color }
    val geometry = remember { mutableStateOf<FlowGeometry?>(null) }

    Layout(
        modifier = modifier.drawBehind {
            geometry.value?.let { drawFlow(it, shares, tints, colors) }
        },
        content = {
            rows.forEach { row ->
                BandRow(
                    row = row,
                    hero = row.band == TirBand.IN_RANGE,
                    inRangeDelta = inRangeDelta,
                    selected = selectedBand == row.band,
                    onClick = { onBandSelected(if (selectedBand == row.band) null else row.band) }
                )
            }
        }
    ) { measurables, constraints ->
        val inset = (FlowBarWidth + FlowRibbonWidth).roundToPx()
        val width = constraints.maxWidth
        val rowWidth = (width - inset).coerceAtLeast(0)
        val rowConstraints = Constraints(minWidth = rowWidth, maxWidth = rowWidth)
        val placeables = measurables.map { it.measure(rowConstraints) }
        val gap = FlowRowGap.roundToPx()
        val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(width, height) {
            val tops = ArrayList<Float>(placeables.size)
            val bottoms = ArrayList<Float>(placeables.size)
            var y = 0
            placeables.forEach { placeable ->
                placeable.place(inset, y)
                tops += y.toFloat()
                bottoms += (y + placeable.height).toFloat()
                y += placeable.height + gap
            }
            // Only the draw phase reads this, so setting it here costs a redraw, not a pass.
            val next = FlowGeometry(tops, bottoms)
            if (geometry.value != next) geometry.value = next
        }
    }
}

private fun DrawScope.drawFlow(
    geometry: FlowGeometry,
    shares: List<State<Float>>,
    tints: List<State<Float>>,
    colors: List<Color>
) {
    val barRight = FlowBarWidth.toPx()
    val rowLeft = barRight + FlowRibbonWidth.toPx()
    val segmentGap = FlowSegmentGap.toPx()
    val minSegment = FlowMinSegment.toPx()
    val values = shares.map { it.value.coerceAtLeast(0f) }
    val present = values.count { it > 0f }
    if (present == 0) return

    // Each non-zero band gets a floor, so a 0.4% sliver stays visible. Only the last tenth
    // of a percent ramps into it, so a band arriving from 0% grows in instead of popping.
    val usable = (size.height - segmentGap * (present - 1)).coerceAtLeast(0f)
    val floors = values.map { minSegment * (it / 0.1f).coerceIn(0f, 1f) }
    val spare = (usable - floors.sum()).coerceAtLeast(0f)
    val total = values.sum()
    val heights = values.indices.map { i -> floors[i] + values[i] / total * spare }

    val firstPresent = values.indexOfFirst { it > 0f }
    val lastPresent = values.indexOfLast { it > 0f }
    val outerCorner = barRight / 2f
    val innerCorner = FlowSegmentInnerCorner.toPx()
    val rowCorner = FlowRowCorner.toPx()
    val bend = (rowLeft - barRight) * 0.55f

    var segmentTop = 0f
    for (i in values.indices) {
        if (values[i] <= 0f) continue
        val segmentBottom = segmentTop + heights[i]
        val rowTop = geometry.rowTops.getOrNull(i) ?: break
        val rowBottom = geometry.rowBottoms[i]
        val right = size.width

        // Ribbon and row as one outline: out of the segment, along the row, back in.
        val alpha = tints[i].value
        if (alpha > 0f) {
            val corner = rowCorner.coerceAtMost((rowBottom - rowTop) / 2f)
            val flow = Path().apply {
                moveTo(barRight, segmentTop)
                cubicTo(barRight + bend, segmentTop, rowLeft - bend, rowTop, rowLeft, rowTop)
                lineTo(right - corner, rowTop)
                arcTo(Rect(right - 2 * corner, rowTop, right, rowTop + 2 * corner), -90f, 90f, false)
                lineTo(right, rowBottom - corner)
                arcTo(Rect(right - 2 * corner, rowBottom - 2 * corner, right, rowBottom), 0f, 90f, false)
                lineTo(rowLeft, rowBottom)
                cubicTo(rowLeft - bend, rowBottom, barRight + bend, segmentBottom, barRight, segmentBottom)
                close()
            }
            drawPath(flow, colors[i].copy(alpha = alpha))
        }

        val height = segmentBottom - segmentTop
        val top = if (i == firstPresent) outerCorner else innerCorner
        val bottom = if (i == lastPresent) outerCorner else innerCorner
        val clampTop = top.coerceAtMost(height / 2f)
        val clampBottom = bottom.coerceAtMost(height / 2f)
        drawPath(
            Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(0f, segmentTop, barRight, segmentBottom),
                        topLeft = CornerRadius(clampTop),
                        topRight = CornerRadius(clampTop),
                        bottomRight = CornerRadius(clampBottom),
                        bottomLeft = CornerRadius(clampBottom)
                    )
                )
            },
            colors[i]
        )
        segmentTop = segmentBottom + segmentGap
    }
}

@Composable
private fun BandRow(
    row: TirBandRow,
    hero: Boolean,
    inRangeDelta: Float?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val empty = row.percent <= 0f
    val percentText = String.format(Locale.getDefault(), "%.1f%%", row.percent)
    val minutes = (row.percent.coerceIn(0f, 100f) / 100f * MinutesPerDay).roundToInt()
    val perDay = if (empty) null else stringResource(R.string.stats_time_a_day, durationText(minutes))
    val textColor = if (empty) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val rowModifier = Modifier
        .fillMaxWidth()
        // The left edge is where the ribbon pours in, so only the far side is rounded.
        .clip(RoundedCornerShape(topEnd = FlowRowCorner, bottomEnd = FlowRowCorner))
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = if (hero) 72.dp else 48.dp)
        .padding(horizontal = 12.dp, vertical = if (hero) 12.dp else 4.dp)
    val labelBlock: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(if (hero) 4.dp else 0.dp)) {
            Text(
                text = row.label,
                style = if (hero) {
                    MaterialTheme.typography.titleMediumEmphasized
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = textColor
            )
            BandDetail(range = row.rangeLabel, perDay = perDay)
        }
    }

    if (hero) {
        HeroRowLayout(modifier = rowModifier, label = labelBlock) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = percentText,
                    style = MaterialTheme.typography.headlineMediumEmphasized.copy(fontFeatureSettings = "tnum"),
                    color = tirHeatColor(row.percent),
                    maxLines = 1,
                    softWrap = false
                )
                inRangeDelta?.takeIf { abs(it) >= 1f }?.let { InRangeDelta(it) }
            }
        }
    } else {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) { labelBlock() }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = percentText,
                style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum"),
                color = textColor,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * Label beside the headline while the label's longest word still fits next to it; on a
 * narrow window at a large font the headline drops below instead of the label breaking
 * mid-word.
 */
@Composable
private fun HeroRowLayout(
    modifier: Modifier,
    label: @Composable () -> Unit,
    headline: @Composable () -> Unit
) {
    Layout(
        modifier = modifier,
        content = {
            Box { label() }
            Box { headline() }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val trailing = measurables[1].measure(loose)
        val labelRoom = width - gap - trailing.width
        val stacked = labelRoom < measurables[0].minIntrinsicWidth(Constraints.Infinity)
        val text = measurables[0].measure(loose.copy(maxWidth = if (stacked) width else labelRoom.coerceAtLeast(0)))
        val contentHeight = if (stacked) text.height + trailing.height else maxOf(text.height, trailing.height)
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val top = (height - contentHeight) / 2
            if (stacked) {
                text.placeRelative(0, top)
                trailing.placeRelative(width - trailing.width, top + text.height)
            } else {
                text.placeRelative(0, (height - text.height) / 2)
                trailing.placeRelative(width - trailing.width, (height - trailing.height) / 2)
            }
        }
    }
}

/**
 * "7.7–10.0 · 1 h 21 min a day" on one line when it fits; otherwise the time takes the
 * second line whole and the dot goes, rather than the wrap splitting the duration.
 */
@Composable
private fun BandDetail(range: String, perDay: String?) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            Text(text = range, style = style, color = color)
            if (perDay != null) {
                Text(text = " · ", style = style, color = color, maxLines = 1, softWrap = false)
                Text(text = perDay, style = style, color = color)
            }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val rangeText = measurables[0].measure(loose)
        if (measurables.size == 1) {
            return@Layout layout(rangeText.width, rangeText.height) { rangeText.place(0, 0) }
        }
        val dotWidth = measurables[1].maxIntrinsicWidth(rangeText.height)
        val timeWidth = measurables[2].maxIntrinsicWidth(rangeText.height)
        if (rangeText.width + dotWidth + timeWidth <= constraints.maxWidth) {
            val dot = measurables[1].measure(loose)
            val time = measurables[2].measure(loose)
            val height = maxOf(rangeText.height, dot.height, time.height)
            layout(rangeText.width + dot.width + time.width, height) {
                rangeText.place(0, 0)
                dot.place(rangeText.width, 0)
                time.place(rangeText.width + dot.width, 0)
            }
        } else {
            val time = measurables[2].measure(loose)
            layout(maxOf(rangeText.width, time.width), rangeText.height + time.height) {
                rangeText.place(0, 0)
                time.place(0, rangeText.height)
            }
        }
    }
}

/** Change in time in range against the window before, in percentage points. */
@Composable
private fun InRangeDelta(delta: Float) {
    val rising = delta >= 0f
    val tone = if (rising) TirInRangeColor else TirHighColor
    val signed = formatSignedNumber(delta)
    val description = stringResource(R.string.gri_vs_previous, signed)
    Row(
        modifier = Modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (rising) Symbols.ArrowDropUpFilled else Symbols.ArrowDropDownFilled,
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = abs(delta).roundToInt().toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = tone,
            maxLines = 1
        )
    }
}
