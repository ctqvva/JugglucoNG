@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DateRangePickerState
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.StableModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tk.glucodata.R
import tk.glucodata.ui.util.ConnectedButtonGroup
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

fun formatStatsDateRange(range: StatsDateRange?): String? {
    if (range == null) return null
    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
    return "${formatter.format(Date(range.startMillis))} - ${formatter.format(Date(range.endMillis))}"
}

fun clampStatsDateRangeToAvailable(range: StatsDateRange?, availableRange: StatsDateRange?): StatsDateRange? {
    if (range == null) return availableRange
    if (availableRange == null) return range
    val startMillis = maxOf(range.startMillis, availableRange.startMillis)
    val endMillis = minOf(range.endMillis, availableRange.endMillis)
    return if (endMillis >= startMillis) {
        StatsDateRange(startMillis = startMillis, endMillis = endMillis)
    } else {
        availableRange
    }
}

fun toPickerUtcDateMillis(timestampMillis: Long): Long {
    val localDate = Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return localDate
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
}

fun pickerUtcDateMillisToLocalStart(utcDateMillis: Long): Long {
    val localDate = Instant.ofEpochMilli(utcDateMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
    return localDate
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

fun pickerUtcDateMillisToLocalEnd(utcDateMillis: Long): Long {
    val localDate = Instant.ofEpochMilli(utcDateMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
    return localDate
        .plusDays(1)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli() - 1L
}

private fun formatPickerHeadline(
    startUtcMillis: Long?,
    endUtcMillis: Long?
): String? {
    if (startUtcMillis == null && endUtcMillis == null) return null
    val monthDayFormatter = SimpleDateFormat("d MMM", Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    val monthDayYearFormatter = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    val yearFormatter = SimpleDateFormat("yyyy", Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    return when {
        startUtcMillis != null && endUtcMillis != null &&
            yearFormatter.format(Date(startUtcMillis)) == yearFormatter.format(Date(endUtcMillis)) ->
            "${monthDayFormatter.format(Date(startUtcMillis))} - ${monthDayYearFormatter.format(Date(endUtcMillis))}"
        startUtcMillis != null && endUtcMillis != null ->
            "${monthDayYearFormatter.format(Date(startUtcMillis))} - ${monthDayYearFormatter.format(Date(endUtcMillis))}"
        startUtcMillis != null -> monthDayYearFormatter.format(Date(startUtcMillis))
        else -> monthDayYearFormatter.format(Date(endUtcMillis!!))
    }
}

@Composable
fun StatsDateRangePickerHeadline(
    state: DateRangePickerState,
    modifier: Modifier = Modifier
) {
    val headline = remember(state.selectedStartDateMillis, state.selectedEndDateMillis) {
        formatPickerHeadline(state.selectedStartDateMillis, state.selectedEndDateMillis)
    }
    if (!headline.isNullOrBlank()) {
        Text(
            text = headline,
            modifier = modifier.padding(start = 24.dp, end = 24.dp, bottom = 2.dp),
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Medium,
                lineHeight = 24.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StatsRangeSelectorControl(
    selectedRange: StatsTimeRange?,
    activeRange: StatsDateRange?,
    isLoading: Boolean,
    hasData: Boolean,
    readingCount: Int,
    onRangeSelected: (StatsTimeRange) -> Unit,
    onCustomRangeClick: () -> Unit,
    modifier: Modifier = Modifier,
    coveragePercent: Float? = null,
    countLabelResId: Int = R.string.points
) {
    val view = LocalView.current
    var lastRangeHapticAt by remember { mutableLongStateOf(0L) }
    val subtitle = when {
        isLoading -> stringResource(R.string.loading_data)
        activeRange != null -> formatStatsDateRange(activeRange)
        else -> stringResource(R.string.statistics_subtitle)
    }
    val ranges = StatsTimeRange.entries.toList()

    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasData && readingCount > 0) {
                // Reading count and sensor coverage are both facts about the window, so
                // they belong on the window's own line rather than inside a result card.
                val countText = "$readingCount ${stringResource(countLabelResId)}"
                Text(
                    text = coveragePercent
                        ?.let { "$countText · ${stringResource(R.string.stats_coverage_active, it.roundToInt())}" }
                        ?: countText,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Surface(
                modifier = Modifier.heightIn(min = 36.dp),
                onClick = onCustomRangeClick,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = if (selectedRange == null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (selectedRange == null) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Row(
                    modifier = Modifier
                        .animateContentSize()
                        .heightIn(min = 36.dp)
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    AnimatedContent(
                        targetState = subtitle ?: stringResource(R.string.statistics_subtitle),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "statsRangeSubtitle"
                    ) { targetSubtitle ->
                        Text(
                            text = targetSubtitle,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        val context = LocalContext.current
        ConnectedButtonGroup(
            options = ranges,
            selectedOption = selectedRange,
            onOptionSelected = { range ->
                if (range != selectedRange) {
                    val now = System.currentTimeMillis()
                    if (now - lastRangeHapticAt >= 90L) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        lastRangeHapticAt = now
                    }
                    onRangeSelected(range)
                }
            },
            labelText = { option -> context.getString(option.labelResId) },
            label = { option ->
                Text(
                    text = stringResource(option.labelResId),
                    style = MaterialTheme.typography.labelLarge
                )
            },
            itemHeight = 40.dp,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Quick spans offered above the calendar, so most visits never touch a date grid. */
private enum class RangePreset(val labelResId: Int, val days: Int) {
    LAST_7(R.string.range_7d, 7),
    LAST_14(R.string.range_14d, 14),
    LAST_30(R.string.range_30d, 30),
    LAST_90(R.string.range_90d, 90)
}

/**
 * Custom range picker.
 *
 * Three things the old dialog got wrong: a single tapped day was not a valid choice, so
 * "show me Tuesday" meant tapping Tuesday twice and hoping; the confirm button stayed
 * dead until it decided you were finished; and there was no way to say "the last
 * fortnight" without counting backwards on a calendar. A sheet also gives the grid the
 * room the dialog never had.
 */
@Composable
fun StatsDateRangeSheet(
    availableRange: StatsDateRange?,
    activeRange: StatsDateRange?,
    onDismiss: () -> Unit,
    onApply: (startMillis: Long, endMillis: Long) -> Unit
) {
    val initialRange = clampStatsDateRangeToAvailable(activeRange, availableRange) ?: availableRange
    val availableStartDateMillis = availableRange?.startMillis?.let(::toPickerUtcDateMillis)
    val availableEndDateMillis = availableRange?.endMillis?.let(::toPickerUtcDateMillis)
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialRange?.startMillis?.let(::toPickerUtcDateMillis),
        initialSelectedEndDateMillis = initialRange?.endMillis?.let(::toPickerUtcDateMillis),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val earliest = availableStartDateMillis ?: 0L
                val latest = availableEndDateMillis ?: toPickerUtcDateMillis(System.currentTimeMillis())
                return utcTimeMillis in earliest..latest
            }
        }
    )

    val startUtc = pickerState.selectedStartDateMillis
    // One tap is a one-day range. Waiting for a second tap made the common case the
    // awkward one.
    val endUtc = pickerState.selectedEndDateMillis ?: startUtc
    val dayCount = if (startUtc != null && endUtc != null) {
        (((endUtc - startUtc) / 86_400_000L) + 1L).toInt().coerceAtLeast(1)
    } else {
        0
    }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = stringResource(R.string.stats_custom_range_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = if (dayCount > 0) {
                        val span = formatPickerHeadline(startUtc, endUtc).orEmpty()
                        "$span · ${stringResource(R.string.stats_span_days, dayCount)}"
                    } else {
                        stringResource(R.string.stats_custom_range_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val presets = RangePreset.entries.toList()
            val presetLabels = presets.associateWith { stringResource(it.labelResId) }
            ConnectedButtonGroup(
                options = presets,
                selectedOption = null,
                onOptionSelected = { preset ->
                    val end = availableRange?.endMillis ?: System.currentTimeMillis()
                    val endDay = toPickerUtcDateMillis(end)
                    pickerState.setSelection(
                        startDateMillis = endDay - ((preset.days - 1).toLong() * 86_400_000L),
                        endDateMillis = endDay
                    )
                },
                labelText = { preset -> presetLabels[preset].orEmpty() },
                label = { preset ->
                    Text(
                        text = presetLabels[preset].orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false
                    )
                },
                itemHeight = 40.dp,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )

            DateRangePicker(
                state = pickerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                title = null,
                headline = null,
                showModeToggle = false
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        val start = startUtc?.let(::pickerUtcDateMillisToLocalStart) ?: return@Button
                        val end = (endUtc ?: startUtc).let(::pickerUtcDateMillisToLocalEnd)
                        onApply(start, end)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = startUtc != null
                ) {
                    Text(text = stringResource(R.string.stats_apply_range))
                }
            }
        }
    }
}
