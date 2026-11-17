@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.restricted

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateUtils
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    // Before the ticker: a card with nothing to show about the wear has no reason to wake.
    if (sensor.startMs <= PLAUSIBLE_START_MS) return null
    // Ticks each minute, so the fill, the day and the time left move on without a reading.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000L)
            value = System.currentTimeMillis()
        }
    }
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

/**
 * "17 days 4 hours" or "17d 4h" (a "5h", a "12m") in the locale's own units and plurals. The two
 * are set side by side, not joined by the locale's list pattern, which puts a comma between
 * them in some languages and not in others.
 */
private fun sensorSpanText(locale: Locale, spanMs: Long, width: MeasureFormat.FormatWidth): String {
    val format = MeasureFormat.getInstance(locale, width)
    val days = spanMs / DAY_MS
    val hours = (spanMs % DAY_MS) / HOUR_MS
    return when {
        days > 0L -> format.format(Measure(days, MeasureUnit.DAY)) + " " + format.format(Measure(hours, MeasureUnit.HOUR))
        hours > 0L -> format.format(Measure(hours, MeasureUnit.HOUR))
        else -> format.format(Measure((spanMs / 60_000L).coerceAtLeast(0L), MeasureUnit.MINUTE))
    }
}

/**
 * The full card's wear block, a stack of three tiles: the wear bar over the start and the end,
 * each of those under its label. The time left is the end date's label, in whole days until
 * the last one.
 */
@Composable
internal fun SensorWear(life: SensorLife, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SensorLifeBar(life)
        Spacer(Modifier.height(SensorDatesGap))
        SensorDates(life, topCorner = SensorStackCorner, bottomCorner = SensorStackCorner, labelBelow = true)
    }
}

/** The radius of the corners inside the wear stack, where one tile meets another. */
private val SensorStackCorner = 4.dp

/**
 * The top of the wear stack: the dashboard's lifetime fill across the width, the sensor's age
 * at the start, and at the end the dashboard's calendar with the days the sensor is rated for
 * ("28 days"), so the fill is read against that span. The age's units are spelled out where there is the
 * room and abbreviated where not.
 */
