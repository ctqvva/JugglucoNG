package tk.glucodata.ui.calibration

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.GlucosePoint
import tk.glucodata.R
import tk.glucodata.data.HistoryRepository
import tk.glucodata.data.calibration.CalibrationEntity
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.theme.displayLargeExpressive
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationBottomSheet(
    onDismiss: () -> Unit,
    initialValueAuto: Float,
    initialValueRaw: Float,
    initialTimestamp: Long,
    glucoseHistory: List<GlucosePoint>,
    isMmol: Boolean = true,
    viewMode: Int = 0,
    onNavigateToHistory: () -> Unit
) {
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val historyRepository = remember { HistoryRepository(context) }

    val isRawMode = viewMode == 1 || viewMode == 3
    val modeLabel = if (isRawMode) stringResource(R.string.raw) else stringResource(R.string.auto)

    // State
    var editingEntity by remember { mutableStateOf<CalibrationEntity?>(null) }
    val currentSensor = tk.glucodata.Natives.lastsensorname() ?: ""
    var selectedTimestamp by remember { mutableLongStateOf(initialTimestamp) }

    val nearestHistoryPoint = remember(selectedTimestamp, glucoseHistory) {
        glucoseHistory.minByOrNull { abs(it.timestamp - selectedTimestamp) }
    }
    val selectedAutoValue = nearestHistoryPoint?.value ?: initialValueAuto
    val selectedRawValue = nearestHistoryPoint?.rawValue ?: initialValueRaw
    val visualContinuity = CalibrationManager.shouldVisualContinuity()
    val sensorOverride = currentSensor.takeIf { it.isNotBlank() }
    val visualBaselineValue = if (visualContinuity) {
        val modeBaseValue = if (isRawMode) selectedRawValue else selectedAutoValue
        if (
            modeBaseValue > 0f &&
            CalibrationManager.hasActiveCalibration(isRawMode, currentSensor)
        ) {
            CalibrationManager.getCalibratedValue(
                value = modeBaseValue,
                timestamp = selectedTimestamp,
                isRawMode = isRawMode,
                sensorIdOverride = sensorOverride
            )
        } else {
            modeBaseValue
        }
    } else {
        null
    }
    val defaultInputValue = when {
        visualContinuity -> visualBaselineValue?.takeIf { it > 0f }
            ?: if (isRawMode) selectedRawValue.takeIf { it > 0f } else selectedAutoValue.takeIf { it > 0f }
        isRawMode -> selectedRawValue.takeIf { it > 0f } ?: selectedAutoValue.takeIf { it > 0f }
        else -> selectedAutoValue.takeIf { it > 0f } ?: selectedRawValue.takeIf { it > 0f }
    } ?: initialValueAuto

    fun triggerRewriteOverwrittenHistory(startTimestamp: Long = 0L) {
        if (!CalibrationManager.shouldOverwriteSensorValues()) return
        if (currentSensor.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            historyRepository.rewriteSensorValuesWithCalibration(
                sensorSerial = currentSensor,
                isRawMode = isRawMode,
                startTimestamp = startTimestamp
            )
        }
    }

    // Values
    val startValue = editingEntity?.userValue ?: defaultInputValue

    var userValue by remember(editingEntity) { mutableFloatStateOf(startValue) }

    var textValue by remember(editingEntity) {
        mutableStateOf(
            TextFieldValue(
                if (isMmol) String.format(Locale.getDefault(), "%.1f", startValue)
                else String.format(Locale.getDefault(), "%.0f", startValue)
            )
        )
    }
    
    // Track if user has modified the value (for showing old → new animation)
    var hasChanged by remember(editingEntity) { mutableStateOf(false) }
    
    // Original value to display in "old → new" format
    // For edit mode: the sensor original value
    // For new mode: the initial auto/raw value
    val originalValue = if (editingEntity != null) {
        if (isRawMode) editingEntity!!.sensorValueRaw else editingEntity!!.sensorValue
    } else {
        if (visualContinuity) {
            visualBaselineValue ?: if (isRawMode) selectedRawValue else selectedAutoValue
        } else {
            if (isRawMode) selectedRawValue else selectedAutoValue
        }
    }

    // Data Flow
    val duplicateThresholdMs = 60_000L
    val allCalibrations by CalibrationManager.getCalibrationsFlow()?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) }
    val calibrations = allCalibrations
        .filter { it.isRawMode == isRawMode }
        .filter { it.sensorId == currentSensor || it.sensorId.isEmpty() }
        .sortedByDescending { it.timestamp }

    // --- SMART MODE SWITCHING LOGIC ---
    // Track if we started in Edit Mode (to handle Cancel behavior)
    var startedInEditMode by remember { mutableStateOf(false) }
    var isInitCheckDone by remember { mutableStateOf(false) }

    // Reactive: Whenever timestamp or calibrations change, update editingEntity automatically.
    LaunchedEffect(selectedTimestamp, calibrations) {
        val match = calibrations.firstOrNull { abs(it.timestamp - selectedTimestamp) < duplicateThresholdMs }
        editingEntity = match

        // Initial Latch: Determine if we opened the sheet in Edit Mode
        // FIX: Ensure we have data (or are sure it's fully loaded/empty logic handled elsewhere)
        // to prevent false negatives during initial DB load.
        // If calibrations is empty, we might just be loading.
        // If we truly started in Edit Mode, we expect a match eventually.
        if (!isInitCheckDone && calibrations.isNotEmpty()) {
             startedInEditMode = (match != null)
             isInitCheckDone = true
        }
    }

    val step = if (isMmol) 0.1f else 1f

    // Trend
    val trendResult by produceState(
        initialValue = TrendEngine.calculateTrend(emptyList<GlucosePoint>(), useRaw = isRawMode, isMmol = isMmol),
        key1 = selectedTimestamp,
        key2 = glucoseHistory
    ) {
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            val historyAtTime = glucoseHistory.filter { it.timestamp <= selectedTimestamp }
            value = TrendEngine.calculateTrend(historyAtTime, useRaw = isRawMode, isMmol = isMmol)
        }
    }

    // Formatters
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val formattedCalibrations = remember(calibrations) {
        calibrations.associate { cal -> cal.id to dateFormatter.format(Date(cal.timestamp)) }
    }

    // Time State
    var isTimeExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        // Fix for Keyboard Gap:
        // When IME (Keyboard) is visible, we don't need navigationBarsPadding because the IME
        // already pushes content up and covers the nav bar area.
        // If we keep it, it creates an empty gap above the keyboard.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val isImeVisible = WindowInsets.ime.getBottom(density) > 0

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompactSheet = maxHeight < 520.dp || maxWidth < 360.dp
            val scrollState = rememberScrollState()
            val horizontalPadding = if (isCompactSheet) 16.dp else 24.dp
            val headerMinHeight = if (isCompactSheet) 44.dp else 48.dp
            val headerBottomSpacing = if (isCompactSheet) 8.dp else 12.dp
            val timeCardPadding = if (isCompactSheet) 12.dp else 16.dp
            val timeIconSpacing = if (isCompactSheet) 10.dp else 16.dp
            val timePickerBottomSpacing = if (isCompactSheet) 12.dp else 16.dp
            val heroBottomSpacing = if (isCompactSheet) 8.dp else 12.dp
            val statusMinHeight = if (isCompactSheet) 30.dp else 32.dp
            val actionsTopSpacing = if (isCompactSheet) 14.dp else 24.dp
            val actionsBottomSpacing = if (isCompactSheet) 12.dp else 24.dp
            val sheetBottomPadding = if (isCompactSheet) 16.dp else 12.dp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isCompactSheet) Modifier.verticalScroll(scrollState) else Modifier)
                    .padding(horizontal = horizontalPadding)
                    .then(
                        if (!isImeVisible) {
                            Modifier.navigationBarsPadding().padding(bottom = sheetBottomPadding)
                        } else {
                            Modifier
                        }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = headerMinHeight),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (editingEntity != null) stringResource(R.string.edit_calibration_title) else stringResource(R.string.new_calibration_with_mode, modeLabel),
                    style = if (isCompactSheet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Header Action: Toggle Enable (Only in Edit Mode)
                if (editingEntity != null) {
                    Row {
                        // Disable/Enable toggle
                        IconButton(
                            onClick = {
                                scope.launch {
                                    CalibrationManager.updateCalibration(editingEntity!!.copy(isEnabled = !editingEntity!!.isEnabled))
                                    triggerRewriteOverwrittenHistory(editingEntity!!.timestamp)
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (editingEntity!!.isEnabled)
                                    MaterialTheme.colorScheme.secondary
                                else
                                    MaterialTheme.colorScheme.tertiary
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                if (editingEntity!!.isEnabled) Icons.Default.Close else Icons.Default.Check,
                                contentDescription = null
                            )
                        }

                        // Delete button
                        IconButton(
                            onClick = {
                                scope.launch {
                                     val deletedTimestamp = editingEntity!!.timestamp
                                     CalibrationManager.deleteCalibration(editingEntity!!)
                                     selectedTimestamp = System.currentTimeMillis()
                                     onDismiss()
                                     triggerRewriteOverwrittenHistory(deletedTimestamp)
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(headerBottomSpacing))


            // --- TIME PICKER ---
            val isNow = abs(selectedTimestamp - System.currentTimeMillis()) < 60_000

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { isTimeExpanded = !isTimeExpanded }
            ) {
                Column(modifier = Modifier.padding(timeCardPadding)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(timeIconSpacing))
                            Column {
//                                Text("Time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (isNow) stringResource(R.string.now) else dateFormatter.format(Date(selectedTimestamp)),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isTimeExpanded && !isNow) {
                                Spacer(modifier = Modifier.width(16.dp))
                                FilledTonalButton(
                                    onClick = { selectedTimestamp = System.currentTimeMillis() },
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(stringResource(R.string.now))
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                            }

                            Icon(if (isTimeExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }

                    AnimatedVisibility(visible = isTimeExpanded) {
                        Column(modifier = Modifier.padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(16.dp))

                            val calendar = remember { Calendar.getInstance() }
                            LaunchedEffect(selectedTimestamp) {
                                calendar.timeInMillis = selectedTimestamp
                            }
                            val minuteFocusRequester = remember { FocusRequester() }
                            // Delayed focus
                            LaunchedEffect(Unit) {
                                delay(150)
                                minuteFocusRequester.requestFocus()
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CustomTimeField(
                                    value = calendar.get(Calendar.HOUR_OF_DAY),
                                    range = 0..23,
                                    onValueChange = { h ->
                                        calendar.set(Calendar.HOUR_OF_DAY, h)
                                        selectedTimestamp = calendar.timeInMillis
                                    }
                                )

                                Text(
                                    ":",
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                CustomTimeField(
                                    value = calendar.get(Calendar.MINUTE),
                                    range = 0..59,
                                    onValueChange = { m ->
                                        calendar.set(Calendar.MINUTE, m)
                                        selectedTimestamp = calendar.timeInMillis
                                    },
                                    focusRequester = minuteFocusRequester
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(timePickerBottomSpacing))

            // --- HERO SECTION ---

            CalibrationHeroSection(
                userValue = userValue,
                textValue = textValue,
                step = step,
                isMmol = isMmol,
                originalValue = originalValue,
                isEditMode = editingEntity != null,
                hasChanged = hasChanged || editingEntity != null, // Edit mode always shows old → new
                view = view,
                compact = isCompactSheet,
                onValueChange = { newVal, newText ->
                    userValue = newVal
                    textValue = newText
                    hasChanged = true
                }
            )
            Spacer(modifier = Modifier.height(heroBottomSpacing))

            // --- BADGE ---
            val isStable = abs(trendResult.velocity) < 1.0
            val statusColor = if (isStable) Color(0xFF4CAF50) else Color(0xFFFFB300)

            Surface(
                color = statusColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(100),
                modifier = Modifier.heightIn(min = statusMinHeight)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    val trendIcon = when {
                        trendResult.velocity > 1.0 -> Icons.Default.ArrowUpward
                        trendResult.velocity < -1.0 -> Icons.Default.ArrowDownward
                        else -> Icons.Default.Check
                    }
                    Icon(trendIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))

                    Spacer(modifier = Modifier.width(6.dp))

                    val statusText = if (isStable) stringResource(R.string.conditions_optimal) else stringResource(R.string.wait_15m_unstable)
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(actionsTopSpacing))

            // --- ACTIONS ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Cancel Button: Visible in Edit Mode
                if (editingEntity != null) {
                    OutlinedButton (
                        onClick = {
                            if (startedInEditMode) {
                                // Started as Edit -> Close
                                onDismiss()
                            } else {
                                // Started as New -> Reset to New
                                selectedTimestamp = System.currentTimeMillis()
                                userValue = defaultInputValue
                                textValue = TextFieldValue(if (isMmol) String.format("%.1f", userValue) else String.format("%.0f", userValue))
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            if (editingEntity != null) {
                                val previousTimestamp = editingEntity!!.timestamp
                                CalibrationManager.updateCalibration(
                                    editingEntity!!.copy(
                                        userValue = userValue,
                                        timestamp = selectedTimestamp
                                    )
                                )
                                onDismiss()
                                triggerRewriteOverwrittenHistory(minOf(previousTimestamp, selectedTimestamp))
                            } else {
                                // New
                                CalibrationManager.addCalibration(
                                    timestamp = selectedTimestamp,
                                    sensorValue = selectedAutoValue,
                                    sensorValueRaw = selectedRawValue,
                                    userValue = userValue,
                                    isRawMode = isRawMode
                                )
                                onDismiss()
                                triggerRewriteOverwrittenHistory(selectedTimestamp)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (editingEntity != null) stringResource(R.string.update) else stringResource(R.string.save))
                }
            }

            Spacer(modifier = Modifier.height(actionsBottomSpacing))

            // --- HISTORY ---
            if (calibrations.isNotEmpty()) {
                CalibrationHistoryList(
                    calibrations = calibrations,
                    formattedCalibrations = formattedCalibrations,
                    isMmol = isMmol,
                    isRawMode = isRawMode,
                    editingEntity = editingEntity,
                    onSelect = { cal ->
                        selectedTimestamp = cal.timestamp
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    },
                    onToggleEnable = { cal ->
                        scope.launch { CalibrationManager.updateCalibration(cal.copy(isEnabled = !cal.isEnabled)) }
                    },
                    onSeeAll = onNavigateToHistory
                )
            }
            }
        }
    }
}

@Composable
fun CustomTimeField(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    var text by remember { mutableStateOf(TextFieldValue(String.format(Locale.getDefault(), "%02d", value))) }
    var isFocused by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            if (interaction is PressInteraction.Release) {
                delay(50)
                text = text.copy(selection = TextRange(0, text.text.length))
            }
        }
    }

    LaunchedEffect(value) {
        // Update text if external value mismatch, even if focused
        val parsed = text.text.toIntOrNull()
        if (parsed != value) {
            text = TextFieldValue(String.format(Locale.getDefault(), "%02d", value))
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.width(96.dp).height(112.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            BasicTextField(
                value = text,
                onValueChange = { newText ->
                    val s = newText.text
                    if (s.length <= 2) {
                        text = newText
                        val num = s.toIntOrNull()
                        if (num != null && num in range) {
                            onValueChange(num)
                        }
                    }
                },
                textStyle = MaterialTheme.typography.displayMedium.copy(
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                interactionSource = interactionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        isFocused = state.isFocused
                        if (state.isFocused) {
                            text = text.copy(selection = TextRange(0, text.text.length))
                        } else {
                            text = TextFieldValue(String.format(Locale.getDefault(), "%02d", value))
                        }
                    }
            )
        }
    }
}


@Composable
private fun CalibrationHistoryList(
    calibrations: List<CalibrationEntity>,
    formattedCalibrations: Map<Int, String>,
    isMmol: Boolean,
    isRawMode: Boolean,
    editingEntity: CalibrationEntity?,
    onSelect: (CalibrationEntity) -> Unit,
    onToggleEnable: (CalibrationEntity) -> Unit,
    onSeeAll: () -> Unit
) {
    val visibleCalibrations = calibrations.take(3)

    Column(modifier = Modifier.fillMaxWidth()) {
//        visibleCalibrations.forEach { cal ->
//            val isEditing = editingEntity?.id == cal.id
//
//            Surface(
//                onClick = { onSelect(cal) },
//                shape = RoundedCornerShape(12.dp),
//                color = if (isEditing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
//                modifier = Modifier.fillMaxWidth()
//            ) {
//                Row(
//                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
//                    verticalAlignment = Alignment.CenterVertically
//                ) {
//                    Column(modifier = Modifier.weight(1f)) {
//                        val sVal = if (isRawMode) cal.sensorValueRaw else cal.sensorValue
//                        val sFmt = if (isMmol) "%.1f" else "%.0f"
//
//                        Row(verticalAlignment = Alignment.CenterVertically) {
//                            Text(
//                                text = "${String.format(Locale.getDefault(), sFmt, sVal)} → ${String.format(Locale.getDefault(), sFmt, cal.userValue)}",
//                                style = MaterialTheme.typography.titleMedium,
//                                fontWeight = FontWeight.SemiBold
//                            )
//                            if (!cal.isEnabled) {
//                                Spacer(modifier = Modifier.width(8.dp))
//                                Text("(Disabled)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
//                            }
//                        }
//                        Text(
//                            text = formattedCalibrations[cal.id] ?: "",
//                            style = MaterialTheme.typography.labelMedium,
//                            color = MaterialTheme.colorScheme.onSurfaceVariant
//                        )
//                    }
//
//                    if (isEditing) {
//                        IconButton(onClick = { onToggleEnable(cal) }) {
//                            Icon(
//                                 if (cal.isEnabled) Icons.Default.Close else Icons.Default.Check,
//                                 contentDescription = if (cal.isEnabled) "Disable" else "Enable",
//                                 tint = if (cal.isEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
//                            )
//                        }
//                    } else {
//                        IconButton(onClick = { onSelect(cal) }) {
//                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.outline)
//                        }
//                    }
//                }
//            }
//
//            Spacer(modifier = Modifier.height(8.dp))
//        }

        if (calibrations.size > 0) {
            TextButton(
                onClick = onSeeAll,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.previous_calibrations))
            }
        }
    }
}


@Composable
private fun CalibrationHeroSection(
    userValue: Float,
    textValue: TextFieldValue,
    step: Float,
    isMmol: Boolean,
    originalValue: Float, // Original sensor value (for edit mode) or initial value (for new mode)
    isEditMode: Boolean,
    hasChanged: Boolean, // True if user has modified the value
    onValueChange: (Float, TextFieldValue) -> Unit,
    view: android.view.View,
    compact: Boolean = false
) {
    val fmt = if (isMmol) "%.1f" else "%.0f"
    val originalStr = String.format(Locale.getDefault(), fmt, originalValue)
    val valuesMatch = kotlin.math.abs(userValue - originalValue) < 0.01f
    val valueButtonSize = if (compact) 56.dp else 64.dp
    val valueIconSize = if (compact) 28.dp else 32.dp
    val valueGap = if (compact) 10.dp else 16.dp
    val valueFontSize = if (compact) 48.sp else 56.sp
    val valueMinWidth = if (compact) 68.dp else 80.dp
    val valueMaxWidth = if (compact) 132.dp else 180.dp
    val originalValueStyle = if (compact) {
        MaterialTheme.typography.headlineMedium
    } else {
        MaterialTheme.typography.headlineLarge
    }
    val originalArrowSize = if (compact) 20.dp else 24.dp
    
    // Show dual display when: value has changed AND values don't match
    val showDual = hasChanged && !valuesMatch
    val heroEnterDuration = if (isEditMode) 140 else 220
    val heroExitDuration = if (isEditMode) 110 else 180
    
    // Focus and selection handling for better UX
    val focusRequester = remember { FocusRequester() }
    var localTextValue by remember(textValue) { mutableStateOf(textValue) }
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    
    // Auto-select all text when tapped (on press release)
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            if (interaction is PressInteraction.Release) {
                delay(50) // Small delay to let focus settle
                localTextValue = localTextValue.copy(selection = TextRange(0, localTextValue.text.length))
            }
        }
    }
    
    // Sync external textValue changes
    LaunchedEffect(textValue) {
        localTextValue = textValue
    }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Original value above (vertical layout)
        AnimatedVisibility(
            visible = showDual,
            enter = fadeIn(animationSpec = tween(heroEnterDuration)) +
                    expandVertically(
                        expandFrom = Alignment.Bottom,
                        animationSpec = tween(heroEnterDuration)
                    ),
            exit = fadeOut(animationSpec = tween(heroExitDuration)) +
                    shrinkVertically(
                        shrinkTowards = Alignment.Bottom,
                        animationSpec = tween(heroExitDuration)
                    )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = originalStr,
                    style = originalValueStyle.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                )
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(originalArrowSize).padding(vertical = 4.dp)
                )
            }
        }
        
        // Editable value row with +/- buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalIconButton(
                onClick = {
                    val newValue = (userValue - step).coerceAtLeast(0f)
                    val newText = String.format(Locale.getDefault(), fmt, newValue)
                    onValueChange(newValue, TextFieldValue(newText, TextRange(newText.length)))
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                },
                modifier = Modifier.size(valueButtonSize)
            ) {
                Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(valueIconSize))
            }

            Spacer(modifier = Modifier.width(valueGap))
            
            // User value input with auto-select on focus
            // Wrap in Box with weight to take available space and center content
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                var isFocused by remember { mutableStateOf(false) }
                
                BasicTextField(
                    value = localTextValue,
                    onValueChange = { newValue ->
                        val newText = newValue.text.replace(",", ".")
                        val isValid = if (isMmol) {
                            newText.matches(Regex("^\\d{0,2}([.,]\\d?)?$"))
                        } else {
                            newText.matches(Regex("^\\d{0,3}$"))
                        }
                        if (isValid) {
                            localTextValue = newValue
                            val floatVal = newText.toFloatOrNull()
                            onValueChange(floatVal ?: if (newText.isEmpty()) 0f else userValue, newValue)
                        }
                    },
                    textStyle = MaterialTheme.typography.displayLarge.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = valueFontSize,
                        textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .widthIn(min = valueMinWidth, max = valueMaxWidth)
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            isFocused = state.isFocused
                        }
                )
                
                // Focus Interceptor: Transparent overlay that catches first tap
                // This ensures we always Select All on first focus, preventing the
                // default behavior of placing the cursor at touch position.
                if (!isFocused) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null // Transparent/No ripple
                            ) {
                                focusRequester.requestFocus()
                                // Select All immediately on focus
                                localTextValue = localTextValue.copy(selection = TextRange(0, localTextValue.text.length))
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.width(valueGap))

            FilledTonalIconButton(
                onClick = {
                    val newValue = userValue + step
                    val newText = String.format(Locale.getDefault(), fmt, newValue)
                    onValueChange(newValue, TextFieldValue(newText, TextRange(newText.length)))
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                },
                modifier = Modifier.size(valueButtonSize)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(valueIconSize))
            }
        }
    }
}
