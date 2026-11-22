@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tk.glucodata.ui

import androidx.compose.material3.ButtonDefaults
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tk.glucodata.R
import tk.glucodata.GlucoseReadingSource
import tk.glucodata.SensorIdentity
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.ui.journal.JournalInlineChip
import tk.glucodata.ui.viewmodel.SensorColors
import tk.glucodata.ui.symbols.Symbols

@Composable
fun ReadingRow(
    point: GlucosePoint,
    unit: String,
    viewMode: Int = 0,
    index: Int = 0,
    totalCount: Int = 1,
    history: List<GlucosePoint> = emptyList(), // Advanced Trend: Need history
    // The row's historical "Δ" (see readingDeltaTexts); null hides the slot entirely.
    deltaText: String? = null,
    /**
     * The same movement the Δ beside it states, in mg/dL per minute. When it is there the
     * arrow turns by it, so the two cannot disagree; without it the arrow falls back to the
     * regression, which is also the case where no number sits next to it.
     */
    deltaRateMgdlPerMinute: Float? = null,
    peerReadings: List<GlucosePoint> = emptyList(),
    peerSeries: Map<String, PeerSensorSeries> = emptyMap(),
    // True whenever the dashboard is in multi-sensor mode, even if THIS row has
    // no peer reading yet (e.g. a just-arrived primary reading). Keeps the
    // primary value's identity tint stable instead of flashing uncolored.
    multiSensorActive: Boolean = false,
    sensorId: String? = null,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity> = emptyList(),
    journalEntries: List<JournalEntry> = emptyList(),
    journalPresetsById: Map<Long, JournalInsulinPreset> = emptyMap(),
    journalFoodsById: Map<Long, JournalFood> = emptyMap(),
    journalChipExpanded: Boolean = false,
    onJournalEntryClick: ((JournalEntry) -> Unit)? = null,
    highlightLeadRow: Boolean = true,
    showLeadingAction: Boolean = false,
    leadingActionEmphasis: Float = 1f,
    onLeadingActionClick: (() -> Unit)? = null,
    onValueClick: (() -> Unit)? = null,
    onSensorValueClick: ((GlucosePoint) -> Unit)? = null,
    onDeleteReading: ((GlucosePoint) -> Unit)? = null,
    isGroupStart: Boolean = index == 0,
    isGroupEnd: Boolean = index == totalCount - 1,
    dividerHorizontalInset: Dp = 16.dp,
    onBaselineHeightMeasured: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // --- DYNAMIC COLOR LOGIC ---
    // User Request:
    // 1. Fresh (0-60s): Active Color -> Stock Color.
    // 2. Normal (60-90s): Stock Color.
    // 3. Stale (>90s): Stock Color -> Darker Shade.

    val isActive = highlightLeadRow && index == 0
    val view = LocalView.current
    var showDeleteDialog by rememberSaveable(point.timestamp, point.sensorSerial) { mutableStateOf(false) }
    val openDeleteDialog = {
        if (onDeleteReading != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showDeleteDialog = true
        }
    }
    val hasValueClick = onValueClick != null || onSensorValueClick != null
    val handlePrimaryValueClick: () -> Unit = {
        if (onSensorValueClick != null) {
            onSensorValueClick(point)
        } else {
            onValueClick?.invoke()
        }
    }

    val activeColor = MaterialTheme.colorScheme.secondaryContainer
    val stockColor = MaterialTheme.colorScheme.surfaceContainerLow
    val staleColor = MaterialTheme.colorScheme.surfaceContainerHighest // Slightly darker/different

    // The newest row eases from the accent colour to the stock row colour over its first minute
    // and darkens once the reading is overdue. The age ticks once a second, so it is read only
    // in the draw phase below: a tick redraws this row's background and divider, and never
    // recomposes the row.
    val ageState = remember(point.timestamp) { mutableLongStateOf(System.currentTimeMillis() - point.timestamp) }
    if (isActive) {
        LaunchedEffect(point.timestamp) {
            while (true) {
                ageState.longValue = System.currentTimeMillis() - point.timestamp
                delay(1000)
            }
        }
    }

    // Shape:
    // Active (Hero): "4dp bottom radius" to imply continuity. Top matches parent (16dp).
    // History: Rectangle (relies on parent clipping for its 4dp bottom).
    val shape = when {
        isActive && isGroupStart && isGroupEnd -> RoundedCornerShape(16.dp)
        isActive -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
        isGroupStart && isGroupEnd -> RoundedCornerShape(16.dp)
        isGroupStart -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        isGroupEnd -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RectangleShape
    }

    // --- ADVANCED TREND ENGINE ---
    // Calculate on the fly using the passed history subset. The engine reads
    // only timestamp, value and raw value, so it is handed the points as they
    // are: the round trip through the native point type was two copies of the
    // tail per row for nothing.
    val regressed = remember(history, index) {
        val relevantHistory = when {
            history.isEmpty() -> listOf(point)
            index == 0 -> history
            else -> history.drop(index)
        }
        tk.glucodata.logic.TrendEngine.calculateTrend(relevantHistory, useRaw = (viewMode == 1 || viewMode == 3), isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit))
    }
    // A row states one movement. Where the Δ knows it, the arrow says the same thing rather
    // than the longer regression, which answers a different question and can point the other
    // way during a reversal — Δ −5 beside an arrow up is the app contradicting itself.
    val trendResult = remember(regressed, deltaRateMgdlPerMinute) {
        rowTrendResult(regressed, deltaRateMgdlPerMinute)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isActive) {
                    Modifier.drawBehind {
                        drawOutline(
                            outline = shape.createOutline(size, layoutDirection, this),
                            color = activeRowColor(ageState.longValue, activeColor, stockColor, staleColor)
                        )
                    }
                } else {
                    Modifier
                }
            )
            .then(
                when {
                    onDeleteReading != null && hasValueClick -> Modifier.combinedClickable(
                        onClick = handlePrimaryValueClick,
                        onLongClick = { openDeleteDialog() },
                        hapticFeedbackEnabled = false
                    )
                    onDeleteReading != null -> Modifier.pointerInput(point.timestamp, point.sensorSerial) {
                        detectTapGestures(onLongPress = { openDeleteDialog() })
                    }
                    hasValueClick -> Modifier.clickable(onClick = handlePrimaryValueClick)
                    else -> Modifier
                }
            ),
        shape = shape,
        color = if (isActive) Color.Transparent else stockColor,
        // User Request: Kill shadows (0dp)
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val hasInlineJournalEntries = journalEntries.isNotEmpty() && onJournalEntryClick != null
            val useClassicLayout = !hasInlineJournalEntries
            val rowMinHeight = if (journalChipExpanded && hasInlineJournalEntries) 64.dp else 48.dp
            val valueMinWidth = when {
                // The value already measures at its natural width. Reserving another
                // 104 dp here forced three short journal chips onto two lines.
                hasInlineJournalEntries -> 80.dp
                showLeadingAction -> 104.dp
                else -> 124.dp
            }
            val leadingActionSlotWidth = 44.dp
            val trendSlotWidth = 30.dp
            val timeStyle = if (isActive) MaterialTheme.typography.bodySmallEmphasized else MaterialTheme.typography.bodySmall
            val timeColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            val cloneTransport = GlucoseReadingSource.cloneTransport(point.source)
            val isNightscoutSource = point.source == GlucoseReadingSource.NIGHTSCOUT
            val isRawModeRR = viewMode == 1 || viewMode == 3
            val calibrationSensorId = sensorId?.takeIf { it.isNotBlank() }
            // Recorded value if this reading has one, otherwise the projection —
            // see SealedGlucoseValue for why every surface asks the same way.
            val calibratedValueRR = SealedGlucoseValue.calibratedFor(
                point = point,
                isRawMode = isRawModeRR,
                sensorId = calibrationSensorId
            )
            val dvs = getDisplayValues(point, viewMode, unit, calibratedValueRR)
            val primaryBaseColor = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            // Multi-sensor: every sensor's value carries a subtle identity tint
            // (the primary a touch weaker than peers) so values pair with chart
            // traces. Tint is driven by multi-sensor MODE, not this row's peers,
            // so a freshly-arrived primary reading is tinted immediately.
            val multiSensorRow = multiSensorActive || peerReadings.isNotEmpty()
            val primaryColor = if (multiSensorRow) {
                val primarySerial = point.sensorSerial?.takeIf { it.isNotBlank() } ?: sensorId
                androidx.compose.ui.graphics.lerp(
                    primaryBaseColor,
                    SensorColors.getColor(SensorIdentity.resolveAppSensorId(primarySerial) ?: primarySerial.orEmpty()),
                    tk.glucodata.SensorVisuals.PRIMARY_TEXT_BLEND
                )
            } else {
                primaryBaseColor
            }
            val secondaryColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            val unitColor = secondaryColor.copy(alpha = 0.6f)
            val tertiaryColor = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            val valueStyle = MaterialTheme.typography.titleMedium
            // Peers read as clearly subordinate to the main value.
            val peerValueStyle = MaterialTheme.typography.bodySmall
            val isMultiSensor = multiSensorRow
            // In multi-sensor mode the arrow sits in a FlowRow next to peer
            // chips: keep it tight against the main value (not floating in a
            // wide right-aligned slot) and give it the primary identity tint.
            val primaryTrendColor = if (isMultiSensor) primaryColor.copy(alpha = 0.7f) else tertiaryColor

            @Composable
            fun ReadingTime() {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tk.glucodata.MinuteTimeFormat.format(point.timestamp),
                        style = timeStyle,
                        color = timeColor
                    )
                    if (cloneTransport != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        CloneSourceMark(
                            transport = cloneTransport,
                            showLabel = false,
                            tint = timeColor,
                            iconSize = 13.dp,
                        )
                    } else if (isNightscoutSource) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Symbols.CloudDownloadFilled,
                            contentDescription = stringResource(R.string.journal_source_nightscout),
                            tint = timeColor,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            @Composable
            fun ReadingValueContent(modifier: Modifier = Modifier) {
                Row(
                    modifier = modifier,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!deltaText.isNullOrEmpty()) {
                        Text(
                            text = deltaText,
                            style = timeStyle.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (isActive) 0.65f else 0.5f
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (tk.glucodata.data.calibration.CalibrationManager.hasCalibrationAt(point.timestamp, isRawModeRR)) {
                        Icon(
                            imageVector = Symbols.WaterDropFilled,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Text(
                        text = buildGlucoseString(dvs, primaryColor, secondaryColor, unitColor, true, "", tertiaryColor),
                        style = valueStyle.copy(fontFeatureSettings = "tnum")
                    )

                    if (isMultiSensor) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.width(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            tk.glucodata.ui.components.TrendIndicator(
                                trendResult = trendResult,
                                color = primaryTrendColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.width(trendSlotWidth),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            tk.glucodata.ui.components.TrendIndicator(
                                trendResult = trendResult,
                                color = tertiaryColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Peer serials are normalized to their logical sensor id by
            // MultiSensorDisplay.buildDisplayData -> direct map hits.
            fun seriesForSensor(serial: String?): PeerSensorSeries? =
                serial?.let { peerSeries[it] }

            @Composable
            fun PeerReadingValue(peer: GlucosePoint) {
                val peerSensorSeries = seriesForSensor(peer.sensorSerial)
                val peerColor = peerSensorSeries?.let { Color(it.colorArgb) }
                    ?: SensorColors.getColor(peer.sensorSerial.orEmpty())
                val peerMode = peerSensorSeries?.viewMode ?: viewMode
                val isRawModePeer = peerMode == 1 || peerMode == 3
                val peerSensorId = peer.sensorSerial?.takeIf { it.isNotBlank() }
                val peerCalibrationValue = remember(
                    peer.value,
                    peer.rawValue,
                    peer.timestamp,
                    peerSensorId,
                    peerMode,
                    calibrations
                ) {
                    SealedGlucoseValue.calibratedFor(
                        point = peer,
                        isRawMode = isRawModePeer,
                        sensorId = peerSensorId
                    )
                }
                val peerTrendResult = remember(peer.timestamp, peer.sensorSerial, peerSensorSeries, peerMode, unit) {
                    val nativeList = MultiSensorDisplay.recentWindow(
                        pointsAscending = peerSensorSeries?.points.orEmpty(),
                        untilTimestamp = peer.timestamp,
                        count = 24
                    )
                        .map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
                        .ifEmpty { listOf(tk.glucodata.GlucosePoint(peer.timestamp, peer.value, peer.rawValue)) }
                    tk.glucodata.logic.TrendEngine.calculateTrend(
                        nativeList,
                        useRaw = isRawModePeer,
                        isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit)
                    )
                }
                val peerDvs = getDisplayValues(peer, peerMode, unit, calibratedValue = peerCalibrationValue)
                val peerPrimaryColor = androidx.compose.ui.graphics.lerp(
                    MaterialTheme.colorScheme.onSurface,
                    peerColor,
                    tk.glucodata.SensorVisuals.PEER_TEXT_BLEND
                ).copy(alpha = if (isActive) 0.95f else 0.85f)
                val peerSecondaryColor = secondaryColor.copy(alpha = if (isActive) 0.72f else 0.62f)
                val peerUnitColor = unitColor.copy(alpha = 0.68f)
                Row(
                    modifier = Modifier.then(
                        if (onSensorValueClick != null) {
                            Modifier.clickable { onSensorValueClick(peer) }
                        } else {
                            Modifier
                        }
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildGlucoseString(
                            peerDvs,
                            peerPrimaryColor,
                            peerSecondaryColor,
                            peerUnitColor,
                            false,
                            "",
                            tertiaryColor.copy(alpha = 0.58f)
                        ),
                        style = peerValueStyle.copy(fontFeatureSettings = "tnum")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier.width(16.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        tk.glucodata.ui.components.TrendIndicator(
                            trendResult = peerTrendResult,
                            color = peerPrimaryColor.copy(alpha = 0.68f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            @Composable
            fun MultiSensorValueContent(modifier: Modifier = Modifier) {
                if (peerReadings.isEmpty()) {
                    ReadingValueContent(modifier)
                    return
                }
                FlowRow(
                    modifier = modifier,
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    ReadingValueContent()
                    peerReadings.forEach { peer ->
                        PeerReadingValue(peer)
                    }
                }
            }

            @Composable
            fun JournalAddAffordance(modifier: Modifier = Modifier) {
                if (showLeadingAction && onLeadingActionClick != null) {
                    Surface(
                        onClick = onLeadingActionClick,
                        modifier = modifier
                            .size(24.dp)
                            .alpha(leadingActionEmphasis.coerceIn(0f, 1f)),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                            alpha = 0.38f + (0.18f * leadingActionEmphasis.coerceIn(0f, 1f))
                        ),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Symbols.AddFilled,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.54f + (0.34f * leadingActionEmphasis.coerceIn(0f, 1f))
                                )
                            )
                        }
                    }
                }
            }

            if (useClassicLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onBaselineHeightMeasured != null) {
                                Modifier.onSizeChanged { onBaselineHeightMeasured(it.height) }
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReadingTime()

                    if (showLeadingAction) {
                        Box(
                            modifier = Modifier.width(leadingActionSlotWidth),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            JournalAddAffordance()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minWidth = valueMinWidth),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        MultiSensorValueContent(
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            } else {
                JournalReadingRowLayout(
                    rowMinHeight = rowMinHeight,
                    leadingGap = if (showLeadingAction) 16.dp else 12.dp,
                    valueMinWidth = valueMinWidth,
                    expandedChips = journalChipExpanded,
                    lead = {
                        Row(
                            modifier = if (onBaselineHeightMeasured != null) {
                                Modifier.onSizeChanged { onBaselineHeightMeasured(it.height) }
                            } else {
                                Modifier
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ReadingTime()

                            if (showLeadingAction) {
                                Box(
                                    modifier = Modifier.width(leadingActionSlotWidth),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    JournalAddAffordance()
                                }
                            }
                        }
                    },
                    chips = {
                        journalEntries.forEach { entry ->
                            JournalInlineChip(
                                entry = entry,
                                unit = unit,
                                insulinPreset = entry.insulinPresetId?.let(journalPresetsById::get),
                                food = entry.foodId?.let(journalFoodsById::get),
                                expanded = journalChipExpanded,
                                onClick = { onJournalEntryClick?.invoke(entry) }
                            )
                        }
                    },
                    value = {
                        Box(contentAlignment = Alignment.CenterEnd) {
                            MultiSensorValueContent(
                                modifier = Modifier.padding(start = 12.dp, end = 16.dp)
                            )
                        }
                    }
                )
            }

            if (index < totalCount - 1) {
                val dividerColor = MaterialTheme.colorScheme.outlineVariant
                Spacer(
                    modifier = Modifier
                        .padding(horizontal = dividerHorizontalInset)
                        .fillMaxWidth()
                        .height(1.dp)
                        .drawBehind {
                            val alpha = if (isActive) activeRowDividerAlpha(ageState.longValue) else ROW_DIVIDER_ALPHA
                            drawRect(dividerColor.copy(alpha = alpha))
                        }
                )
            }
        }
    }

    if (showDeleteDialog && onDeleteReading != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = stringResource(R.string.delete_reading_title)) },
            text = { Text(text = stringResource(R.string.delete_reading_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteReading(point)
                        showDeleteDialog = false
                    },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * A reading row that carries journal chips: time (and the add button) at the start, the value
 * at the end, chips in between. Only the first chip line shares its width with the value; chips
 * that do not fit there at their full width, as beside a calibrated value and its source value,
 * flow onto lines below that run on under the value to its end edge. Those lines start where
 * the first one does, so every row's chips keep one left edge. When not even the first chip
 * fits beside the value, the first line holds the time and value alone.
 */
@Composable
private fun JournalReadingRowLayout(
    rowMinHeight: Dp,
    leadingGap: Dp,
    valueMinWidth: Dp,
    expandedChips: Boolean,
    lead: @Composable () -> Unit,
    chips: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Layout(
        contents = listOf(lead, chips, value),
        modifier = modifier.fillMaxWidth()
    ) { (leadMeasurables, chipMeasurables, valueMeasurables), constraints ->
        val startPadding = ROW_START_PADDING.roundToPx()
        val gap = leadingGap.roundToPx()
        val leadMeasurable = leadMeasurables.single()
        val valueMeasurable = valueMeasurables.single()
        val leadWidth = leadMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            startPadding + leadWidth + gap + valueMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        }
        val chipsStart = (startPadding + leadWidth + gap).coerceAtMost(width)
        val valueMaxWidth = width - chipsStart
        val valuePlaceable = valueMeasurable.measure(
            Constraints(minWidth = valueMinWidth.roundToPx().coerceAtMost(valueMaxWidth), maxWidth = valueMaxWidth)
        )
        val besideValueWidth = (valueMaxWidth - valuePlaceable.width - INLINE_CHIPS_END_PADDING.roundToPx()).coerceAtLeast(0)
        val belowValueWidth = (valueMaxWidth - BELOW_VALUE_CHIPS_END_PADDING.roundToPx()).coerceAtLeast(0)
        val spacing = (if (expandedChips) 8.dp else 6.dp).roundToPx()
        val lines = packJournalChipLines(
            widths = IntArray(chipMeasurables.size) { chipMeasurables[it].maxIntrinsicWidth(Constraints.Infinity) },
            firstLineWidth = besideValueWidth,
            otherLinesWidth = belowValueWidth,
            spacing = spacing
        )
        val chipPlaceables = chipMeasurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints(maxWidth = if (lines.line[index] == 0) besideValueWidth else belowValueWidth))
        }
        val lineCount = (lines.line.maxOrNull() ?: -1) + 1
        val lineHeights = IntArray(lineCount)
        chipPlaceables.forEachIndexed { index, placeable ->
            val line = lines.line[index]
            lineHeights[line] = maxOf(lineHeights[line], placeable.height)
        }
        val firstLineEmpty = lineCount == 0 || lineHeights[0] == 0

        val lineMinHeight = if (firstLineEmpty) minOf(rowMinHeight, OWN_LINE_FIRST_LINE_HEIGHT).roundToPx() else rowMinHeight.roundToPx()
        val leadPlaceable = leadMeasurable.measure(
            Constraints(minHeight = lineMinHeight, maxWidth = (width - startPadding).coerceAtLeast(0))
        )
        val valueTop = ((lineMinHeight - valuePlaceable.height) / 2).coerceAtLeast(0)
        val valueBottom = valueTop + valuePlaceable.height
        val lineSpacing = 4.dp.roundToPx()

        val lineTops = IntArray(lineCount)
        val chipsBottom: Int
        if (firstLineEmpty) {
            var top = maxOf(leadPlaceable.height, valueBottom)
            for (line in 1 until lineCount) {
                lineTops[line] = top
                top += lineHeights[line] + lineSpacing
            }
            chipsBottom = if (lineCount > 1) top - lineSpacing + OWN_LINE_CHIPS_BOTTOM_PADDING.roundToPx() else 0
        } else {
            // The first chip line centres on the time and value, as it always has.
            val firstLineInset = maxOf(
                if (expandedChips) 8.dp.roundToPx() else 0,
                (lineMinHeight - lineHeights[0]) / 2
            )
            lineTops[0] = firstLineInset
            for (line in 1 until lineCount) {
                // From here on the lines run under the value, so they clear it as well.
                lineTops[line] = maxOf(
                    lineTops[line - 1] + lineHeights[line - 1],
                    if (line == 1) valueBottom else 0
                ) + lineSpacing
            }
            val bottomInset = if (expandedChips) 8.dp.roundToPx() else firstLineInset
            chipsBottom = maxOf(lineMinHeight, lineTops[lineCount - 1] + lineHeights[lineCount - 1] + bottomInset)
        }
        val height = maxOf(leadPlaceable.height, valueBottom, chipsBottom)

        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            leadPlaceable.placeRelative(startPadding, 0)
            valuePlaceable.placeRelative(width - valuePlaceable.width, valueTop)
            chipPlaceables.forEachIndexed { index, placeable ->
                val line = lines.line[index]
                val inLine = if (expandedChips) 0 else (lineHeights[line] - placeable.height) / 2
                placeable.placeRelative(chipsStart + lines.x[index], lineTops[line] + inLine)
            }
        }
    }
}

private val ROW_START_PADDING = 16.dp
private val INLINE_CHIPS_END_PADDING = 8.dp
/** The value's own end padding, so chip lines below the value end where the value does. */
private val BELOW_VALUE_CHIPS_END_PADDING = 16.dp
private val OWN_LINE_FIRST_LINE_HEIGHT = 48.dp
private val OWN_LINE_CHIPS_BOTTOM_PADDING = 12.dp

/** Which line each chip lands on, and its x in that line. Line 0 is beside the value and may stay empty. */
internal class JournalChipLines(val x: IntArray, val line: IntArray)

/**
 * Packs chips left to right at their natural [widths]. The first line is [firstLineWidth] wide
 * and takes a chip only whole; later lines are [otherLinesWidth] wide, and a chip too wide even
 * for one of those gets a line to itself.
 */
internal fun packJournalChipLines(widths: IntArray, firstLineWidth: Int, otherLinesWidth: Int, spacing: Int): JournalChipLines {
    val x = IntArray(widths.size)
    val line = IntArray(widths.size)
    var lineIndex = 0
    var lineX = 0
    var lineHasChip = false
    widths.forEachIndexed { index, natural ->
        val capacity = if (lineIndex == 0) firstLineWidth else otherLinesWidth
        val width = if (lineIndex == 0) natural else minOf(natural, capacity)
        val spacedX = if (lineHasChip) lineX + spacing else 0
        if ((lineHasChip || lineIndex == 0) && spacedX + width > capacity) {
            lineIndex += 1
            lineX = minOf(natural, otherLinesWidth)
            x[index] = 0
        } else {
            x[index] = spacedX
            lineX = spacedX + width
        }
        line[index] = lineIndex
        lineHasChip = true
    }
    return JournalChipLines(x, line)
}

@Composable
fun JournalTimelineRow(
    timestamp: Long,
    unit: String,
    journalEntries: List<JournalEntry>,
    journalPresetsById: Map<Long, JournalInsulinPreset> = emptyMap(),
    journalFoodsById: Map<Long, JournalFood> = emptyMap(),
    onJournalEntryClick: ((JournalEntry) -> Unit)? = null,
    onAddJournalEntry: (() -> Unit)? = null,
    index: Int = 0,
    totalCount: Int = 1,
    dividerHorizontalInset: Dp = 16.dp,
    // Lines the chips up with rows that do carry a value, at the cost of their width.
    reserveValueSlot: Boolean = true,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val rowMinHeight = 58.dp
    val timeSlotWidth = 58.dp
    val valueSlotWidth = 112.dp
    val shape = when {
        totalCount <= 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        index == totalCount - 1 -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RectangleShape
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onAddJournalEntry != null) {
                    Modifier.clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onAddJournalEntry()
                    }
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = rowMinHeight)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tk.glucodata.MinuteTimeFormat.format(timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(timeSlotWidth)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = rowMinHeight)
                        .padding(top = 8.dp, end = 12.dp, bottom = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        journalEntries.forEach { entry ->
                            JournalInlineChip(
                                entry = entry,
                                unit = unit,
                                insulinPreset = entry.insulinPresetId?.let(journalPresetsById::get),
                                food = entry.foodId?.let(journalFoodsById::get),
                                expanded = true,
                                onClick = { onJournalEntryClick?.invoke(entry) }
                            )
                        }
                    }
                }

                if (reserveValueSlot) {
                    Spacer(modifier = Modifier.width(valueSlotWidth))
                }
            }

            if (index < totalCount - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = dividerHorizontalInset),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
                    thickness = 1.dp
                )
            }
        }
    }
}

