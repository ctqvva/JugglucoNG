@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.restricted

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import tk.glucodata.R
import tk.glucodata.SensorVendor
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.ui.AnimatedHourglassIcon
import tk.glucodata.ui.CustomCalendarIcon
import tk.glucodata.ui.StatusPalette
import tk.glucodata.ui.lifetimeEndMs
import tk.glucodata.ui.viewmodel.SensorInfo
import tk.glucodata.ui.viewmodel.SensorViewModel

/** Starts before this are unset or bogus (1970); nothing about the wear is shown then. */
private const val PLAUSIBLE_START_MS = 1_577_836_800_000L // 2020-01-01
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24L * HOUR_MS

/** How the wear shows on a restricted card, in the dashboard sensor card's colours. */
internal class SensorLife(
    val progress: Float,
    val containerColor: Color,
    val contentColor: Color,
    val fillColor: Color,
    val daysText: String,
    val currentDay: Int,
    val hoursLeft: Long?,
    val startMs: Long,
    val endMs: Long,
    val nowMs: Long,
    /** An Anytime CT5 still running past its advisory expected end: not ended. */
    val overrun: Boolean,
)

/** WCAG contrast ratio between two opaque colours. */
private fun contrastRatio(a: Color, b: Color): Float {
    val (light, dark) = a.luminance().let { la -> b.luminance().let { lb -> if (la > lb) la to lb else lb to la } }
    return (light + 0.05f) / (dark + 0.05f)
}

/** Null when the start is unset or bogus: nothing about the wear is known then. */
@Composable
internal fun sensorLife(sensor: SensorInfo): SensorLife? {
    // Ticks each minute, so the fill, the day and the time left move on without a reading.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000L)
            value = System.currentTimeMillis()
        }
    }
    if (sensor.startMs <= PLAUSIBLE_START_MS) return null
    val endMs = sensor.lifetimeEndMs()
    // The dashboard's rule: an Anytime CT5's expected end is advisory, and it keeps running.
    val allowOverrun = sensor.isAnytime && sensor.expectedEndMs > 0L && !sensor.isSensorExpired
    val lifecycle = ManagedSensorStatusPolicy.resolveLifecycleSummary(
        startTimeMs = sensor.startMs,
        officialEndMs = endMs,
        expectedEndMs = endMs,
        allowExpectedEndOverrun = allowOverrun,
        nowMs = now,
    )
    val scheme = MaterialTheme.colorScheme
    val isExpiring = lifecycle.progress > 0.90f
    val container = if (isExpiring) {
        scheme.tertiaryContainer
    } else {
        scheme.primaryContainer.copy(alpha = 0.4f).compositeOver(scheme.background)
    }
    // The 40% tone is no longer primaryContainer, so its "on" colour is not guaranteed to read
    // on it: a vivid dynamic scheme leaves it mid-toned with a dark onPrimaryContainer. Take
    // whichever of that and onSurface stands out more from the tone actually drawn.
    val content = if (isExpiring) {
        scheme.onTertiaryContainer
    } else {
        listOf(scheme.onPrimaryContainer, scheme.onSurface).maxBy { contrastRatio(it, container) }
    }
    return SensorLife(
        progress = lifecycle.progress,
        containerColor = container,
        contentColor = content,
        fillColor = when {
            lifecycle.progress > 0.95f -> scheme.error
            lifecycle.progress > 0.80f -> scheme.tertiary
            else -> StatusPalette.sensorLifeFill
        }.copy(alpha = 0.25f),
        daysText = lifecycle.daysText,
        currentDay = lifecycle.currentDay,
        hoursLeft = lifecycle.remainingHours.takeIf { now < endMs && it in 0L..24L },
        startMs = sensor.startMs,
        endMs = endMs,
        nowMs = now,
        overrun = allowOverrun && now >= endMs,
    )
}

