package tk.glucodata.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.indication
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import tk.glucodata.R
import tk.glucodata.ui.isAppInDarkTheme
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.SettingsSlider
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
 * the card: each shows its own time in range over a faint wash of its own band mix. Any
 * blocks can be combined, or a window of any hours set below the strip, and the card
 * re-pours for those hours.
 *
 * It replaces a ring: a circle had to share a row with the band list, which left it either
 * too small to read or stranded on its own line, and its sweep gave a 2% band a sliver
 * nobody could tap.
 */

/** As wide as the card's own padding, so the bar sits in a margin of its own width. */
private val FlowBarWidth = 16.dp
private val FlowRibbonWidth = 28.dp
private val FlowRowGap = 2.dp
private val FlowSegmentGap = 1.5.dp
/** A non-zero band never disappears from the bar. */
private val FlowMinSegment = 2.dp
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

/** Hours of the day as bits, bit 0 for 00:00–01:00. Zero means the whole day. */
private const val AllHours = (1 shl 24) - 1

/** The strip's wash of each block's band mix, resting and chosen. */
private const val StripWashAlpha = 0.14f
private const val StripWashCheckedAlpha = 0.40f

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
    modifier: Modifier = Modifier,
    ring: Boolean = false
) {
    var hourMask by rememberSaveable { mutableStateOf(0) }
    var editingWindow by rememberSaveable { mutableStateOf(false) }
    val hourly = summary.hourlyStats
    val hoursAvailable = hourly.size == 24 && hourly.any { it.sampleCount > 0 }
    val filtered = hoursAvailable && hourMask != 0 && hourMask != AllHours
    val window = if (filtered) windowTir(hourly, hourMask) else null
    val noData = window != null && window.second == 0
    val tir = window?.first ?: summary.tir
    val multiDay = summary.coverage.windowDays > 1.5f

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
        // A band with no time is left out: an empty row beside the bar's top segment read as
        // that segment's band. In range always stays, and carries "–" when there is no data.
        val shown = rows.filter { !it.empty || it.band == TirBand.IN_RANGE }
        val visibleBands = shown.map { it.band }
        LaunchedEffect(visibleBands, selectedBand) {
            if (selectedBand != null && selectedBand !in visibleBands) onBandSelected(null)
        }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // The comparison is for the whole window, so it only stands beside the whole.
            val inRangeDelta = if (window == null) summary.comparison?.inRangeDelta else null
            // One day: the time a typical day spends in each band. Over several days the
            // out-of-range bands add up instead, the time actually measured there, and In
            // range drops its hours, which only restate its percentage.
            val spanMinutes = if (multiDay) {
                val cadence = summary.coverage.cadenceMillis / 60_000f
                ((window?.second ?: summary.coverage.readingCount) * cadence).roundToInt()
            } else if (window == null) MinutesPerDay else Integer.bitCount(hourMask) * 60
            if (ring) {
                RangeRing(
                    rows = shown,
                    inRangeDelta = inRangeDelta,
                    spanMinutes = spanMinutes,
                    multiDay = multiDay,
                    noData = noData,
                    selectedBand = selectedBand,
                    onBandSelected = onBandSelected,
                    modifier = Modifier.fillMaxWidth().animateContentSize()
                )
            } else {
                RangeFlow(
                    rows = shown,
                    inRangeDelta = inRangeDelta,
                    spanMinutes = spanMinutes,
                    multiDay = multiDay,
                    noData = noData,
                    selectedBand = selectedBand,
                    onBandSelected = onBandSelected,
                    modifier = Modifier.fillMaxWidth().animateContentSize()
                )
            }
            if (hoursAvailable) {
                Spacer(modifier = Modifier.height(12.dp))
                DayPartStrip(
                    hourly = hourly,
                    mask = hourMask,
                    onToggle = { part ->
                        val block = part.hourMask
                        val next = if (hourMask and block == block) hourMask and block.inv() else hourMask or block
                        hourMask = if (next == AllHours) 0 else next
                    }
                )
                if (filtered || editingWindow) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TimeWindowControl(
                        mask = hourMask,
                        editing = editingWindow,
                        onEditingChange = { editingWindow = it },
                        onWindowChange = { start, end -> hourMask = windowMask(start, end) },
                        onClear = {
                            hourMask = 0
                            editingWindow = false
                        }
                    )
                }
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

private val DayPart.hourMask: Int get() = ((1 shl 6) - 1) shl startHour

/** Every hour from [start] up to [end], going past midnight when [end] is not after it. */
private fun windowMask(start: Int, end: Int): Int {
    val length = ((end - start) % 24 + 24) % 24
    var mask = 0
    repeat(if (length == 0) 24 else length) { mask = mask or (1 shl ((start + it) % 24)) }
    return mask
}

/** The chosen hours as runs (start, end), in the order they come round from a free hour. */
private fun hourRuns(mask: Int): List<Pair<Int, Int>> {
    if (mask == 0 || mask == AllHours) return listOf(0 to 24)
    val free = (0 until 24).first { mask and (1 shl it) == 0 }
    val runs = ArrayList<Pair<Int, Int>>()
    var start = -1
    for (step in 1..24) {
        val hour = (free + step) % 24
        val on = mask and (1 shl hour) != 0
        if (on && start < 0) start = hour
        if (!on && start >= 0) {
            runs += start to hour
            start = -1
        }
    }
    return runs
}

/** Time in range over the chosen hours, weighted by readings, and how many readings. */
private fun windowTir(hourly: List<HourlyGlucoseStats>, mask: Int): Pair<TimeInRangeBreakdown, Int> {
    var count = 0
    var veryLow = 0f
    var low = 0f
    var inRange = 0f
    var high = 0f
    var veryHigh = 0f
    hourly.forEach { hour ->
        if (mask and (1 shl hour.hour) == 0 || hour.sampleCount == 0) return@forEach
        val n = hour.sampleCount
        count += n
        veryLow += hour.tir.veryLowPercent * n
        low += hour.tir.lowPercent * n
        inRange += hour.tir.inRangePercent * n
        high += hour.tir.highPercent * n
        veryHigh += hour.tir.veryHighPercent * n
    }
    if (count == 0) return TimeInRangeBreakdown() to 0
    return TimeInRangeBreakdown(veryLow / count, low / count, inRange / count, high / count, veryHigh / count) to count
}

private fun formatWindow(mask: Int): String =
    hourRuns(mask).joinToString(", ") { (start, end) -> "${formatHour(start)}–${formatHour(if (end == 0) 24 else end)}" }

/**
 * The day's four six-hour blocks, each with its own time in range, coloured on the same
 * scale as the headline, over a faint wash of its own band mix. They compare at a glance
 * without a tap. Any of them can be chosen at once, and the card re-pours for those hours.
 *
 * Smaller than the range buttons above the card (32 against 40dp) so the two never read
 * as one control, and at the foot of the card, since it refines what is above it.
 */
@Composable
private fun DayPartStrip(
    hourly: List<HourlyGlucoseStats>,
    mask: Int,
    onToggle: (DayPart) -> Unit
) {
    val inRangeWord = stringResource(R.string.in_range)
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val labelStyleChecked = MaterialTheme.typography.labelLargeEmphasized.copy(fontFeatureSettings = "tnum")
    // 32dp, the extra-small button height, unless a larger font needs more for its line.
    val stripHeight = maxOf(32.dp, with(LocalDensity.current) { labelStyle.lineHeight.toDp() } + 12.dp)
    Row(
        modifier = Modifier.fillMaxWidth().height(stripHeight),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        DayPart.entries.forEachIndexed { index, part ->
            val block = part.hourMask
            val (tir, count) = windowTir(hourly, block)
            val hasData = count > 0
            val checked = mask != 0 && mask and block == block
            val value = if (hasData) String.format(Locale.getDefault(), "%.0f%%", tir.inRangePercent) else "–"
            val name = stringResource(part.labelResId)
            val description = "$name, ${formatWindow(block)}, $value $inRangeWord"
            val wash by animateFloatAsState(
                targetValue = if (checked) StripWashCheckedAlpha else StripWashAlpha,
                animationSpec = ExpressiveMotion.defaultEffects(),
                label = "stripWash"
            )
            val content = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            // A chosen block lifts its container a step and brings its number towards the
            // text colour, so the choice reads at a glance over the wash.
            val resting = MaterialTheme.colorScheme.surfaceContainerHighest
            val container = if (checked) lerp(resting, MaterialTheme.colorScheme.onSurface, 0.10f) else resting
            val heat = if (hasData) tirHeatColor(tir.inRangePercent) else Color.Unspecified
            val valueColor = when {
                !hasData -> Color.Unspecified
                checked -> lerp(heat, MaterialTheme.colorScheme.onSurface, 0.30f)
                else -> heat.copy(alpha = 0.85f)
            }
            ToggleButton(
                checked = checked,
                onCheckedChange = { onToggle(part) },
                // A block with no readings has nothing to show.
                enabled = hasData,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    DayPart.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    containerColor = container,
                    contentColor = content,
                    checkedContainerColor = container,
                    checkedContentColor = content,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // The block's own band mix, low to high, as a faint wash.
                        .drawBehind { if (hasData) drawBandWash(tir, wash) }
                        .clearAndSetSemantics { contentDescription = description },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = part.symbol, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = value,
                        style = if (checked) labelStyleChecked else labelStyle,
                        color = valueColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawBandWash(tir: TimeInRangeBreakdown, alpha: Float) {
    val parts = listOf(
        tir.veryLowPercent to TirVeryLowColor,
        tir.lowPercent to TirLowColor,
        tir.inRangePercent to TirInRangeColor,
        tir.highPercent to TirHighColor,
        tir.veryHighPercent to TirVeryHighColor
    )
    val total = parts.sumOf { it.first.toDouble() }.toFloat()
    if (total <= 0f) return
    var x = 0f
    parts.forEach { (percent, color) ->
        val width = size.width * percent / total
        if (width > 0f) drawRect(color.copy(alpha = alpha), topLeft = Offset(x, 0f), size = Size(width, size.height))
        x += width
    }
}

/**
 * The chosen hours as a button that opens a window of any hours, and a way back to the
 * whole day. Picking blocks above and setting the window here edit the same hours.
 */
@Composable
private fun TimeWindowControl(
    mask: Int,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onWindowChange: (start: Int, end: Int) -> Unit,
    onClear: () -> Unit
) {
    val runs = hourRuns(mask)
    // The sliders keep their own ends while the mask is still theirs, so Start meeting End
    // (a whole day) doesn't snap them to 00:00–24:00 mid-drag. Otherwise several runs open
    // as the window that covers them all.
    var ends by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val (start, end) = ends?.takeIf { windowMask(it.first, it.second) == mask }
        ?: (runs.first().first to runs.last().second)
    val setWindow = { s: Int, e: Int -> ends = s to e; onWindowChange(s, e) }
    val clearLabel = stringResource(R.string.clear)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = { onEditingChange(!editing) },
                shapes = ButtonDefaults.shapes(),
                // Shrinks and wraps before it can push Clear off the line.
                modifier = Modifier.weight(1f, fill = false).heightIn(min = 32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Icon(imageVector = Symbols.ScheduleFilled, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatWindow(mask),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (editing) Symbols.ExpandLessFilled else Symbols.ExpandMoreFilled,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButtonTooltip(clearLabel) {
                IconButton(onClick = onClear, shapes = IconButtonDefaults.shapes()) {
                    Icon(imageVector = Symbols.CloseFilled, contentDescription = clearLabel)
                }
            }
        }
        AnimatedVisibility(
            visible = editing,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsSlider(
                    title = stringResource(R.string.start),
                    valueText = formatHour(start),
                    value = start.toFloat(),
                    onValueChange = { setWindow(it.roundToInt(), end) },
                    range = 0f..23f,
                    steps = 22
                )
                SettingsSlider(
                    title = stringResource(R.string.end),
                    valueText = formatHour(if (end == 0) 24 else end),
                    value = (if (end == 0) 24 else end).toFloat(),
                    onValueChange = { setWindow(start, it.roundToInt()) },
                    range = 1f..24f,
                    steps = 22
                )
            }
        }
    }
}

@Composable
private fun RangeFlow(
    rows: List<TirBandRow>,
    inRangeDelta: Float?,
    spanMinutes: Int,
    multiDay: Boolean,
    noData: Boolean,
    selectedBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit,
    modifier: Modifier = Modifier
) {
    // Read in the draw phase only: a range change re-pours the bar without recomposing.
    val shares = bandShares(rows)
    val presences = bandPresences(rows)
    val interactions = rows.map { row -> key(row.band) { remember { MutableInteractionSource() } } }
    val tints = bandTints(rows, selectedBand, interactions)
    val colors = rows.map { it.color }
    val geometry = remember { mutableStateOf<FlowGeometry?>(null) }

    Layout(
        modifier = modifier.drawBehind {
            geometry.value?.let { drawFlow(it, shares, presences, tints, colors) }
        },
        content = {
            rows.forEachIndexed { index, row -> key(row.band) {
                BandRow(
                    row = row,
                    hero = row.band == TirBand.IN_RANGE,
                    inRangeDelta = inRangeDelta,
                    spanMinutes = spanMinutes,
                    multiDay = multiDay,
                    noData = noData,
                    selected = selectedBand == row.band,
                    interactionSource = interactions[index],
                    onClick = { onBandSelected(if (selectedBand == row.band) null else row.band) }
                )
            } }
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

/** Each band's animated share of the time; zero for an empty band. */
@Composable
private fun bandShares(rows: List<TirBandRow>): List<State<Float>> = rows.map { row -> key(row.band) {
    animateFloatAsState(
        targetValue = if (row.empty) 0f else row.percent.coerceIn(0f, 100f),
        animationSpec = ExpressiveMotion.slowEffects(),
        label = "bandShare"
    )
} }

/**
 * Whether a band has a segment at all. Animated on its own, so a band arriving from 0%
 * grows into its minimum size while a tiny share keeps the full minimum once there.
 */
@Composable
private fun bandPresences(rows: List<TirBandRow>): List<State<Float>> = rows.map { row -> key(row.band) {
    animateFloatAsState(
        targetValue = if (row.empty) 0f else 1f,
        animationSpec = ExpressiveMotion.slowEffects(),
        label = "bandPresence"
    )
} }

/** Each band row's tint, by its share of the time, the highlight and a press. */
@Composable
private fun bandTints(
    rows: List<TirBandRow>,
    selectedBand: TirBand?,
    interactions: List<MutableInteractionSource>
): List<State<Float>> {
    return rows.mapIndexed { index, row -> key(row.band) {
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
    } }
}

/** Each band's segment on the bar, top to bottom, or null for a band with no time. */
private fun Density.segmentSpans(
    shares: List<State<Float>>,
    presences: List<State<Float>>,
    height: Float
): List<Pair<Float, Float>?> {
    val values = shares.map { it.value.coerceAtLeast(0f) }
    val presence = presences.map { it.value.coerceIn(0f, 1f) }
    val shown = values.indices.map { values[it] > 0f || presence[it] > 0f }
    val present = shown.count { it }
    if (present == 0) return values.map { null }
    val gap = FlowSegmentGap.toPx()
    val minSegment = FlowMinSegment.toPx()
    val floors = presence.map { minSegment * it }
    val usable = (height - gap * (present - 1)).coerceAtLeast(0f)
    val spare = (usable - floors.sum()).coerceAtLeast(0f)
    val total = values.sum().coerceAtLeast(0.0001f)
    var top = 0f
    return values.indices.map { i ->
        if (!shown[i]) {
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
    presences: List<State<Float>>,
    tints: List<State<Float>>,
    colors: List<Color>
) {
    val barRight = FlowBarWidth.toPx()
    val rowLeft = barRight + FlowRibbonWidth.toPx()
    val spans = segmentSpans(shares, presences, size.height)
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
    spanMinutes: Int,
    multiDay: Boolean,
    noData: Boolean,
    selected: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    inset: Dp = FlowRibbonWidth + 12.dp,
    surface: Shape = RowSurface,
    percentColor: Color? = null,
    showTime: Boolean = true
) {
    // Hours with no readings have no split to show, rather than a row of zeroes.
    val percentText = if (noData) "–" else String.format(Locale.getDefault(), "%.1f%%", row.percent)
    val minutes = (row.percent.coerceIn(0f, 100f) / 100f * spanMinutes).roundToInt()
    val time = if (!showTime || row.empty || (multiDay && row.band == TirBand.IN_RANGE)) null else bandDurationText(minutes)
    // An empty band steps right back: it has nothing to say beyond its threshold.
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val textColor = if (row.empty) quiet else MaterialTheme.colorScheme.onSurface
    // The band's full colour, which the tint no longer carries at a low share. Pulled
    // towards the text colour on a light surface, where yellow alone is unreadable.
    val dark = isAppInDarkTheme()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val rangeColor = remember(row.color, dark, onSurface, row.empty) {
        val base = if (dark) row.color else lerp(row.color, onSurface, 0.35f)
        if (row.empty) base.copy(alpha = 0.45f) else base
    }

    val padding = Modifier
        .heightIn(min = if (hero) 72.dp else 48.dp)
        .padding(
            start = inset,
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
            color = if (row.empty) quiet else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // The whole row, ribbon included, is the touch target; only the ripple is kept to the
    // row itself, while the canvas brightens the ribbon with the rest of the band.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // A band with no time has nothing to highlight, so it is not a target.
                if (row.empty) {
                    Modifier
                } else {
                    Modifier.selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = onClick
                    )
                }
            )
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(surface)
                .indication(interactionSource, ripple())
        )
        if (hero) {
            HeroRowLayout(
                modifier = Modifier.fillMaxWidth().then(padding),
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
                            color = if (noData) quiet else tirHeatColor(row.percent),
                            maxLines = 1,
                            softWrap = false
                        )
                        inRangeDelta?.takeIf { abs(it) >= 1f }?.let { InRangeDelta(it) }
                    }
                }
            )
        } else {
            // Label and share in the same weight, so neither shouts over the other.
            val bandStyle = MaterialTheme.typography.titleSmall
            Row(
                modifier = Modifier.fillMaxWidth().then(padding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = row.label, style = bandStyle, color = textColor)
                    detail()
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = percentText,
                    style = bandStyle.copy(fontFeatureSettings = "tnum"),
                    color = percentColor ?: textColor,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End
                )
            }
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

/**
 * A band's time, coarser as it grows: "23 h 30 min" within a day, whole hours up to three
 * days, then "3 d 20 h", and whole days from a week on, a whole number of weeks named so.
 */
@Composable
private fun bandDurationText(minutes: Int): String {
    val hours = minutes / 60
    val days = hours / 24
    return when {
        hours < 24 -> durationText(minutes)
        hours < 72 -> stringResource(R.string.stats_duration_hours, hours)
        days < 7 -> if (hours % 24 == 0) {
            stringResource(R.string.stats_duration_days, days)
        } else {
            stringResource(R.string.stats_duration_days_hours, days, hours % 24)
        }
        days % 7 == 0 -> pluralStringResource(R.plurals.stats_duration_weeks, days / 7, days / 7)
        else -> stringResource(R.string.stats_duration_days, days)
    }
}

/*
 * The ring, for people who would rather see the window as a dial. Same card, same rows,
 * strip and hour window; only the bar and its ribbons give way to a circle.
 *
 * What the old ring got wrong, and this one does not:
 * - Its rows were a 32dp list of label, range and band-coloured percent, too small to tap
 *   and loud even at 0.0%. These are the flow's own rows: 48dp, tinted by share, and a band
 *   with no time is left out.
 * - Adjacent arcs were split by a hairline of a degree, so two reds ran together. Arcs now
 *   keep a gap of fixed width and slightly rounded corners, like the bar's segments, and a
 *   tiny share keeps a small block of its own.
 * The ring itself stays the old one: "TIR" in the middle with its number in the heat
 * colour, lows first clockwise from the top, and each band's percentage in its own colour.
 * It takes whatever width the rows leave, so it is as large as the card allows; and a row
 * never stacks to three lines: when range and time cannot share a line, the times go.
 */

private val RingStroke = 16.dp
/** The visible gap between two arcs, whatever the ring's size; the bar's, a touch wider. */
private val RingGap = 2.dp
/** Each arc's corners, rounded like the bar's inner corners rather than capped into pills. */
private val RingCorner = 3.dp
private val RingMin = 104.dp
private val RingMax = 168.dp
private val RingSpacing = 12.dp
/**
 * Below this the rows would be squeezed, so the ring goes above them instead. Low on
 * purpose: a ring alone on its own line was what people disliked about the old one.
 */
private val RingRowsMin = 168.dp
private const val RingDimmedAlpha = 0.32f
/** Between the ring and the change when it sits outside, so the two do not touch. */
private val RingDeltaGap = 8.dp

@Composable
private fun RangeRing(
    rows: List<TirBandRow>,
    inRangeDelta: Float?,
    spanMinutes: Int,
    multiDay: Boolean,
    noData: Boolean,
    selectedBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit,
    modifier: Modifier = Modifier
) {
    // The ring runs clockwise from the top through the lows, in range and the highs, as it
    // always did, so the two ends of the scale meet at twelve o'clock; the list reads high
    // to low.
    val ringRows = rows.reversed()
    val shares = bandShares(ringRows)
    val presences = bandPresences(ringRows)
    val interactions = rows.map { row -> key(row.band) { remember { MutableInteractionSource() } } }
    val tints = bandTints(rows, selectedBand, interactions)
    val rowShape = RoundedCornerShape(FlowRowCorner)
    val inRange = rows.firstOrNull { it.band == TirBand.IN_RANGE }?.percent ?: 0f

    // Each band's percentage in its own colour, as on the old ring, nudged towards the text
    // on a light surface, where yellow alone is unreadable.
    val dark = isAppInDarkTheme()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val tones = rows.map { row ->
        when {
            noData || row.empty -> quiet
            dark -> row.color
            else -> lerp(row.color, onSurface, 0.2f)
        }
    }

    // What each row needs on one line beside its percentage, to size the ring from.
    val times = rows.map { row ->
        val minutes = (row.percent.coerceIn(0f, 100f) / 100f * spanMinutes).roundToInt()
        if (row.empty || (multiDay && row.band == TirBand.IN_RANGE)) null else bandDurationText(minutes)
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.titleSmall
    val detailStyle = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    val separator = " · "
    val needs = remember(rows, times, density, labelStyle, detailStyle) {
        fun w(text: String, style: TextStyle) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
        val percentWidth = w(String.format(Locale.getDefault(), "%.1f%%", 100f), labelStyle.copy(fontFeatureSettings = "tnum"))
        // The row's 12dp padding either side and the 12dp before its percentage.
        val chrome = with(density) { 36.dp.roundToPx() }
        fun need(withTimes: Boolean) = rows.indices.maxOf { i ->
            val detail = times[i]?.takeIf { withTimes }?.let { rows[i].rangeLabel + separator + it } ?: rows[i].rangeLabel
            maxOf(w(rows[i].label, labelStyle), w(detail, detailStyle)) + percentWidth + chrome
        }
        with(density) { need(true).toDp() to need(false).toDp() }
    }

    val list: @Composable (Modifier, Boolean) -> Unit = { listModifier, showTimes ->
        Column(modifier = listModifier, verticalArrangement = Arrangement.spacedBy(FlowRowGap)) {
            rows.forEachIndexed { index, row -> key(row.band) {
                Box(
                    modifier = Modifier.drawBehind {
                        val outline = rowShape.createOutline(size, layoutDirection, this)
                        drawOutline(outline, row.color.copy(alpha = tints[index].value))
                    }
                ) {
                    BandRow(
                        row = row,
                        hero = false,
                        inRangeDelta = null,
                        spanMinutes = spanMinutes,
                        multiDay = multiDay,
                        noData = noData,
                        selected = selectedBand == row.band,
                        interactionSource = interactions[index],
                        onClick = { onBandSelected(if (selectedBand == row.band) null else row.band) },
                        inset = 12.dp,
                        surface = rowShape,
                        percentColor = tones[index],
                        showTime = showTimes
                    )
                }
            } }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val (withTimes, withoutTimes) = needs
        // Times stay only if the rows can keep them on one line beside the smallest ring.
        val timesFit = maxWidth - RingMin - RingSpacing >= withTimes
        val rowsNeed = if (timesFit) withTimes else withoutTimes
        val stacked = maxWidth - RingMin - RingSpacing < maxOf(rowsNeed, RingRowsMin)
        // The ring takes whatever the rows leave, so the two fill the card as one block.
        val size = (maxWidth - RingSpacing - rowsNeed).coerceIn(RingMin, RingMax)
        val stackedSize = RingMax.coerceAtMost(maxWidth)
        val dial: @Composable (Dp, Boolean) -> Unit = { ringSize, centred ->
            RingDial(
                rows = ringRows,
                inRange = inRange,
                inRangeDelta = inRangeDelta?.takeIf { !noData && abs(it) >= 1f },
                shares = shares,
                presences = presences,
                noData = noData,
                selectedBand = selectedBand,
                onBandSelected = onBandSelected,
                ringSize = ringSize,
                centred = centred
            )
        }
        if (stacked) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                dial(stackedSize, false)
                Spacer(modifier = Modifier.height(RingSpacing))
                list(Modifier.fillMaxWidth(), true)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dial(size, true)
                Spacer(modifier = Modifier.width(RingSpacing))
                list(Modifier.weight(1f), timesFit)
            }
        }
    }
}

/** Each band's slot on the ring as start angle and sweep, clockwise from the top. */
private fun ringSlots(
    shares: List<State<Float>>,
    presences: List<State<Float>>,
    minSweep: Float
): List<Pair<Float, Float>?> {
    val values = shares.map { it.value.coerceAtLeast(0f) }
    val presence = presences.map { it.value.coerceIn(0f, 1f) }
    val shown = values.indices.map { values[it] > 0f || presence[it] > 0f }
    if (shown.none { it }) return values.map { null }
    // A floor, not an addition: a small band is lifted to the minimum and no further, so
    // 0.2% and 2% still differ, and the larger bands give up the difference.
    val total = values.sum().coerceAtLeast(0.0001f)
    val raw = values.map { it / total * 360f }
    val floors = presence.map { minSweep * it }
    val lifted = raw.indices.filter { shown[it] && raw[it] < floors[it] }.toSet()
    val free = raw.indices.filter { shown[it] && it !in lifted }
    val room = (360f - lifted.sumOf { floors[it].toDouble() }.toFloat()).coerceAtLeast(0f)
    val freeTotal = free.sumOf { raw[it].toDouble() }.toFloat().coerceAtLeast(0.0001f)
    var start = -90f
    return values.indices.map { i ->
        if (!shown[i]) {
            null
        } else {
            val sweep = if (i in lifted) floors[i] else raw[i] / freeTotal * room
            (start to sweep).also { start += sweep }
        }
    }
}

@Composable
private fun RingDial(
    rows: List<TirBandRow>,
    inRange: Float,
    inRangeDelta: Float?,
    shares: List<State<Float>>,
    presences: List<State<Float>>,
    noData: Boolean,
    selectedBand: TirBand?,
    onBandSelected: (TirBand?) -> Unit,
    ringSize: Dp,
    centred: Boolean
) {
    val colors = rows.map { it.color }
    val bands = rows.map { it.band }
    val dims = rows.map { row -> key(row.band) {
        animateFloatAsState(
            targetValue = if (selectedBand == null || selectedBand == row.band) 1f else RingDimmedAlpha,
            animationSpec = ExpressiveMotion.fastEffects(),
            label = "ringDim"
        )
    } }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val latestBands by rememberUpdatedState(bands)
    val latestSelected by rememberUpdatedState(selectedBand)
    val latestSelect by rememberUpdatedState(onBandSelected)

    // The old ring's centre: time in range under "TIR", only the number in the heat colour.
    val caption = stringResource(R.string.tir)
    val value = if (noData) "–" else String.format(Locale.getDefault(), "%.0f%%", inRange)
    val number = if (noData) "" else NumberFormat.getIntegerInstance().format(inRange.roundToInt())
    val tone = tirHeatColor(inRange)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val change = inRangeDelta?.let { stringResource(R.string.gri_vs_previous, formatSignedNumber(it)) }

    val inner = (ringSize - RingStroke * 2 - 4.dp).coerceAtLeast(0.dp)
    // The old ring's sizes, stepping down only when this circle cannot hold them.
    val styles = listOf(
        MaterialTheme.typography.headlineLarge,
        MaterialTheme.typography.headlineMedium,
        MaterialTheme.typography.headlineSmall,
        MaterialTheme.typography.titleLarge,
        MaterialTheme.typography.titleMediumEmphasized
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val captions = listOf(
        MaterialTheme.typography.titleMedium,
        MaterialTheme.typography.labelLarge,
        MaterialTheme.typography.labelMedium,
        MaterialTheme.typography.labelSmall
    )
    val deltaStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val deltaSlot = with(density) { maxOf(16.dp, deltaStyle.lineHeight.toDp()) }
    // The largest headline and caption whose block fits inside the hole; the caption gives
    // way first, since the number is what the ring is for. The change never costs them a
    // size: it sits above the number when there is room at that size, else under the ring.
    // Its slot is held whether it shows or not, and decided by the ring's size alone, so
    // nothing moves or resizes when it appears.
    val centre = remember(value, caption, inner, density, styles, captions, deltaStyle, deltaSlot) {
        val radius = with(density) { inner.toPx() } / 2f
        val slot = with(density) { deltaSlot.toPx() }
        val slotWidth = measurer.measure("88", deltaStyle, maxLines = 1).size.width +
            with(density) { 16.dp.toPx() }
        val pairs = styles.flatMap { style -> captions.map { style to it } }
        val sizes = pairs.map { (style, captionStyle) ->
            val number = measurer.measure(value, style.copy(fontFeatureSettings = "tnum"), maxLines = 1).size
            val label = measurer.measure(caption, captionStyle, maxLines = 1).size
            maxOf(number.width, label.width) / 2f to (number.height + label.height) / 2f
        }
        val fit = sizes.indexOfFirst { (halfWidth, halfHeight) ->
            kotlin.math.hypot(halfWidth, halfHeight) <= radius
        }.takeIf { it >= 0 } ?: pairs.lastIndex
        val halfHeight = sizes[fit].second
        Triple(pairs[fit].first, pairs[fit].second, kotlin.math.hypot(slotWidth / 2f, halfHeight + slot) <= radius)
    }
    val (valueStyle, captionStyle, deltaInside) = centre
    val deltaSlotContent: @Composable () -> Unit = {
        Box(modifier = Modifier.height(deltaSlot), contentAlignment = Alignment.Center) {
            inRangeDelta?.let { InRangeDelta(it) }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Held above too when beside the rows, so the ring stays centred against them.
        if (!deltaInside && centred) Spacer(modifier = Modifier.height(deltaSlot + RingDeltaGap))
        Box(
            modifier = Modifier
                .size(ringSize)
                .clearAndSetSemantics {
                    contentDescription = "$value $caption"
                    change?.let { stateDescription = it }
                }
                .drawBehind {
                    val stroke = RingStroke.toPx()
                    val radius = (size.minDimension - stroke) / 2f
                    if (radius <= 0f) return@drawBehind
                    val topLeft = Offset(center.x - radius, center.y - radius)
                    val arcSize = Size(radius * 2f, radius * 2f)
                    val toDegrees = 180f / Math.PI.toFloat()
                    val gap = RingGap.toPx() / radius * toDegrees
                    val corner = RingCorner.toPx()
                    val inner = radius - stroke / 2f
                    val outer = radius + stroke / 2f
                    // Small enough that 0.2% and 2% still differ; a sliver this thin rounds its
                    // corners less, below.
                    val minSweep = gap + FlowMinSegment.toPx() * 1.5f / inner * toDegrees
                    val slots = ringSlots(shares, presences, minSweep = minSweep)
                    val present = slots.count { it != null }
                    if (present == 0) {
                        drawCircle(track, radius, center, style = Stroke(stroke))
                        return@drawBehind
                    }
                    slots.forEachIndexed { i, slot ->
                        if (slot == null) return@forEachIndexed
                        val color = colors[i].copy(alpha = dims[i].value)
                        if (present == 1) {
                            drawCircle(color, radius, center, style = Stroke(stroke))
                            return@forEachIndexed
                        }
                        // The sector, shrunk by the corner radius on every side, then filled and
                        // stroked with a round join: that gives it corners of exactly that radius.
                        val start = slot.first + gap / 2f
                        val sweep = slot.second - gap
                        // Never more than a third of the arc's narrow edge, so a sliver stays a sliver.
                        val c = corner.coerceAtMost(sweep / toDegrees * inner / 3f)
                        val ro = outer - c
                        val ri = inner + c
                        val co = c / ro * toDegrees
                        val ci = c / ri * toDegrees
                        val outerSweep = (sweep - co * 2f).coerceAtLeast(0.01f)
                        val innerSweep = (sweep - ci * 2f).coerceAtLeast(0.01f)
                        val mid = start + sweep / 2f
                        val path = Path().apply {
                            arcTo(
                                Rect(center.x - ro, center.y - ro, center.x + ro, center.y + ro),
                                mid - outerSweep / 2f, outerSweep, true
                            )
                            arcTo(
                                Rect(center.x - ri, center.y - ri, center.x + ri, center.y + ri),
                                mid + innerSweep / 2f, -innerSweep, false
                            )
                            close()
                        }
                        drawPath(path, color)
                        drawPath(path, color, style = Stroke(width = c * 2f, join = StrokeJoin.Round))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val stroke = RingStroke.toPx()
                        val radius = (size.width.coerceAtMost(size.height) - stroke) / 2f
                        val dx = tap.x - size.width / 2f
                        val dy = tap.y - size.height / 2f
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                        // The ring and a finger's width either side of it; the hole clears.
                        if (abs(distance - radius) > stroke / 2f + 12.dp.toPx()) {
                            latestSelect(null)
                            return@detectTapGestures
                        }
                        val angle = (kotlin.math.atan2(dy, dx) * 180f / Math.PI.toFloat() + 450f) % 360f
                        val slots = ringSlots(shares, presences, minSweep = 0f)
                        val hit = slots.indexOfFirst { slot ->
                            slot != null && angle >= slot.first + 90f && angle < slot.first + 90f + slot.second
                        }
                        val band = latestBands.getOrNull(hit)
                        latestSelect(if (band == null || band == latestSelected) null else band)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (deltaInside) deltaSlotContent()
                Text(
                    text = buildAnnotatedString {
                        append(value)
                        val at = if (number.isEmpty()) -1 else value.indexOf(number)
                        if (at >= 0) addStyle(SpanStyle(color = tone), at, at + number.length)
                    },
                    style = valueStyle.copy(fontFeatureSettings = "tnum"),
                    color = muted,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = caption,
                    style = captionStyle,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = inner)
                )
                if (deltaInside) Spacer(modifier = Modifier.height(deltaSlot))
            }
        }
        if (!deltaInside) {
            Spacer(modifier = Modifier.height(RingDeltaGap))
            deltaSlotContent()
        }
    }
}