/**
 * The arrow's own reading of a row: the movement the Δ beside it states when there is one,
 * the regression otherwise. Only the velocity and the state it maps to are replaced; how
 * confident and how noisy the neighbourhood is are properties of the data, not of which
 * window was measured.
 */
internal fun rowTrendResult(
    regressed: tk.glucodata.logic.TrendEngine.TrendResult,
    deltaRateMgdlPerMinute: Float?,
): tk.glucodata.logic.TrendEngine.TrendResult {
    val rate = deltaRateMgdlPerMinute?.takeIf { it.isFinite() } ?: return regressed
    return regressed.copy(state = tk.glucodata.logic.TrendEngine.stateFor(rate), velocity = rate)
}

private const val ROW_DIVIDER_ALPHA = 0.1f
private const val ACTIVE_ROW_SETTLE_MS = 60_000L
private const val ACTIVE_ROW_STALE_AFTER_MS = 90_000L
private const val ACTIVE_ROW_STALE_SPAN_MS = 60_000L

/** Newest row: accent to stock over its first minute, stock until 90s, then toward the stale shade. */
internal fun activeRowColor(ageMs: Long, active: Color, stock: Color, stale: Color): Color = when {
    ageMs < ACTIVE_ROW_SETTLE_MS -> lerp(active, stock, (ageMs / ACTIVE_ROW_SETTLE_MS.toFloat()).coerceIn(0f, 1f))
    ageMs < ACTIVE_ROW_STALE_AFTER_MS -> stock
    else -> lerp(stock, stale, ((ageMs - ACTIVE_ROW_STALE_AFTER_MS) / ACTIVE_ROW_STALE_SPAN_MS.toFloat()).coerceIn(0f, 1f))
}

/** The newest row's divider follows its colour: hidden while accented, back once settled, gone again when stale. */
internal fun activeRowDividerAlpha(ageMs: Long): Float = when {
    ageMs < ACTIVE_ROW_SETTLE_MS -> ROW_DIVIDER_ALPHA * (ageMs / ACTIVE_ROW_SETTLE_MS.toFloat()).coerceIn(0f, 1f)
    ageMs < ACTIVE_ROW_STALE_AFTER_MS -> ROW_DIVIDER_ALPHA
    else -> ROW_DIVIDER_ALPHA * (1f - ((ageMs - ACTIVE_ROW_STALE_AFTER_MS) / ACTIVE_ROW_STALE_SPAN_MS.toFloat()).coerceIn(0f, 1f))
}
