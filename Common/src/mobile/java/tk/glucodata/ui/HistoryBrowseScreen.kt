@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.data.HistoryRepository
import tk.glucodata.data.TimelineExtents
import tk.glucodata.data.TimelineRangeSummary
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.ui.journal.buildJournalChartMarkers
import tk.glucodata.ui.journal.journalQuickAddTimestamp
import tk.glucodata.ui.journal.journalTypeColor
import tk.glucodata.ui.journal.journalTypeSelectedContainerColor
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.stats.StatsDateRange
import tk.glucodata.ui.stats.StatsDateRangePickerHeadline
import tk.glucodata.ui.stats.StatsRangeSelectorControl
import tk.glucodata.ui.stats.StatsTimeRange
import tk.glucodata.ui.stats.clampStatsDateRangeToAvailable
import tk.glucodata.ui.stats.pickerUtcDateMillisToLocalEnd
import tk.glucodata.ui.stats.pickerUtcDateMillisToLocalStart
import tk.glucodata.ui.stats.toPickerUtcDateMillis
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The viewport a freshly chosen range is loaded for before its chart can say where it is looking: TimeRange.H24, the screen's default. */
private const val HISTORY_RANGE_FIRST_VIEWPORT_MS = 24L * 60L * 60L * 1000L

private data class HistoryDateSection(
    val date: LocalDate,
    val label: String,
    val items: List<TimelineRowItem>,
    /** One stable LazyColumn key per item; see [uniqueRowKeys]. */
    val keys: List<String>
)

private data class TimelineRowItem(
    val timestamp: Long,
    val point: GlucosePoint?,
    val journalEntries: List<JournalEntry>,
    /** What the row's arrow regresses over; see [rowTrendHistory]. Empty for a journal-only row. */
    val trendHistory: List<GlucosePoint> = emptyList()
)

private fun TimelineRowItem.rowKey(): String {
    val journalKey = journalEntries.joinToString(separator = ",") { it.id.toString() }
    return "$timestamp-${point?.timestamp ?: "journal"}-$journalKey"
}

private data class TimelineJournalGrouping(
    val entriesByPointTimestamp: Map<Long, List<JournalEntry>>,
    val journalOnlyEntriesByTimestamp: Map<Long, List<JournalEntry>>
)

enum class TimelineBrowseMode {
    HISTORY,
    JOURNAL
}

private sealed class HistoryFilterOption {
    object Readings : HistoryFilterOption()
    data class Journal(val type: JournalEntryType) : HistoryFilterOption()
}

private fun List<GlucosePoint>.sliceByTimestampRange(startMillis: Long, endMillis: Long): List<GlucosePoint> {
    if (isEmpty()) return emptyList()
    val startIndex = binarySearchBy(startMillis) { it.timestamp }
        .let { if (it >= 0) it else (-it - 1).coerceAtLeast(0) }
    val endInsertionPoint = binarySearchBy(endMillis) { it.timestamp }
        .let { if (it >= 0) it + 1 else (-it - 1) }
        .coerceAtMost(size)
    if (startIndex >= endInsertionPoint) return emptyList()
    return subList(startIndex, endInsertionPoint)
}

private fun resolveHistoryActiveRange(
    selectedRange: StatsTimeRange?,
    customRange: StatsDateRange?,
    availableRange: StatsDateRange?
): StatsDateRange? {
    val boundedAvailableRange = availableRange ?: return customRange
    return when {
        selectedRange == null -> clampStatsDateRangeToAvailable(customRange, boundedAvailableRange)
        selectedRange == StatsTimeRange.DAY_ALL -> boundedAvailableRange
        else -> {
            val endMillis = boundedAvailableRange.endMillis
            val startMillis = endMillis - (selectedRange.days * 24L * 60L * 60L * 1000L) + 1L
            clampStatsDateRangeToAvailable(
                StatsDateRange(startMillis = startMillis, endMillis = endMillis),
                boundedAvailableRange
            )
        }
    }
}

