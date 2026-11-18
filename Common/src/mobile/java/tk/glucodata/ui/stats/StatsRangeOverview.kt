package tk.glucodata.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.util.ExpressiveMotion
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt

private data class OverviewBand(val label: String, val range: String, val share: Float, val color: Color)

@Composable
internal fun GlycemicOverviewCard(
    summary: StatsSummary, targets: StatsTargets, unit: GlucoseUnit, modifier: Modifier = Modifier
) {
    var selectedPart by rememberSaveable { mutableStateOf<DayPart?>(null) }
    val parts = summary.dayParts
    val active = parts.firstOrNull { it.part == selectedPart && it.readingCount > 0 }
    val tir = active?.tir ?: summary.tir
    var selectedBand by rememberSaveable(active?.part) { mutableStateOf<Int?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val percent = remember(locale) {
        NumberFormat.getPercentInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
    }
    val low = formatMgDl(targets.lowMgDl, unit)
    val high = formatMgDl(targets.highMgDl, unit)
    val veryLow = formatMgDl(targets.veryLowMgDl, unit)
    val veryHigh = formatMgDl(targets.veryHighMgDl, unit)
    val bands = listOf(
        OverviewBand(stringResource(R.string.very_high), "≥ $veryHigh", tir.veryHighPercent, TirVeryHighColor),
        OverviewBand(stringResource(R.string.high_range), "$high–$veryHigh", tir.highPercent, TirHighColor),
        OverviewBand(stringResource(R.string.in_range), "$low–$high", tir.inRangePercent, TirInRangeColor),
        OverviewBand(stringResource(R.string.low_range), "$veryLow–$low", tir.lowPercent, TirLowColor),
        OverviewBand(stringResource(R.string.very_low), "< $veryLow", tir.veryLowPercent, TirVeryLowColor)
    )
    val band = selectedBand?.let(bands::get)
    val hours = if (active == null) 24 else 6
    val minutes = ((band?.share ?: tir.inRangePercent) / 100f * hours * 60).roundToInt()
    val duration = "≈ ${durationText(minutes)} / ${durationText(hours * 60)}"
    val comparison = summary.comparison
    val delta = if (active == null) comparison?.inRangeDelta else {
        comparison?.previous?.dayParts?.firstOrNull {
            it.part == active.part && active.readingCount >= StatsViewModel.MIN_COMPARISON_READINGS &&
                it.readingCount >= active.readingCount * StatsViewModel.MIN_COMPARISON_COVERAGE
        }?.let { active.tir.inRangePercent - it.tir.inRangePercent }
    }
    Card(modifier.fillMaxWidth(), shape = StatsCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TimeBlockHeader(parts, active?.part, summary.tir, percent,
                band?.let { "${it.label} · $duration" } ?: duration) {
                selectedPart = it
                selectedBand = null
            }
            Spacer(Modifier.height(8.dp))
            RangeProfile(bands, percent, selectedBand, delta, { selectedBand = it })
        }
    }
}

@Composable
private fun RangeProfile(
    bands: List<OverviewBand>, percent: NumberFormat, selected: Int?, delta: Float?, onSelect: (Int?) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyMedium
    val range = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val value = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum")
    val headline = MaterialTheme.typography.headlineSmallEmphasized.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val valuesWidth = with(density) {
        bands.mapIndexed { index, band ->
            measurer.measure(percent.format(band.share / 100.0), if (index == 2) headline else value).size.width
        }.max().toDp()
    } + 4.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inline = with(density) {
            val labels = bands.maxOf { measurer.measure(it.label, body, softWrap = false).size.width }
            val ranges = bands.maxOf { measurer.measure(it.range, range, softWrap = false).size.width }
            (labels + ranges).toDp() + valuesWidth + 88.dp <= maxWidth
        }
        Column(Modifier.fillMaxWidth().padding(start = 56.dp)) {
            bands.forEachIndexed { index, band ->
                val ink = remember(band.color, scheme.surfaceContainerHigh, scheme.onSurface) {
                    readableBandColor(band.color, scheme.surfaceContainerHigh, scheme.onSurface)
                }
                val fill by animateColorAsState(
                    if (selected == index) ink.copy(alpha = 0.14f) else Color.Transparent,
                    ExpressiveMotion.defaultEffects(), label = "band background"
                )
                val modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fill)
                    .selectable(selected == index, role = Role.Button) { onSelect(index.takeUnless { selected == it }) }
                    .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp)
                val numberStyle = if (index == 2) headline else value
                val number = percent.format(band.share / 100.0)
                val labelNeedsOwnLine = !inline && with(density) {
                    val labelWord = measurer.measure(band.label, body).multiParagraph.intrinsics.minIntrinsicWidth.toDp()
                    val numberWidth = measurer.measure(number, numberStyle).size.width.toDp()
                    labelWord + numberWidth + 80.dp > this@BoxWithConstraints.maxWidth
                }
                val valueColumn: @Composable () -> Unit = {
                    Column(if (inline) Modifier.width(valuesWidth) else Modifier, horizontalAlignment = Alignment.End) {
                        Text(number, style = numberStyle, color = ink)
                        if (index == 2 && delta != null && abs(delta) >= 1f) ComparisonArrow(delta)
                    }
                }
                if (labelNeedsOwnLine) {
                    Column(modifier, verticalArrangement = Arrangement.Center) {
                        Text(band.label, style = body)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(band.range, Modifier.weight(1f), style = range, color = scheme.onSurfaceVariant)
                            valueColumn()
                        }
                    }
                } else {
                    Row(modifier, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(band.label, style = body)
                            if (!inline) Text(band.range, style = range, color = scheme.onSurfaceVariant)
                        }
                        if (inline) Text(band.range, style = range, color = scheme.onSurfaceVariant)
                        valueColumn()
                    }
                }
            }
        }
        RangeBar(bands, selected, onSelect, Modifier.matchParentSize().wrapContentWidth(Alignment.Start).width(48.dp))
    }
}

