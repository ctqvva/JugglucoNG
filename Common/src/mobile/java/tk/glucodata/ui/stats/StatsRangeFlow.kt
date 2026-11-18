package tk.glucodata.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import tk.glucodata.R
import tk.glucodata.ui.isAppInDarkTheme
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.ExpressiveMotion

/*
 * The overview card: where the window's time went, band by band.
 *
 * A stacked bar on the left is the honest picture: each band's segment is as tall as its
 * share of the time, so 88% in range dwarfs a 2% low the way it should. Five rows on the
 * right make every band readable, however small: each segment flows into its own row
 * through a ribbon in the row's tint, so a 2% sliver fans out into a full row and the
 * in-range mass narrows into the headline.
 *
 * The bar keeps its own pill shape. The ribbon starts behind it, so there is no gap at the
 * segment's rounded corners, and a faint smudge in the band's colour right at the join
 * keeps the hand-off from bar to ribbon from reading as a hard edge.
 *
 * A row's tint follows its share of the time, so 97% in range glows and 47% does not even
 * at a glance; the band's full colour lives on in its range numbers.
 *
 * Rows run high to low, the way glucose reads on every chart in the app, and in range sits
 * in the middle as the larger row carrying the headline percentage and its change, so the
 * card needs no separate title or ring. A strip of the day's four six-hour blocks closes
 * the card: each shows its own time in range, and a tap re-pours the card for that block.
 *
 * It replaces a ring: a circle had to share a row with the band list, which left it either
 * too small to read or stranded on its own line, and its sweep gave a 2% band a sliver
 * nobody could tap.
 */

/** As wide as the card's own padding, so the bar sits in a margin of its own width. */
private val FlowBarWidth = 16.dp
private val FlowRibbonWidth = 28.dp
private val FlowRowGap = 4.dp
private val FlowSegmentGap = 2.dp
/** A non-zero band never disappears from the bar. */
private val FlowMinSegment = 4.dp
private val FlowRowCorner = 12.dp
private val FlowSegmentInnerCorner = 2.dp
/** How far the smudge at the bar's edge reaches into the ribbon. */
private val FlowSmudgeWidth = 8.dp

/** The tint a band gets at 100% of the time; everything below scales down from it. */
private const val TintMaxAlpha = 0.14f
/** Just enough that a 1% row still shows which band it is. */
private const val TintMinAlpha = 0.03f
private const val TintSelectedAlpha = 0.28f
/** What a press adds to a band's tint, ribbon included, while the ripple runs in the row. */
private const val TintPressedBoost = 0.08f
/** The smudge's strength at the bar's edge, fading to nothing over [FlowSmudgeWidth]. */
private const val SmudgeAlpha = 0.16f

/** Below this a band reads "0.0%": no segment, no ribbon, no touch target. */
private const val EmptyPercent = 0.05f

private const val MinutesPerDay = 24 * 60
private const val MinutesPerDayPart = 6 * 60

private data class TirBandRow(
    val band: TirBand,
    val label: String,
    val rangeLabel: String,
    val percent: Float,
    val color: Color
) {
    val empty: Boolean get() = percent < EmptyPercent
}

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
    var selectedPart by rememberSaveable { mutableStateOf<DayPart?>(null) }
    val dayParts = summary.dayParts
    val partsAvailable = dayParts.size == DayPart.entries.size && dayParts.any { it.readingCount > 0 }
    val part = selectedPart?.takeIf { partsAvailable }?.let { chosen -> dayParts.firstOrNull { it.part == chosen } }
    val tir = part?.tir ?: summary.tir

    val rows = listOf(
        TirBandRow(
            band = TirBand.VERY_HIGH,
            label = stringResource(R.string.very_high),
            rangeLabel = "≥ ${formatMgDl(targets.veryHighMgDl, unit)}",
            percent = tir.veryHighPercent,
            color = TirVeryHighColor
        ),
        TirBandRow(
            band = TirBand.HIGH,
            label = stringResource(R.string.high_range),
            rangeLabel = "${formatMgDl(targets.highMgDl, unit)}–${formatMgDl(targets.veryHighMgDl, unit)}",
            percent = tir.highPercent,
            color = TirHighColor
        ),
        TirBandRow(
            band = TirBand.IN_RANGE,
            label = stringResource(R.string.in_range),
            rangeLabel = "${formatMgDl(targets.lowMgDl, unit)}–${formatMgDl(targets.highMgDl, unit)}",
            percent = tir.inRangePercent,
            color = TirInRangeColor
        ),
        TirBandRow(
            band = TirBand.LOW,
            label = stringResource(R.string.low_range),
            rangeLabel = "${formatMgDl(targets.veryLowMgDl, unit)}–${formatMgDl(targets.lowMgDl, unit)}",
            percent = tir.lowPercent,
            color = TirLowColor
        ),
        TirBandRow(
            band = TirBand.VERY_LOW,
            label = stringResource(R.string.very_low),
            rangeLabel = "< ${formatMgDl(targets.veryLowMgDl, unit)}",
            percent = tir.veryLowPercent,
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
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            RangeFlow(
                rows = rows,
                // The comparison is for the whole window, so it only stands beside the whole.
                inRangeDelta = if (part == null) summary.comparison?.inRangeDelta else null,
                minutesPerDay = if (part == null) MinutesPerDay else MinutesPerDayPart,
                selectedBand = selectedBand,
                onBandSelected = onBandSelected,
                modifier = Modifier.fillMaxWidth()
            )
            if (partsAvailable) {
                Spacer(modifier = Modifier.height(16.dp))
                DayPartStrip(
                    parts = dayParts,
                    selected = part?.part,
                    // Tapping the chosen block again goes back to the whole day, so the
                    // strip needs no fifth "all" choice.
                    onSelected = { chosen -> selectedPart = if (chosen == selectedPart) null else chosen }
                )
            }
        }
    }
}