private fun defaultViewportPoints(
    points: List<GlucosePoint>,
    selectedRange: TimeRange
): List<GlucosePoint> {
    if (points.isEmpty()) return emptyList()
    val endMillis = points.last().timestamp
    val startMillis = endMillis - (selectedRange.hours * 60L * 60L * 1000L)
    return points.sliceByTimestampRange(startMillis, endMillis)
}

private fun buildHistorySections(items: List<TimelineRowItem>): List<HistoryDateSection> {
    if (items.isEmpty()) return emptyList()
    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    val sections = ArrayList<HistoryDateSection>()
    // Keys are unique across the whole list, not per section: LazyColumn requires it.
    val keys = uniqueRowKeys(items.map(TimelineRowItem::rowKey)).iterator()
    var currentDate: LocalDate? = null
    var currentItems = ArrayList<TimelineRowItem>()
    var currentKeys = ArrayList<String>()

    fun flushSection() {
        val date = currentDate ?: return
        if (currentItems.isEmpty()) return
        sections.add(
            HistoryDateSection(
                date = date,
                label = formatter.format(Date(currentItems.first().timestamp)),
                items = currentItems.toList(),
                keys = currentKeys.toList()
            )
        )
    }

    for (item in items) {
        val itemDate = Instant.ofEpochMilli(item.timestamp).atZone(zone).toLocalDate()
        if (currentDate == null || itemDate != currentDate) {
            flushSection()
            currentDate = itemDate
            currentItems = ArrayList()
            currentKeys = ArrayList()
        }
        currentItems.add(item)
        currentKeys.add(keys.next())
    }
    flushSection()
    return sections
}

/**
 * How far the timeline reaches: the store's ends, from its extents rather than
 * from the loaded list — which is now only the stretch on screen — widened by
 * any journal entry outside them.
 */
internal fun resolveAvailableTimelineRange(
    extents: TimelineExtents?,
    entries: List<JournalEntry>
): StatsDateRange? {
    val startMillis = listOfNotNull(
        extents?.earliestMs,
        entries.minOfOrNull { it.timestamp }
    ).minOrNull() ?: return null
    val endMillis = listOfNotNull(
        extents?.latestMs,
        entries.maxOfOrNull { it.timestamp }
    ).maxOrNull() ?: return null
    return StatsDateRange(startMillis = startMillis, endMillis = endMillis)
}

fun groupJournalEntriesByReading(
    points: List<GlucosePoint>,
    entries: List<JournalEntry>,
    maxDistanceMillis: Long = 20L * 60L * 1000L
): Map<Long, List<JournalEntry>> {
    if (points.isEmpty() || entries.isEmpty()) return emptyMap()
    val sortedPoints = points.ascendingByTimestamp()
    val grouped = linkedMapOf<Long, MutableList<JournalEntry>>()

    entries.forEach { entry ->
        val insertionIndex = sortedPoints.binarySearchBy(entry.timestamp) { it.timestamp }
            .let { if (it >= 0) it else (-it - 1) }
            .coerceIn(0, sortedPoints.lastIndex)

        var closestPoint: GlucosePoint? = null
        var closestDistance = Long.MAX_VALUE

        for (candidateIndex in max(0, insertionIndex - 1)..min(sortedPoints.lastIndex, insertionIndex + 1)) {
            val candidate = sortedPoints[candidateIndex]
            val distance = abs(candidate.timestamp - entry.timestamp)
            if (distance < closestDistance) {
                closestPoint = candidate
                closestDistance = distance
            }
        }

        val targetPoint = closestPoint ?: return@forEach
        if (closestDistance <= maxDistanceMillis) {
            grouped.getOrPut(targetPoint.timestamp) { mutableListOf() }.add(entry)
        }
    }

    return grouped.mapValues { (_, groupedEntries) ->
        groupedEntries.sortedByDescending { it.timestamp }
    }
}