/** The dashboard's lifetime fill, from the start edge. */
internal fun Modifier.sensorLifeFill(life: SensorLife): Modifier = drawBehind {
    val width = size.width * life.progress
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    drawRect(color = life.fillColor, topLeft = Offset(left, 0f), size = Size(width, size.height))
}

/** "8 / 28" with the calendar, or the hours left with the hourglass in the last day. */
@Composable
internal fun SensorLifeDays(life: SensorLife, color: Color = life.contentColor) {
    val hoursLeft = life.hoursLeft
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = hoursLeft?.let { "${it}h" } ?: life.daysText,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        if (hoursLeft != null) {
            AnimatedHourglassIcon(
                color = color,
                modifier = Modifier.size(16.dp),
                cycleDuration = (500 + (1500 * (hoursLeft / 24f))).toInt().coerceAtLeast(500),
            )
        } else {
            CustomCalendarIcon(
                color = color,
                modifier = Modifier.size(16.dp),
                currentDay = life.currentDay,
            )
        }
    }
}

/** "17 days, 4 hours" or "17d 4h" (a "5h", a "12m") in the locale's own units and plurals. */
private fun sensorSpanText(locale: Locale, spanMs: Long, width: MeasureFormat.FormatWidth): String {
    val format = MeasureFormat.getInstance(locale, width)
    val days = spanMs / DAY_MS
    val hours = (spanMs % DAY_MS) / HOUR_MS
    return when {
        days > 0L -> format.formatMeasures(Measure(days, MeasureUnit.DAY), Measure(hours, MeasureUnit.HOUR))
        hours > 0L -> format.format(Measure(hours, MeasureUnit.HOUR))
        else -> format.format(Measure((spanMs / 60_000L).coerceAtLeast(0L), MeasureUnit.MINUTE))
    }
}

/**
 * The full card's wear block: the wear bar over the two dates, with one arrowhead cut into the
 * middle of both and pointed at the end date. It holds the day of wear, small and subdued since
 * the bar's age already says it, and the days the sensor is rated for, large and bold. Age and
 * start date sit behind the arrow, time left and end date ahead. It sits on the gap between
 * the bar and the dates with its point on the gap, and its hole is the stack's own edge: arrow
 * and hole ease into place together when the day turns, and on a tap.
 */
