package tk.glucodata.ui.calibration

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.data.HistoryRepository
import tk.glucodata.data.calibration.CalibrationEntity
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.components.StyledSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CalibrationDeleteSwipeThresholdFraction = 0.4f
private const val CalibrationUiPrefsName = "tk.glucodata_preferences"
// Bump the key so the previous broken one-shot behavior does not consume the discoverability pass.
private const val CalibrationMasterCardSeenPref = "manual_calibration_master_card_seen_v2"

private suspend fun SnackbarHostState.showCalibrationUndoSnackbar(
    message: String,
    actionLabel: String
): SnackbarResult {
    currentSnackbarData?.dismiss()
    val result = showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = SnackbarDuration.Short
    )
    if (result == SnackbarResult.ActionPerformed) {
        currentSnackbarData?.dismiss()
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationListScreen(
    navController: NavController,
    isMmol: Boolean,
    viewMode: Int = 0, // Default to 0 (Auto)
    sensorId: String,
    onAdd: () -> Unit,
    onEdit: (CalibrationEntity) -> Unit,
    onOpenModelTable: () -> Unit
) {
    val isRawMode = viewMode == 1 || viewMode == 3
    val modeTitle = if (isRawMode) stringResource(R.string.raw) else stringResource(R.string.auto)
    
    // Collect Data
    val allCalibrations by CalibrationManager
        .getCalibrationsFlow()
        .collectAsState(initial = CalibrationManager.getCachedCalibrations())
    val currentSensor = SensorIdentity.resolveAppSensorId(sensorId) ?: sensorId
    
    // Filter by mode and current sensor
    val calibrations = allCalibrations
        .asSequence()
        .filter { CalibrationManager.matchesMode(it, isRawMode) }
        .filter {
            currentSensor.isNotBlank() &&
                CalibrationManager.calibrationMatchesSensor(it.sensorId, currentSensor)
        }
        .sortedByDescending { it.timestamp }
        .toList()

    // Toggle State
    val calibrationRevision by CalibrationManager.revision.collectAsState()
    val isCalibrationEnabled = remember(isRawMode, currentSensor, calibrationRevision) {
        CalibrationManager.isEnabledForMode(isRawMode, currentSensor)
    }
    val algorithmForRaw by CalibrationManager.algorithmForRaw.collectAsState()
    val algorithmForAuto by CalibrationManager.algorithmForAuto.collectAsState()
    val selectedAlgorithm = if (isRawMode) algorithmForRaw else algorithmForAuto
    val diagnosticsForRaw by CalibrationManager.diagnosticsForRaw.collectAsState()
    val diagnosticsForAuto by CalibrationManager.diagnosticsForAuto.collectAsState()
    val diagnostics = if (isRawMode) diagnosticsForRaw else diagnosticsForAuto
    val hideInitialWhenCalibrated by CalibrationManager.hideInitialWhenCalibrated.collectAsState()
    val applyToPast by CalibrationManager.applyToPast.collectAsState()
    val lockPastHistory by CalibrationManager.lockPastHistory.collectAsState()
    val keepDisabledHistory by CalibrationManager.keepDisabledHistory.collectAsState()
    val overwriteSensorValues by CalibrationManager.overwriteSensorValues.collectAsState()
    val visualContinuity by CalibrationManager.visualContinuity.collectAsState()
    val calibrateFromJournal by CalibrationManager.calibrateFromJournal.collectAsState()
    val weightMode by CalibrationManager.weightMode.collectAsState()

    val dateFormatter = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val historyRepository = remember { HistoryRepository(context) }
    val calibrationUiPrefs = remember(context) {
        context.getSharedPreferences(CalibrationUiPrefsName, Context.MODE_PRIVATE)
    }
    val expandMasterCalibrationCardOnFirstVisit = remember(calibrationUiPrefs) {
        !calibrationUiPrefs.getBoolean(CalibrationMasterCardSeenPref, false)
    }
    suspend fun rewriteHistoryIfNeeded() {
        if (!overwriteSensorValues || currentSensor.isBlank()) return
        historyRepository.rewriteSensorValuesWithCalibration(currentSensor, isRawMode)
    }

    var showImportExportSheet by rememberSaveable { mutableStateOf(false) }
    var pendingExportPayload by remember { mutableStateOf<String?>(null) }
    var replaceExistingOnImport by remember { mutableStateOf(false) }

    val exportProfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val payload = pendingExportPayload ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(payload)
                    } ?: error(context.getString(R.string.unable_to_open_destination_file))
                }
            }
            snackbarHostState.showSnackbar(
                if (result.isSuccess) context.getString(R.string.calibration_profile_exported)
                else context.getString(R.string.export_failed_with_error, result.exceptionOrNull()?.message ?: context.getString(R.string.unknown_error))
            )
        }
    }

    val importProfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val importResult = withContext(Dispatchers.IO) {
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error(context.getString(R.string.unable_to_open_source_file))

                    CalibrationManager.importProfileFromJson(
                        json = json,
                        replaceExisting = replaceExistingOnImport,
                        overrideSensorId = currentSensor
                    )
                }
            }

            if (importResult.isSuccess) {
                val summary = importResult.getOrThrow()
                snackbarHostState.showSnackbar(
                    context.getString(R.string.import_summary, summary.imported, summary.skipped, summary.replaced)
                )
            } else {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.import_failed_with_error, importResult.exceptionOrNull()?.message ?: context.getString(R.string.invalid_profile))
                )
            }
        }
    }

    fun exportCurrentSensorProfile() {
        if (currentSensor.isBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.no_active_sensor_selected))
            }
            return
        }

        val payload = CalibrationManager.exportProfileForSensorAsJson(currentSensor)
        if (payload.isNullOrBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.no_calibrations_to_export))
            }
            return
        }

        pendingExportPayload = payload
        exportProfileLauncher.launch("calibration_profile_$currentSensor.json")
    }

    fun importCurrentSensorProfile(replaceExisting: Boolean) {
        replaceExistingOnImport = replaceExisting
        importProfileLauncher.launch(arrayOf("application/json", "text/*"))
    }
    
    // Multi-select state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Int>()) }

    // Clear confirmation
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showBulkDeleteConfirmation by remember { mutableStateOf(false) }

    // Exit selection mode when no items selected
    LaunchedEffect(selectedIds) {
        if (selectedIds.isEmpty() && isSelectionMode) {
            isSelectionMode = false
        }
    }

    LaunchedEffect(isRawMode, selectedAlgorithm, isCalibrationEnabled, calibrations) {
        CalibrationManager.refreshDiagnosticsPreview(isRawMode = isRawMode, force = true)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                // Selection Mode TopBar
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { 
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    },
                    actions = {
                        // Select All
                        TextButton(onClick = {
                            selectedIds = if (selectedIds.size == calibrations.size) {
                                emptySet()
                            } else {
                                calibrations.map { it.id }.toSet()
                            }
                        }) {
                            Text(if (selectedIds.size == calibrations.size) stringResource(R.string.deselect_all) else stringResource(R.string.select_all))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = { 
                        Text(
                            stringResource(R.string.calibration_with_mode, modeTitle),
                            fontWeight = FontWeight.SemiBold
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenModelTable) {
                            Icon(
                                imageVector = Icons.Default.Analytics,
                                contentDescription = stringResource(R.string.calibration_model_table_action)
                            )
                        }
                        IconButton(onClick = { showImportExportSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.ImportExport,
                                contentDescription = stringResource(R.string.calibration_import_export_action)
                            )
                        }
                    }
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0) 
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main List Content
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, 
                    end = 16.dp, 
                    top = 16.dp, 
                    bottom = 100.dp // Space for floating toolbar
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Enable Toggle (not shown in selection mode)
                if (!isSelectionMode) {
                    item {
                        MasterCalibrationCard(
                            initiallyExpanded = expandMasterCalibrationCardOnFirstVisit,
                            onInitialExpansionShown = {
                                calibrationUiPrefs.edit()
                                    .putBoolean(CalibrationMasterCardSeenPref, true)
                                    .apply()
                            },
                            isEnabled = isCalibrationEnabled,
                            hideInitialWhenCalibrated = hideInitialWhenCalibrated,
                            applyToPast = applyToPast,
                            lockPastHistory = lockPastHistory,
                            keepDisabledHistory = keepDisabledHistory,
                            overwriteSensorValues = overwriteSensorValues,
                            visualContinuity = visualContinuity,
                            calibrateFromJournal = calibrateFromJournal,
                            onToggle = { enabled ->
                                CalibrationManager.setEnabledForMode(isRawMode, enabled, currentSensor)
                                if (enabled && overwriteSensorValues && currentSensor.isNotBlank()) {
                                    scope.launch {
                                        historyRepository.rewriteSensorValuesWithCalibration(currentSensor, isRawMode)
                                    }
                                }
                            },
                            onToggleHideInitial = { enabled ->
                                CalibrationManager.setHideInitialWhenCalibrated(enabled)
                            },
                            onToggleApplyToPast = { enabled ->
                                CalibrationManager.setApplyToPast(enabled)
                                if (overwriteSensorValues && currentSensor.isNotBlank()) {
                                    scope.launch {
                                        historyRepository.rewriteSensorValuesWithCalibration(currentSensor, isRawMode)
                                    }
                                }
                            },
                            onToggleLockPastHistory = { enabled ->
                                CalibrationManager.setLockPastHistory(enabled)
                                if (overwriteSensorValues && currentSensor.isNotBlank()) {
                                    scope.launch {
                                        historyRepository.rewriteSensorValuesWithCalibration(currentSensor, isRawMode)
                                    }
                                }
                            },
                            onToggleKeepDisabledHistory = { enabled ->
                                CalibrationManager.setKeepDisabledHistory(enabled)
                                if (overwriteSensorValues && currentSensor.isNotBlank()) {
                                    scope.launch {
                                        historyRepository.rewriteSensorValuesWithCalibration(currentSensor, isRawMode)
                                    }
                                }
                            },
                            onToggleOverwriteSensorValues = { enabled ->
                                CalibrationManager.setOverwriteSensorValues(enabled)
                                if (enabled && currentSensor.isNotBlank()) {
                                    scope.launch {
                                        historyRepository.rewriteSensorValuesWithCalibration(currentSensor, isRawMode)
                                    }
                                }
                            },
                            onToggleVisualContinuity = { enabled ->
                                CalibrationManager.setVisualContinuity(enabled)
                            },
                            onToggleCalibrateFromJournal = { enabled ->
                                CalibrationManager.setCalibrateFromJournal(enabled)
                            }
                        )
                    }

                    item {
                        CalibrationAlgorithmCard(
                            isCalibrationEnabled = isCalibrationEnabled,
                            selectedAlgorithm = selectedAlgorithm,
                            diagnostics = diagnostics,
                            selectedWeightMode = weightMode,
                            onSelectAlgorithm = { algorithm ->
                                CalibrationManager.setAlgorithmForMode(isRawMode, algorithm)
                            },
                            onSelectWeightMode = { mode ->
                                CalibrationManager.setWeightMode(mode)
                                if (overwriteSensorValues && currentSensor.isNotBlank()) {
                                    scope.launch {
                                        historyRepository.rewriteSensorValuesWithCalibration(currentSensor, isRawMode)
                                    }
                                }
                            }
                        )
                    }
                    
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                // 2. Calibration Items with Swipe
                items(calibrations, key = { it.id }) { cal ->
                    SwipeableCalibrationRow(
                        modifier = Modifier
                            .animateItem()
                            .graphicsLayer { alpha = if (isCalibrationEnabled) 1f else 0.58f },
                        cal = cal,
                        isMmol = isMmol,
                        isRawMode = isRawMode,
                        dateFormatter = dateFormatter,
                        isCalibrationEnabled = isCalibrationEnabled,
                        hideInitialWhenCalibrated = hideInitialWhenCalibrated,
                        isSelectionMode = isSelectionMode,
                        isSelected = selectedIds.contains(cal.id),
                        onTap = {
                            if (isSelectionMode) {
                                // Toggle selection
                                selectedIds = if (selectedIds.contains(cal.id)) {
                                    selectedIds - cal.id
                                } else {
                                    selectedIds + cal.id
                                }
                            } else {
                                onEdit(cal)
                            }
                        },
                        onLongPress = {
                            // Enter selection mode
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedIds = setOf(cal.id)
                            }
                        },
                        onDelete = { 
                            scope.launch {
                                val backup = cal
                                CalibrationManager.deleteCalibration(cal)
                                rewriteHistoryIfNeeded()
                                
                                val result = snackbarHostState.showCalibrationUndoSnackbar(
                                    message = context.getString(R.string.calibration_deleted),
                                    actionLabel = context.getString(R.string.undo)
                                )
                                
                                if (result == SnackbarResult.ActionPerformed) {
                                    CalibrationManager.addCalibration(
                                        timestamp = backup.timestamp,
                                        sensorValue = backup.sensorValue,
                                        sensorValueRaw = backup.sensorValueRaw,
                                        userValue = backup.userValue,
                                        sensorId = backup.sensorId,
                                        isRawMode = backup.isRawMode
                                    )
                                    rewriteHistoryIfNeeded()
                                }
                            }
                        },
                        onToggleDisable = { 
                            scope.launch { 
                                CalibrationManager.updateCalibration(cal.copy(isEnabled = !cal.isEnabled))
                                rewriteHistoryIfNeeded()
                            }
                        }
                    )
                }
            }

            // 3. Floating Toolbar (Bottom)
            AnimatedVisibility(
                visible = !isSelectionMode,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                FloatingActionToolbar(
                    showClearAll = calibrations.isNotEmpty(),
                    onClearAll = { showClearConfirmation = true },
                    onAdd = onAdd,
                    enabled = isCalibrationEnabled
                )
            }
            
            // Selection Mode Toolbar
            AnimatedVisibility(
                visible = isSelectionMode,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                SelectionModeToolbar(
                    selectedCount = selectedIds.size,
                    onDelete = { showBulkDeleteConfirmation = true },
                    onDisable = {
                        scope.launch {
                            val toUpdate = calibrations.filter { selectedIds.contains(it.id) }
                            toUpdate.forEach { cal ->
                                CalibrationManager.updateCalibration(cal.copy(isEnabled = false))
                            }
                            rewriteHistoryIfNeeded()
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }
                    },
                    onEnable = {
                        scope.launch {
                            val toUpdate = calibrations.filter { selectedIds.contains(it.id) }
                            toUpdate.forEach { cal ->
                                CalibrationManager.updateCalibration(cal.copy(isEnabled = true))
                            }
                            rewriteHistoryIfNeeded()
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }
                    }
                )
            }
            
            // Bulk Delete Confirmation Dialog
            if (showBulkDeleteConfirmation) {
                AlertDialog(
                    onDismissRequest = { showBulkDeleteConfirmation = false },
                    title = { Text(stringResource(R.string.delete_calibrations_count, selectedIds.size)) },
                    text = { Text(stringResource(R.string.this_action_can_be_undone)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showBulkDeleteConfirmation = false
                                scope.launch {
                                    val toDelete = calibrations.filter { selectedIds.contains(it.id) }
                                    toDelete.forEach { CalibrationManager.deleteCalibration(it) }
                                    rewriteHistoryIfNeeded()
                                    
                                    val result = snackbarHostState.showCalibrationUndoSnackbar(
                                        message = context.getString(R.string.calibrations_deleted_count, toDelete.size),
                                        actionLabel = context.getString(R.string.undo)
                                    )
                                    
                                    if (result == SnackbarResult.ActionPerformed) {
                                        toDelete.forEach { cal ->
                                            CalibrationManager.addCalibration(
                                                timestamp = cal.timestamp,
                                                sensorValue = cal.sensorValue,
                                                sensorValueRaw = cal.sensorValueRaw,
                                                userValue = cal.userValue,
                                                sensorId = cal.sensorId,
                                                isRawMode = cal.isRawMode
                                            )
                                        }
                                        rewriteHistoryIfNeeded()
                                    }
                                    
                                    isSelectionMode = false
                                    selectedIds = emptySet()
                                }
                            }
                        ) { Text(stringResource(R.string.delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBulkDeleteConfirmation = false }) { Text(stringResource(R.string.cancel)) }
                    }
                )
            }
        }
    }
    
    // Clear Options Bottom Sheet
    if (showClearConfirmation) {
        ClearOptionsBottomSheet(
            onDismiss = { showClearConfirmation = false },
            onClearDisabled = {
                scope.launch {
                    val disabled = calibrations.filter { !it.isEnabled }
                    disabled.forEach { CalibrationManager.deleteCalibration(it) }
                    rewriteHistoryIfNeeded()
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.disabled_calibrations_cleared, disabled.size),
                        duration = SnackbarDuration.Short
                    )
                }
                showClearConfirmation = false
            },
            onClearAll = {
                scope.launch {
                    val backup = calibrations
                    CalibrationManager.clearAll()
                    rewriteHistoryIfNeeded()
                    
                    val result = snackbarHostState.showCalibrationUndoSnackbar(
                        message = context.getString(R.string.all_calibrations_cleared),
                        actionLabel = context.getString(R.string.undo)
                    )
                    
                    if (result == SnackbarResult.ActionPerformed) {
                        CalibrationManager.restoreAll(backup)
                        rewriteHistoryIfNeeded()
                    }
                }
                showClearConfirmation = false
            },
            disabledCount = calibrations.count { !it.isEnabled },
            totalCount = calibrations.size
        )
    }

    if (showImportExportSheet) {
        CalibrationImportExportBottomSheet(
            onDismiss = { showImportExportSheet = false },
            onExport = {
                showImportExportSheet = false
                exportCurrentSensorProfile()
            },
            onImportMerge = {
                showImportExportSheet = false
                importCurrentSensorProfile(replaceExisting = false)
            },
            onImportReplace = {
                showImportExportSheet = false
                importCurrentSensorProfile(replaceExisting = true)
            }
        )
    }
    
}