private fun groupJournalEntriesForTimeline(
    points: List<GlucosePoint>,
    entries: List<JournalEntry>,
    maxDistanceMillis: Long = 20L * 60L * 1000L
): TimelineJournalGrouping {
    if (entries.isEmpty()) {
        return TimelineJournalGrouping(
            entriesByPointTimestamp = emptyMap(),
            journalOnlyEntriesByTimestamp = emptyMap()
        )
    }
    if (points.isEmpty()) {
        return TimelineJournalGrouping(
            entriesByPointTimestamp = emptyMap(),
            journalOnlyEntriesByTimestamp = entries
                .groupBy { it.timestamp }
                .mapValues { (_, groupedEntries) -> groupedEntries.sortedByDescending { it.timestamp } }
        )
    }

    val sortedPoints = points.ascendingByTimestamp()
    val entriesByPoint = linkedMapOf<Long, MutableList<JournalEntry>>()
    val journalOnly = linkedMapOf<Long, MutableList<JournalEntry>>()

    entries.forEach { entry ->
        val insertionIndex = sortedPoints.binarySearchBy(entry.timestamp) { it.timestamp }
            .let { if (it >= 0) it else (-it - 1) }
            .coerceIn(0, sortedPoints.lastIndex)

        var closestPoint: GlucosePoint? = null
        var closestDistance = Long.MAX_VALUE

        for (candidateIndex in max(0, insertionIndex - 1)..min(sortedPoints.lastIndex, insertionIndex + 1)) {
            val candidate = sortedPoints[candidateIndex]
            val distance = abs(candidate.timestamp - entry.timestamp)
            if (distance < closestDistance) {
                closestPoint = candidate
                closestDistance = distance
            }
        }

        val targetPoint = closestPoint
        if (targetPoint != null && closestDistance <= maxDistanceMillis) {
            entriesByPoint.getOrPut(targetPoint.timestamp) { mutableListOf() }.add(entry)
        } else {
            journalOnly.getOrPut(entry.timestamp) { mutableListOf() }.add(entry)
        }
    }

    return TimelineJournalGrouping(
        entriesByPointTimestamp = entriesByPoint.mapValues { (_, groupedEntries) ->
            groupedEntries.sortedByDescending { it.timestamp }
        },
        journalOnlyEntriesByTimestamp = journalOnly.mapValues { (_, groupedEntries) ->
            groupedEntries.sortedByDescending { it.timestamp }
        }
    )
}

private fun buildTimelineRows(
    points: List<GlucosePoint>,
    entries: List<JournalEntry>,
    browseMode: TimelineBrowseMode,
    /** The whole history, ascending, for the rows' arrows; [points] is the visible slice. */
    trendSource: List<GlucosePoint> = points
): List<TimelineRowItem> {
    val grouping = groupJournalEntriesForTimeline(points, entries)
    val pointRows = points.mapNotNull { point ->
        val rowEntries = grouping.entriesByPointTimestamp[point.timestamp].orEmpty()
        when (browseMode) {
            TimelineBrowseMode.HISTORY -> TimelineRowItem(
                timestamp = point.timestamp,
                point = point,
                journalEntries = rowEntries,
                trendHistory = rowTrendHistory(trendSource, point.timestamp)
            )

            TimelineBrowseMode.JOURNAL -> rowEntries.takeIf { it.isNotEmpty() }?.let {
                TimelineRowItem(
                    timestamp = point.timestamp,
                    point = point,
                    journalEntries = it,
                    trendHistory = rowTrendHistory(trendSource, point.timestamp)
                )
            }
        }
    }
    val journalOnlyRows = grouping.journalOnlyEntriesByTimestamp.map { (timestamp, groupedEntries) ->
        TimelineRowItem(
            timestamp = timestamp,
            point = null,
            journalEntries = groupedEntries
        )
    }
    return (pointRows + journalOnlyRows).sortedByDescending { it.timestamp }
}

private fun JournalEntryType.historyFilterIcon(): ImageVector = when (this) {
    JournalEntryType.INSULIN -> Icons.Default.Vaccines
    JournalEntryType.CARBS -> Icons.Default.LunchDining
    JournalEntryType.FINGERSTICK -> Icons.Default.Bloodtype
    JournalEntryType.ACTIVITY -> Icons.Default.DirectionsRun
    JournalEntryType.NOTE -> Icons.Default.Label
}

private fun HistoryFilterOption.historyFilterIcon(): ImageVector = when (this) {
    HistoryFilterOption.Readings -> Icons.Default.ShowChart
    is HistoryFilterOption.Journal -> type.historyFilterIcon()
}