@Composable
internal fun SensorWear(life: SensorLife, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val ratedDays = ((life.endMs - life.startMs + DAY_MS / 2) / DAY_MS).coerceAtLeast(1L)
    val ratedSpoken = remember(locale, ratedDays) {
        MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).format(Measure(ratedDays, MeasureUnit.DAY))
    }
    val day = life.currentDay.coerceAtLeast(1)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // How far behind its place the pair is, as a share of the push.
    var replay by remember { mutableIntStateOf(0) }
    val push = remember { Animatable(-1f) }
    val settle = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val rollIn = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val fadeInSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(day, replay) {
        push.snapTo(-1f)
        push.animateTo(0f, settle)
    }
    // Plain theme roles, no mixed tones: the day is the quiet neutral pair, the rated days the tonal one.
    val scheme = MaterialTheme.colorScheme
    val dayFace = scheme.surfaceContainerHighest
    val dayInk = scheme.onSurfaceVariant
    val ratedFace = scheme.secondaryContainer
    val ratedInk = scheme.onSecondaryContainer
    var barHeight by remember { mutableIntStateOf(0) }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                // Its own layer, so the hole is cut out of the bar, its fill and both date
                // tiles at once, down to whatever the card is drawn on.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // The pair sits on the gap between the bar and the dates, its point on
                    // the gap itself, and the hole travels with it: when the pair moves, the
                    // edges of the bar and the dates move, not two chips over them.
                    val seamTop = barHeight.toFloat()
                    val seamBottom = seamTop + SensorDatesGap.toPx()
                    val reach = SensorDayTagReach.toPx() * (if (rtl) -1f else 1f)
                    val origin = Offset(
                        (size.width - SensorDayTagWidth.toPx()) / 2f + push.value * reach,
                        (seamTop + seamBottom - SensorDayTagHeight.toPx()) / 2f,
                    )
                    // One hole round the pair, not one round each, so its outline has no dips.
                    drawSensorDayTag(sensorDayTagOutline(SensorDayTagPiece.BOTH), Color.Black, origin, rtl, grow = SensorDayTagCut.toPx(), blendMode = BlendMode.Clear)
                    // Every tile corner the hole makes is rounded like the stack's others.
                    // The gap between the bar and the dates runs into the hole's blunt end
                    // and its point, both at mid-height where its edge is upright; the gap
                    // between the two dates runs into its flat underside. All six corners
                    // are square ones.
                    val holeNear = (SensorDayTagWidth - SensorDatesGap) / 2 - SensorDayTagDayWidth - SensorDayTagCut
                    val holeFar = SensorDayTagWidth + SensorDayTagCut
                    val near = origin.x + if (rtl) (SensorDayTagWidth - holeNear).toPx() else holeNear.toPx()
                    val far = origin.x + if (rtl) (SensorDayTagWidth - holeFar).toPx() else holeFar.toPx()
                    val away = if (rtl) -1f else 1f
                    val underside = origin.y + (SensorDayTagHeight + SensorDayTagCut).toPx()
                    val halfGap = SensorDatesGap.toPx() / 2f
                    val corner = SensorDayTagRound.toPx()
                    // At the point the hole's edge curves away above and below the gap, so
                    // the rounding there ends on the edge itself, a corner's height off the
                    // gap, not straight above the corner; otherwise a sliver of tile is left.
                    val pointRound = (SensorDayTagRound + SensorDayTagCut).toPx()
                    val fromMiddle = halfGap + corner
                    val farTurn = (SensorDayTagWidth - SensorDayTagRound).toPx() +
                        kotlin.math.sqrt((pointRound * pointRound - fromMiddle * fromMiddle).coerceAtLeast(0f))
                    val farEdge = origin.x + if (rtl) SensorDayTagWidth.toPx() - farTurn else farTurn
                    val fillets = Path().apply {
                        listOf(seamTop to -1f, seamBottom to 1f).forEach { (y, into) ->
                            addSensorTileFillet(near, y, -away, into, corner)
                            moveTo(far, y)
                            lineTo(far + away * corner, y)
                            quadraticTo(far, y, farEdge, y + into * corner)
                            close()
                        }
                        addSensorTileFillet(size.width / 2f - halfGap, underside, -1f, 1f, corner)
                        addSensorTileFillet(size.width / 2f + halfGap, underside, 1f, 1f, corner)
                    }
                    drawPath(fillets, Color.Black, blendMode = BlendMode.Clear)
                },
        ) {
            SensorLifeBar(life, centerReserve = SensorDayTagReserve, modifier = Modifier.onSizeChanged { barHeight = it.height })
            Spacer(Modifier.height(SensorDatesGap))
            SensorDates(life, topCorner = SensorDayTagRound, bottomCorner = SensorDayTagRound, plain = true, centerReserve = SensorDayTagReserve)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    IntOffset(0, (barHeight + (SensorDatesGap.toPx() - SensorDayTagHeight.toPx()) / 2f).roundToInt())
                }
                .size(SensorDayTagWidth, SensorDayTagHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { replay++ }
                .drawBehind {
                    val shift = Offset(push.value * SensorDayTagReach.toPx() * (if (rtl) -1f else 1f), 0f)
                    drawSensorDayTag(sensorDayTagOutline(SensorDayTagPiece.DAY), dayFace, shift, rtl)
                    drawSensorDayTag(sensorDayTagOutline(SensorDayTagPiece.RATED), ratedFace, shift, rtl)
                }
                .semantics { contentDescription = "$day / $ratedSpoken" },
        ) {
            val density = LocalDensity.current
            val reach = with(density) { SensorDayTagReach.toPx() } * (if (rtl) -1f else 1f)
            // The shapes are a fixed size, so the type in them keeps the type scale's own
            // sizes and does not follow the system font size.
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                // Today's day: the smaller and quieter of the two, since the bar's age already
                // says it; it rolls over when the day turns.
                AnimatedContent(
                    targetState = day,
                    transitionSpec = {
                        (slideInVertically(rollIn) { it } + fadeIn(fadeInSpec))
                            .togetherWith(slideOutVertically(rollIn) { -it } + fadeOut(fadeInSpec))
                    },
                    contentAlignment = Alignment.Center,
                    label = "sensorDayOfWear",
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = (SensorDayTagWidth - SensorDatesGap) / 2 - SensorDayTagDayWidth)
                        .width(SensorDayTagDayWidth)
                        .graphicsLayer { translationX = push.value * reach },
                ) { shown ->
                    Text(
                        text = String.format(locale, "%d", shown),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = dayInk,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = String.format(locale, "%d", ratedDays),
                    style = MaterialTheme.typography.titleSmallEmphasized.copy(fontFeatureSettings = "tnum"),
                    color = ratedInk,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = (SensorDayTagWidth + SensorDatesGap) / 2)
                        // Centred on the arrowhead's body and half of its point.
                        .width(SensorDayTagRatedWidth - SensorDayTagPoint / 2)
                        .graphicsLayer { translationX = push.value * reach },
                )
            }
        }
    }
}