private val DayPart.symbol: ImageVector
    get() = when (this) {
        DayPart.NIGHT -> Symbols.BedtimeFilled
        DayPart.MORNING -> Symbols.WbTwilightFilled
        DayPart.AFTERNOON -> Symbols.LightModeFilled
        DayPart.EVENING -> Symbols.RoutineFilled
    }

/**
 * The day's four six-hour blocks, each with its own time in range, coloured on the same
 * scale as the headline. They compare at a glance without a tap; a tap re-pours the whole
 * card for that block, and a second tap returns to the whole day.
 *
 * Smaller than the range buttons above the card (32 against 40dp) so the two never read
 * as one control, and at the foot of the card, since it refines what is above it.
 */
@Composable
private fun DayPartStrip(
    parts: List<DayPartStats>,
    selected: DayPart?,
    onSelected: (DayPart) -> Unit
) {
    val names = DayPart.entries.associateWith { stringResource(it.labelResId) }
    val inRangeWord = stringResource(R.string.in_range)
    ConnectedButtonGroup(
        options = DayPart.entries,
        selectedOption = selected,
        onOptionSelected = onSelected,
        label = { option ->
            val stats = parts.firstOrNull { it.part == option }
            val hasData = stats != null && stats.readingCount > 0
            val percent = stats?.tir?.inRangePercent ?: 0f
            val value = if (hasData) String.format(Locale.getDefault(), "%.0f%%", percent) else "–"
            val hours = String.format(Locale.getDefault(), "%02d–%02d", option.startHour, option.startHour + 6)
            val description = "${names[option]}, $hours, $value $inRangeWord"
            Row(
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = option.symbol,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = value,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                    color = if (hasData) tirHeatColor(percent) else LocalTextStyle.current.color,
                    maxLines = 1,
                    softWrap = false
                )
            }
        },
        itemHeight = 32.dp,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RangeFlow(
    rows: List<TirBandRow>,
    inRangeDelta: Float?,
    minutesPerDay: Int,
    selectedBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit,
    modifier: Modifier = Modifier
) {
    // Read in the draw phase only: a range change re-pours the bar without recomposing.
    val shares = rows.map { row ->
        animateFloatAsState(
            targetValue = if (row.empty) 0f else row.percent.coerceIn(0f, 100f),
            animationSpec = ExpressiveMotion.slowEffects(),
            label = "flowShare"
        )
    }
    val interactions = rows.map { remember { MutableInteractionSource() } }
    val tints = rows.mapIndexed { index, row ->
        val pressed by interactions[index].collectIsPressedAsState()
        // Squared, so the gap between a good and a poor share is plain at a glance: 95% in
        // range keeps nearly the full tint, 47% gets about a quarter of it.
        val fraction = row.percent.coerceIn(0f, 100f) / 100f
        val share = TintMinAlpha + (TintMaxAlpha - TintMinAlpha) * fraction * fraction
        val resting = when {
            row.empty -> 0f
            selectedBand == null -> share
            selectedBand == row.band -> TintSelectedAlpha
            else -> share / 2f
        }
        animateFloatAsState(
            targetValue = if (pressed && !row.empty) resting + TintPressedBoost else resting,
            animationSpec = ExpressiveMotion.fastEffects(),
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
            rows.forEachIndexed { index, row ->
                BandRow(
                    row = row,
                    hero = row.band == TirBand.IN_RANGE,
                    inRangeDelta = inRangeDelta,
                    minutesPerDay = minutesPerDay,
                    selected = selectedBand == row.band,
                    interactionSource = interactions[index],
                    onClick = { onBandSelected(if (selectedBand == row.band) null else row.band) }
                )
            }
        }
    ) { measurables, constraints ->
        // Rows start at the bar's edge, so the ribbon is part of each row's touch target.
        val inset = FlowBarWidth.roundToPx()
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

/** Each band's segment on the bar, top to bottom, or null for a band with no time. */
private fun Density.segmentSpans(shares: List<State<Float>>, height: Float): List<Pair<Float, Float>?> {
    val values = shares.map { it.value.coerceAtLeast(0f) }
    val present = values.count { it > 0f }
    if (present == 0) return values.map { null }
    val gap = FlowSegmentGap.toPx()
    val minSegment = FlowMinSegment.toPx()
    // The floor ramps in over the first tenth of a percent, so a band arriving from 0%
    // grows into view instead of popping in at full height.
    val floors = values.map { minSegment * (it / 0.1f).coerceIn(0f, 1f) }
    val usable = (height - gap * (present - 1)).coerceAtLeast(0f)
    val spare = (usable - floors.sum()).coerceAtLeast(0f)
    val total = values.sum()
    var top = 0f
    return values.indices.map { i ->
        if (values[i] <= 0f) {
            null
        } else {
            val bottom = top + floors[i] + values[i] / total * spare
            (top to bottom).also { top = bottom + gap }
        }
    }
}

/**
 * One band's ribbon and row as a single outline. It starts at [start], behind the
 * segment, so the segment's rounded corners sit on the ribbon instead of on a gap.
 */
private fun bandOutline(
    segmentTop: Float,
    segmentBottom: Float,
    rowTop: Float,
    rowBottom: Float,
    start: Float,
    barRight: Float,
    rowLeft: Float,
    right: Float,
    corner: Float
): Path {
    val bend = (rowLeft - barRight) * 0.55f
    val radius = corner.coerceAtMost((rowBottom - rowTop) / 2f)
    return Path().apply {
        moveTo(start, segmentTop)
        lineTo(barRight, segmentTop)
        cubicTo(barRight + bend, segmentTop, rowLeft - bend, rowTop, rowLeft, rowTop)
        lineTo(right - radius, rowTop)
        arcTo(Rect(right - 2 * radius, rowTop, right, rowTop + 2 * radius), -90f, 90f, false)
        lineTo(right, rowBottom - radius)
        arcTo(Rect(right - 2 * radius, rowBottom - 2 * radius, right, rowBottom), 0f, 90f, false)
        lineTo(rowLeft, rowBottom)
        cubicTo(rowLeft - bend, rowBottom, barRight + bend, segmentBottom, barRight, segmentBottom)
        lineTo(start, segmentBottom)
        close()
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
    val spans = segmentSpans(shares, size.height)
    val present = spans.indices.filter { spans[it] != null }
    if (present.isEmpty()) return
    val outerCorner = barRight / 2f
    val innerCorner = FlowSegmentInnerCorner.toPx()
    val rowCorner = FlowRowCorner.toPx()
    val smudgeEnd = barRight + FlowSmudgeWidth.toPx()

    for (i in present) {
        val (segmentTop, segmentBottom) = spans[i]!!
        val rowTop = geometry.rowTops.getOrNull(i) ?: continue
        val rowBottom = geometry.rowBottoms[i]
        val color = colors[i]
        val outline = bandOutline(
            segmentTop, segmentBottom, rowTop, rowBottom,
            start = barRight - outerCorner, barRight = barRight, rowLeft = rowLeft,
            right = size.width, corner = rowCorner
        )
        drawPath(outline, color.copy(alpha = tints[i].value))
        // A faint smudge in the band's colour at the join, gone within a few dp.
        drawPath(
            outline,
            Brush.horizontalGradient(
                colors = listOf(color.copy(alpha = SmudgeAlpha), color.copy(alpha = 0f)),
                startX = barRight,
                endX = smudgeEnd
            )
        )

        val height = segmentBottom - segmentTop
        val top = (if (i == present.first()) outerCorner else innerCorner).coerceAtMost(height / 2f)
        val bottom = (if (i == present.last()) outerCorner else innerCorner).coerceAtMost(height / 2f)
        drawPath(
            Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(0f, segmentTop, barRight, segmentBottom),
                        topLeft = CornerRadius(top),
                        topRight = CornerRadius(top),
                        bottomRight = CornerRadius(bottom),
                        bottomLeft = CornerRadius(bottom)
                    )
                )
            },
            color
        )
    }
}

/**
 * Where a row's ripple runs: the row itself, past the ribbon. The ribbon answers a press
 * by brightening with the rest of the band, which the canvas draws, so nothing is cut off
 * where the ribbon flares beyond the row.
 */
private class RowSurfaceShape(private val inset: Dp, private val corner: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = with(density) { inset.toPx() }
        val radius = with(density) { corner.toPx() }.coerceAtMost(size.height / 2f)
        return Outline.Rounded(
            RoundRect(
                rect = Rect(left, 0f, size.width, size.height),
                topLeft = CornerRadius.Zero,
                topRight = CornerRadius(radius),
                bottomRight = CornerRadius(radius),
                bottomLeft = CornerRadius.Zero
            )
        )
    }
}

