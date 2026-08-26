@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tk.glucodata.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.WaterDrop
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.delay
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.ui.journal.JournalInlineChip
import tk.glucodata.ui.viewmodel.SensorColors

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

    val ageState = remember(point.timestamp) { mutableStateOf(System.currentTimeMillis() - point.timestamp) }
    // Timer to update age for the active item
    if (isActive) {
        LaunchedEffect(point.timestamp) {
            while (true) {
                ageState.value = System.currentTimeMillis() - point.timestamp
                delay(1000) // Update every second
            }
        }
    }

    val containerColor = if (isActive) {
        val age = ageState.value
        when {
            age < 60_000 -> {
                // Phase 1: Fade to Stock
                val progress = age / 60_000f
                Color(
                    ColorUtils.blendARGB(
                        activeColor.toArgb(),
                        stockColor.toArgb(),
                        progress.coerceIn(0f, 1f)
                    )
                )
            }
            age < 90_000 -> {
                // Phase 2: Stock (Stable)
                stockColor
            }
            else -> {
                 // Phase 3: Stale (Darken slowly)
                 // "slowly start getting different darker shade"
                 // Let's cap the darkening at 100% after another 60s (just to have a bound)
                 val staleProgress = ((age - 90_000) / 60_000f).coerceIn(0f, 1f)
                 Color(
                    ColorUtils.blendARGB(
                        stockColor.toArgb(),
                        staleColor.toArgb(),
                        staleProgress
                    )
                )
            }
        }
    } else {
        // History: Always Stock
        stockColor
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

    // Divider Alpha Logic
    val baseDividerAlpha = 0.1f // User Request: "perhaps make it 0.1f"
    val dividerAlpha = if (isActive) {
        val age = ageState.value
        when {
            age < 60_000 -> {
                // Fresh: Color is Distinct -> Stock. Divider: Hidden -> Visible.
                val progress = age / 60_000f
                baseDividerAlpha * progress.coerceIn(0f, 1f)
            }
            age < 90_000 -> {
                // Normal: Color is Stock. Divider: Visible.
                baseDividerAlpha
            }
            else -> {
                // Stale: Color is Stock -> Distinct. Divider: Visible -> Hidden.
                val staleProgress = ((age - 90_000) / 60_000f).coerceIn(0f, 1f)
                baseDividerAlpha * (1f - staleProgress)
            }
        }
    } else {
        baseDividerAlpha
    }

    // --- ADVANCED TREND ENGINE ---
    // Calculate on the fly using the passed history subset
    val regressed = remember(history, index) {
        val relevantHistory = if (history.isNotEmpty()) history.drop(index) else listOf(point)
        val nativeList = relevantHistory.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
        tk.glucodata.logic.TrendEngine.calculateTrend(nativeList, useRaw = (viewMode == 1 || viewMode == 3), isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit))
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
        color = containerColor,
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
            val timeStyle = MaterialTheme.typography.bodySmall
            val timeColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            val timeWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
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
            fun ReadingValueContent(modifier: Modifier = Modifier) {
                Row(
                    modifier = modifier,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!deltaText.isNullOrEmpty()) {
                        Text(
                            text = deltaText,
                            style = timeStyle.copy(fontFeatureSettings = "tnum"),
                            color = tertiaryColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (tk.glucodata.data.calibration.CalibrationManager.hasCalibrationAt(point.timestamp, isRawModeRR)) {
                        Icon(
                            imageVector = Icons.Filled.WaterDrop,
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
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
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
                    Text(
                        text = java.text.SimpleDateFormat(
                            "HH:mm",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date(point.timestamp)),
                        style = timeStyle,
                        fontWeight = timeWeight,
                        color = timeColor
                    )

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = rowMinHeight)
                        .padding(start = 16.dp),
                    // Keep the timestamp and glucose anchored to the first chip line when
                    // compact entries wrap. Extra chip lines grow only the middle slot.
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        modifier = Modifier
                            .defaultMinSize(minHeight = rowMinHeight)
                            .then(
                                if (onBaselineHeightMeasured != null) {
                                    Modifier.onSizeChanged { onBaselineHeightMeasured(it.height) }
                                } else {
                                    Modifier
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = java.text.SimpleDateFormat(
                                "HH:mm",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(point.timestamp)),
                            style = timeStyle,
                            fontWeight = timeWeight,
                            color = timeColor
                        )

                        if (showLeadingAction) {
                            Box(
                                modifier = Modifier.width(leadingActionSlotWidth),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                JournalAddAffordance()
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(if (showLeadingAction) 16.dp else 12.dp))

                    if (hasInlineJournalEntries) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = rowMinHeight)
                                .padding(
                                    top = if (journalChipExpanded) 8.dp else 0.dp,
                                    end = 8.dp,
                                    bottom = if (journalChipExpanded) 8.dp else 0.dp
                                ),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (journalChipExpanded) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            } else {
                                CompactJournalFlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    firstLineSlotHeight = rowMinHeight
                                ) {
                                    journalEntries.forEach { entry ->
                                        JournalInlineChip(
                                            entry = entry,
                                            unit = unit,
                                            insulinPreset = entry.insulinPresetId?.let(journalPresetsById::get),
                                            food = entry.foodId?.let(journalFoodsById::get),
                                            expanded = false,
                                            onClick = { onJournalEntryClick?.invoke(entry) }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = rowMinHeight),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            MultiSensorValueContent(
                                modifier = Modifier.padding(start = 12.dp, end = 16.dp)
                            )
                        }
                    }

                    if (hasInlineJournalEntries) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = valueMinWidth, minHeight = rowMinHeight),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            MultiSensorValueContent(
                                modifier = Modifier.padding(start = 12.dp, end = 16.dp)
                            )
                        }
                    }
                }
            }

            if (index < totalCount - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = dividerHorizontalInset),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha),
                    thickness = 1.dp
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
                    }
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Packs compact journal chips by their visible bounds while keeping the first line aligned
 * with the reading's fixed-height timestamp and value slots.
 */