/*
 * The pair's measures, all on the 4dp grid but the arrowhead's width, which is on the 2dp one
 * so the dates beside it keep their time on one line. The gap between the two pieces is the
 * stack's own SensorDatesGap, and it sits on the middle of the card.
 */
private val SensorDayTagHeight = 24.dp
private val SensorDayTagDayWidth = 20.dp
private val SensorDayTagRatedWidth = 26.dp
/** How far the arrowhead's point runs past its body. */
private val SensorDayTagPoint = 4.dp
/** Corner radius of both pieces, the same as the stack's inner corners. */
private val SensorDayTagRound = 4.dp
/** The ring of card showing around the pair. */
private val SensorDayTagCut = 4.dp
/** The pair's box: the gap in its middle, and the wider piece's width either side of it. */
private val SensorDayTagWidth get() = SensorDayTagRatedWidth * 2 + SensorDatesGap
/** What each half keeps clear of the middle: half the pair and its cut. */
private val SensorDayTagReserve get() = SensorDayTagWidth / 2 + SensorDayTagCut
/** How far the pair travels as it settles toward the end date. */
private val SensorDayTagReach = 4.dp

private enum class SensorDayTagPiece { DAY, RATED, BOTH }

/**
 * A piece's outline inside the pair's box, drawn [SensorDayTagRound] in from its edges: the
 * round joins it is stroked with carry it back out, which is what rounds the corners.
 */
private fun Density.sensorDayTagOutline(piece: SensorDayTagPiece) = Path().apply {
    val round = SensorDayTagRound.toPx()
    val middle = SensorDayTagWidth.toPx() / 2f
    val halfGap = SensorDatesGap.toPx() / 2f
    val top = round
    val bottom = SensorDayTagHeight.toPx() - round
    val dayStart = middle - halfGap - SensorDayTagDayWidth.toPx() + round
    val tip = SensorDayTagWidth.toPx() - round
    val shoulder = tip - SensorDayTagPoint.toPx()
    val start = if (piece == SensorDayTagPiece.RATED) middle + halfGap + round else dayStart
    moveTo(start, top)
    if (piece == SensorDayTagPiece.DAY) {
        lineTo(middle - halfGap - round, top)
        lineTo(middle - halfGap - round, bottom)
    } else {
        lineTo(shoulder, top)
        lineTo(tip, (top + bottom) / 2f)
        lineTo(shoulder, bottom)
    }
    lineTo(start, bottom)
    close()
}