private val RowSurface = RowSurfaceShape(FlowRibbonWidth, FlowRowCorner)

@Composable
private fun BandRow(
    row: TirBandRow,
    hero: Boolean,
    inRangeDelta: Float?,
    minutesPerDay: Int,
    selected: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit
) {
    val percentText = String.format(Locale.getDefault(), "%.1f%%", row.percent)
    val minutes = (row.percent.coerceIn(0f, 100f) / 100f * minutesPerDay).roundToInt()
    val time = if (row.empty) null else durationText(minutes)
    val textColor = if (row.empty) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // The band's full colour, which the tint no longer carries at a low share. Pulled
    // towards the text colour on a light surface, where yellow alone is unreadable.
    val dark = isAppInDarkTheme()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val rangeColor = remember(row.color, dark, onSurface, row.empty) {
        val base = if (dark) row.color else lerp(row.color, onSurface, 0.35f)
        if (row.empty) base.copy(alpha = 0.6f) else base
    }

    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RowSurface)
        // A band with no time has nothing to highlight, so it is not a target.
        .then(
            if (row.empty) {
                Modifier
            } else {
                Modifier.selectable(
                    selected = selected,
                    interactionSource = interactionSource,
                    indication = ripple(),
                    role = Role.RadioButton,
                    onClick = onClick
                )
            }
        )
        .heightIn(min = if (hero) 72.dp else 48.dp)
        .padding(
            start = FlowRibbonWidth + 12.dp,
            end = 12.dp,
            top = if (hero) 12.dp else 4.dp,
            bottom = if (hero) 12.dp else 4.dp
        )
    val detail: @Composable () -> Unit = {
        JoinedOrStackedText(
            first = row.rangeLabel,
            second = time,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            firstColor = rangeColor,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (hero) {
        HeroRowLayout(
            modifier = rowModifier,
            title = {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = textColor
                )
            },
            detail = detail,
            headline = {
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
        )
    } else {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.label, style = MaterialTheme.typography.bodyMedium, color = textColor)
                detail()
            }
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
 * The in-range row. The headline stays on the right for as long as anything can make room
 * for it, in this order:
 * 1. title and detail beside it, the detail stacking its two parts if it has to;
 * 2. the title on a line of its own, the detail and headline sharing the line below;
 * 3. only then the headline below everything, end-aligned.
 */
@Composable
private fun HeroRowLayout(
    modifier: Modifier,
    title: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    headline: @Composable () -> Unit
) {
    Layout(
        modifier = modifier,
        content = {
            Box { title() }
            Box { detail() }
            Box { headline() }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val titleGap = 4.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val (titleM, detailM, headlineM) = measurables
        val end = headlineM.measure(loose)
        val room = (width - gap - end.width).coerceAtLeast(0)
        val titleWidth = titleM.maxIntrinsicWidth(Constraints.Infinity)
        val detailNarrowest = detailM.minIntrinsicWidth(Constraints.Infinity)

        fun finish(contentHeight: Int, arrange: androidx.compose.ui.layout.Placeable.PlacementScope.(Int) -> Unit): MeasureResult {
            val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            return layout(width, height) { arrange((height - contentHeight) / 2) }
        }

        when {
            titleWidth <= room && detailNarrowest <= room -> {
                val t = titleM.measure(loose.copy(maxWidth = room))
                val d = detailM.measure(loose.copy(maxWidth = room))
                val left = t.height + titleGap + d.height
                finish(maxOf(left, end.height)) { top ->
                    val leftTop = top + (maxOf(left, end.height) - left) / 2
                    t.placeRelative(0, leftTop)
                    d.placeRelative(0, leftTop + t.height + titleGap)
                    end.placeRelative(width - end.width, top + (maxOf(left, end.height) - end.height) / 2)
                }
            }
            detailNarrowest <= room -> {
                val t = titleM.measure(loose.copy(maxWidth = width))
                val d = detailM.measure(loose.copy(maxWidth = room))
                val lower = maxOf(d.height, end.height)
                finish(t.height + titleGap + lower) { top ->
                    t.placeRelative(0, top)
                    val lowerTop = top + t.height + titleGap
                    d.placeRelative(0, lowerTop + (lower - d.height) / 2)
                    end.placeRelative(width - end.width, lowerTop + (lower - end.height) / 2)
                }
            }
            else -> {
                val t = titleM.measure(loose.copy(maxWidth = width))
                val d = detailM.measure(loose.copy(maxWidth = width))
                finish(t.height + titleGap + d.height + end.height) { top ->
                    t.placeRelative(0, top)
                    d.placeRelative(0, top + t.height + titleGap)
                    end.placeRelative(width - end.width, top + t.height + titleGap + d.height)
                }
            }
        }
    }
}

/**
 * [leading] beside [trailing] while the leading block's narrowest sensible width (its
 * longest word, or for [JoinedOrStackedText] its longer part) still fits next to it;
 * otherwise [trailing] drops below, end-aligned, rather than the text breaking mid-word.
 */
@Composable
internal fun BesideOrBelow(
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
    stackGap: Dp = 0.dp,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    Layout(
        modifier = modifier,
        content = {
            Box { leading() }
            Box { trailing() }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val space = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val end = measurables[1].measure(loose)
        val room = width - space - end.width
        val stacked = room < measurables[0].minIntrinsicWidth(Constraints.Infinity)
        val start = measurables[0].measure(loose.copy(maxWidth = if (stacked) width else room.coerceAtLeast(0)))
        val below = stackGap.roundToPx()
        val contentHeight = if (stacked) start.height + below + end.height else maxOf(start.height, end.height)
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val top = (height - contentHeight) / 2
            if (stacked) {
                start.placeRelative(0, top)
                end.placeRelative(width - end.width, top + start.height + below)
            } else {
                start.placeRelative(0, (height - start.height) / 2)
                end.placeRelative(width - end.width, (height - end.height) / 2)
            }
        }
    }
}

/**
 * "[first] · [second]" on one line when it fits; otherwise [second] takes the next line
 * whole and the dot goes, rather than a wrap splitting either part. Its narrowest width is
 * the longer part, which is what [BesideOrBelow] reads to decide whether to stack.
 */
@Composable
internal fun JoinedOrStackedText(
    first: String,
    second: String?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    firstColor: Color = color
) {
    Layout(
        modifier = modifier,
        content = {
            Text(text = first, style = style, color = firstColor, maxLines = 1, softWrap = false)
            if (second != null) {
                Text(text = " · ", style = style, color = color, maxLines = 1, softWrap = false)
                Text(text = second, style = style, color = color)
            }
        },
        measurePolicy = JoinedOrStackedPolicy
    )
}

private object JoinedOrStackedPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val first = measurables[0].measure(loose)
        if (measurables.size == 1) return layout(first.width, first.height) { first.place(0, 0) }
        val dotWidth = measurables[1].maxIntrinsicWidth(first.height)
        val secondWidth = measurables[2].maxIntrinsicWidth(first.height)
        return if (first.width + dotWidth + secondWidth <= constraints.maxWidth) {
            val dot = measurables[1].measure(loose)
            val second = measurables[2].measure(loose)
            layout(first.width + dot.width + second.width, maxOf(first.height, dot.height, second.height)) {
                first.place(0, 0)
                dot.place(first.width, 0)
                second.place(first.width + dot.width, 0)
            }
        } else {
            val second = measurables[2].measure(loose)
            layout(maxOf(first.width, second.width), first.height + second.height) {
                first.place(0, 0)
                second.place(0, first.height)
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        if (measurables.size == 1) {
            measurables[0].maxIntrinsicWidth(height)
        } else {
            maxOf(measurables[0].maxIntrinsicWidth(height), measurables[2].maxIntrinsicWidth(height))
        }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.sumOf { it.maxIntrinsicWidth(height) }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        intrinsicHeight(measurables, width)

    private fun intrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val first = measurables[0].maxIntrinsicHeight(width)
        if (measurables.size == 1) return first
        val joined = measurables.sumOf { it.maxIntrinsicWidth(first) }
        return if (joined <= width) first else first + measurables[2].maxIntrinsicHeight(width)
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
