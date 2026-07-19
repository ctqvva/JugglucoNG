@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalPeriodSummary
import tk.glucodata.data.journal.JournalPeriodSummaryCalculator
import tk.glucodata.ui.ChartViewportSnapshot
import tk.glucodata.ui.DashboardChartSection
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.JournalTimelineRow
import tk.glucodata.ui.ReadingRow
import tk.glucodata.ui.TimeRange
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.rememberAdaptiveWindowMetrics
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private data class JournalLedgerItem(
    val timestamp: Long,
    val entries: List<JournalEntry>,
    val point: GlucosePoint?
)

private data class JournalDateSection(
    val date: LocalDate,
    val label: String,
    val items: List<JournalLedgerItem>
)

@Composable
fun JournalScreen(
    glucoseHistory: List<GlucosePoint>,
    unit: String,
    viewMode: Int,
    graphLow: Float,
    graphHigh: Float,
    targetLow: Float,
    targetHigh: Float,
    graphSmoothingLevel: Int,
    previewWindowMode: Int,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity>,
    journalEntries: List<JournalEntry>,
    journalInsulinPresets: List<JournalInsulinPreset>,
    journalFoods: List<JournalFood>,
    sensorId: String?,
    onPointClick: ((GlucosePoint) -> Unit)?,
    onJournalEntryClick: ((JournalEntry) -> Unit)?,
    onAddJournalEntry: (Long, JournalEntryType?, Float?, Float?) -> Unit,
    onOpenFoodLibrary: () -> Unit,
    onOpenInsulinLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    useStatusBarsPadding: Boolean = true,
    bottomContentPadding: Dp = 104.dp,
    chartRangeColors: Boolean = false
) {
    val view = LocalView.current
    val sortedHistory = remember(glucoseHistory) { glucoseHistory.sortedBy { it.timestamp } }
    val presetsById = remember(journalInsulinPresets) { journalInsulinPresets.associateBy { it.id } }
    val foodsById = remember(journalFoods) { journalFoods.associateBy { it.id } }
    var selectedChartRange by rememberSaveable { mutableStateOf(TimeRange.H3) }
    var viewportSnapshot by remember { mutableStateOf<ChartViewportSnapshot?>(null) }
    var selectedTypeFilters by rememberSaveable {
        mutableStateOf(JournalEntryType.entries.map { it.name })
    }
    var chartActionTimestamp by rememberSaveable { mutableStateOf<Long?>(null) }
    var chartActionDisplayValue by remember { mutableStateOf<Float?>(null) }
    var chartActionAmountFraction by remember { mutableStateOf<Float?>(null) }
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    var showAllRecords by rememberSaveable { mutableStateOf(false) }

    val selectedTypes = remember(selectedTypeFilters) {
        selectedTypeFilters.mapNotNull { name ->
            runCatching { JournalEntryType.valueOf(name) }.getOrNull()
        }
    }
    val filteredEntries = remember(journalEntries, selectedTypes) {
        journalEntries.filter { it.type in selectedTypes }
    }
    val zone = remember { ZoneId.systemDefault() }
    val viewportMidpoint = viewportSnapshot?.let { snapshot ->
        snapshot.startMillis + ((snapshot.endMillis - snapshot.startMillis) / 2L)
    }
    val selectedTimestamp = viewportSnapshot?.selectedPoint?.timestamp
        ?: viewportMidpoint
        ?: sortedHistory.lastOrNull()?.timestamp
        ?: journalEntries.maxOfOrNull { it.timestamp }
        ?: System.currentTimeMillis()
    val selectedDate = remember(selectedTimestamp, zone) {
        Instant.ofEpochMilli(selectedTimestamp).atZone(zone).toLocalDate()
    }
    val selectedDayStart = remember(selectedDate, zone) {
        selectedDate.atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val selectedDayEnd = remember(selectedDate, zone) {
        selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val selectedDateLabel = remember(selectedDate) {
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(selectedDayStart))
    }
    val selectedDayEntries = remember(filteredEntries, selectedDayStart, selectedDayEnd) {
        filteredEntries.filter { it.timestamp in selectedDayStart until selectedDayEnd }
    }
    val ledgerEntries = if (showAllRecords) filteredEntries else selectedDayEntries
    val sections = remember(ledgerEntries, sortedHistory) { buildJournalSections(ledgerEntries, sortedHistory) }
    val periodSummary = remember(journalEntries, selectedDayStart, selectedDayEnd) {
        JournalPeriodSummaryCalculator.calculate(journalEntries, selectedDayStart, selectedDayEnd)
    }
    val markers = remember(filteredEntries, presetsById, foodsById, unit, sortedHistory) {
        buildJournalChartMarkers(filteredEntries, presetsById, unit, sortedHistory, foodsById)
    }
    val entriesById = remember(filteredEntries) { filteredEntries.associateBy { it.id } }
    val selectedDisplayGlucose = viewportSnapshot?.selectedPoint?.value
        ?: viewportSnapshot?.visiblePoints?.minByOrNull { kotlin.math.abs(it.timestamp - selectedTimestamp) }?.value
    val isLandscape = rememberAdaptiveWindowMetrics().isLandscape

    LaunchedEffect(selectedDate) {
        showAllRecords = false
    }

    fun clearChartAction() {
        chartActionTimestamp = null
        chartActionDisplayValue = null
        chartActionAmountFraction = null
    }

    val chartPanel: @Composable (Modifier) -> Unit = { chartModifier ->
        Box(modifier = chartModifier) {
            DashboardChartSection(
                modifier = Modifier.matchParentSize(),
                appChartRangeColors = chartRangeColors,
                glucoseHistory = sortedHistory,
                journalMarkers = markers,
                graphSmoothingLevel = graphSmoothingLevel,
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
                onPointClick = {
                    clearChartAction()
                    onPointClick?.invoke(it)
                },
                onCalibrationClick = null,
                onTimelineTap = { suggestion ->
                    if (chartActionTimestamp != null && !suggestion.forceMenu) {
                        clearChartAction()
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    } else {
                        chartActionTimestamp = suggestion.timestamp
                        chartActionDisplayValue = suggestion.suggestedDisplayGlucose
                        chartActionAmountFraction = suggestion.normalizedYFraction
                        view.performHapticFeedback(
                            if (suggestion.forceMenu) HapticFeedbackConstants.LONG_PRESS
                            else HapticFeedbackConstants.CLOCK_TICK
                        )
                    }
                },
                journalActionTimestamp = chartActionTimestamp,
                journalActionDisplayValue = chartActionDisplayValue,
                onDismissJournalAction = { clearChartAction() },
                onJournalMarkerClick = { entryId ->
                    entriesById[entryId]?.let { onJournalEntryClick?.invoke(it) }
                },
                onViewportSnapshotChanged = { viewportSnapshot = it }
            )

            chartActionTimestamp?.let { actionTimestamp ->
                JournalFloatingActionMenu(
                    visible = true,
                    selectedTimestamp = actionTimestamp,
                    viewportSnapshot = viewportSnapshot,
                    menuTopOffset = 40.dp,
                    menuItemSpacing = 6.dp,
                    menuYOffset = (-36).dp,
                    modifier = Modifier.matchParentSize(),
                    onTypeSelected = { type ->
                        onAddJournalEntry(
                            actionTimestamp,
                            type,
                            chartActionDisplayValue,
                            chartActionAmountFraction
                        )
                        clearChartAction()
                        fabExpanded = false
                    }
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (useStatusBarsPadding) Modifier.statusBarsPadding() else Modifier)
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(0.34f)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomContentPadding)
                ) {
                    if (showTitle) {
                        item(key = "journal-landscape-title") {
                            JournalHeader(
                                onOpenFoodLibrary = onOpenFoodLibrary,
                                onOpenInsulinLibrary = onOpenInsulinLibrary
                            )
                        }
                    }
                    item(key = "journal-landscape-filter") {
                        JournalTypeFilter(
                            selectedTypes = selectedTypes,
                            onToggle = { type ->
                                selectedTypeFilters = if (type in selectedTypes) {
                                    selectedTypes.filterNot { it == type }.map { it.name }
                                } else {
                                    (selectedTypes + type).map { it.name }
                                }
                                clearChartAction()
                            }
                        )
                    }
                    item(key = "journal-landscape-scope") {
                        JournalRecordScopeRow(
                            showAllRecords = showAllRecords,
                            selectedDateLabel = selectedDateLabel,
                            onToggle = { showAllRecords = !showAllRecords }
                        )
                    }
                    journalLedgerItems(
                        sections = sections,
                        unit = unit,
                        viewMode = viewMode,
                        sensorId = sensorId,
                        calibrations = calibrations,
                        presetsById = presetsById,
                        foodsById = foodsById,
                        selectedTypes = selectedTypes,
                        onJournalEntryClick = onJournalEntryClick,
                        onAddJournalEntry = onAddJournalEntry
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.66f)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    JournalMetricsPanel(
                        summary = periodSummary,
                        dateLabel = selectedDateLabel,
                        compact = true
                    )
                    if (sortedHistory.isNotEmpty()) {
                        chartPanel(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = if (showTitle) 16.dp else 8.dp,
                bottom = bottomContentPadding
            )
        ) {
            if (showTitle) {
                item(key = "journal-title") {
                    JournalHeader(
                        onOpenFoodLibrary = onOpenFoodLibrary,
                        onOpenInsulinLibrary = onOpenInsulinLibrary
                    )
                }
            }

            item(key = "journal-metrics") {
                JournalMetricsPanel(
                    summary = periodSummary,
                    dateLabel = selectedDateLabel,
                    compact = false
                )
            }

            if (sortedHistory.isNotEmpty()) {
                item(key = "journal-chart") {
                    Spacer(modifier = Modifier.height(12.dp))
                    chartPanel(
                        Modifier
                            .fillMaxWidth()
                            .height(if (showTitle) 324.dp else 348.dp)
                    )
                }
            }

            item(key = "journal-filter") {
                Spacer(modifier = Modifier.height(12.dp))
                JournalTypeFilter(
                    selectedTypes = selectedTypes,
                    onToggle = { type ->
                        selectedTypeFilters = if (type in selectedTypes) {
                            selectedTypes.filterNot { it == type }.map { it.name }
                        } else {
                            (selectedTypes + type).map { it.name }
                        }
                        clearChartAction()
                    }
                )
            }

            item(key = "journal-record-scope") {
                JournalRecordScopeRow(
                    showAllRecords = showAllRecords,
                    selectedDateLabel = selectedDateLabel,
                    onToggle = { showAllRecords = !showAllRecords }
                )
            }

            journalLedgerItems(
                sections = sections,
                unit = unit,
                viewMode = viewMode,
                sensorId = sensorId,
                calibrations = calibrations,
                presetsById = presetsById,
                foodsById = foodsById,
                selectedTypes = selectedTypes,
                onJournalEntryClick = onJournalEntryClick,
                onAddJournalEntry = onAddJournalEntry
            )
        }
        }

        JournalExpandableFab(
            expanded = fabExpanded,
            onExpandedChange = {
                fabExpanded = it
                if (it) clearChartAction()
            },
            onTypeSelected = { type ->
                onAddJournalEntry(
                    selectedTimestamp,
                    type,
                    selectedDisplayGlucose.takeIf { type == JournalEntryType.FINGERSTICK },
                    null
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
        )
    }
}

@Composable
private fun JournalRecordScopeRow(
    showAllRecords: Boolean,
    selectedDateLabel: String,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (showAllRecords) stringResource(R.string.journal_title) else selectedDateLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onToggle) {
            Text(
                stringResource(
                    if (showAllRecords) R.string.journal_show_selected_day
                    else R.string.journal_show_all_records
                )
            )
        }
    }
}

private fun LazyListScope.journalLedgerItems(
    sections: List<JournalDateSection>,
    unit: String,
    viewMode: Int,
    sensorId: String?,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity>,
    presetsById: Map<Long, JournalInsulinPreset>,
    foodsById: Map<Long, JournalFood>,
    selectedTypes: List<JournalEntryType>,
    onJournalEntryClick: ((JournalEntry) -> Unit)?,
    onAddJournalEntry: (Long, JournalEntryType?, Float?, Float?) -> Unit
) {
    if (sections.isEmpty()) {
        item(key = "journal-ledger-empty") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.journal_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    sections.forEachIndexed { sectionIndex, section ->
        item(key = "journal-ledger-date-${section.date.toEpochDay()}") {
            Text(
                text = section.label,
                modifier = Modifier.padding(
                    start = 16.dp,
                    top = if (sectionIndex == 0) 12.dp else 18.dp,
                    bottom = 8.dp
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        itemsIndexed(
            items = section.items,
            key = { index, item ->
                "ledger-${item.timestamp}-${item.entries.joinToString(",") { it.id.toString() }}-$index"
            }
        ) { index, item ->
            val point = item.point
            if (point != null) {
                val sectionPoints = section.items.mapNotNull(JournalLedgerItem::point)
                val pointIndex = sectionPoints.indexOfFirst { it.timestamp == point.timestamp }
                    .takeIf { it >= 0 }
                    ?: index
                ReadingRow(
                    point = point,
                    unit = unit,
                    viewMode = viewMode,
                    index = pointIndex,
                    totalCount = section.items.size,
                    history = sectionPoints,
                    sensorId = sensorId,
                    calibrations = calibrations,
                    journalEntries = item.entries,
                    journalPresetsById = presetsById,
                    journalFoodsById = foodsById,
                    journalChipExpanded = true,
                    onJournalEntryClick = onJournalEntryClick,
                    highlightLeadRow = false,
                    showLeadingAction = false,
                    onLeadingActionClick = {
                        onAddJournalEntry(item.timestamp, selectedTypes.singleOrNull(), point.value, null)
                    },
                    isGroupStart = index == 0,
                    isGroupEnd = index == section.items.lastIndex,
                    dividerHorizontalInset = 0.dp,
                    onValueClick = {
                        onAddJournalEntry(item.timestamp, selectedTypes.singleOrNull(), point.value, null)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                JournalTimelineRow(
                    timestamp = item.timestamp,
                    unit = unit,
                    journalEntries = item.entries,
                    journalPresetsById = presetsById,
                    journalFoodsById = foodsById,
                    onJournalEntryClick = onJournalEntryClick,
                    onAddJournalEntry = {
                        onAddJournalEntry(item.timestamp, selectedTypes.singleOrNull(), null, null)
                    },
                    index = index,
                    totalCount = section.items.size,
                    dividerHorizontalInset = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun JournalHeader(
    onOpenFoodLibrary: () -> Unit,
    onOpenInsulinLibrary: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.journal_title),
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenFoodLibrary, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Restaurant,
                    contentDescription = stringResource(R.string.journal_food_library),
                    tint = journalTypeColor(JournalEntryType.CARBS)
                )
            }
            IconButton(onClick = onOpenInsulinLibrary, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Vaccines,
                    contentDescription = stringResource(R.string.journal_insulin_library),
                    tint = journalTypeColor(JournalEntryType.INSULIN)
                )
            }
        }
    }
}

@Composable
private fun JournalMetricsPanel(
    summary: JournalPeriodSummary,
    dateLabel: String,
    compact: Boolean
) {
    val foodDetail = buildList {
        add(dateLabel)
        if (summary.proteinGrams > 0f) {
            add(stringResource(R.string.journal_food_protein_short, formatJournalMetric(summary.proteinGrams)))
        }
        if (summary.fatGrams > 0f) {
            add(stringResource(R.string.journal_food_fat_short, formatJournalMetric(summary.fatGrams)))
        }
    }.joinToString(" · ")
    val cards: @Composable (Modifier) -> Unit = { cardModifier ->
        JournalMetricCard(
            title = stringResource(R.string.journal_type_food),
            value = "${formatJournalMetric(summary.carbsGrams, wholeNumber = true)} g",
            detail = foodDetail,
            icon = Icons.Default.Restaurant,
            type = JournalEntryType.CARBS,
            modifier = cardModifier
        )
        JournalMetricCard(
            title = stringResource(R.string.journal_type_insulin),
            value = "${formatJournalMetric(summary.insulinUnits)} U",
            detail = dateLabel,
            icon = Icons.Default.Vaccines,
            type = JournalEntryType.INSULIN,
            modifier = cardModifier
        )
        JournalMetricCard(
            title = stringResource(R.string.journal_type_activity),
            value = stringResource(R.string.minutes_short_format, summary.activityMinutes),
            detail = dateLabel,
            icon = Icons.Default.DirectionsRun,
            type = JournalEntryType.ACTIVITY,
            modifier = cardModifier
        )
    }

    if (compact) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cards(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JournalMetricCard(
                    title = stringResource(R.string.journal_type_food),
                    value = "${formatJournalMetric(summary.carbsGrams, wholeNumber = true)} g",
                    detail = foodDetail,
                    icon = Icons.Default.Restaurant,
                    type = JournalEntryType.CARBS,
                    modifier = Modifier.weight(1f)
                )
                JournalMetricCard(
                    title = stringResource(R.string.journal_type_insulin),
                    value = "${formatJournalMetric(summary.insulinUnits)} U",
                    detail = dateLabel,
                    icon = Icons.Default.Vaccines,
                    type = JournalEntryType.INSULIN,
                    modifier = Modifier.weight(1f)
                )
            }
            JournalMetricCard(
                title = stringResource(R.string.journal_type_activity),
                value = stringResource(R.string.minutes_short_format, summary.activityMinutes),
                detail = dateLabel,
                icon = Icons.Default.DirectionsRun,
                type = JournalEntryType.ACTIVITY,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun JournalMetricCard(
    title: String,
    value: String,
    detail: String,
    icon: ImageVector,
    type: JournalEntryType,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val tint = journalTypeColor(type)
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.heightIn(min = 74.dp),
        shape = RoundedCornerShape(18.dp),
        color = journalTypeSelectedContainerColor(
            type,
            MaterialTheme.colorScheme.surfaceContainerHighest
        ).copy(alpha = 0.68f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(12.dp),
                color = tint.copy(alpha = 0.18f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun JournalTypeFilter(
    selectedTypes: List<JournalEntryType>,
    onToggle: (JournalEntryType) -> Unit
) {
    val selectedContainerBase = MaterialTheme.colorScheme.surfaceContainerHigh
    val selectedContentColor = MaterialTheme.colorScheme.onSurface
    val unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
    val unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ConnectedButtonGroup(
        options = JournalEntryType.entries,
        selectedOptions = selectedTypes,
        multiSelect = true,
        onOptionSelected = onToggle,
        label = { },
        icon = { it.journalActionIcon() },
        iconOnly = true,
        modifier = Modifier.fillMaxWidth(),
        itemHeight = 44.dp,
        spacing = 3.dp,
        selectedContainerColorFor = { type ->
            journalTypeSelectedContainerColor(type, selectedContainerBase)
        },
        selectedContentColorFor = { selectedContentColor },
        iconTint = { type, _ -> journalTypeColor(type) },
        unselectedContainerColor = unselectedContainerColor,
        unselectedContentColor = unselectedContentColor
    )
}

private fun buildJournalSections(
    entries: List<JournalEntry>,
    points: List<GlucosePoint>
): List<JournalDateSection> {
    if (entries.isEmpty()) return emptyList()
    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    return entries
        .groupBy { it.timestamp }
        .map { (timestamp, groupedEntries) ->
            JournalLedgerItem(
                timestamp = timestamp,
                entries = groupedEntries.sortedByDescending { it.timestamp },
                point = findClosestPoint(points, timestamp)
            )
        }
        .sortedByDescending { it.timestamp }
        .fold(mutableListOf<JournalDateSectionBuilder>()) { sections, item ->
            val date = Instant.ofEpochMilli(item.timestamp).atZone(zone).toLocalDate()
            val section = sections.lastOrNull()?.takeIf { it.date == date }
                ?: JournalDateSectionBuilder(
                    date = date,
                    label = formatter.format(Date(item.timestamp))
                ).also(sections::add)
            section.items.add(item)
            sections
        }
        .map { builder ->
            JournalDateSection(
                date = builder.date,
                label = builder.label,
                items = builder.items.toList()
            )
        }
}

private fun findClosestPoint(
    points: List<GlucosePoint>,
    timestamp: Long,
    maxDistanceMillis: Long = 20L * 60L * 1000L
): GlucosePoint? {
    if (points.isEmpty()) return null
    val insertionIndex = points.binarySearchBy(timestamp) { it.timestamp }
        .let { if (it >= 0) it else (-it - 1) }
        .coerceIn(0, points.lastIndex)
    var closestPoint: GlucosePoint? = null
    var closestDistance = Long.MAX_VALUE
    for (candidateIndex in maxOf(0, insertionIndex - 1)..minOf(points.lastIndex, insertionIndex + 1)) {
        val candidate = points[candidateIndex]
        val distance = kotlin.math.abs(candidate.timestamp - timestamp)
        if (distance < closestDistance) {
            closestPoint = candidate
            closestDistance = distance
        }
    }
    return closestPoint.takeIf { closestDistance <= maxDistanceMillis }
}

private fun formatJournalMetric(value: Float, wholeNumber: Boolean = false): String {
    val pattern = when {
        wholeNumber -> "%.0f"
        kotlin.math.abs(value) >= 10f -> "%.0f"
        else -> "%.1f"
    }
    return String.format(Locale.getDefault(), pattern, value)
}

private class JournalDateSectionBuilder(
    val date: LocalDate,
    val label: String,
    val items: MutableList<JournalLedgerItem> = mutableListOf()
)