@Composable
private fun MasterCalibrationCard(
    initiallyExpanded: Boolean,
    onInitialExpansionShown: () -> Unit,
    isEnabled: Boolean,
    hideInitialWhenCalibrated: Boolean,
    applyToPast: Boolean,
    lockPastHistory: Boolean,
    keepDisabledHistory: Boolean,
    overwriteSensorValues: Boolean,
    visualContinuity: Boolean,
    calibrateFromJournal: Boolean,
    onToggle: (Boolean) -> Unit,
    onToggleHideInitial: (Boolean) -> Unit,
    onToggleApplyToPast: (Boolean) -> Unit,
    onToggleLockPastHistory: (Boolean) -> Unit,
    onToggleKeepDisabledHistory: (Boolean) -> Unit,
    onToggleOverwriteSensorValues: (Boolean) -> Unit,
    onToggleVisualContinuity: (Boolean) -> Unit,
    onToggleCalibrateFromJournal: (Boolean) -> Unit
) {
    var expanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(initiallyExpanded) {
        if (initiallyExpanded) {
            onInitialExpansionShown()
        }
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "masterCalibrationChevron"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.74f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "calibrationMasterContainer"
    )
    val expandedContainerColor = if (isEnabled) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isEnabled)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.enable_calibration),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isEnabled) stringResource(R.string.enabled_status) else stringResource(R.string.disabled_status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    StyledSwitch(
                        checked = isEnabled,
                        onCheckedChange = onToggle
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = expandedContainerColor
                ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val rowDividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
                    HorizontalDivider(color = rowDividerColor)
                    MasterToggleRow(
                        title = stringResource(R.string.calibration_hide_initial_values),
                        subtitle = if (hideInitialWhenCalibrated) {
                            stringResource(R.string.calibration_initial_hidden_status)
                        } else {
                            stringResource(R.string.calibration_initial_shown_status)
                        },
                        checked = hideInitialWhenCalibrated,
                        enabled = isEnabled,
                        onToggle = onToggleHideInitial
                    )
                    HorizontalDivider(color = rowDividerColor)
                    MasterToggleRow(
                        title = stringResource(R.string.calibratepast),
                        subtitle = stringResource(R.string.calibration_apply_past_subtitle),
                        checked = applyToPast,
                        enabled = isEnabled,
                        onToggle = onToggleApplyToPast
                    )
                    HorizontalDivider(color = rowDividerColor)
                    MasterToggleRow(
                        title = stringResource(R.string.calibration_lock_past_history_title),
                        subtitle = stringResource(R.string.calibration_lock_past_history_subtitle),
                        checked = lockPastHistory,
                        enabled = isEnabled,
                        onToggle = onToggleLockPastHistory
                    )
                    HorizontalDivider(color = rowDividerColor)
                    MasterToggleRow(
                        title = stringResource(R.string.calibration_keep_disabled_history_title),
                        subtitle = stringResource(R.string.calibration_keep_disabled_history_subtitle),
                        checked = keepDisabledHistory,
                        enabled = isEnabled && lockPastHistory,
                        onToggle = onToggleKeepDisabledHistory
                    )
                    HorizontalDivider(color = rowDividerColor)
//                    MasterToggleRow(
//                        title = stringResource(R.string.calibration_overwrite_values_title),
//                        subtitle = stringResource(R.string.calibration_overwrite_values_subtitle),
//                        checked = overwriteSensorValues,
//                        enabled = isEnabled,
//                        onToggle = onToggleOverwriteSensorValues
//                    )
//                    HorizontalDivider(color = rowDividerColor)
                    MasterToggleRow(
                        title = stringResource(R.string.calibration_from_journal_title),
                        subtitle = stringResource(R.string.calibration_from_journal_subtitle),
                        checked = calibrateFromJournal,
                        enabled = isEnabled,
                        onToggle = onToggleCalibrateFromJournal
                    )
                    HorizontalDivider(color = rowDividerColor)
                    MasterToggleRow(
                        title = stringResource(R.string.calibrate_a),
                        subtitle = stringResource(R.string.calibration_visual_continuity_subtitle),
                        checked = visualContinuity,
                        enabled = isEnabled,
                        onToggle = onToggleVisualContinuity
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun MasterToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.62f }
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier.width(56.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            StyledSwitch(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun CalibrationAlgorithmCard(
    isCalibrationEnabled: Boolean,
    selectedAlgorithm: CalibrationManager.CalibrationAlgorithm,
    diagnostics: CalibrationManager.CalibrationDiagnostics,
    selectedWeightMode: CalibrationManager.CalibrationWeightMode,
    onSelectAlgorithm: (CalibrationManager.CalibrationAlgorithm) -> Unit,
    onSelectWeightMode: (CalibrationManager.CalibrationWeightMode) -> Unit
) {
    val algorithms = remember { CalibrationManager.CalibrationAlgorithm.values().toList() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val leadingGrid = 40.dp
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "algorithmChevronRotation"
    )
    val cardAlpha = if (isCalibrationEnabled) 1f else 0.62f
    val containerColor by animateColorAsState(
        targetValue = if (isCalibrationEnabled) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.44f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "algorithmContainer"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = cardAlpha },
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(leadingGrid),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.calibration_algorithm),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = selectedAlgorithm.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    algorithms.forEachIndexed { index, algorithm ->
                        val isSelected = algorithm == selectedAlgorithm

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSecondary)
                                .clickable { onSelectAlgorithm(algorithm) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.width(leadingGrid),
                                contentAlignment = Alignment.Center
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onSelectAlgorithm(algorithm) }
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = algorithm.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                )
                                Text(
                                    text = algorithm.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (index < algorithms.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    CalibrationWeightControl(
                        selectedMode = selectedWeightMode,
                        enabled = isCalibrationEnabled,
                        onSelectMode = onSelectWeightMode
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Box(modifier = Modifier.padding(12.dp)) {
                        CalibrationDiagnosticsPanel(diagnostics = diagnostics)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationWeightControl(
    selectedMode: CalibrationManager.CalibrationWeightMode,
    enabled: Boolean,
    onSelectMode: (CalibrationManager.CalibrationWeightMode) -> Unit
) {
    val modes = remember { CalibrationManager.CalibrationWeightMode.values().toList() }
    val selectedDescription = when (selectedMode) {
        CalibrationManager.CalibrationWeightMode.FRESH -> stringResource(R.string.calibration_weight_fresh_desc)
        CalibrationManager.CalibrationWeightMode.BALANCED -> stringResource(R.string.calibration_weight_balanced_desc)
        CalibrationManager.CalibrationWeightMode.STABLE -> stringResource(R.string.calibration_weight_stable_desc)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.62f }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.calibration_weight_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            modes.forEach { mode ->
                FilterChip(
                    selected = mode == selectedMode,
                    onClick = { if (enabled) onSelectMode(mode) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = when (mode) {
                                CalibrationManager.CalibrationWeightMode.FRESH -> stringResource(R.string.calibration_weight_fresh)
                                CalibrationManager.CalibrationWeightMode.BALANCED -> stringResource(R.string.calibration_weight_balanced)
                                CalibrationManager.CalibrationWeightMode.STABLE -> stringResource(R.string.calibration_weight_stable)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            text = selectedDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CalibrationDiagnosticsPanel(
    diagnostics: CalibrationManager.CalibrationDiagnostics
) {
    fun fmt(value: Float?): String {
        return value?.let { String.format(Locale.getDefault(), "%.3f", it) } ?: "-"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = diagnostics.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticValuePill(
                    label = stringResource(R.string.points),
                    value = diagnostics.pointCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                DiagnosticValuePill(
                    label = stringResource(R.string.slope),
                    value = fmt(diagnostics.slope),
                    modifier = Modifier.weight(1f)
                )
                DiagnosticValuePill(
                    label = stringResource(R.string.offset),
                    value = fmt(diagnostics.offset),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticValuePill(
                    label = stringResource(R.string.intercept),
                    value = fmt(diagnostics.intercept),
                    modifier = Modifier.weight(1f)
                )
                DiagnosticValuePill(
                    label = stringResource(R.string.anchor),
                    value = fmt(diagnostics.anchorInfluence),
                    modifier = Modifier.weight(1f)
                )
                DiagnosticValuePill(
                    label = stringResource(R.string.confidence),
                    value = fmt(diagnostics.confidence),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DiagnosticValuePill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Swipeable calibration row using Material 3 SwipeToDismissBox
 * - Swipe LEFT to DELETE (red background, trash icon)
 * - Swipe RIGHT to DISABLE/ENABLE (secondary/tertiary background)
 * - Tap to open modal
 * - Long press to enter selection mode
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableCalibrationRow(
    modifier: Modifier = Modifier,
    cal: CalibrationEntity,
    isMmol: Boolean,
    isRawMode: Boolean,
    dateFormatter: SimpleDateFormat,
    isCalibrationEnabled: Boolean,
    hideInitialWhenCalibrated: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onToggleDisable: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var deleteArmHapticSent by remember(cal.id, cal.isEnabled) { mutableStateOf(false) }
    var rowWidthPx by remember(cal.id, cal.isEnabled) { mutableStateOf(0) }
    
    // In selection mode, just show the card without swipe
    if (isSelectionMode) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { onLongPress() }
                    )
                }
        ) {
            CalibrationItemContent(
                cal = cal,
                isMmol = isMmol,
                isRawMode = isRawMode,
                dateFormatter = dateFormatter,
                isCalibrationEnabled = isCalibrationEnabled,
                hideInitialWhenCalibrated = hideInitialWhenCalibrated,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected
            )
        }
        return
    }
    
    // Key on isEnabled so state resets when toggled
    val dismissState = key(cal.isEnabled) {
        rememberSwipeToDismissBoxState(
            // Require a more deliberate swipe before destructive actions fire.
            positionalThreshold = { it * CalibrationDeleteSwipeThresholdFraction },
            confirmValueChange = { dismissValue ->
                when (dismissValue) {
                    SwipeToDismissBoxValue.EndToStart -> {
                        // Swipe left = Delete
                        onDelete()
                        true
                    }
                    SwipeToDismissBoxValue.StartToEnd -> {
                        // Swipe right = Toggle disable
                        onToggleDisable()
                        false
                    }
                    SwipeToDismissBoxValue.Settled -> false
                }
            }
        )
    }

    val currentOffset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
    val deleteArmed =
        rowWidthPx > 0 &&
            dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart &&
            -currentOffset >= rowWidthPx * CalibrationDeleteSwipeThresholdFraction

    LaunchedEffect(deleteArmed) {
        if (deleteArmed) {
            if (!deleteArmHapticSent) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                deleteArmHapticSent = true
            }
        } else {
            deleteArmHapticSent = false
        }
    }
    
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.onSizeChanged { rowWidthPx = it.width },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                SwipeToDismissBoxValue.StartToEnd -> if (cal.isEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                else -> Color.Transparent
            }
            val alignment = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                else -> Alignment.Center
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Delete
                SwipeToDismissBoxValue.StartToEnd -> if (cal.isEnabled) Icons.Filled.Close else Icons.Filled.Check
                else -> Icons.Filled.Delete
            }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(color)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        content = {
            Box(
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onLongPress = { onLongPress() }
                        )
                    }
            ) {
                CalibrationItemContent(
                    cal = cal,
                    isMmol = isMmol,
                    isRawMode = isRawMode,
                    dateFormatter = dateFormatter,
                    isCalibrationEnabled = isCalibrationEnabled,
                    hideInitialWhenCalibrated = hideInitialWhenCalibrated,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected
                )
            }
        }
    )
}

@Composable
private fun CalibrationItemContent(
    cal: CalibrationEntity,
    isMmol: Boolean,
    isRawMode: Boolean,
    dateFormatter: SimpleDateFormat,
    isCalibrationEnabled: Boolean,
    hideInitialWhenCalibrated: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean
) {
    val sFmt = if (isMmol) "%.1f" else "%.0f"
    val showOnlyCalibrated = isCalibrationEnabled && hideInitialWhenCalibrated
    
    // Determine primary and secondary values based on mode
    val primaryValue = if (isRawMode) cal.sensorValueRaw else cal.sensorValue
    val secondaryValue = if (isRawMode) cal.sensorValue else cal.sensorValueRaw
    val secondaryLabel = if (isRawMode) stringResource(R.string.auto) else stringResource(R.string.raw)
    val hasSecondary = secondaryValue != 0f && secondaryValue != primaryValue
    
    // Disabled state: reduce opacity
    val cardAlpha = if (cal.isEnabled) 1f else 0.6f
    
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            !cal.isEnabled -> MaterialTheme.colorScheme.surfaceDim
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .animateContentSize() // Smooth content shift when checkbox appears
                .padding(16.dp)
                .defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox with standard M3 scale animation
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Row {
                    Icon(
                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }
            
            // Values & Info
            Column(modifier = Modifier.weight(1f).graphicsLayer { alpha = cardAlpha }) {
                // Primary: Sensor → Calibrated
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!showOnlyCalibrated) {
                        Text(
                            text = String.format(Locale.getDefault(), sFmt, primaryValue),
                            style = MaterialTheme.typography.titleLarge,
                            color = if (cal.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = String.format(Locale.getDefault(), sFmt, cal.userValue),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (cal.isEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Metadata: Date | Secondary value | Disabled badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dateFormatter.format(Date(cal.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (!showOnlyCalibrated && hasSecondary) {
                        Text(
                            text = " · $secondaryLabel: ${String.format(Locale.getDefault(), sFmt, secondaryValue)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Derived points come back on the next sync, so say where they
                    // came from rather than let a deleted one look like a bug.
                    if (cal.journalEntryId != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                stringResource(R.string.calibration_from_journal_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    if (!cal.isEnabled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                stringResource(R.string.disabled_status), 
                                style = MaterialTheme.typography.labelSmall, 
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingActionToolbar(
    showClearAll: Boolean,
    onClearAll: () -> Unit,
    onAdd: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.58f },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showClearAll) {
            FilledTonalButton(
                onClick = onClearAll,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    bottomStart = 16.dp, 
                    topEnd = 4.dp,
                    bottomEnd = 4.dp
                ),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.clear), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Button(
            onClick = onAdd,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(
                topStart = 4.dp,
                bottomStart = 4.dp,
                topEnd = 28.dp,
                bottomEnd = 28.dp
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Icon(Icons.Filled.WaterDrop, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.calibrate_action), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SelectionModeToolbar(
    selectedCount: Int,
    onDelete: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Disable
            FilledTonalButton(
                onClick = onDisable,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.disable), style = MaterialTheme.typography.labelLarge)
            }
            
            // Enable
            FilledTonalButton(
                onClick = onEnable,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.enable), style = MaterialTheme.typography.labelLarge)
            }
            
            // Delete
            FilledTonalButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.delete), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalibrationImportExportBottomSheet(
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImportMerge: () -> Unit,
    onImportReplace: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.calibration_import_export_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.calibration_import_export_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            CalibrationTransferAction(
                title = stringResource(R.string.export_profile),
                subtitle = stringResource(R.string.calibration_export_profile_desc),
                icon = Icons.Default.FileUpload,
                onClick = onExport
            )

            Spacer(modifier = Modifier.height(8.dp))

            CalibrationTransferAction(
                title = stringResource(R.string.import_profile_merge),
                subtitle = stringResource(R.string.calibration_import_merge_desc),
                icon = Icons.Default.FileDownload,
                onClick = onImportMerge
            )

            Spacer(modifier = Modifier.height(8.dp))

            CalibrationTransferAction(
                title = stringResource(R.string.import_profile_replace),
                subtitle = stringResource(R.string.calibration_import_replace_desc),
                icon = Icons.Default.FileDownload,
                onClick = onImportReplace,
                isDestructive = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun CalibrationTransferAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val contentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val containerColor = if (isDestructive) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = contentColor.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isDestructive) contentColor else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Bottom sheet for clear options following M3 guidelines
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClearOptionsBottomSheet(
    onDismiss: () -> Unit,
    onClearDisabled: () -> Unit,
    onClearAll: () -> Unit,
    disabledCount: Int,
    totalCount: Int
) {
    val sheetState = rememberModalBottomSheetState()
    
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.clear_calibrations_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.choose_what_to_clear),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Clear Disabled Only
            if (disabledCount > 0) {
                Surface(
                    onClick = onClearDisabled,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.clear_disabled_only),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                stringResource(R.string.disabled_calibrations_count, disabledCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Clear All
            Surface(
                onClick = onClearAll,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.clear),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            stringResource(R.string.calibrations_will_be_removed_count, totalCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Cancel
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