@Composable
private fun SensorLifeBar(life: SensorLife, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val ageMs = (life.nowMs - life.startMs).coerceAtLeast(0L)
    val ratedDays = ((life.endMs - life.startMs + DAY_MS / 2) / DAY_MS).coerceAtLeast(1L)
    val textStyle = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum")
    val sensorAgeLabel = stringResource(R.string.sensor_age)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = 12.dp,
            topEnd = 12.dp,
            bottomStart = SensorStackCorner,
            bottomEnd = SensorStackCorner,
        ),
        color = life.containerColor,
        contentColor = life.contentColor,
    ) {
        // The dates' own side padding, so the bar's two ends sit over the two dates.
        Row(
            modifier = Modifier
                .sensorLifeFill(life)
                .heightIn(min = 40.dp)
                .padding(horizontal = SensorDatesPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val wideText = sensorSpanText(locale, ageMs, MeasureFormat.FormatWidth.WIDE)
            // Abbreviated once the spelled-out age has been seen not to fit.
            var abbreviated by remember(wideText.length, textStyle) { mutableStateOf(false) }
            val unitWidth = if (abbreviated) MeasureFormat.FormatWidth.NARROW else MeasureFormat.FormatWidth.WIDE
            val ageText = if (abbreviated) sensorSpanText(locale, ageMs, unitWidth) else wideText
            val ratedText = remember(locale, ratedDays, unitWidth) {
                MeasureFormat.getInstance(locale, unitWidth).format(Measure(ratedDays, MeasureUnit.DAY))
            }
            Text(
                text = ageText,
                style = textStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow) abbreviated = true },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "$sensorAgeLabel $ageText" },
            )
            Spacer(Modifier.width(16.dp))
            // The dashboard's sensor card draws the calendar at this size and strength.
            CustomCalendarIcon(
                color = life.contentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp),
                currentDay = life.currentDay,
            )
            Spacer(Modifier.width(8.dp))
            // Text at the edge, so it ends where the end date's time ends below it.
            Text(
                text = ratedText,
                style = textStyle,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Start and end side by side, one labelled tile each, the start date at one edge and the end
 * date at the other: the date with its time beside it, or under it where the two do not fit
 * on a line. The end's label is the time left, or what the end is when it is not ahead. The
 * full card puts the two under its wear bar, squares the corners that touch it, and sets each
 * label under its date ([labelBelow]) so the dates sit right under the bar.
 */
@Composable
internal fun SensorDates(
    life: SensorLife,
    topCorner: Dp = 12.dp,
    bottomCorner: Dp = 12.dp,
    labelBelow: Boolean = false,
) {
    val leftMs = life.endMs - life.nowMs
    val ended = leftMs <= 0L && !life.overrun
    val endLabel = when {
        life.overrun -> stringResource(R.string.sensor_expected_end)
        ended -> stringResource(R.string.restricted_sensor_ended)
        leftMs < HOUR_MS -> stringResource(R.string.restricted_sensor_ends_soon)
        // Up to 24 hours in hours, as the pill shows "24h" with the hourglass.
        leftMs < DAY_MS + HOUR_MS -> (leftMs / HOUR_MS).toInt().let {
            pluralStringResource(R.plurals.restricted_sensor_ends_in_hours, it, it)
        }
        else -> (leftMs / DAY_MS).toInt().let {
            pluralStringResource(R.plurals.restricted_sensor_ends_in_days, it, it)
        }
    }
    val startLabel = stringResource(R.string.sensor_started)
    val context = LocalContext.current
    // A language change is handled in place, and the dates are worded in the language.
    val configuration = LocalConfiguration.current
    val dateStyle = MaterialTheme.typography.titleMediumEmphasized
    val timeStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    val timeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val start = remember(context, configuration, life.startMs, timeStyle, timeColor) {
        sensorDateTexts(context, life.startMs, timeStyle, timeColor)
    }
    val end = remember(context, configuration, life.endMs, timeStyle, timeColor) {
        sensorDateTexts(context, life.endMs, timeStyle, timeColor)
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // The time sits beside the date when both dates have the room, and under it in
        // both when either does not, so the two always read the same way.
        val sideWidth = with(LocalDensity.current) {
            (maxWidth / 2 - SensorDatesPadding * 2).roundToPx()
        }
        // The measurer is a new one after a font-size change, which redoes the fit.
        val timeBeside = remember(start, end, dateStyle, sideWidth, measurer) {
            listOf(start, end).all {
                measurer.measure(it.oneLine, dateStyle, softWrap = false).size.width <= sideWidth
            }
        }
        val tileColor = MaterialTheme.colorScheme.surfaceContainer
        // Two halves, as tall as the taller needs.
        Layout(
            content = {
                Surface(
                    shape = RoundedCornerShape(topStart = topCorner, bottomStart = bottomCorner, topEnd = SensorStackCorner, bottomEnd = SensorStackCorner),
                    color = tileColor,
                ) {
                    SensorDate(
                        label = startLabel,
                        texts = start,
                        timeBeside = timeBeside,
                        labelBelow = labelBelow,
                        alignment = Alignment.Start,
                        modifier = Modifier.padding(SensorDatesPadding),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(topStart = SensorStackCorner, bottomStart = SensorStackCorner, topEnd = topCorner, bottomEnd = bottomCorner),
                    color = tileColor,
                ) {
                    SensorDate(
                        label = endLabel,
                        labelColor = if (ended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        texts = end,
                        timeBeside = timeBeside,
                        labelBelow = labelBelow,
                        alignment = Alignment.End,
                        modifier = Modifier.padding(SensorDatesPadding),
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
    labelBelow: Boolean,
    alignment: Alignment.Horizontal,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val textAlign = if (alignment == Alignment.End) TextAlign.End else TextAlign.Start
    val labelText: @Composable () -> Unit = {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                textAlign = textAlign,
            )
        }
    }
    // From the edge the dates are on, so the two dates stay level when one label takes two lines.
    Column(
        modifier = Modifier.fillMaxSize().then(modifier),
        verticalArrangement = if (labelBelow) Arrangement.Top else Arrangement.Bottom,
        horizontalAlignment = alignment,
    ) {
        if (label != null && !labelBelow) {
            labelText()
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
        if (label != null && labelBelow) {
            Spacer(Modifier.height(4.dp))
            labelText()
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