@Composable
fun HistoryBrowseScreen(
    glucoseHistory: List<GlucosePoint>,
    unit: String,
    viewMode: Int,
    sensorId: String,
    graphLow: Float,
    graphHigh: Float,
    targetLow: Float,
    targetHigh: Float,
    graphSmoothingMinutes: Int,
    /** The window the reading itself carries here; see DataSmoothing.localSmoothingMinutes. */
    evaluationSmoothingMinutes: Int,
    collapseSmoothedData: Boolean,
    previewWindowMode: Int,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity>,
    title: String,
    browseMode: TimelineBrowseMode = TimelineBrowseMode.HISTORY,
    journalEnabled: Boolean = false,
    chartRangeColors: Boolean = false,
    journalEntries: List<JournalEntry> = emptyList(),
    journalInsulinPresets: List<JournalInsulinPreset> = emptyList(),
    journalFoods: List<JournalFood> = emptyList(),
    onBack: (() -> Unit)? = null,
    onPointClick: ((GlucosePoint) -> Unit)? = null,
    onDeleteReading: ((GlucosePoint) -> Unit)? = null,
    onJournalEntryClick: ((JournalEntry) -> Unit)? = null,
    onAddJournalEntry: ((Long, JournalEntryType?, Float?) -> Unit)? = null,
    showTransferActions: Boolean = true,
    quickAddAlwaysNow: Boolean = false,
    showRowDelta: Boolean = false,
    deltaIntervalMinutes: Int = tk.glucodata.GlucoseDelta.DEFAULT_INTERVAL_MINUTES,
    /**
     * The whole store's ends. [glucoseHistory] is the stretch loaded around the
     * chart's viewport plus the live tail, not the whole timeline, so anything
     * that needs to know how far the data reaches asks this.
     */
    timelineExtents: TimelineExtents? = null,
    /** Reports the chart's viewport so the owner of [glucoseHistory] can load around it. */
    onVisibleRangeChanged: ((startMs: Long, endMs: Long) -> Unit)? = null,
    /** What a range of the timeline holds, live; null falls back to the loaded list. */
    rangeSummaryFlow: ((startMs: Long, endMs: Long) -> kotlinx.coroutines.flow.Flow<TimelineRangeSummary?>)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sortedHistory = remember(glucoseHistory) { glucoseHistory.ascendingByTimestamp() }
    val journalPresetsById = remember(journalInsulinPresets) { journalInsulinPresets.associateBy { it.id } }
    val journalFoodsById = remember(journalFoods) { journalFoods.associateBy { it.id } }
    val loadedExtents = remember(sortedHistory) {
        sortedHistory.takeIf { it.isNotEmpty() }?.let {
            TimelineExtents(it.first().timestamp, it.last().timestamp, it.size)
        }
    }
    val effectiveExtents = timelineExtents ?: loadedExtents
    val availableRange = remember(effectiveExtents, journalEntries) {
        resolveAvailableTimelineRange(effectiveExtents, journalEntries)
    }

    var selectedHistoryRange by rememberSaveable(browseMode) {
        mutableStateOf<StatsTimeRange?>(
            if (browseMode == TimelineBrowseMode.JOURNAL) StatsTimeRange.DAY_7 else StatsTimeRange.DAY_30
        )
    }
    var customRangeStartMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var customRangeEndMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedChartRange by rememberSaveable { mutableStateOf(TimeRange.H24) }
    var showDateRangePicker by rememberSaveable { mutableStateOf(false) }
    var showExportSheet by rememberSaveable { mutableStateOf(false) }
    var viewportSnapshot by remember { mutableStateOf<ChartViewportSnapshot?>(null) }
    var showReadingRows by rememberSaveable { mutableStateOf(true) }
    var selectedJournalTypeFilters by rememberSaveable {
        mutableStateOf(JournalEntryType.entries.map { it.name })
    }
    val selectedJournalTypes = remember(selectedJournalTypeFilters) {
        selectedJournalTypeFilters.mapNotNull { name ->
            runCatching { JournalEntryType.valueOf(name) }.getOrNull()
        }
    }

    val customRange = remember(customRangeStartMillis, customRangeEndMillis) {
        val startMillis = customRangeStartMillis
        val endMillis = customRangeEndMillis
        if (startMillis != null && endMillis != null) {
            StatsDateRange(startMillis = startMillis, endMillis = endMillis)
        } else {
            null
        }
    }
    val activeRange = remember(selectedHistoryRange, customRange, availableRange) {
        resolveHistoryActiveRange(selectedHistoryRange, customRange, availableRange)
    }
    val activeHistory = remember(sortedHistory, activeRange) {
        activeRange?.let { sortedHistory.sliceByTimestampRange(it.startMillis, it.endMillis) } ?: sortedHistory
    }
    // What the active range holds across the whole store — the loaded list is
    // only a window of it. Live, so the count follows the store like it did.
    val loadedRangeSummary = remember(activeHistory) {
        activeHistory.takeIf { it.isNotEmpty() }?.let {
            TimelineRangeSummary(it.size, it.first().timestamp, it.last().timestamp)
        }
    }
    val rangeSummary = if (rangeSummaryFlow != null && activeRange != null) {
        val flow = remember(activeRange, rangeSummaryFlow) {
            rangeSummaryFlow(activeRange.startMillis, activeRange.endMillis)
        }
        flow.collectAsStateWithLifecycle(initialValue = loadedRangeSummary).value
    } else {
        loadedRangeSummary
    }
    // A range the loaded stretches do not reach — last month, say — has
    // nothing on screen to ask for it; ask for its last day here, and the
    // chart takes over once it has data to report a viewport from.
    LaunchedEffect(activeRange, rangeSummary?.latestMs, activeHistory.isEmpty(), onVisibleRangeChanged) {
        val callback = onVisibleRangeChanged ?: return@LaunchedEffect
        val end = rangeSummary?.latestMs ?: return@LaunchedEffect
        if (activeHistory.isEmpty()) {
            callback(end - HISTORY_RANGE_FIRST_VIEWPORT_MS, end)
        }
    }
    val activeJournalEntries = remember(journalEntries, activeRange) {
        activeRange?.let { range ->
            journalEntries.filter { entry -> entry.timestamp in range.startMillis..range.endMillis }
        } ?: journalEntries
    }
    val filteredJournalEntries = remember(activeJournalEntries, selectedJournalTypes, journalEnabled) {
        if (!journalEnabled) {
            activeJournalEntries
        } else {
            activeJournalEntries.filter { it.type in selectedJournalTypes }
        }
    }
    val filteredHistory = remember(activeHistory, showReadingRows, journalEnabled) {
        if (journalEnabled && !showReadingRows) {
            emptyList()
        } else {
            activeHistory
        }
    }
    val effectiveBrowseMode = if (journalEnabled && !showReadingRows) {
        TimelineBrowseMode.JOURNAL
    } else {
        browseMode
    }
    val viewportStart = viewportSnapshot?.startMillis ?: activeRange?.startMillis ?: availableRange?.startMillis
    val viewportEnd = viewportSnapshot?.endMillis ?: activeRange?.endMillis ?: availableRange?.endMillis
    val visibleTimelineRows = remember(filteredHistory, filteredJournalEntries, viewportStart, viewportEnd, effectiveBrowseMode) {
        val windowStart = viewportStart ?: Long.MIN_VALUE
        val windowEnd = viewportEnd ?: Long.MAX_VALUE
        val visibleHistory = filteredHistory.sliceByTimestampRange(windowStart, windowEnd)
        val visibleJournalEntries = filteredJournalEntries.filter { entry ->
            entry.timestamp in windowStart..windowEnd
        }
        buildTimelineRows(
            points = visibleHistory,
            entries = visibleJournalEntries,
            browseMode = effectiveBrowseMode,
            trendSource = sortedHistory
        )
    }
    val visibleSections = remember(visibleTimelineRows) { buildHistorySections(visibleTimelineRows) }
    // The reading rows' "Δ", the dashboard's setting and function: null while the
    // setting is off, so the row's slot stays hidden exactly as it does today. The
    // index is built once per history; the visible window only asks it.
    // The Δ states a movement, so it reads the reading as the app reasons with it rather
    // than as it was stored — the same series the dashboard, the notification and the delta
    // alarms use. Built once per history alongside the index it feeds.
    val evaluationHistory = remember(sortedHistory, evaluationSmoothingMinutes, collapseSmoothedData) {
        buildSmoothedConsumerHistory(
            points = sortedHistory,
            evaluationSmoothingMinutes = evaluationSmoothingMinutes,
            collapseChunks = collapseSmoothedData
        )
    }
    val rowDeltaIndex = remember(showRowDelta, evaluationHistory) {
        if (showRowDelta) {
            RowDeltaIndex(
                evaluationHistory,
                sameSensor = SensorIdentity::matches,
                isShared = { HistoryRepository.isImportedHistorySerial(it) }
            )
        } else null
    }
    val rowDeltas = remember(rowDeltaIndex, visibleTimelineRows, unit, deltaIntervalMinutes) {
        rowDeltaIndex?.deltasFor(
            visibleTimelineRows.mapNotNull(TimelineRowItem::point),
            tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit),
            deltaIntervalMinutes
        ).orEmpty()
    }
    // Not keyed on the history: the markers do not depend on it, and keying on
    // it rebuilt them — and so recomposed the chart — on every new reading.
    val journalMarkers = remember(filteredJournalEntries, journalPresetsById, journalFoodsById, unit) {
        buildJournalChartMarkers(filteredJournalEntries, journalPresetsById, unit, journalFoodsById)
    }
    val journalEntriesById = remember(filteredJournalEntries) { filteredJournalEntries.associateBy { it.id } }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = tk.glucodata.data.HistoryExporter.importFromCsv(context, uri)
                if (result.success) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.imported_readings_count, result.successCount),
                        Toast.LENGTH_LONG
                    ).show()
                    UiRefreshBus.requestDataRefresh()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_failed_with_error, result.errorMessage ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    onBack?.let { handleBack ->
                        IconButton(onClick = handleBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    }
                },
                actions = {
                    if (showTransferActions) {
                        if (journalEnabled && onAddJournalEntry != null) {
                            IconButton(
                                onClick = {
                                    onAddJournalEntry(
                                        journalQuickAddTimestamp(
                                            viewportSnapshot?.selectedPoint?.timestamp,
                                            System.currentTimeMillis(),
                                            quickAddAlwaysNow
                                        ),
                                        selectedJournalTypes.singleOrNull(),
                                        viewportSnapshot?.selectedPoint?.value
                                            ?.takeIf { !quickAddAlwaysNow }
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null
                                )
                            }
                        }
                        IconButton(onClick = { showExportSheet = true }) {
                            Icon(
                                imageVector = Icons.Filled.CloudUpload,
                                contentDescription = stringResource(R.string.export_data)
                            )
                        }
                        IconButton(onClick = { importLauncher.launch(arrayOf("text/csv", "text/tab-separated-values", "text/plain", "*/*")) }) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = stringResource(R.string.import_data)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (effectiveExtents == null && journalEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_data_available),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "history-range-selector") {
                Box(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
                    StatsRangeSelectorControl(
                        selectedRange = selectedHistoryRange,
                        activeRange = activeRange,
                        isLoading = false,
                        hasData = visibleTimelineRows.isNotEmpty(),
                        readingCount = if (effectiveBrowseMode == TimelineBrowseMode.JOURNAL) {
                            visibleTimelineRows.size
                        } else if (journalEnabled && !showReadingRows) {
                            0
                        } else {
                            rangeSummary?.readingCount ?: 0
                        },
                        countLabelResId = if (effectiveBrowseMode == TimelineBrowseMode.JOURNAL) {
                            R.string.journal_visible_events
                        } else {
                            R.string.readings
                        },
                        onRangeSelected = { range ->
                            selectedHistoryRange = range
                            viewportSnapshot = null
                        },
                        onCustomRangeClick = { showDateRangePicker = true }
                    )
                }
            }

            if (activeHistory.isNotEmpty() || rangeSummary != null) {
                item(key = "history-chart") {
                    Box(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
                        DashboardChartSection(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp),
                            appChartRangeColors = chartRangeColors,
                            glucoseHistory = activeHistory,
                            // The chart may pan over the whole active range, of
                            // which it holds a window; "latest" is the range's
                            // last reading, as it was when the list was the range.
                            dataBounds = rangeSummary?.let { ChartDataBounds(it.earliestMs, it.latestMs) },
                            onVisibleRangeChanged = onVisibleRangeChanged,
                            journalMarkers = journalMarkers,
                            graphSmoothingMinutes = graphSmoothingMinutes,
                            collapseSmoothedData = collapseSmoothedData,
                            previewWindowMode = previewWindowMode,
                            graphLow = graphLow,
                            graphHigh = graphHigh,
                            targetLow = targetLow,
                            targetHigh = targetHigh,
                            unit = unit,
                            viewMode = viewMode,
                            calibrations = calibrations,
                            onTimeRangeSelected = { selectedChartRange = it },
                            selectedTimeRange = selectedChartRange,
                            isExpanded = false,
                            expandedProgress = 0f,
                            onToggleExpanded = null,
                            onPointClick = onPointClick,
                            onCalibrationClick = null,
                            onJournalMarkerClick = { entryId ->
                                journalEntriesById[entryId]?.let { onJournalEntryClick?.invoke(it) }
                            },
                            resetToLatestOnResume = false,
                            onViewportSnapshotChanged = { viewportSnapshot = it }
                        )
                    }
                }
            }
            if (journalEnabled) {
                item(key = "history-journal-filter") {
                    val filterOptions = remember {
                        listOf(HistoryFilterOption.Readings) +
                            JournalEntryType.entries.map { HistoryFilterOption.Journal(it) }
                    }
                    val selectedFilterOptions = remember(showReadingRows, selectedJournalTypes) {
                        buildList {
                            if (showReadingRows) add(HistoryFilterOption.Readings)
                            selectedJournalTypes.forEach { add(HistoryFilterOption.Journal(it)) }
                        }
                    }
                    val selectedFilterContentColor = MaterialTheme.colorScheme.onSurface
                    val selectedFilterContainerBase = MaterialTheme.colorScheme.surfaceContainerHigh
                    val readingsSelectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    val readingsSelectedIconColor = MaterialTheme.colorScheme.onSurface
                    val readingsUnselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ConnectedButtonGroup(
                        options = filterOptions,
                        selectedOptions = selectedFilterOptions,
                        multiSelect = true,
                        onOptionSelected = { filterOption ->
                            when (filterOption) {
                                HistoryFilterOption.Readings -> {
                                    showReadingRows = !showReadingRows
                                }
                                is HistoryFilterOption.Journal -> {
                                    val filterType = filterOption.type
                                    selectedJournalTypeFilters = if (filterType in selectedJournalTypes) {
                                        selectedJournalTypes.filterNot { it == filterType }.map { it.name }
                                    } else {
                                        (selectedJournalTypes + filterType).map { it.name }
                                    }
                                }
                            }
                            viewportSnapshot = null
                        },
                        label = { _ -> },
                        icon = { filterOption -> filterOption.historyFilterIcon() },
                        selectedContainerColorFor = { filterOption ->
                            when (filterOption) {
                                HistoryFilterOption.Readings -> readingsSelectedContainerColor
                                is HistoryFilterOption.Journal -> journalTypeSelectedContainerColor(
                                    filterOption.type,
                                    selectedFilterContainerBase
                                )
                            }
                        },
                        selectedContentColorFor = { selectedFilterContentColor },
                        iconTint = { filterOption, selected ->
                            when (filterOption) {
                                HistoryFilterOption.Readings -> if (selected) {
                                    readingsSelectedIconColor
                                } else {
                                    readingsUnselectedIconColor
                                }
                                is HistoryFilterOption.Journal -> journalTypeColor(filterOption.type)
                            }
                        },
                        modifier = Modifier
                            .padding(start = 16.dp, top = 12.dp, end = 16.dp)
                            .fillMaxWidth(),
                        itemHeight = 44.dp,
                        spacing = 3.dp,
                        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (visibleSections.isEmpty()) {
                item(key = "history-empty-window") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(
                                if (effectiveBrowseMode == TimelineBrowseMode.JOURNAL) {
                                    R.string.journal_empty
                                } else {
                                    R.string.no_data_available
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item(key = "history-list-gap") {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                visibleSections.forEachIndexed { sectionIndex, section ->
                    item(key = "history-date-${section.date.toEpochDay()}") {
                        HistoryDateMarker(
                            label = section.label,
                            modifier = Modifier
                                .padding(start = 32.dp, top = if (sectionIndex == 0) 0.dp else 12.dp, end = 16.dp, bottom = 8.dp)
                                .animateItem()
                        )
                    }

                    itemsIndexed(
                        items = section.items,
                        key = { index, _ -> section.keys[index] }
                    ) { index, item ->
                        val readingPoint = item.point
                        if (readingPoint != null) {
                            ReadingRow(
                                point = readingPoint,
                                unit = unit,
                                viewMode = viewMode,
                                index = 0,
                                totalCount = section.items.size,
                                history = item.trendHistory,
                                deltaText = rowDeltas[readingPoint]?.text,
                                deltaRateMgdlPerMinute = rowDeltas[readingPoint]?.rateMgdlPerMinute,
                                sensorId = sensorId,
                                calibrations = calibrations,
                                highlightLeadRow = false,
                                showLeadingAction = journalEnabled && onAddJournalEntry != null,
                                onLeadingActionClick = if (journalEnabled && onAddJournalEntry != null) {
                                    {
                                        onAddJournalEntry(
                                            readingPoint.timestamp,
                                            selectedJournalTypes.singleOrNull(),
                                            readingPoint.value
                                        )
                                    }
                                } else {
                                    null
                                },
                                isGroupStart = index == 0,
                                isGroupEnd = index == section.items.lastIndex,
                                dividerHorizontalInset = 0.dp,
                                onValueClick = { onPointClick?.invoke(readingPoint) },
                                onDeleteReading = onDeleteReading,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .animateItem()
                            )
                        } else {
                            JournalTimelineRow(
                                timestamp = item.timestamp,
                                unit = unit,
                                journalEntries = item.journalEntries,
                                journalPresetsById = journalPresetsById,
                                journalFoodsById = journalFoodsById,
                                onJournalEntryClick = onJournalEntryClick,
                                onAddJournalEntry = if (journalEnabled && onAddJournalEntry != null) {
                                    {
                                        onAddJournalEntry(
                                            item.timestamp,
                                            selectedJournalTypes.singleOrNull(),
                                            null
                                        )
                                    }
                                } else {
                                    null
                                },
                                index = index,
                                totalCount = section.items.size,
                                dividerHorizontalInset = 0.dp,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDateRangePicker) {
        val initialRange = clampStatsDateRangeToAvailable(activeRange, availableRange) ?: availableRange
        val availableStartDateMillis = availableRange?.startMillis?.let(::toPickerUtcDateMillis)
        val availableEndDateMillis = availableRange?.endMillis?.let(::toPickerUtcDateMillis)
        val dateRangePickerState = rememberDateRangePickerState(
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
        val canSaveRange =
            dateRangePickerState.selectedStartDateMillis != null &&
                dateRangePickerState.selectedEndDateMillis != null

        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = dateRangePickerState.selectedStartDateMillis
                            ?.let { pickerUtcDateMillisToLocalStart(it) }
                            ?: return@TextButton
                        val end = dateRangePickerState.selectedEndDateMillis
                            ?.let { pickerUtcDateMillisToLocalEnd(it) }
                            ?: return@TextButton
                        customRangeStartMillis = start
                        customRangeEndMillis = end
                        selectedHistoryRange = null
                        viewportSnapshot = null
                        showDateRangePicker = false
                    },
                    enabled = canSaveRange
                ) {
                    Text(text = stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateRangePicker = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier.height(448.dp),
                title = {},
                headline = {
                    StatsDateRangePickerHeadline(dateRangePickerState)
                },
                showModeToggle = true
            )
        }
    }

    if (showExportSheet && showTransferActions) {
        HistoryExportSheet(
            onDismiss = { showExportSheet = false },
            sheetState = rememberModalBottomSheetState()
        )
    }
}

@Composable
private fun HistoryDateMarker(
    label: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