/**
 * The sliver between a tile's square corner at ([x], [y]) and that corner rounded by
 * [radius]; the tile lies toward [alongX] and [alongY] from the corner.
 */
private fun Path.addSensorTileFillet(x: Float, y: Float, alongX: Float, alongY: Float, radius: Float) {
    moveTo(x, y)
    lineTo(x + alongX * radius, y)
    quadraticTo(x, y, x, y + alongY * radius)
    close()
}

private fun Path.mirrorAcross(x: Float) {
    transform(Matrix().apply {
        translate(x * 2f, 0f)
        scale(-1f, 1f)
    })
}

/**
 * One of the pair's outlines, filled and stroked with round joins, which is what rounds its
 * outer corners, the point included; [grow] widens it evenly, for the hole it sits in.
 */
private fun DrawScope.drawSensorDayTag(
    outline: Path,
    color: Color,
    topLeft: Offset,
    rtl: Boolean,
    grow: Float = 0f,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    if (rtl) outline.mirrorAcross(SensorDayTagWidth.toPx() / 2f)
    outline.translate(topLeft)
    drawPath(outline, color, blendMode = blendMode)
    drawPath(
        outline,
        color,
        style = Stroke(width = (SensorDayTagRound.toPx() + grow) * 2f, join = StrokeJoin.Round),
        blendMode = blendMode,
    )
}

/**
 * The wear bar: the dashboard's lifetime fill across the width, the sensor's age at the start
 * and the time left at the end. Units are spelled out where each end has the room and
 * abbreviated where either does not. It is the top of a stack, so only its top corners are round.
 */