@Composable
private fun CompactJournalFlowRow(
    modifier: Modifier = Modifier,
    firstLineSlotHeight: Dp,
    horizontalSpacing: Dp = 6.dp,
    verticalSpacing: Dp = 4.dp,
    content: @Composable () -> Unit
) {
    Layout(
        modifier = modifier,
        content = content
    ) { measurables, constraints ->
        val horizontalSpacingPx = horizontalSpacing.roundToPx()
        val verticalSpacingPx = verticalSpacing.roundToPx()
        val firstLineSlotHeightPx = firstLineSlotHeight.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        val xPositions = IntArray(placeables.size)
        val lineIndices = IntArray(placeables.size)
        val lineHeights = mutableListOf<Int>()
        val availableWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE

        var lineIndex = 0
        var lineX = 0
        var usedWidth = 0
        placeables.forEachIndexed { index, placeable ->
            val spacedX = if (lineX == 0) 0 else lineX + horizontalSpacingPx
            if (lineX > 0 && spacedX + placeable.width > availableWidth) {
                lineIndex += 1
                lineX = 0
            } else {
                lineX = spacedX
            }
            while (lineHeights.size <= lineIndex) lineHeights.add(0)
            xPositions[index] = lineX
            lineIndices[index] = lineIndex
            lineHeights[lineIndex] = maxOf(lineHeights[lineIndex], placeable.height)
            lineX += placeable.width
            usedWidth = maxOf(usedWidth, lineX)
        }

        val lineY = IntArray(lineHeights.size)
        var firstLineInset = 0
        if (lineHeights.isNotEmpty()) {
            firstLineInset = ((firstLineSlotHeightPx - lineHeights[0]) / 2).coerceAtLeast(0)
            lineY[0] = firstLineInset
            for (index in 1 until lineHeights.size) {
                lineY[index] = lineY[index - 1] + lineHeights[index - 1] + verticalSpacingPx
            }
        }
        val contentHeight = if (lineHeights.isEmpty()) {
            firstLineSlotHeightPx
        } else {
            maxOf(firstLineSlotHeightPx, lineY.last() + lineHeights.last() + firstLineInset)
        }
        val requestedWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else usedWidth

        layout(
            width = requestedWidth.coerceIn(constraints.minWidth, constraints.maxWidth),
            height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        ) {
            placeables.forEachIndexed { index, placeable ->
                val childLine = lineIndices[index]
                val centeredY = lineY[childLine] + (lineHeights[childLine] - placeable.height) / 2
                placeable.placeRelative(xPositions[index], centeredY)
            }
        }
    }
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
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(timestamp)),
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

                Spacer(modifier = Modifier.width(valueSlotWidth))
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