@Composable
private fun ComparisonArrow(delta: Float) {
    val locale = LocalConfiguration.current.locales[0]
    val description = stringResource(R.string.stats_tir_change, String.format(locale, "%+.1f", delta))
    val color = if (delta > 0f) TirInRangeColor else TirHighColor
    val ink = readableBandColor(color, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface)
    Row(Modifier.clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Icon(if (delta > 0f) Symbols.ArrowDropUpFilled else Symbols.ArrowDropDownFilled, null,
            modifier = Modifier.size(16.dp), tint = ink)
        Text(NumberFormat.getIntegerInstance(locale).format(abs(delta).roundToInt()),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = ink)
    }
}

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
private fun RangeBar(bands: List<OverviewBand>, selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier) {
    val sizes = bands.map { animateFloatAsState(it.share, ExpressiveMotion.slowEffects(), label = "band share") }
    val colors = bands.mapIndexed { index, band ->
        animateColorAsState(band.color.copy(alpha = if (selected == null || selected == index) 1f else 0.32f),
            ExpressiveMotion.defaultEffects(), label = "band emphasis")
    }
    val select by rememberUpdatedState(onSelect)
    val selection by rememberUpdatedState(selected)
    fun bandAt(fraction: Float): Int? {
        var position = fraction.coerceIn(0f, 0.999999f) * sizes.sumOf { it.value.toDouble() }.toFloat()
        return sizes.indices.firstOrNull { position -= sizes[it].value; position < 0f }
    }
    Canvas(modifier.clip(RoundedCornerShape(12.dp))
        .pointerInput(Unit) { detectTapGestures { tap ->
            val index = bandAt(tap.y / size.height)
            select(index.takeUnless { it == selection })
        } }
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { select(bandAt(it.y / size.height)) }) { change, _ ->
                change.consume()
                select(bandAt(change.position.y / size.height))
            }
        }) {
        val total = sizes.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        var y = 0f
        sizes.forEachIndexed { index, share ->
            val height = size.height * share.value / total
            drawRect(colors[index].value, Offset(0f, y), Size(size.width, height))
            y += height
        }
    }
}

@Composable
private fun TimeBlockHeader(
    parts: List<DayPartStats>, selected: DayPart?, overall: TimeInRangeBreakdown,
    percent: NumberFormat, detail: String, onSelect: (DayPart?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(-1) }
    val choices = listOf(null) + DayPart.entries
    val enabled = choices.map { part -> part == null || parts.any { it.part == part && it.readingCount > 0 } }
    val enabledNow by rememberUpdatedState(enabled)
    val pick by rememberUpdatedState(onSelect)
    val bounds = remember { arrayOfNulls<Rect>(choices.size) }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val allDay = stringResource(R.string.stats_all_day)
    val available = parts.any { it.readingCount > 0 }
    BackHandler(expanded) { expanded = false }
    val gesture = if (!available) Modifier else Modifier
        .onGloballyPositioned { anchor = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val dragged = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var moved = false
                    do {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        if (moved) change.consume()
                    } while (!moved)
                    moved
                } ?: true
                if (!dragged) return@awaitEachGesture
                expanded = true
                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        val point = anchor?.takeIf { it.isAttached }?.localToRoot(change.position) ?: break
                        hover = bounds.indices.firstOrNull { enabledNow[it] && bounds[it]?.contains(point) == true } ?: -1
                        change.consume()
                        if (!change.pressed) {
                            if (hover >= 0) { pick(choices[hover]); expanded = false }
                            break
                        }
                    }
                } finally { hover = -1 }
            }
        }.clickable(role = Role.Button) { expanded = !expanded }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(gesture).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.stats_card_overview), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMediumEmphasized)
            Crossfade(detail, animationSpec = ExpressiveMotion.defaultEffects(), label = "band duration") {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (available) {
            Text(selected?.let { stringResource(it.labelResId) } ?: allDay, style = MaterialTheme.typography.labelLarge)
            val angle by animateFloatAsState(if (expanded) 180f else 0f, ExpressiveMotion.fastSpatial(), label = "disclosure")
            Icon(Symbols.ExpandMoreFilled, null, Modifier.size(20.dp).rotate(angle))
        }
    }
    AnimatedVisibility(expanded && available) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            choices.forEachIndexed { index, part ->
                val split = if (part == null) overall else parts.firstOrNull { it.part == part && it.readingCount > 0 }?.tir
                val colors = MaterialTheme.colorScheme
                val fill = when { hover == index -> colors.secondaryContainer; part == selected -> colors.surfaceContainerHighest; else -> Color.Transparent }
                Row(Modifier.fillMaxWidth().onGloballyPositioned { bounds[index] = it.boundsInRoot() }
                    .clip(RoundedCornerShape(12.dp)).background(fill)
                    .selectable(part == selected, enabled = split != null, role = Role.RadioButton) {
                        onSelect(part); expanded = false
                    }.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(part?.let { stringResource(it.labelResId) } ?: allDay, style = MaterialTheme.typography.bodyMedium)
                        if (part != null) Text("${formatHour(part.startHour)}–${formatHour(part.startHour + 6)}",
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Text(split?.let { percent.format(it.inRangePercent / 100.0) } ?: "—",
                        style = MaterialTheme.typography.labelLargeEmphasized)
                }
            }
        }
    }
}