@Composable
private fun SensorLifeBar(life: SensorLife, centerReserve: Dp, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val leftMs = life.endMs - life.nowMs
    val ageMs = (life.nowMs - life.startMs).coerceAtLeast(0L)
    // Ended, or running past an advisory end: the dates below say which, and nothing is left.
    val hasLeft = !life.overrun && leftMs > 0L
    val textStyle = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum")
    val sensorAgeLabel = stringResource(R.string.sensor_age)
    val remainingLabel = stringResource(R.string.sensor_life)
    val measurer = rememberTextMeasurer()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
        color = life.containerColor,
        contentColor = life.contentColor,
    ) {
        BoxWithConstraints(modifier = Modifier.sensorLifeFill(life)) {
            val sideRoom = with(LocalDensity.current) {
                (maxWidth / 2 - centerReserve - SensorDatesPadding).roundToPx()
            }
            val iconRoom = with(LocalDensity.current) { (16.dp + 8.dp).roundToPx() }
            val wide = remember(locale, ageMs / 60_000L, leftMs / 60_000L, sideRoom, measurer, textStyle) {
                val age = sensorSpanText(locale, ageMs, MeasureFormat.FormatWidth.WIDE)
                val left = if (hasLeft) sensorSpanText(locale, leftMs, MeasureFormat.FormatWidth.WIDE) else ""
                measurer.measure(age, textStyle, softWrap = false).size.width <= sideRoom &&
                    measurer.measure(left, textStyle, softWrap = false).size.width + iconRoom <= sideRoom
            }
            val unitWidth = if (wide) MeasureFormat.FormatWidth.WIDE else MeasureFormat.FormatWidth.NARROW
            val ageText = sensorSpanText(locale, ageMs, unitWidth)
            val leftText = if (hasLeft) sensorSpanText(locale, leftMs, unitWidth) else null
            // The dates' own side padding, so the bar's two ends sit over the two dates.
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .padding(horizontal = SensorDatesPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ageText,
                    style = textStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "$sensorAgeLabel $ageText" },
                )
                Spacer(Modifier.width(centerReserve * 2))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leftText != null) {
                        val hoursLeft = life.hoursLeft
                        if (hoursLeft != null) {
                            AnimatedHourglassIcon(
                                color = life.contentColor,
                                modifier = Modifier.size(16.dp),
                                cycleDuration = (500 + (1500 * (hoursLeft / 24f))).toInt().coerceAtLeast(500),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.HourglassBottom,
                                contentDescription = null,
                                tint = life.contentColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = leftText,
                            style = textStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .semantics { contentDescription = "$remainingLabel $leftText" },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Start and end side by side, one tile each, the start date at one edge and the end date at
 * the other: the date with its time beside it, or under it where the two do not fit on a
 * line. The restricted card labels the two; the full card puts them under its wear bar,
 * squares the corners that touch, keeps [centerReserve] clear in the middle for the rated-days
 * page, and labels them ([plain]) only when the end is something other than the rated one
 * ahead, since its bar already gives the age and the time left.
 */
@Composable
internal fun SensorDates(
    life: SensorLife,
    topCorner: Dp = 12.dp,
    bottomCorner: Dp = 12.dp,
    plain: Boolean = false,
    centerReserve: Dp = SensorDatesPadding,
) {
    val leftMs = life.endMs - life.nowMs
    val ended = leftMs <= 0L && !life.overrun
    val endLabel = when {
        life.overrun -> stringResource(R.string.sensor_expected_end)
        ended -> stringResource(R.string.restricted_sensor_ended)
        plain -> null
        leftMs < HOUR_MS -> stringResource(R.string.restricted_sensor_ends_soon)
        // Up to 24 hours in hours, as the pill shows "24h" with the hourglass.
        leftMs < DAY_MS + HOUR_MS -> (leftMs / HOUR_MS).toInt().let {
            pluralStringResource(R.plurals.restricted_sensor_ends_in_hours, it, it)
        }
        else -> (leftMs / DAY_MS).toInt().let {
            pluralStringResource(R.plurals.restricted_sensor_ends_in_days, it, it)
        }
    }
    val startLabel = if (endLabel != null) stringResource(R.string.sensor_started) else null
    val context = LocalContext.current
    val dateStyle = MaterialTheme.typography.titleMediumEmphasized
    val timeStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val start = remember(context, life.startMs, timeStyle, timeColor) {
        sensorDateTexts(context, life.startMs, timeStyle, timeColor)
    }
    val end = remember(context, life.endMs, timeStyle, timeColor) {
        sensorDateTexts(context, life.endMs, timeStyle, timeColor)
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // The time sits beside the date when both dates have the room, and under it in
        // both when either does not, so the two always read the same way.
        val sideWidth = with(LocalDensity.current) {
            (maxWidth / 2 - SensorDatesPadding - centerReserve).roundToPx()
        }
        // The measurer is a new one after a font-size change, which redoes the fit.
        val timeBeside = remember(start, end, dateStyle, sideWidth, measurer) {
            listOf(start, end).all {
                measurer.measure(it.oneLine, dateStyle, softWrap = false).size.width <= sideWidth
            }
        }
        val tileColor = MaterialTheme.colorScheme.surfaceContainer
        val innerPadding = centerReserve - SensorDatesGap / 2
        // Two halves, as tall as the taller needs.
        Layout(
            content = {
                Surface(
                    shape = RoundedCornerShape(topStart = topCorner, bottomStart = bottomCorner, topEnd = 4.dp, bottomEnd = 4.dp),
                    color = tileColor,
                ) {
                    SensorDate(
                        label = startLabel,
                        texts = start,
                        timeBeside = timeBeside,
                        alignment = Alignment.Start,
                        modifier = Modifier.padding(
                            start = SensorDatesPadding,
                            top = SensorDatesPadding,
                            end = innerPadding,
                            bottom = SensorDatesPadding,
                        ),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = topCorner, bottomEnd = bottomCorner),
                    color = tileColor,
                ) {
                    SensorDate(
                        label = endLabel,
                        labelColor = if (ended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        texts = end,
                        timeBeside = timeBeside,
                        alignment = Alignment.End,
                        modifier = Modifier.padding(
                            start = innerPadding,
                            top = SensorDatesPadding,
                            end = SensorDatesPadding,
                            bottom = SensorDatesPadding,
                        ),
                    )
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val tileWidth = ((width - SensorDatesGap.roundToPx()) / 2).coerceAtLeast(0)
            val height = measurables.maxOfOrNull { it.minIntrinsicHeight(tileWidth) } ?: 0
            val placeables = measurables.map { it.measure(Constraints.fixed(tileWidth, height)) }
            layout(width, height) {
                placeables[0].placeRelative(0, 0)
                placeables[1].placeRelative(width - tileWidth, 0)
            }
        }
    }
}

private val SensorDatesPadding = 12.dp
private val SensorDatesGap = 2.dp

/** A date and its time, apart and as the one line that carries both. */
private class SensorDateTexts(val date: String, val time: String, val oneLine: AnnotatedString)

private fun sensorDateTexts(
    context: Context,
    timeMs: Long,
    timeStyle: TextStyle,
    timeColor: Color,
): SensorDateTexts {
    val date = DateUtils.formatDateTime(
        context,
        timeMs,
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
    )
    val time = DateUtils.formatDateTime(context, timeMs, DateUtils.FORMAT_SHOW_TIME)
    val oneLine = buildAnnotatedString {
        append(date)
        append("  ")
        withStyle(timeStyle.toSpanStyle().copy(color = timeColor)) { append(time) }
    }
    return SensorDateTexts(date, time, oneLine)
}

@Composable
private fun SensorDate(
    label: String?,
    texts: SensorDateTexts,
    timeBeside: Boolean,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val textAlign = if (alignment == Alignment.End) TextAlign.End else TextAlign.Start
    // From the bottom, so the two dates stay level when one label takes two lines.
    Column(
        modifier = Modifier.fillMaxSize().then(modifier),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = alignment,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                textAlign = textAlign,
            )
            Spacer(Modifier.height(4.dp))
        }
        if (timeBeside) {
            Text(
                text = texts.oneLine,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        } else {
            Text(
                text = texts.date,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = textAlign,
            )
            Text(
                text = texts.time,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What replaces the full card's details and controls while restricted: when the sensor
 * started and ends, and Disconnect when the adult allowed sensor changes. That Disconnect
 * never deletes history.
 */
@Composable
internal fun RestrictedSensorDetails(
    sensor: SensorInfo,
    life: SensorLife?,
    viewModel: SensorViewModel,
    allowDisconnect: Boolean,
) {
    val context = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }

    if (life != null) {
        Spacer(modifier = Modifier.height(12.dp))
        SensorDates(life)
    }

    if (allowDisconnect && !sensor.isCloneSource) {
        Spacer(modifier = Modifier.height(16.dp))
        FilledTonalButton(
            onClick = { confirmDisconnect = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text(stringResource(R.string.disconnect))
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.disconnect_sensor_title)) },
            text = { Text(stringResource(R.string.restricted_disconnect_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        // The keep-everything variant of each vendor's Disconnect: history
                        // stays, AiDex stays paired, Ottai keeps its saved keys.
                        // AiDex's keep-pairing Disconnect is terminateSensor itself, which says
                        // whether the sensor went.
                        val removed = when {
                            sensor.vendor == SensorVendor.OTTAI ->
                                viewModel.disconnectOttaiSensor(sensor.serial, wipeData = false, forgetAndUnbind = false)
                            else -> viewModel.terminateSensor(sensor.serial, wipeData = false)
                        }
                        if (!removed) {
                            Toast.makeText(context, R.string.disconnect_sensor_failed, Toast.LENGTH_LONG).show()
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(R.string.disconnect)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDisconnect = false },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The Sensors tab with no sensor, on a restricted phone that may not add one. */
@Composable
internal fun RestrictedSensorsLockedState(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.no_sensors_connected),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.restricted_sensors_locked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = onUnlock,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier.heightIn(min = 40.dp),
        ) {
            Text(stringResource(R.string.restricted_unlock_title))
        }
    }
}
